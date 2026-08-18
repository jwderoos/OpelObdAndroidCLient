package nl.jwdr.ooc.catalogstore

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import nl.jwdr.ooc.catalog.CatalogImporter
import nl.jwdr.ooc.catalog.CatalogTree

/** What the UI shows about the stored catalog. */
data class CatalogSummary(
    val label: String,
    val sourceHash: String,
    val importedAtEpochMillis: Long,
    val ecuCount: Int,
)

class CatalogRepository(
    private val dao: CatalogDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val summary: Flow<CatalogSummary?> =
        combine(dao.observeCatalog(), dao.observeEcuCount()) { catalog, ecuCount ->
            catalog?.let {
                CatalogSummary(it.label, it.sourceHash, it.importedAtEpochMillis, ecuCount)
            }
        }

    /** Validates and stores [tree], replacing any previous catalog. */
    suspend fun import(tree: CatalogTree, label: String) {
        val imported = CatalogImporter.import(tree)
        dao.replaceCatalog(imported.toPayload(label, clock()))
    }
}
