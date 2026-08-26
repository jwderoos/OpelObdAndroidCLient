package nl.jwdr.ooc.catalogstore

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory [CatalogDao] mirroring the SQL of the real queries. */
class FakeCatalogDao : CatalogDao {
    val stored = MutableStateFlow<CatalogPayload?>(null)

    override fun observeCatalog(): Flow<CatalogEntity?> = stored.map { it?.catalog }
    override fun observeEcuCount(): Flow<Int> = stored.map { it?.ecus?.size ?: 0 }
    override suspend fun ecusByCatalogKey(catalogKey: String): List<EcuEntity> =
        stored.value?.ecus?.filter { it.catalogKey == catalogKey }.orEmpty()
    override fun observeVehicles(): Flow<List<VehicleRef>> = stored.map { payload ->
        payload?.ecus.orEmpty()
            .map { VehicleRef(it.modelYear, it.vehicle) }
            .distinct()
            .sortedWith(compareBy({ it.modelYear }, { it.vehicle }))
    }
    override fun observeVehicleNames(): Flow<List<String>> = stored.map { payload ->
        payload?.ecus.orEmpty().map { it.vehicle }.distinct().sorted()
    }
    override suspend fun yearsForVehicle(vehicle: String): List<String> =
        stored.value?.ecus.orEmpty()
            .filter { it.vehicle == vehicle }
            .map { it.modelYear }
            .distinct()
            .sorted()
    override suspend fun groupsForVehicleYear(modelYear: String, vehicle: String): List<String> =
        stored.value?.ecus.orEmpty()
            .filter { it.modelYear == modelYear && it.vehicle == vehicle }
            .map { it.groupName }
            .distinct()
            .sorted()
    override suspend fun canEcusForVehicle(modelYear: String, vehicle: String): List<EcuEntity> =
        stored.value?.ecus.orEmpty()
            .filter { it.modelYear == modelYear && it.vehicle == vehicle && it.addressType == "CAN" }
            .sortedBy { it.name }
    override suspend fun canEcusForVehicleGroup(
        modelYear: String,
        vehicle: String,
        groupName: String,
    ): List<EcuEntity> =
        stored.value?.ecus.orEmpty()
            .filter {
                it.modelYear == modelYear && it.vehicle == vehicle &&
                    it.groupName == groupName && it.addressType == "CAN"
            }
            .sortedBy { it.name }
    override suspend fun updateSelectedVehicle(modelYear: String?, vehicle: String?) {
        stored.value = stored.value?.let {
            it.copy(catalog = it.catalog.copy(selectedModelYear = modelYear, selectedVehicle = vehicle))
        }
    }
    override suspend fun filesFor(kind: String, fileKey: String): List<CatalogFileEntity> =
        stored.value?.files?.filter { it.kind == kind && it.fileKey == fileKey }.orEmpty()
    override suspend fun fileKeysFor(kind: String): List<String> =
        stored.value?.files?.filter { it.kind == kind }?.map { it.fileKey }?.distinct().orEmpty()
    override suspend fun deleteCatalogs() { stored.value = null }
    override suspend fun deleteEcus() {}
    override suspend fun deleteFiles() {}
    override suspend fun insertCatalog(catalog: CatalogEntity) = throw AssertionError("use replaceCatalog")
    override suspend fun insertEcus(ecus: List<EcuEntity>) = throw AssertionError("use replaceCatalog")
    override suspend fun insertFiles(files: List<CatalogFileEntity>) = throw AssertionError("use replaceCatalog")
    override suspend fun replaceCatalog(payload: CatalogPayload) { stored.value = payload }
}
