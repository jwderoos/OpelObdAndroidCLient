package nl.jwdr.ooc.catalog

/** Parses `ErrorCodes/<KEY>.txt` files. */
object FaultCodeParser {

    /** SAE J2012 codes (low three nibbles are hex) or legacy numeric codes. */
    private val codeLine = Regex("""[PCBU]\d[0-9A-Fa-f]{3}|\d{2,6}""")
    /**
     * Symptom markers are hex bytes (`-10` = 0x10, `-E0`); `-?`/`-??` is the
     * any-symptom wildcard and `-D?` a nibble wildcard (any low nibble).
     */
    private val symptomLine = Regex("""-([0-9A-Fa-f]+|\?\??|[0-9A-Fa-f]\?) *\t+(.*)""")

    /** K-line style: code and text on one line, no symptom sub-lines. */
    private val inlineCodeLine = Regex("""([PCBU]\d[0-9A-Fa-f]{3}|\d{2,6}) *\t+(.*)""")

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
                // Variant-dispatch directives ([DEFAFAULT], [SELECTIVE],
                // [SUZUKIDIAG], ...): select sub-catalogs by hardware id;
                // semantics not implemented yet, tolerated and skipped.
                line.text.startsWith("[") -> Unit
                // Stray raw hex value (one real file); meaning not established.
                line.text.startsWith("0x") -> Unit
                symptom != null -> {
                    val code = currentCode ?: throw CatalogFormatException(
                        fileName, line.number, "symptom text without a preceding fault code",
                    )
                    val marker = symptom.groupValues[1]
                    val text = symptom.groupValues[2].trim()
                    when {
                        marker.startsWith("?") ->
                            codes += FaultCode(code, FaultCode.ANY_SYMPTOM, text)
                        // Nibble wildcard `-D?`: expand so lookups stay exact.
                        marker.endsWith("?") -> {
                            val high = marker.dropLast(1).toInt(16) shl 4
                            (0x0..0xF).forEach { codes += FaultCode(code, high or it, text) }
                        }
                        else -> codes += FaultCode(code, marker.toInt(16), text)
                    }
                }
                codeLine.matchEntire(line.text) != null -> currentCode = line.text
                inlineCodeLine.matchEntire(line.text) != null -> {
                    val match = inlineCodeLine.matchEntire(line.text)!!
                    currentCode = match.groupValues[1]
                    codes += FaultCode(
                        code = match.groupValues[1],
                        symptom = 0,
                        text = match.groupValues[2].trim(),
                    )
                }
                else -> throw CatalogFormatException(
                    fileName, line.number, "unexpected line: '${line.text}'",
                )
            }
        }
        return FaultCodeCatalog(measuringBlockKey, codes)
    }
}
