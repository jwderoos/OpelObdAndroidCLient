package nl.jwdr.ooc.catalog

/** Parses `ErrorCodes/<KEY>.txt` files. */
object FaultCodeParser {

    private val codeLine = Regex("""[PCBU]?\d{4,5}""")
    private val symptomLine = Regex("""-(\d+)\t+(.*)""")

    fun parse(text: String, fileName: String): FaultCodeCatalog {
        var measuringBlockKey: String? = null
        val codes = mutableListOf<FaultCode>()
        var currentCode: String? = null

        for (line in CatalogText.contentLines(text)) {
            val symptom = symptomLine.matchEntire(line.text)
            when {
                line.text.startsWith("[MB]") -> {
                    measuringBlockKey = line.text.removePrefix("[MB]").trim()
                }
                symptom != null -> {
                    val code = currentCode ?: throw CatalogFormatException(
                        fileName, line.number, "symptom text without a preceding fault code",
                    )
                    codes += FaultCode(
                        code = code,
                        symptom = symptom.groupValues[1].toInt(),
                        text = symptom.groupValues[2].trim(),
                    )
                }
                codeLine.matchEntire(line.text) != null -> currentCode = line.text
                else -> throw CatalogFormatException(
                    fileName, line.number, "unexpected line: '${line.text}'",
                )
            }
        }
        return FaultCodeCatalog(measuringBlockKey, codes)
    }
}
