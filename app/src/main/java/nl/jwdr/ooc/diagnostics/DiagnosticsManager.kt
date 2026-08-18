package nl.jwdr.ooc.diagnostics

import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import nl.jwdr.ooc.protocol.isotp.IsoTpAddress
import nl.jwdr.ooc.protocol.kwp2000.ClearDiagnosticInformation
import nl.jwdr.ooc.protocol.kwp2000.Dtc
import nl.jwdr.ooc.protocol.kwp2000.ReadDTCByStatus
import nl.jwdr.ooc.protocol.session.DiagnosticSession
import nl.jwdr.ooc.protocol.session.SessionConfig
import nl.jwdr.ooc.protocol.session.SessionException
import nl.jwdr.ooc.transport.ConnectionState
import nl.jwdr.ooc.transport.FakeEcuTransport
import nl.jwdr.ooc.transport.ObdTransport
import nl.jwdr.ooc.transport.ReplayTransport

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
     */
    val isSimulated: Boolean =
        transport is FakeEcuTransport || transport is ReplayTransport

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
    }
}
