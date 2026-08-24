package nl.jwdr.ooc.ui.settings

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow

/**
 * Debug-only settings that exist purely to let a future session capture
 * fine-grained diagnostics on real hardware without recompiling. Off by
 * default; see `AppContainer.verboseOpComLogging`.
 */
class DebugViewModel(
    val verboseOpComLogging: StateFlow<Boolean>,
    private val setVerboseOpComLogging: (Boolean) -> Unit,
) : ViewModel() {
    fun setVerboseOpComLogging(enabled: Boolean) = setVerboseOpComLogging.invoke(enabled)
}
