package nl.jwdr.ooc.diagnostics

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import nl.jwdr.ooc.catalog.BlockReading
import nl.jwdr.ooc.catalog.CodingTable
import nl.jwdr.ooc.catalog.DataRow
import nl.jwdr.ooc.catalog.DisplayTagBindings
import nl.jwdr.ooc.catalog.MeasuringBlock
import nl.jwdr.ooc.catalog.CommandRecord
import nl.jwdr.ooc.catalog.LiveDecodeRule
import nl.jwdr.ooc.catalog.LiveMeasuringBlockDecoder
import nl.jwdr.ooc.catalog.MeasuringBlockDecoder
import nl.jwdr.ooc.catalog.OutputTest
import nl.jwdr.ooc.catalog.TagBinding
import nl.jwdr.ooc.protocol.gmlan.GmlanServices
import nl.jwdr.ooc.protocol.gmlan.PeriodicDataMonitor
import nl.jwdr.ooc.protocol.gmlan.ReadDiagnosticInformation
import nl.jwdr.ooc.protocol.gmlan.ReturnToNormalMode
import nl.jwdr.ooc.protocol.gmlan.readDiagnosticInformation
import nl.jwdr.ooc.protocol.isotp.IsoTpAddress
import nl.jwdr.ooc.protocol.kwp2000.ClearDiagnosticInformation
import nl.jwdr.ooc.protocol.kwp2000.Dtc
import nl.jwdr.ooc.protocol.kwp2000.ReadDTCByStatus
import nl.jwdr.ooc.protocol.kwp2000.ReadECUIdentification
import nl.jwdr.ooc.protocol.kwp2000.RawRequest
import nl.jwdr.ooc.protocol.kwp2000.WriteDataByLocalIdentifier
import nl.jwdr.ooc.protocol.obd2.ClearEmissionData
import nl.jwdr.ooc.protocol.obd2.Obd2Pid
import nl.jwdr.ooc.protocol.obd2.Obd2Pids
import nl.jwdr.ooc.protocol.obd2.ReadCurrentData
import nl.jwdr.ooc.protocol.obd2.ReadStoredDtcs
import nl.jwdr.ooc.protocol.session.DiagnosticSession
import nl.jwdr.ooc.protocol.session.SessionConfig
import nl.jwdr.ooc.protocol.session.SessionException
import nl.jwdr.ooc.transport.CanFrame
import nl.jwdr.ooc.transport.ConnectionState
import nl.jwdr.ooc.transport.FakeEcuTransport
import nl.jwdr.ooc.transport.ObdTransport
import nl.jwdr.ooc.transport.RecordingTransport
import nl.jwdr.ooc.transport.ReplayTransport
import nl.jwdr.ooc.transport.SwitchableObdTransport
import nl.jwdr.ooc.transport.opcom.BusSelectable
import nl.jwdr.ooc.transport.opcom.OpComBusNotAwakeException

/**
 * Facade composing the protocol stack and the imported catalog behind one
 * API for the ViewModels ("read measuring block 5 of the ABS ECU" = catalog
 * lookup + protocol call + scaling).
 *
 * Skeleton for now: connection lifecycle and state only. The feature issues
 * (#11–#18) add scan/DTC/live-data/output-test/coding operations here.
 */
class DiagnosticsManager(
    private val transport: ObdTransport,
    /**
     * Receives a one-line description of every diagnostic action as it
     * starts (`readDtcs ecu=Engine req=0x7E0 resp=0x7E8`). The app routes it
     * into the session capture (issue #29) as `# event` comments so a recorded
     * `.canlog` can be correlated with what the user did. No-op by default.
     */
    private val annotate: (String) -> Unit = {},
) {
    /** Top-level connection state for the UI chrome. */
    val connectionState: StateFlow<ConnectionState> = transport.state

    /**
     * True when the session is not talking to a real vehicle. The UI must
     * badge simulated sessions on every screen (design spec safety rule).
     * A [SwitchableObdTransport] is judged by whatever currently backs it,
     * so the badge follows adapter selection.
     */
    val isSimulated: StateFlow<Boolean> = object : StateFlow<Boolean> {
        override val value: Boolean get() = transport.isSimulatedTransport()
        override val replayCache: List<Boolean> get() = listOf(value)
        override suspend fun collect(collector: FlowCollector<Boolean>): Nothing {
            when (transport) {
                is SwitchableObdTransport ->
                    transport.active
                        .map { it.isSimulatedTransport() }
                        .distinctUntilChanged()
                        .collect(collector)
                else -> MutableStateFlow(value).collect(collector)
            }
            error("state flows never complete")
        }
    }

    private fun ObdTransport.isSimulatedTransport(): Boolean = when (this) {
        is FakeEcuTransport, is ReplayTransport -> true
        is SwitchableObdTransport -> active.value.isSimulatedTransport()
        is RecordingTransport -> delegate.isSimulatedTransport()
        else -> false
    }

    private fun ObdTransport.asBusSelectable(): BusSelectable? = when (this) {
        is BusSelectable -> this
        is SwitchableObdTransport -> active.value.asBusSelectable()
        is RecordingTransport -> delegate.asBusSelectable()
        else -> null
    }

    /**
     * Puts the OP-COM interface on [target]'s bus with RX filters for its
     * ECU before a session opens (issue #30) — a no-op when [target] has no
     * known bus (OBD-II fallback) or the transport isn't OP-COM.
     */
    private suspend fun ensureBusConfigured(target: EcuScanTarget) {
        val bus = target.bus ?: return
        val selectable = transport.asBusSelectable() ?: return
        selectable.configureBus(bus.toOpComBus(), target.requestId, target.secondaryId ?: 0, target.responseId)
    }

    suspend fun connect() = transport.connect()

    private fun annotate(action: String, target: EcuScanTarget) {
        annotate("$action ecu=${target.name} req=0x%X resp=0x%X".format(target.requestId, target.responseId))
    }

    suspend fun disconnect() = transport.disconnect()

    /**
     * Probes each target sequentially (one bus, one request in flight) and
     * emits one [EcuScanResult] per target, in order. Fails with a
     * [nl.jwdr.ooc.protocol.session.SessionException.TransportLost] when the
     * connection drops mid-scan.
     */
    fun scanEcus(targets: List<EcuScanTarget>): Flow<EcuScanResult> = flow {
        // Keep the (single-wire) CAN bus from sleeping between probes: SW-CAN
        // powers down after ~30 s idle, after which the interface's bus-awake
        // poll fails until a reconnect (issue #35). A low-rate all-nodes
        // tester-present, exactly as the vendor keeps alive, prevents that.
        coroutineScope {
            val keepAlive = launch { keepBusAwakeLoop() }
            try {
                for (target in targets) {
                    emit(EcuScanResult(target, probe(target)))
                }
            } finally {
                keepAlive.cancel()
            }
        }
    }

    /**
     * Sends [ALL_NODES_TESTER_PRESENT] every [BUS_KEEPALIVE_INTERVAL] while a
     * scan runs, so the bus stays awake between ECUs. Best-effort: it only
     * fires while the transport is ready and never lets a failed keep-alive
     * abort the scan.
     */
    private suspend fun keepBusAwakeLoop() {
        while (true) {
            delay(BUS_KEEPALIVE_INTERVAL)
            if (connectionState.value == ConnectionState.Ready) {
                runCatching { transport.send(ALL_NODES_TESTER_PRESENT) }
            }
        }
    }

    private suspend fun probe(target: EcuScanTarget): EcuScanStatus {
        annotate("scanProbe", target)
        return try {
            probeSession(target)
        } catch (e: SessionException.TransportLost) {
            // A dropped connection is fatal to the whole scan, by contract.
            throw e
        } catch (e: OpComBusNotAwakeException) {
            // Bus asleep (no car/ignition): report this ECU and keep scanning (#33).
            EcuScanStatus.Unreachable
        } catch (e: Exception) {
            // Any other per-ECU failure must not abort the scan either.
            EcuScanStatus.Unreachable
        }
    }

    private suspend fun probeSession(target: EcuScanTarget): EcuScanStatus =
        withSession(target, SCAN_SESSION_CONFIG) { session ->
            try {
                val dtcCount = if (target.isGmlan) {
                    session.execute(ReturnToNormalMode)
                    try {
                        session.readGmlanDtcs(target.secondaryId!!, SCAN_SESSION_CONFIG.responseTimeout).size
                    } catch (e: SessionException.ResponseTimeout) {
                        // ReturnToNormalMode already proved the ECU is alive;
                        // it just didn't report DTCs by A9 within the scan's
                        // fast timeout (recorded captures show that happening).
                        return@withSession EcuScanStatus.Present(dtcCount = null)
                    }
                } else {
                    session.execute(ReadDTCByStatus(DTC_STATUS_ALL, DTC_GROUP_ALL)).dtcs.size
                }
                EcuScanStatus.Present(dtcCount = dtcCount)
            } catch (e: SessionException.NegativeResponse) {
                // It answered, so it exists; it just won't report DTCs this way.
                EcuScanStatus.Present(dtcCount = null)
            } catch (e: SessionException.ResponseTimeout) {
                EcuScanStatus.Absent
            }
        }

    /**
     * Reads the stored DTCs of one known-present ECU. Unlike a scan probe
     * this uses the conversational timeout/retry policy; failures (negative
     * response, timeout) propagate as [SessionException]s. GMLAN-addressed
     * ECUs (see [EcuScanTarget.isGmlan]) read via readDiagnosticInformation
     * (0xA9); every other ECU keeps readDTCByStatus (0x18) (issue #31).
     */
    suspend fun readDtcs(target: EcuScanTarget): List<Dtc> {
        annotate("readDtcs", target)
        val config = SessionConfig()
        return withSession(target, config) { session ->
            if (target.isGmlan) {
                session.execute(ReturnToNormalMode)
                session.readGmlanDtcs(target.secondaryId!!, config.pendingTimeout)
            } else {
                session.execute(ReadDTCByStatus(DTC_STATUS_ALL, DTC_GROUP_ALL)).dtcs
            }
        }
    }

    /**
     * Clears all stored DTC groups of one ECU, then reads back and returns
     * what it still stores (same session), so the UI shows the ECU's actual
     * state rather than an assumption. Destructive: callers must obtain
     * explicit user confirmation first (design spec safety rule).
     * GMLAN-addressed ECUs (see [EcuScanTarget.isGmlan]) clear with OBD
     * mode 04 ([ClearEmissionData]), never KWP2000's
     * clearDiagnosticInformation (0x14) (issue #31).
     */
    suspend fun clearDtcs(target: EcuScanTarget): List<Dtc> {
        annotate("clearDtcs", target)
        val config = SessionConfig()
        return withSession(target, config) { session ->
            if (target.isGmlan) {
                session.execute(ReturnToNormalMode)
                session.execute(ClearEmissionData)
                session.readGmlanDtcs(target.secondaryId!!, config.pendingTimeout)
            } else {
                session.execute(ClearDiagnosticInformation(DTC_GROUP_ALL))
                session.execute(ReadDTCByStatus(DTC_STATUS_ALL, DTC_GROUP_ALL)).dtcs
            }
        }
    }

    /**
     * Reads every entry of [table] from [target], in `table.didEntries` order.
     * Raw bytes only (issue #18 v1): the DID-to-row mapping is not established
     * (docs/catalog-format.md), so this returns each entry's record verbatim,
     * not decoded coding values. Failures propagate as [SessionException]s,
     * like every other read in this class.
     */
    suspend fun readCoding(target: EcuScanTarget, table: CodingTable): CodingReadResult {
        annotate("readCoding", target)
        return withSession(target, SessionConfig()) { session ->
            CodingReadResult(
                table.didEntries.map { entry ->
                    CodingEntryRead(entry.id, session.execute(ReadECUIdentification(entry.id)).record)
                },
            )
        }
    }

    /**
     * Writes [edits] (entry id -> new raw record) into [table]'s entries on
     * [target], then re-reads every entry to verify. Destructive: callers
     * must obtain explicit user confirmation first (design spec safety
     * rule), behind the expert-mode toggle (issue #18). No SecurityAccess
     * unlock is attempted — issue #36 tracks that gap; the only real
     * capture of this flow used none.
     *
     * On the first write failure, every remaining edited entry is left
     * [CodingEntryOutcome.NotAttempted] rather than attempted: a
     * half-applied coding record is the real risk here, not one bad value.
     */
    suspend fun writeCoding(
        target: EcuScanTarget,
        table: CodingTable,
        edits: Map<Int, ByteArray>,
    ): CodingWriteResult {
        annotate("writeCoding", target)
        val knownIds = table.didEntries.map { it.id }.toSet()
        require(edits.keys.all { it in knownIds }) {
            "writeCoding: edits contains an id not in table.didEntries: ${edits.keys - knownIds}"
        }
        return withSession(target, SessionConfig()) { session ->
            var failed = false
            val outcomes = mutableMapOf<Int, CodingEntryOutcome>()
            for (entry in table.didEntries) {
                if (entry.id !in edits) continue
                if (failed) {
                    outcomes[entry.id] = CodingEntryOutcome.NotAttempted(entry.id)
                    continue
                }
                try {
                    session.execute(WriteDataByLocalIdentifier(entry.id, edits.getValue(entry.id)))
                } catch (e: SessionException) {
                    failed = true
                    outcomes[entry.id] = CodingEntryOutcome.Failed(entry.id, e.message ?: e.toString())
                }
            }
            val reread = table.didEntries.map { entry ->
                CodingEntryRead(entry.id, session.execute(ReadECUIdentification(entry.id)).record)
            }
            val rereadById = reread.associateBy { it.id }
            for (id in edits.keys) {
                if (outcomes.containsKey(id)) continue
                val expected = edits.getValue(id)
                val actual = rereadById.getValue(id).bytes
                outcomes[id] = if (actual.contentEquals(expected)) {
                    CodingEntryOutcome.Written(id, actual)
                } else {
                    CodingEntryOutcome.VerificationMismatch(id, expected, actual)
                }
            }
            CodingWriteResult(
                outcomes = table.didEntries.mapNotNull { outcomes[it.id] },
                entries = reread,
            )
        }
    }

    /**
     * Reads one GMLAN readDiagnosticInformation/reportDTCByStatusMask reply
     * on [secondaryId] and maps it to the shared [Dtc] shape (the GMLAN
     * reply's status byte has no KWP2000 counterpart and is dropped).
     */
    private suspend fun DiagnosticSession.readGmlanDtcs(secondaryId: Int, timeout: Duration): List<Dtc> =
        readDiagnosticInformation(
            transport,
            secondaryId,
            ReadDiagnosticInformation(DTC_STATUS_MASK_ALL),
            timeout,
        ).map { Dtc(code = it.code, symptom = it.failureType) }

    /**
     * Polls one measuring block and emits a decoded reading per [interval]:
     * one GMLAN readDataByPacketIdentifier request schedules the block's
     * MEASDATA verbatim (scheduling-rate byte + DPID ids, as recorded
     * sessions show — issue #25), the values arrive as UUDT broadcasts on
     * [EcuScanTarget.secondaryId] at [DisplayTagBindings.ROWS_PER_DPID] data
     * bytes per DPID, and each reading decodes the latest broadcast of every
     * DPID (rows of DPIDs not yet seen read as no-data). One session spans
     * the whole poll; when the collector cancels, `AA 00` stops the schedule.
     */
    fun pollMeasuringBlock(
        target: EcuScanTarget,
        block: MeasuringBlock,
        rows: List<DataRow>,
        interval: Duration,
        decodeRules: Map<Int, LiveDecodeRule> = emptyMap(),
    ): Flow<BlockReading> = flow {
        annotate("pollMeasuringBlock block=${block.number} ecu=${target.name}")
        val secondaryId = requireNotNull(target.secondaryId) {
            "${target.name}: GMLAN live data needs the ECU's secondary CAN id"
        }
        val dpids = block.measData.drop(1)
        require(dpids.isNotEmpty()) {
            "block ${block.number}: MEASDATA has no DPID ids after the rate byte"
        }
        withSession(target, SessionConfig()) { session ->
            coroutineScope {
                val latest = MutableStateFlow(emptyMap<Int, ByteArray>())
                // UNDISPATCHED: subscribed before the schedule request goes out.
                val monitor = launch(start = CoroutineStart.UNDISPATCHED) {
                    PeriodicDataMonitor(transport, secondaryId).records.collect { record ->
                        if (record.dpid in dpids) latest.update { it + (record.dpid to record.data) }
                    }
                }
                try {
                    session.sendWithoutResponse(
                        byteArrayOf(GmlanServices.READ_DATA_BY_PACKET_IDENTIFIER.toByte()) +
                            block.measData.map(Int::toByte).toByteArray(),
                    )
                    while (true) {
                        delay(interval)
                        emit(decodeReading(block, rows, latest.value, decodeRules))
                    }
                } finally {
                    monitor.cancel()
                    withContext(NonCancellable) {
                        runCatching { session.sendWithoutResponse(STOP_PERIODIC_DATA) }
                    }
                }
            }
        }
    }

    /**
     * Starts one catalog output test on [target]: opens a session, runs the
     * test's before-test records, and returns a handle for the interactive
     * phase. Actuates vehicle hardware: callers must obtain explicit user
     * confirmation, showing the test's pre-test instructions, first (design
     * spec safety rule). The caller must always call [OutputTestRun.finish],
     * which runs the teardown records and closes the session.
     *
     * [bindings] (from [nl.jwdr.ooc.catalog.DisplayTagBindings]) enable the
     * live display-tag readouts on [OutputTestRun.readouts], decoded from the
     * GMLAN periodic-data broadcasts on [EcuScanTarget.secondaryId] that the
     * script's readDataByPacketIdentifier records schedule.
     */
    suspend fun startOutputTest(
        target: EcuScanTarget,
        test: OutputTest,
        bindings: List<TagBinding> = emptyList(),
    ): OutputTestRun {
        annotate("startOutputTest test=${test.title} ecu=${target.name}")
        ensureBusConfigured(target)
        val sessionScope = CoroutineScope(currentCoroutineContext() + Job())
        val session = DiagnosticSession(
            transport,
            IsoTpAddress(target.requestId, target.responseId),
            config = SessionConfig(),
            scope = sessionScope,
        )
        val secondaryId = target.secondaryId
        val monitored = bindings.isNotEmpty() && secondaryId != null
        val readouts = MutableStateFlow(
            if (monitored) {
                bindings.map { TagReadout(it, raw = null, display = MeasuringBlockDecoder.NO_DATA) }
            } else {
                emptyList()
            },
        )
        // Subscribe before the before-test records go out: the script's AA
        // schedule record starts the broadcasts immediately.
        if (monitored && secondaryId != null) {
            val monitor = PeriodicDataMonitor(transport, secondaryId)
            sessionScope.launch(start = CoroutineStart.UNDISPATCHED) {
                monitor.records.collect { record ->
                    readouts.update { current ->
                        current.map { readout ->
                            if (readout.binding.dpid != record.dpid) return@map readout
                            val raw = record.data.getOrNull(readout.binding.byteIndex)
                                ?.toInt()?.and(0xFF)
                            TagReadout(
                                readout.binding,
                                raw,
                                MeasuringBlockDecoder.displayFor(readout.binding.row, raw),
                            )
                        }
                    }
                }
            }
        }
        try {
            for (record in test.beforeTest) {
                session.sendRecord(record)
            }
        } catch (e: Throwable) {
            session.close()
            sessionScope.cancel()
            throw e
        }
        // Recorded sessions hold the test mode with the periodic GMLAN
        // all-nodes testerPresent broadcast (the ECU's 7E answers on the
        // diagnostic id are skipped as stale replies), not a per-ECU 3E.
        sessionScope.launch {
            while (true) {
                delay(ALL_NODES_TESTER_PRESENT_INTERVAL)
                transport.send(ALL_NODES_TESTER_PRESENT)
            }
        }
        return OutputTestRun(test, session, sessionScope, readouts)
    }

    /**
     * Decodes one live reading. With a per-ECU [decodeRules] set (issue: GMLAN
     * DPID decode), each row reads its own DPID/byte with the vendor's real
     * scale/bit rules; without one, falls back to the positional heuristic so
     * uncovered ECUs still show something (bytes concatenated at seven per DPID).
     */
    private fun decodeReading(
        block: MeasuringBlock,
        rows: List<DataRow>,
        latest: Map<Int, ByteArray>,
        decodeRules: Map<Int, LiveDecodeRule>,
    ): BlockReading {
        if (decodeRules.isNotEmpty()) {
            val readings = LiveMeasuringBlockDecoder.decode(
                firstRowNumber = block.enabledRows.first,
                rows = rows,
                dpidBytes = latest,
                rules = decodeRules,
            )
            return BlockReading(block, readings, ByteArray(0))
        }
        val dpids = block.measData.drop(1)
        val record = dpids.flatMap { dpid ->
            val data = latest[dpid]
            List(DisplayTagBindings.ROWS_PER_DPID) { index ->
                data?.getOrNull(index)?.toInt()?.and(0xFF)
            }
        }
        return MeasuringBlockDecoder.decode(block, rows, record)
    }

    /**
     * Sends the functional mode 01 PID 00 probe on 0x7DF and returns a target
     * per ISO 15765-4 ECU that answers (0x7E8..0x7EF, physical request ID 8
     * below). This is the entry point of the no-catalog OBD-II fallback;
     * everything after discovery uses physical addressing.
     */
    suspend fun discoverObd2Ecus(): List<EcuScanTarget> {
        annotate("discoverObd2Ecus")
        val responders = sortedSetOf<Int>()
        coroutineScope {
            val collector = launch {
                transport.incomingFrames.collect { frame ->
                    if (frame.id in OBD2_RESPONSE_IDS) responders += frame.id
                }
            }
            transport.send(CanFrame(OBD2_FUNCTIONAL_ID, OBD2_PROBE_PAYLOAD))
            delay(OBD2_DISCOVERY_WINDOW)
            collector.cancel()
        }
        return responders.map { responseId ->
            val requestId = responseId - 8
            EcuScanTarget("0x%X".format(requestId), requestId, responseId)
        }
    }

    /**
     * Queries the supported-PID bitmasks (0x00, then each chained range) and
     * returns the supported PIDs this app knows how to scale, ascending.
     */
    suspend fun obd2SupportedPids(target: EcuScanTarget): List<Obd2Pid> {
        annotate("obd2SupportedPids", target)
        return withSession(target, SessionConfig()) { session ->
            val supported = mutableSetOf<Int>()
            var base = 0x00
            while (true) {
                val response = session.execute(ReadCurrentData(base))
                supported += Obd2Pids.supportedFrom(base, response.data)
                base += 0x20
                if (base > 0xE0 || base !in supported) break
            }
            Obd2Pids.all.filter { it.id in supported }
        }
    }

    /**
     * Polls the given PIDs at a fixed [interval], emitting one scaled reading
     * list per cycle in [pids] order, until the collector cancels.
     */
    fun pollObd2Pids(
        target: EcuScanTarget,
        pids: List<Obd2Pid>,
        interval: Duration,
    ): Flow<List<Obd2Value>> = flow {
        annotate("pollObd2Pids", target)
        withSession(target, SessionConfig()) { session ->
            while (true) {
                emit(
                    pids.map { pid ->
                        val response = session.execute(ReadCurrentData(pid.id))
                        Obd2Value(pid, pid.value(response.data), pid.format(response.data))
                    },
                )
                delay(interval)
            }
        }
    }

    /** Reads the stored emission DTCs (mode 03) as raw two-byte codes. */
    suspend fun obd2ReadDtcs(target: EcuScanTarget): List<Int> {
        annotate("obd2ReadDtcs", target)
        return withSession(target, SessionConfig()) { session ->
            session.execute(ReadStoredDtcs).codes
        }
    }

    /**
     * Clears emission-related data (mode 04), then reads back what the ECU
     * still stores. Destructive: callers must obtain explicit user
     * confirmation first (design spec safety rule).
     */
    suspend fun obd2ClearDtcs(target: EcuScanTarget): List<Int> {
        annotate("obd2ClearDtcs", target)
        return withSession(target, SessionConfig()) { session ->
            session.execute(ClearEmissionData)
            session.execute(ReadStoredDtcs).codes
        }
    }

    private suspend fun <T> withSession(
        target: EcuScanTarget,
        config: SessionConfig,
        block: suspend (DiagnosticSession) -> T,
    ): T {
        ensureBusConfigured(target)
        // DiagnosticSession needs a real scope for its collector coroutines;
        // an inline coroutineScope would never return while they run.
        val sessionScope = CoroutineScope(currentCoroutineContext() + Job())
        try {
            val session = DiagnosticSession(
                transport,
                IsoTpAddress(target.requestId, target.responseId),
                config = config,
                scope = sessionScope,
            )
            try {
                return block(session)
            } finally {
                session.close()
            }
        } finally {
            sessionScope.cancel()
        }
    }

    private companion object {
        /** readDTCByStatus sub-function: all identified DTCs. */
        const val DTC_STATUS_ALL = 0x02

        /** groupOfDTC covering all groups. */
        const val DTC_GROUP_ALL = 0xFF00

        /** GMLAN reportDTCByStatusMask mask matching all DTCs, as sent by the vendor tool. */
        const val DTC_STATUS_MASK_ALL = 0x12

        /**
         * Probe policy: silence means absent, so don't retry, and don't wait
         * the full conversational timeout per empty address.
         */
        val SCAN_SESSION_CONFIG = SessionConfig(
            responseTimeout = 500.milliseconds,
            maxRetries = 0,
        )

        /** ISO 15765-4 functional request ID. */
        const val OBD2_FUNCTIONAL_ID = 0x7DF

        /** ISO 15765-4 physical response IDs. */
        val OBD2_RESPONSE_IDS = 0x7E8..0x7EF

        /** Single-frame mode 01 PID 00: every OBD-II ECU must answer it. */
        val OBD2_PROBE_PAYLOAD =
            byteArrayOf(0x02, 0x01, 0x00) + ByteArray(5) { 0xAA.toByte() }

        val OBD2_DISCOVERY_WINDOW = 500.milliseconds

        /** GMLAN all-nodes testerPresent, byte for byte as recorded. */
        val ALL_NODES_TESTER_PRESENT = CanFrame(
            0x101,
            byteArrayOf(0xFE.toByte(), 0x01, 0x3E, 0x00, 0x00, 0x00, 0x00, 0x00),
        )

        val ALL_NODES_TESTER_PRESENT_INTERVAL = 2.seconds

        /** How often a running scan pings the bus to keep SW-CAN awake (issue #35). */
        val BUS_KEEPALIVE_INTERVAL = 2.seconds

        /** readDataByPacketIdentifier rate 0: stop the periodic-data schedule. */
        val STOP_PERIODIC_DATA = byteArrayOf(
            GmlanServices.READ_DATA_BY_PACKET_IDENTIFIER.toByte(),
            0x00,
        )
    }
}

/** One scaled OBD-II reading. */
data class Obd2Value(val pid: Obd2Pid, val value: Double, val display: String)

/** One live display-tag reading shown while an output test runs. */
data class TagReadout(
    val binding: TagBinding,
    /** Unsigned raw byte from the DPID broadcast, or null before the first one. */
    val raw: Int?,
    val display: String,
)

/**
 * The interactive phase of one running output test, created by
 * [DiagnosticsManager.startOutputTest]. Owns the diagnostic session (its
 * tester-present keep-alive holds the test mode) until [finish].
 */
class OutputTestRun internal constructor(
    private val test: OutputTest,
    private val session: DiagnosticSession,
    private val sessionScope: CoroutineScope,
    /** Live display-tag readings; empty when the test has no resolvable tags. */
    val readouts: StateFlow<List<TagReadout>> = MutableStateFlow(emptyList()),
) {
    /** Sends the go-activate records. Repeatable (REPEAT/UPDOWN tests). */
    suspend fun activate() = send(test.goActivate)

    /** Sends the de-activate records. Repeatable. */
    suspend fun deactivate() = send(test.deActivate)

    /**
     * Runs the teardown records and closes the session. Must always be
     * called, also after a failed [activate]/[deactivate], so the ECU is
     * returned to its normal state.
     */
    suspend fun finish() {
        try {
            send(test.afterTest)
        } finally {
            session.close()
            sessionScope.cancel()
        }
    }

    private suspend fun send(records: List<CommandRecord>) {
        for (record in records) {
            session.sendRecord(record)
        }
    }
}

/**
 * True when [EcuScanTarget] is addressed via the GMLAN 11-bit scheme
 * (response id = request id + 0x400) with a secondary CAN id to receive
 * UUDT replies on. The only ECUs the recorded vendor sessions ever send an
 * A9 readDiagnosticInformation request to (issue #31) — a non-null
 * secondaryId alone is not sufficient: ISO15765-addressed ECUs (engine,
 * transmission) also carry one, but are not on this addressing scheme and
 * have no recorded evidence of accepting A9.
 */
private val EcuScanTarget.isGmlan: Boolean
    get() = secondaryId != null && responseId == requestId + 0x400

private fun CommandRecord.toPayload() =
    ByteArray(significantBytes.size) { significantBytes[it].toByte() }

/**
 * Sends one catalog command record: GMLAN readDataByPacketIdentifier gets no
 * USDT response (its reply is the UUDT stream on the secondary id), so it
 * goes out fire-and-forget; everything else awaits its positive response.
 */
private suspend fun DiagnosticSession.sendRecord(record: CommandRecord) {
    if (record.significantBytes.isEmpty()) return
    val payload = record.toPayload()
    if ((payload[0].toInt() and 0xFF) == GmlanServices.READ_DATA_BY_PACKET_IDENTIFIER) {
        sendWithoutResponse(payload)
    } else {
        execute(RawRequest(payload))
    }
}
