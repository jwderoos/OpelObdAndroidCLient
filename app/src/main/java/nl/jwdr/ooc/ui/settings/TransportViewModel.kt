package nl.jwdr.ooc.ui.settings

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import nl.jwdr.ooc.diagnostics.TransportSelection
import nl.jwdr.ooc.transport.ConnectionState

/**
 * Adapter selection state for the settings screen. [applySelection] performs
 * the actual switch (the composition root's `selectTransport`); switching
 * while connected is refused there and surfaced as [errorMessage].
 */
class TransportViewModel(
    val selection: StateFlow<TransportSelection>,
    val connectionState: StateFlow<ConnectionState>,
    private val applySelection: (TransportSelection) -> Unit,
) : ViewModel() {

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    /** Adapter choice is only offered while no session could be disturbed. */
    val canSwitch: StateFlow<Boolean> = object : StateFlow<Boolean> {
        override val value: Boolean get() = connectionState.value.allowsSwitching()
        override val replayCache: List<Boolean> get() = listOf(value)
        override suspend fun collect(collector: FlowCollector<Boolean>): Nothing {
            connectionState.map { it.allowsSwitching() }.distinctUntilChanged().collect(collector)
            error("state flows never complete")
        }
    }

    fun select(selection: TransportSelection) {
        try {
            applySelection(selection)
            _errorMessage.value = null
        } catch (e: IllegalStateException) {
            // SwitchableObdTransport refuses to swap while connected.
            _errorMessage.value = "Disconnect before switching adapters."
        } catch (e: Exception) {
            _errorMessage.value = "Could not set up the adapter: ${e.message}"
        }
    }

    private fun ConnectionState.allowsSwitching(): Boolean =
        this is ConnectionState.Disconnected || this is ConnectionState.Error
}
