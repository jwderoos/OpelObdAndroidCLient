package nl.jwdr.ooc.catalog

/** Parses `MeasuringBlocks/<KEY>.MBF.txt` files. */
object MeasuringBlockParser {

    private val blockHeader = Regex("""##MB(\d+)=(.*)""")
    private val enableRange = Regex("""ENABLE_RANGE=(\d+)-(\d+)""")

    fun parse(text: String, fileName: String): MeasuringBlockCatalog {
        fun fail(line: Int?, problem: String): Nothing =
            throw CatalogFormatException(fileName, line, problem)

        val lines = CatalogText.contentLines(text)
        val blocks = mutableListOf<MeasuringBlock>()
        val dataRows = mutableListOf<DataRow>()
        var ecuId: String? = null
        var implicitMeasData: List<Int>? = null

        var i = 0
        var sawStructure = false
        while (i < lines.size) {
            val line = lines[i]
            val header = blockHeader.matchEntire(line.text)
            when {
                header != null -> {
                    val number = header.groupValues[1].toInt()
                    val title = header.groupValues[2].trim()
                    if (lines.getOrNull(i + 1)?.text != "[begin]") {
                        fail(line.number, "block ##MB${header.groupValues[1]} is not followed by [begin]")
                    }
                    val group = parseGroup(lines, i + 1, fileName)
                    i = group.nextIndex
                    if (group.measData == null && group.rawCommands.isEmpty()) {
                        fail(line.number, "block ##MB$number has neither MEASDATA nor MEASBLOCKCMD")
                    }
                    blocks += MeasuringBlock(
                        number = number,
                        title = title,
                        measData = group.measData.orEmpty(),
                        enabledRows = group.range
                            ?: fail(line.number, "block ##MB$number has no ENABLE_RANGE"),
                        preMeas = group.preMeas,
                        rawCommands = group.rawCommands,
                    )
                }
                // One real file continues a block with a headerless [begin]
                // group holding the next ENABLE_RANGE; merge it into the
                // preceding block.
                line.text == "[begin]" -> {
                    val previous = blocks.removeLastOrNull()
                        ?: fail(line.number, "[begin] group without a preceding ##MB block")
                    val group = parseGroup(lines, i, fileName)
                    i = group.nextIndex
                    val range = group.range
                        ?: fail(line.number, "continuation group has no ENABLE_RANGE")
                    blocks += previous.copy(
                        enabledRows = minOf(previous.enabledRows.first, range.first)..
                            maxOf(previous.enabledRows.last, range.last),
                    )
                }
                line.text.startsWith("ID=") -> {
                    ecuId = line.text.removePrefix("ID=").trim()
                    i++
                }
                // Standalone metadata like ID=; meaning not yet established
                // (docs/catalog-format.md), so tolerated and skipped.
                line.text.startsWith("SM=") -> i++
                // Headerless variant: one top-level MEASDATA and no ##MB
                // blocks — the whole data table forms a single implicit block.
                line.text.startsWith("MEASDATA=") -> {
                    implicitMeasData = parseByteList(line, "MEASDATA", fileName)
                    i++
                }
                // Unknown bracketed sections are skipped wholesale: [TABLEnnn]
                // scaling lookups of old K-line engine files (out of scope)
                // and one-off oddities like `[SUPPORTED IDETIFIERS]` (sic).
                line.text.startsWith("[") && line.text != "[MEASURING BLOCK DATA]" -> {
                    i++
                    while (i < lines.size && !lines[i].text.startsWith("[") &&
                        !lines[i].text.startsWith("##MB")
                    ) {
                        i++
                    }
                }
                line.text == "[MEASURING BLOCK DATA]" -> {
                    i++
                    while (i < lines.size) {
                        dataRows += parseDataRow(lines[i], fileName)
                        i++
                    }
                }
                // Junk is tolerated only before the first structural line:
                // one real file opens with a comment missing its `;`.
                !sawStructure -> i++
                else -> fail(line.number, "unexpected line: '${line.text}'")
            }
            if (header != null || line.text.startsWith("[") || line.text.startsWith("ID=") ||
                line.text.startsWith("SM=") || line.text.startsWith("MEASDATA=")
            ) {
                sawStructure = true
            }
        }

        implicitMeasData?.let { measData ->
            blocks += MeasuringBlock(
                number = 1,
                title = "",
                measData = measData,
                enabledRows = 1..dataRows.size,
            )
        }

        val validated = blocks.map { block ->
            // Stub files (blocks but no data table at all) keep their blocks
            // with nothing displayable.
            if (dataRows.isEmpty()) {
                return@map block.copy(enabledRows = IntRange.EMPTY)
            }
            if (block.enabledRows.first > dataRows.size) {
                fail(
                    null,
                    "block ##MB${block.number} enables rows ${block.enabledRows.first}-" +
                        "${block.enabledRows.last} but the data table has only ${dataRows.size} rows",
                )
            }
            // Real tables end with blank line(s) that ranges may still count;
            // blanks never occur mid-table, so clamping loses nothing.
            if (block.enabledRows.last > dataRows.size) {
                block.copy(enabledRows = block.enabledRows.first..dataRows.size)
            } else {
                block
            }
        }
        return MeasuringBlockCatalog(validated, dataRows, ecuId)
    }

    private class Group(
        val measData: List<Int>?,
        val range: IntRange?,
        val preMeas: List<List<Int>>,
        val rawCommands: List<List<Int>>,
        val nextIndex: Int,
    )

    /** Parses one `[begin]`..`[end]` group starting at [beginIndex]. */
    private fun parseGroup(lines: List<CatalogText.Line>, beginIndex: Int, fileName: String): Group {
        fun fail(line: Int?, problem: String): Nothing =
            throw CatalogFormatException(fileName, line, problem)
        var measData: List<Int>? = null
        var range: IntRange? = null
        val preMeas = mutableListOf<List<Int>>()
        val rawCommands = mutableListOf<List<Int>>()
        var i = beginIndex + 1
        while (i < lines.size) {
            val body = lines[i]
            when {
                body.text == "[end]" ->
                    return Group(measData, range, preMeas, rawCommands, nextIndex = i + 1)
                body.text == "DISABLE_ALL" -> i++
                body.text.startsWith("MEASDATA=") -> {
                    measData = parseByteList(body, "MEASDATA", fileName)
                    i++
                }
                body.text.startsWith("PRE_MEAS=") -> {
                    preMeas += parseByteList(body, "PRE_MEAS", fileName)
                    i++
                }
                body.text.startsWith("MEASBLOCKCMD=") -> {
                    rawCommands += parseByteList(body, "MEASBLOCKCMD", fileName)
                    i++
                }
                else -> {
                    val match = enableRange.matchEntire(body.text)
                        ?: fail(body.number, "unexpected line inside block: '${body.text}'")
                    range = match.groupValues[1].toInt()..match.groupValues[2].toInt()
                    i++
                }
            }
        }
        fail(lines[beginIndex].number, "group has no [end]")
    }

    /** `KEY=aa,bb,...` byte lists: bare or 0x-prefixed hex, trailing commas allowed. */
    private fun parseByteList(line: CatalogText.Line, key: String, fileName: String): List<Int> =
        line.text.removePrefix("$key=")
            .split(',')
            .filter { it.isNotBlank() }
            .map { field ->
                field.trim().removePrefix("0x").removePrefix("0X").toIntOrNull(16)
                    ?: throw CatalogFormatException(fileName, line.number, "invalid $key byte '$field'")
            }

    internal fun parseDataRow(line: CatalogText.Line, fileName: String): DataRow {
        val fields = line.text.split(',').map { it.trim() }.dropLastWhile { it.isEmpty() }
        if (fields.size < 2) {
            throw CatalogFormatException(fileName, line.number, "data row needs at least a label and a type")
        }
        val label = fields[0]
        var values = fields.drop(2)
        var tag: String? = null
        values.lastOrNull()?.let {
            if (it.startsWith("**") && it.endsWith("**")) {
                tag = it.removeSurrounding("**")
                values = values.dropLast(1)
            }
        }
        val unit = values.singleOrNull()
            ?.takeIf { it.startsWith("[") && it.endsWith("]") }
            ?.removeSurrounding("[", "]")
        return if (unit != null) {
            DataRow(label = label, unit = unit, tag = tag)
        } else {
            DataRow(label = label, states = values, tag = tag)
        }
    }
}
