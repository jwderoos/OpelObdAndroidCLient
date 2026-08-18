package nl.jwdr.ooc.catalog

/** One decoded value row of a live measuring-block reading. */
data class RowReading(
    val row: DataRow,
    /** Unsigned raw byte value, or null when the record had no byte for this row. */
    val raw: Int?,
    /** Display text: state label, decimal value, or a placeholder when absent. */
    val display: String,
)

/** A decoded measuring-block record. */
data class BlockReading(
    val block: MeasuringBlock,
    val rows: List<RowReading>,
    /** Record bytes beyond the enabled rows; kept visible rather than dropped. */
    val unmappedBytes: ByteArray,
)

/**
 * Maps raw record bytes onto a block's enabled data rows.
 *
 * The catalog format documents no byte layout or scaling, so this uses the only
 * mapping its state rows imply: one byte per enabled row, in table order. Rows
 * without a byte read as no-data; surplus bytes are surfaced as unmapped.
 */
object MeasuringBlockDecoder {

    const val NO_DATA = "—"

    fun decode(block: MeasuringBlock, rows: List<DataRow>, record: ByteArray): BlockReading {
        val readings = rows.mapIndexed { index, row ->
            val raw = record.getOrNull(index)?.toInt()?.and(0xFF)
            RowReading(row, raw, displayFor(row, raw))
        }
        val unmapped = if (record.size > rows.size) record.copyOfRange(rows.size, record.size) else ByteArray(0)
        return BlockReading(block, readings, unmapped)
    }

    private fun displayFor(row: DataRow, raw: Int?): String = when {
        raw == null -> NO_DATA
        row.states.isNotEmpty() -> row.states.getOrNull(raw) ?: "0x%02X".format(raw)
        else -> raw.toString()
    }
}
