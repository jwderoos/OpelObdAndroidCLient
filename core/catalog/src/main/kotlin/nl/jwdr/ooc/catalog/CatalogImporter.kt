package nl.jwdr.ooc.catalog

/**
 * Read access to a catalog tree being imported. Implemented over SAF in the
 * app and over plain maps/directories in tests.
 */
interface CatalogTree {
    /** File names (not paths) directly under [directory], "" for the root. */
    fun list(directory: String): List<String>

    /** File content, or null when [path] does not exist. */
    fun read(path: String): ByteArray?
}

/** Which per-ECU catalog directory a file came from. */
enum class CatalogFileKind(val directory: String) {
    MEASURING_BLOCKS("MeasuringBlocks"),
    ERROR_CODES("ErrorCodes"),
    OUTPUT_TESTS("OutputTests"),
    CODING("CANVARCODING"),
}

/** One validated per-ECU file, kept verbatim for lazy parsing after import. */
class CatalogFile(
    val kind: CatalogFileKind,
    /** Catalog key linking back to `opeldata.txt` (file name up to the first dot). */
    val key: String,
    val fileName: String,
    val bytes: ByteArray,
)

/** A fully validated catalog, ready to persist. */
class ImportedCatalog(
    val ecuDefinitions: List<EcuDefinition>,
    val files: List<CatalogFile>,
    /** SHA-256 over all imported content; identifies the source for re-import. */
    val sourceHash: String,
)

/**
 * Walks a decoded catalog tree, validates every recognized file by parsing it,
 * and produces the import payload. Throws [CatalogFormatException] with a
 * user-presentable message on any invalid or missing required file.
 */
object CatalogImporter {

    private const val OPELDATA = "opeldata.txt"

    fun import(tree: CatalogTree): ImportedCatalog {
        val opelDataBytes = tree.read(OPELDATA)
            ?: throw CatalogFormatException(OPELDATA, null, "not found — select the decoded catalog folder containing opeldata.txt")
        val ecuDefinitions = OpelDataParser.parse(CatalogText.decode(opelDataBytes), OPELDATA)

        val hashed = sortedMapOf(OPELDATA to opelDataBytes)
        val files = mutableListOf<CatalogFile>()
        for (kind in CatalogFileKind.entries) {
            for (fileName in tree.list(kind.directory).sorted()) {
                val path = "${kind.directory}/$fileName"
                val bytes = tree.read(path) ?: continue
                val text = CatalogText.decode(bytes)
                when (kind) {
                    CatalogFileKind.MEASURING_BLOCKS -> MeasuringBlockParser.parse(text, fileName)
                    CatalogFileKind.ERROR_CODES -> FaultCodeParser.parse(text, fileName)
                    CatalogFileKind.OUTPUT_TESTS -> OutputTestParser.parse(text, fileName)
                    CatalogFileKind.CODING -> CodingTableParser.parse(text, fileName)
                }
                files += CatalogFile(
                    kind = kind,
                    key = fileName.substringBefore('.'),
                    fileName = fileName,
                    bytes = bytes,
                )
                hashed[path] = bytes
            }
        }
        return ImportedCatalog(ecuDefinitions, files, sourceHash(hashed))
    }

    private fun sourceHash(files: Map<String, ByteArray>): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        files.forEach { (path, bytes) ->
            digest.update(path.toByteArray())
            digest.update(0)
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
