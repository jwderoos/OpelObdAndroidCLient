package nl.jwdr.ooc.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.jwdr.ooc.catalogstore.SafCatalogTree
import nl.jwdr.ooc.catalogstore.SafSingleFileTree
import nl.jwdr.ooc.ui.catalog.CatalogScreen
import nl.jwdr.ooc.ui.catalog.CatalogViewModel
import nl.jwdr.ooc.ui.containerViewModel

/** Settings / import: catalog import via SAF. Adapter config lands with #19/#20. */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val viewModel = containerViewModel { CatalogViewModel(it.catalogRepository) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            viewModel.import(
                SafCatalogTree(context, uri),
                label = uri.lastPathSegment?.substringAfterLast('/')
                    ?.substringAfterLast(':') ?: "Imported catalog",
            )
        }
    }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.import(SafSingleFileTree(context, uri), label = "opeldata.txt")
        }
    }

    CatalogScreen(
        state = state,
        onImportFolder = { folderPicker.launch(null) },
        onImportFile = { filePicker.launch(arrayOf("text/plain", "application/octet-stream", "*/*")) },
        modifier = modifier,
    )
}
