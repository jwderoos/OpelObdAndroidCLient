package nl.jwdr.ooc.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import nl.jwdr.ooc.diagnostics.DiagnosticsManager
import nl.jwdr.ooc.transport.ConnectionState

/**
 * App-level connection state for the shell chrome (status indicator,
 * simulated-session badge) and the home screen's connect control.
 */
class ShellViewModel(
    private val diagnosticsManager: DiagnosticsManager,
    val expertMode: StateFlow<Boolean>,
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = diagnosticsManager.connectionState

    val isSimulated: StateFlow<Boolean> = diagnosticsManager.isSimulated

    fun toggleConnection() {
        viewModelScope.launch {
            try {
                when (connectionState.value) {
                    ConnectionState.Ready, ConnectionState.Connecting ->
                        diagnosticsManager.disconnect()
                    else ->
                        diagnosticsManager.connect()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Real adapters throw on failed connects (off, out of range,
                // permission revoked) after moving the transport to Error —
                // the state drives the UI; an uncaught throw would crash.
            }
        }
    }
}
