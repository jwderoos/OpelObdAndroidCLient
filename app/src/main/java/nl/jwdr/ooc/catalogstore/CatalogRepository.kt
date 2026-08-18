package nl.jwdr.ooc.catalogstore

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import nl.jwdr.ooc.catalog.CatalogImporter
import nl.jwdr.ooc.catalog.CatalogTree
import nl.jwdr.ooc.catalog.EcuDefinition

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

    /** The catalog's selectable vehicles, one per distinct model year + name. */
    val vehicles: Flow<List<VehicleRef>> = dao.observeVehicles()

    /** The persisted vehicle selection; null when none is chosen. */
    val selectedVehicle: Flow<VehicleRef?> = dao.observeCatalog().map { catalog ->
        val modelYear = catalog?.selectedModelYear ?: return@map null
        val vehicle = catalog.selectedVehicle ?: return@map null
        VehicleRef(modelYear, vehicle)
    }

    /** Persists [ref] as the working vehicle; null clears the selection. */
    suspend fun selectVehicle(ref: VehicleRef?) {
        dao.updateSelectedVehicle(ref?.modelYear, ref?.vehicle)
    }

    /** The diagnosable (CAN-addressed) ECUs of [ref], as domain definitions. */
    suspend fun canEcusFor(ref: VehicleRef): List<EcuDefinition> =
        dao.canEcusForVehicle(ref.modelYear, ref.vehicle).map { it.toDefinition() }
}
