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

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val header = blockHeader.matchEntire(line.text)
            when {
                header != null -> {
                    val number = header.groupValues[1].toInt()
                    val title = header.groupValues[2].trim()
                    var measData: List<Int>? = null
                    var range: IntRange? = null
                    if (lines.getOrNull(i + 1)?.text != "[begin]") {
                        fail(line.number, "block ##MB${header.groupValues[1]} is not followed by [begin]")
                    }
                    i += 2
                    var closed = false
                    while (i < lines.size) {
                        val body = lines[i]
                        when {
                            body.text == "[end]" -> { closed = true; i++; break }
                            body.text == "DISABLE_ALL" -> i++
                            body.text.startsWith("MEASDATA=") -> {
                                measData = body.text.removePrefix("MEASDATA=")
                                    .split(',')
                                    .filter { it.isNotBlank() }
                                    .map {
                                        it.trim().toIntOrNull(16)
                                            ?: fail(body.number, "invalid MEASDATA byte '$it'")
                                    }
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
                    if (!closed) fail(line.number, "block ##MB$number has no [end]")
                    blocks += MeasuringBlock(
                        number = number,
                        title = title,
                        measData = measData ?: fail(line.number, "block ##MB$number has no MEASDATA"),
                        enabledRows = range ?: fail(line.number, "block ##MB$number has no ENABLE_RANGE"),
                    )
                }
                line.text.startsWith("ID=") -> {
                    ecuId = line.text.removePrefix("ID=").trim()
                    i++
                }
                line.text == "[MEASURING BLOCK DATA]" -> {
                    i++
                    while (i < lines.size) {
                        dataRows += parseDataRow(lines[i], fileName)
                        i++
                    }
                }
                else -> fail(line.number, "unexpected line: '${line.text}'")
            }
        }

        for (block in blocks) {
            if (block.enabledRows.last > dataRows.size) {
                fail(
                    null,
                    "block ##MB${block.number} enables rows ${block.enabledRows.first}-" +
                        "${block.enabledRows.last} but the data table has only ${dataRows.size} rows",
                )
            }
        }
        return MeasuringBlockCatalog(blocks, dataRows, ecuId)
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
