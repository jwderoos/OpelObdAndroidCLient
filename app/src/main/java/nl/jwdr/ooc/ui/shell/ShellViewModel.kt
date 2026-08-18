package nl.jwdr.ooc.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = diagnosticsManager.connectionState

    val isSimulated: Boolean = diagnosticsManager.isSimulated

    fun toggleConnection() {
        viewModelScope.launch {
            when (connectionState.value) {
                ConnectionState.Ready, ConnectionState.Connecting ->
                    diagnosticsManager.disconnect()
                else ->
                    diagnosticsManager.connect()
            }
        }
    }
}
