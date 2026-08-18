package nl.jwdr.ooc.catalog

/** One `hexId,count` pair from a coding table's DID list; mapping to rows not yet established. */
data class DidEntry(val id: Int, val count: Int)

/** One selectable value slot of a coding row. `**DISABLED**` slots are not selectable. */
data class CodingValue(val label: String, val selectable: Boolean = true)

/** One coding field: label plus its value labels indexed by raw coded value. */
data class CodingRow(val label: String, val values: List<CodingValue>)

/** Parsed content of one `CANVARCODING/<KEY>.0x<DID>.txt` file. */
data class CodingTable(
    /** Coding data identifier from the file name, e.g. 0x1201. */
    val dataIdentifier: Int,
    val didEntries: List<DidEntry>,
    val rows: List<CodingRow>,
)
