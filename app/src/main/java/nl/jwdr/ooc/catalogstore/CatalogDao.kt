package nl.jwdr.ooc.catalogstore

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface CatalogDao {

    @Query("SELECT * FROM catalogs WHERE id = ${CatalogEntity.SINGLETON_ID}")
    fun observeCatalog(): Flow<CatalogEntity?>

    @Query("SELECT COUNT(*) FROM ecus")
    fun observeEcuCount(): Flow<Int>

    @Query("SELECT * FROM ecus WHERE catalogKey = :catalogKey")
    suspend fun ecusByCatalogKey(catalogKey: String): List<EcuEntity>

    @Query("SELECT DISTINCT modelYear, vehicle FROM ecus ORDER BY modelYear, vehicle")
    fun observeVehicles(): Flow<List<VehicleRef>>

    @Query(
        "SELECT * FROM ecus WHERE modelYear = :modelYear AND vehicle = :vehicle " +
            "AND addressType = 'CAN' ORDER BY name",
    )
    suspend fun canEcusForVehicle(modelYear: String, vehicle: String): List<EcuEntity>

    @Query("UPDATE catalogs SET selectedModelYear = :modelYear, selectedVehicle = :vehicle")
    suspend fun updateSelectedVehicle(modelYear: String?, vehicle: String?)

    @Query("SELECT * FROM catalog_files WHERE kind = :kind AND fileKey = :fileKey")
    suspend fun filesFor(kind: String, fileKey: String): List<CatalogFileEntity>

    @Query("DELETE FROM catalogs")
    suspend fun deleteCatalogs()

    @Query("DELETE FROM ecus")
    suspend fun deleteEcus()

    @Query("DELETE FROM catalog_files")
    suspend fun deleteFiles()

    @Insert
    suspend fun insertCatalog(catalog: CatalogEntity)

    @Insert
    suspend fun insertEcus(ecus: List<EcuEntity>)

    @Insert
    suspend fun insertFiles(files: List<CatalogFileEntity>)

    /** Re-import replaces the stored catalog (design doc: versioning). */
    @Transaction
    suspend fun replaceCatalog(payload: CatalogPayload) {
        deleteFiles()
        deleteEcus()
        deleteCatalogs()
        insertCatalog(payload.catalog)
        insertEcus(payload.ecus)
        insertFiles(payload.files)
    }
}
