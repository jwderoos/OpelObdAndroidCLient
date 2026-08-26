package nl.jwdr.ooc.ui.settings

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow

/**
 * Expert mode: off by default, gates the Coding screen (issue #18). Kept
 * separate from [DebugViewModel]'s settings — this is a safety toggle for
 * end users, not diagnostic instrumentation for a development session.
 */
class ExpertModeViewModel(
    val expertMode: StateFlow<Boolean>,
    private val setExpertMode: (Boolean) -> Unit,
) : ViewModel() {
    fun setExpertMode(enabled: Boolean) = setExpertMode.invoke(enabled)
}
