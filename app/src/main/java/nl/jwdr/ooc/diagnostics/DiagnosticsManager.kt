package nl.jwdr.ooc.diagnostics

import kotlinx.coroutines.flow.StateFlow
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
}
