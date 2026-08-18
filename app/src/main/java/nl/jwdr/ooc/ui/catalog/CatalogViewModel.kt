package nl.jwdr.ooc.ui.catalog

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.jwdr.ooc.catalog.CatalogFormatException
import nl.jwdr.ooc.catalog.CatalogTree
import nl.jwdr.ooc.catalogstore.CatalogRepository
import nl.jwdr.ooc.catalogstore.CatalogSummary

data class CatalogUiState(
    val summary: CatalogSummary? = null,
    val importing: Boolean = false,
    val errorMessage: String? = null,
)

/** Running import progress: [done] of [total] files, [path] just validated. */
data class ImportProgress(val done: Int, val total: Int, val path: String)

class CatalogViewModel(
    private val repository: CatalogRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val importing = MutableStateFlow(false)
    private val errorMessage = MutableStateFlow<String?>(null)

    private val _progress = MutableStateFlow<ImportProgress?>(null)

    /**
     * Per-file progress of the running import. Reset when a new import
     * starts, kept after completion — the UI only shows it while
     * [CatalogUiState.importing].
     */
    val progress: StateFlow<ImportProgress?> = _progress

    val state: StateFlow<CatalogUiState> =
        combine(repository.summary, importing, errorMessage) { summary, busy, error ->
            CatalogUiState(summary, busy, error)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, CatalogUiState())

    fun import(tree: CatalogTree, label: String) {
        viewModelScope.launch {
            importing.value = true
            errorMessage.value = null
            _progress.value = null
            try {
                withContext(ioDispatcher) {
                    repository.import(tree, label) { done, total, path ->
                        _progress.value = ImportProgress(done, total, path)
                        Log.i(LOG_TAG, "validated $done/$total: $path")
                    }
                }
            } catch (e: CatalogFormatException) {
                errorMessage.value = e.message
            } catch (e: Exception) {
                errorMessage.value = "Import failed: ${e.message ?: "unknown error"}"
            } finally {
                importing.value = false
            }
        }
    }

    private companion object {
        /** Debug channel: `adb logcat -s CatalogImport` follows a live import. */
        const val LOG_TAG = "CatalogImport"
    }
}
