package nl.jwdr.ooc.ui.catalog

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

class CatalogViewModel(
    private val repository: CatalogRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val importing = MutableStateFlow(false)
    private val errorMessage = MutableStateFlow<String?>(null)

    val state: StateFlow<CatalogUiState> =
        combine(repository.summary, importing, errorMessage) { summary, busy, error ->
            CatalogUiState(summary, busy, error)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, CatalogUiState())

    fun import(tree: CatalogTree, label: String) {
        viewModelScope.launch {
            importing.value = true
            errorMessage.value = null
            try {
                withContext(ioDispatcher) { repository.import(tree, label) }
            } catch (e: CatalogFormatException) {
                errorMessage.value = e.message
            } catch (e: Exception) {
                errorMessage.value = "Import failed: ${e.message ?: "unknown error"}"
            } finally {
                importing.value = false
            }
        }
    }
}
