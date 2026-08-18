package nl.jwdr.ooc.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import nl.jwdr.ooc.AppContainer
import nl.jwdr.ooc.OocApplication

/**
 * The ViewModel wiring convention: obtain a ViewModel whose dependencies come
 * from the [AppContainer].
 *
 * ```
 * val viewModel = containerViewModel { ShellViewModel(it.diagnosticsManager) }
 * ```
 */
@Composable
inline fun <reified VM : ViewModel> containerViewModel(
    crossinline create: (AppContainer) -> VM,
): VM {
    val container =
        (LocalContext.current.applicationContext as OocApplication).container
    return viewModel(factory = viewModelFactory { initializer { create(container) } })
}
