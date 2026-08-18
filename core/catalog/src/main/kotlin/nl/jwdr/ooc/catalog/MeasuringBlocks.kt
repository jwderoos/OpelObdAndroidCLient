package nl.jwdr.ooc.catalog

/** One displayable value row of a measuring-block data table. */
data class DataRow(
    val label: String,
    /** Unit as written, brackets stripped (`km/h`, `°C`), for numeric rows. */
    val unit: String? = null,
    /** State labels indexed by raw value, for enumerated rows. */
    val states: List<String> = emptyList(),
    /** Internal `**NAME**` tag, preserved verbatim (without the asterisks). */
    val tag: String? = null,
)

/** One measuring block (a live-data screen the user can open). */
data class MeasuringBlock(
    val number: Int,
    val title: String,
    /**
     * Identifier bytes requested to populate the block, as written in
     * MEASDATA. Empty for raw-command blocks (see [rawCommands]).
     */
    val measData: List<Int>,
    /** 1-based inclusive row range into the data table. */
    val enabledRows: IntRange,
    /**
     * PRE_MEAS setup commands (typically dynamicallyDefineLocalIdentifier,
     * 0x2C) to send before reading; preserved verbatim, not yet executed by
     * the app.
     */
    val preMeas: List<List<Int>> = emptyList(),
    /**
     * MEASBLOCKCMD raw request frames for blocks defined without MEASDATA
     * (old K-line engine files); preserved verbatim, not yet executed.
     */
    val rawCommands: List<List<Int>> = emptyList(),
)

/** Parsed content of one `MeasuringBlocks/<KEY>.MBF.txt` file. */
data class MeasuringBlockCatalog(
    val blocks: List<MeasuringBlock>,
    val dataRows: List<DataRow>,
    /** Standalone `ID=` value, preserved verbatim; semantics not yet established. */
    val ecuId: String? = null,
) {
    /** The data rows visible in [block], in table order. */
    fun rowsFor(block: MeasuringBlock): List<DataRow> =
        block.enabledRows.map { dataRows[it - 1] }
}
