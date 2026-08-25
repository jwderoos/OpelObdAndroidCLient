package nl.jwdr.ooc.ui.settings

import androidx.lifecycle.ViewModel
import java.io.File
import kotlinx.coroutines.flow.StateFlow

/**
 * Debug-only settings that exist purely to let a future session capture
 * fine-grained diagnostics on real hardware without recompiling. Off by
 * default; see `AppContainer.verboseOpComLogging`.
 */
class DebugViewModel(
    val verboseOpComLogging: StateFlow<Boolean>,
    private val setVerboseOpComLogging: (Boolean) -> Unit,
    val recordSessions: StateFlow<Boolean>,
    private val setRecordSessions: (Boolean) -> Unit,
    /** Zips the newest capture directory and returns it, or null when nothing was recorded yet. */
    private val zipLatestCapture: () -> File?,
) : ViewModel() {
    fun setVerboseOpComLogging(enabled: Boolean) = setVerboseOpComLogging.invoke(enabled)

    fun setRecordSessions(enabled: Boolean) = setRecordSessions.invoke(enabled)

    fun zipLatestCapture(): File? = zipLatestCapture.invoke()
}
