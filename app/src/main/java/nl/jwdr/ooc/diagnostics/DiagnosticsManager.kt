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
import nl.jwdr.ooc.catalog.DataRow
import nl.jwdr.ooc.catalog.DisplayTagBindings
import nl.jwdr.ooc.catalog.MeasuringBlock
import nl.jwdr.ooc.catalog.CommandRecord
import nl.jwdr.ooc.catalog.MeasuringBlockDecoder
import nl.jwdr.ooc.catalog.OutputTest
import nl.jwdr.ooc.catalog.TagBinding
import nl.jwdr.ooc.protocol.gmlan.GmlanServices
import nl.jwdr.ooc.protocol.gmlan.PeriodicDataMonitor
import nl.jwdr.ooc.protocol.isotp.IsoTpAddress
import nl.jwdr.ooc.protocol.kwp2000.ClearDiagnosticInformation
import nl.jwdr.ooc.protocol.kwp2000.Dtc
import nl.jwdr.ooc.protocol.kwp2000.ReadDTCByStatus
import nl.jwdr.ooc.protocol.kwp2000.RawRequest
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
import nl.jwdr.ooc.transport.ReplayTransport
import nl.jwdr.ooc.transport.SwitchableObdTransport

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
        else -> false
    }

    suspend fun connect() = transport.connect()

    suspend fun disconnect() = transport.disconnect()

    /**
     * Probes each target sequentially (one bus, one request in flight) and
     * emits one [EcuScanResult] per target, in order. Fails with a
     * [nl.jwdr.ooc.protocol.session.SessionException.TransportLost] when the
     * connection drops mid-scan.
     */
    fun scanEcus(targets: List<EcuScanTarget>): Flow<EcuScanResult> = flow {
        for (target in targets) {
            emit(EcuScanResult(target, probe(target)))
        }
    }

    private suspend fun probe(target: EcuScanTarget): EcuScanStatus =
        withSession(target, SCAN_SESSION_CONFIG) { session ->
            try {
                val response = session.execute(ReadDTCByStatus(DTC_STATUS_ALL, DTC_GROUP_ALL))
                EcuScanStatus.Present(dtcCount = response.dtcs.size)
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
     * response, timeout) propagate as [SessionException]s.
     */
    suspend fun readDtcs(target: EcuScanTarget): List<Dtc> =
        withSession(target, SessionConfig()) { session ->
            session.execute(ReadDTCByStatus(DTC_STATUS_ALL, DTC_GROUP_ALL)).dtcs
        }

    /**
     * Clears all stored DTC groups of one ECU, then reads back and returns
     * what it still stores (same session), so the UI shows the ECU's actual
     * state rather than an assumption. Destructive: callers must obtain
     * explicit user confirmation first (design spec safety rule).
     */
    suspend fun clearDtcs(target: EcuScanTarget): List<Dtc> =
        withSession(target, SessionConfig()) { session ->
            session.execute(ClearDiagnosticInformation(DTC_GROUP_ALL))
            session.execute(ReadDTCByStatus(DTC_STATUS_ALL, DTC_GROUP_ALL)).dtcs
        }

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
    ): Flow<BlockReading> = flow {
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
                        val record = dpids.flatMap { dpid ->
                            val data = latest.value[dpid]
                            List(DisplayTagBindings.ROWS_PER_DPID) { index ->
                                data?.getOrNull(index)?.toInt()?.and(0xFF)
                            }
                        }
                        emit(MeasuringBlockDecoder.decode(block, rows, record))
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
     * Sends the functional mode 01 PID 00 probe on 0x7DF and returns a target
     * per ISO 15765-4 ECU that answers (0x7E8..0x7EF, physical request ID 8
     * below). This is the entry point of the no-catalog OBD-II fallback;
     * everything after discovery uses physical addressing.
     */
    suspend fun discoverObd2Ecus(): List<EcuScanTarget> {
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
    suspend fun obd2SupportedPids(target: EcuScanTarget): List<Obd2Pid> =
        withSession(target, SessionConfig()) { session ->
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

    /**
     * Polls the given PIDs at a fixed [interval], emitting one scaled reading
     * list per cycle in [pids] order, until the collector cancels.
     */
    fun pollObd2Pids(
        target: EcuScanTarget,
        pids: List<Obd2Pid>,
        interval: Duration,
    ): Flow<List<Obd2Value>> = flow {
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
    suspend fun obd2ReadDtcs(target: EcuScanTarget): List<Int> =
        withSession(target, SessionConfig()) { session ->
            session.execute(ReadStoredDtcs).codes
        }

    /**
     * Clears emission-related data (mode 04), then reads back what the ECU
     * still stores. Destructive: callers must obtain explicit user
     * confirmation first (design spec safety rule).
     */
    suspend fun obd2ClearDtcs(target: EcuScanTarget): List<Int> =
        withSession(target, SessionConfig()) { session ->
            session.execute(ClearEmissionData)
            session.execute(ReadStoredDtcs).codes
        }

    private suspend fun <T> withSession(
        target: EcuScanTarget,
        config: SessionConfig,
        block: suspend (DiagnosticSession) -> T,
    ): T {
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
