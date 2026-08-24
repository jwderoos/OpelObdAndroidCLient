package nl.jwdr.ooc.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.jwdr.ooc.catalogstore.SafCatalogTree
import nl.jwdr.ooc.catalogstore.SafSingleFileTree
import nl.jwdr.ooc.ui.catalog.CatalogScreen
import nl.jwdr.ooc.ui.catalog.CatalogViewModel
import nl.jwdr.ooc.ui.containerViewModel

/** Settings: adapter selection (#19) and catalog import via SAF. */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val viewModel = containerViewModel { CatalogViewModel(it.catalogRepository) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()

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

    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        AdapterSection(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp))
        CatalogScreen(
            state = state,
            onImportFolder = { folderPicker.launch(null) },
            onImportFile = { filePicker.launch(arrayOf("text/plain", "application/octet-stream", "*/*")) },
            progress = progress,
        )
        DebugSection(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp))
    }
}
