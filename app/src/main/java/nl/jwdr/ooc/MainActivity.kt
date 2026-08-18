package nl.jwdr.ooc

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import nl.jwdr.ooc.catalogstore.CatalogDatabase
import nl.jwdr.ooc.catalogstore.CatalogRepository
import nl.jwdr.ooc.catalogstore.SafCatalogTree
import nl.jwdr.ooc.catalogstore.SafSingleFileTree
import nl.jwdr.ooc.ui.catalog.CatalogScreen
import nl.jwdr.ooc.ui.catalog.CatalogViewModel
import nl.jwdr.ooc.ui.theme.OpelOBDClientTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OpelOBDClientTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    CatalogRoute(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
private fun CatalogRoute(modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val viewModel: CatalogViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                CatalogViewModel(CatalogRepository(CatalogDatabase.get(context).catalogDao())) as T
        },
    )
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
