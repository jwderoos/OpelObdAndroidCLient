package nl.jwdr.ooc.catalog

/** Parses `CANVARCODING/<KEY>.0x<DID>.txt` files. */
object CodingTableParser {

    private val didInName = Regex("""\.0[xX]([0-9A-Fa-f]+)\.""")
    private val didEntry = Regex("""([0-9A-Fa-f]+),(\d+)""")
    private const val DISABLED = "**DISABLED**"

    /** [fileName] must carry the coding DID, e.g. `EXAMPLIADIS.0x1201.txt`. */
    fun parse(text: String, fileName: String): CodingTable {
        val dataIdentifier = didInName.find(fileName)?.groupValues?.get(1)?.toInt(16)
            ?: throw CatalogFormatException(
                fileName, null, "file name does not carry a coding data identifier (expected '<KEY>.0x<DID>.txt')",
            )

        val didEntries = mutableListOf<DidEntry>()
        val rows = mutableListOf<CodingRow>()
        var section: String? = null
        for (line in CatalogText.contentLines(text)) {
            when (line.text) {
                "[DID_begin]" -> section = "did"
                "[DID_end]" -> section = null
                "[VARIANT CODING DATA]" -> section = "rows"
                else -> when (section) {
                    "did" -> {
                        val match = didEntry.matchEntire(line.text)
                            ?: throw CatalogFormatException(
                                fileName, line.number, "expected 'hexId,count', found '${line.text}'",
                            )
                        didEntries += DidEntry(
                            id = match.groupValues[1].toInt(16),
                            count = match.groupValues[2].toInt(),
                        )
                    }
                    "rows" -> {
                        val fields = line.text.split(',').map { it.trim() }.dropLastWhile { it.isEmpty() }
                        if (fields.size < 2) {
                            throw CatalogFormatException(
                                fileName, line.number, "coding row needs at least a label and a type",
                            )
                        }
                        rows += CodingRow(
                            label = fields[0],
                            values = fields.drop(2).map {
                                CodingValue(label = it, selectable = it != DISABLED)
                            },
                        )
                    }
                    else -> throw CatalogFormatException(
                        fileName, line.number, "unexpected line outside any section: '${line.text}'",
                    )
                }
            }
        }
        return CodingTable(dataIdentifier, didEntries, rows)
    }
}
