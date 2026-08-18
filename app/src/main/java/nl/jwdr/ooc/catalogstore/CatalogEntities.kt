package nl.jwdr.ooc.catalogstore

import androidx.room.Entity
import androidx.room.PrimaryKey

/** The single imported catalog; re-import replaces it (design doc: versioning). */
@Entity(tableName = "catalogs")
data class CatalogEntity(
    @PrimaryKey val id: Long = SINGLETON_ID,
    val label: String,
    val sourceHash: String,
    val importedAtEpochMillis: Long,
    // The vehicle the user works on; lives on the catalog row so re-import
    // (which replaces the row) can never leave a stale selection behind.
    val selectedModelYear: String? = null,
    val selectedVehicle: String? = null,
) {
    companion object {
        const val SINGLETON_ID = 1L
    }
}

/** One selectable vehicle of the catalog: a distinct model year + name pair. */
data class VehicleRef(
    val modelYear: String,
    val vehicle: String,
)

/** Flattened [nl.jwdr.ooc.catalog.EcuDefinition] row. */
@Entity(tableName = "ecus")
data class EcuEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val catalogId: Long,
    val modelYear: String,
    val vehicle: String,
    val groupName: String,
    val name: String,
    val systemName: String,
    val protocol: String,
    val builtinFunction: String?,
    val catalogKey: String?,
    val addressType: String,
    val canBus: String?,
    val bitRateTenthsKbps: Int?,
    val requestId: Int?,
    val secondaryId: Int?,
    val responseId: Int?,
    val baudRate: Int?,
    val klineAddress: Int?,
    val initType: Int?,
    val extra: Int?,
)

/** One verbatim per-ECU catalog file, parsed lazily by later milestones. */
@Entity(tableName = "catalog_files")
data class CatalogFileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val catalogId: Long,
    val kind: String,
    val fileKey: String,
    val fileName: String,
    val content: ByteArray,
)
