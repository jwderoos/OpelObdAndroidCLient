package nl.jwdr.ooc.catalog

/**
 * Where one output-test display tag lives in the GMLAN periodic-data stream:
 * the DPID whose broadcast carries it and the 0-based offset into that
 * DPID's data bytes, plus the data row that decodes the byte.
 */
data class TagBinding(
    val tag: String,
    val row: DataRow,
    val dpid: Int,
    val byteIndex: Int,
)

/**
 * Resolves output-test `**TAG**` display tags against a measuring-block
 * catalog. MEASDATA is a scheduling-rate byte followed by DPID ids, and a
 * block's enabled rows spread over those DPIDs in table order at
 * [ROWS_PER_DPID] rows per DPID, one byte per row (verified against
 * recorded sessions; see the 2026-08-19 design spec).
 */
object DisplayTagBindings {

    /** A UUDT frame carries the DPID id plus 7 data bytes. */
    const val ROWS_PER_DPID = 7

    /**
     * One binding per element of [tags] that a tagged data row matches,
     * in [tags] order; unmatched tags are skipped.
     */
    fun resolve(catalog: MeasuringBlockCatalog, tags: List<String>): List<TagBinding> {
        if (tags.isEmpty()) return emptyList()
        val found = mutableMapOf<String, TagBinding>()
        for (block in catalog.blocks) {
            // measData[0] is the scheduling rate, not a DPID.
            val dpids = block.measData.drop(1)
            if (dpids.isEmpty()) continue
            catalog.rowsFor(block).forEachIndexed { position, row ->
                val tag = row.tag ?: return@forEachIndexed
                if (tag !in tags || tag in found) return@forEachIndexed
                val dpidIndex = position / ROWS_PER_DPID
                if (dpidIndex >= dpids.size) return@forEachIndexed
                found[tag] = TagBinding(tag, row, dpids[dpidIndex], position % ROWS_PER_DPID)
            }
        }
        return tags.mapNotNull(found::get)
    }
}
