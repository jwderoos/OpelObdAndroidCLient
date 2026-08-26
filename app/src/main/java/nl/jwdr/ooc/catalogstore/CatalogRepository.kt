package nl.jwdr.ooc.catalogstore

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import nl.jwdr.ooc.catalog.CatalogFileKind
import nl.jwdr.ooc.catalog.CatalogImporter
import nl.jwdr.ooc.catalog.CatalogText
import nl.jwdr.ooc.catalog.CatalogTree
import nl.jwdr.ooc.catalog.CodingTable
import nl.jwdr.ooc.catalog.CodingTableParser
import nl.jwdr.ooc.catalog.EcuDefinition
import nl.jwdr.ooc.catalog.FaultCodeCatalog
import nl.jwdr.ooc.catalog.FaultCodeParser
import nl.jwdr.ooc.catalog.MeasuringBlockCatalog
import nl.jwdr.ooc.catalog.MeasuringBlockParser
import nl.jwdr.ooc.catalog.OutputTestCatalog
import nl.jwdr.ooc.catalog.OutputTestParser

/** What the UI shows about the stored catalog. */
data class CatalogSummary(
    val label: String,
    val sourceHash: String,
    val importedAtEpochMillis: Long,
    val ecuCount: Int,
)

/** Outcome of resolving which ECU group to query, per [CatalogRepository.resolveEcuGroup]. */
sealed interface EcuGroupResolution {
    /** [group] is settled. */
    data class Resolved(val group: String) : EcuGroupResolution

    /** More than one group exists and none is chosen yet; offer [groups]. */
    data class NeedsPick(val groups: List<String>) : EcuGroupResolution
}

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
    suspend fun import(
        tree: CatalogTree,
        label: String,
        onProgress: (done: Int, total: Int, path: String) -> Unit = { _, _, _ -> },
    ) {
        val imported = CatalogImporter.import(tree, onProgress)
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

    /** The catalog's distinct vehicle names, one per distinct `vehicle` value. */
    val vehicleNames: Flow<List<String>> = dao.observeVehicleNames()

    /** The distinct model years [vehicle] is catalogued under. */
    suspend fun yearsFor(vehicle: String): List<String> = dao.yearsForVehicle(vehicle)

    /** The distinct ECU groups (e.g. Engine, Chassis) catalogued for [ref]. */
    suspend fun groupsFor(ref: VehicleRef): List<String> =
        dao.groupsForVehicleYear(ref.modelYear, ref.vehicle)

    /** The diagnosable (CAN-addressed) ECUs of [ref] within [group], as domain definitions. */
    suspend fun canEcusFor(ref: VehicleRef, group: String): List<EcuDefinition> =
        dao.canEcusForVehicleGroup(ref.modelYear, ref.vehicle, group).map { it.toDefinition() }

    /**
     * Resolves which ECU group to query for [ref]: [selectedGroup] if
     * already chosen, the vehicle's only group if it has just one, or a pick
     * request over [groupsFor] otherwise. Mirrors the ECU list's own
     * skip-if-single group step so every ECU-picking screen behaves alike.
     */
    suspend fun resolveEcuGroup(ref: VehicleRef, selectedGroup: String?): EcuGroupResolution {
        if (selectedGroup != null) return EcuGroupResolution.Resolved(selectedGroup)
        val groups = groupsFor(ref)
        val single = groups.singleOrNull()
        return if (single != null) EcuGroupResolution.Resolved(single) else EcuGroupResolution.NeedsPick(groups)
    }

    /**
     * The parsed fault-code texts of [catalogKey], merging suffixed variant
     * files; null when the catalog has no fault-code file for the key.
     */
    /**
     * The parsed measuring blocks of [catalogKey]; null when the catalog has
     * no measuring-block file for the key. Row indices are per-file, so
     * unlike fault codes, variant files cannot be merged; the first file wins.
     */
    /** Catalog keys that have a measuring-blocks file (i.e. offer live data). */
    suspend fun measuringBlockKeys(): Set<String> =
        dao.fileKeysFor(CatalogFileKind.MEASURING_BLOCKS.name).toSet()

    suspend fun measuringBlocksFor(catalogKey: String): MeasuringBlockCatalog? {
        val file = dao.filesFor(CatalogFileKind.MEASURING_BLOCKS.name, catalogKey).firstOrNull()
            ?: return null
        return MeasuringBlockParser.parse(CatalogText.decode(file.content), file.fileName)
    }

    /** Catalog keys that have an output-tests file (i.e. offer output tests). */
    suspend fun outputTestKeys(): Set<String> =
        dao.fileKeysFor(CatalogFileKind.OUTPUT_TESTS.name).toSet()

    /**
     * The parsed output tests of [catalogKey]; null when the catalog has no
     * output-test file for the key. Like measuring blocks, variant files are
     * not merged; the first file wins.
     */
    suspend fun outputTestsFor(catalogKey: String): OutputTestCatalog? {
        val file = dao.filesFor(CatalogFileKind.OUTPUT_TESTS.name, catalogKey).firstOrNull()
            ?: return null
        return OutputTestParser.parse(CatalogText.decode(file.content), file.fileName)
    }

    /** Catalog keys that have a coding file (i.e. offer ECU coding). */
    suspend fun codingTableKeys(): Set<String> =
        dao.fileKeysFor(CatalogFileKind.CODING.name).toSet()

    /** Every coding table for [catalogKey] — one per `.0x<DID>.txt` file, unlike measuring blocks/output tests, none are merged or "first wins": each is a distinct DID table. */
    suspend fun codingTablesFor(catalogKey: String): List<CodingTable> =
        dao.filesFor(CatalogFileKind.CODING.name, catalogKey).map {
            CodingTableParser.parse(CatalogText.decode(it.content), it.fileName)
        }

    suspend fun faultCodesFor(catalogKey: String): FaultCodeCatalog? {
        val files = dao.filesFor(CatalogFileKind.ERROR_CODES.name, catalogKey)
        if (files.isEmpty()) return null
        val parsed = files.map {
            FaultCodeParser.parse(CatalogText.decode(it.content), it.fileName)
        }
        return FaultCodeCatalog(
            measuringBlockKey = parsed.firstNotNullOfOrNull { it.measuringBlockKey },
            codes = parsed.flatMap { it.codes },
        )
    }
}
