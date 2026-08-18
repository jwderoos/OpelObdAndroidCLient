package nl.jwdr.ooc.catalog

/** Parses `opeldata.txt` — the vehicle / ECU address map. */
object OpelDataParser {

    private val builtinFunctions = setOf("IDENT", "GETECULIST", "GETERRORCODESLIST")
    private const val NO_KEY = "????"

    fun parse(text: String, fileName: String = "opeldata.txt"): List<EcuDefinition> =
        CatalogText.contentLines(text).map { line -> parseRecord(line, fileName) }

    private fun parseRecord(line: CatalogText.Line, fileName: String): EcuDefinition {
        val fields = line.text.split('\t').map { it.trim() }.dropLastWhile { it.isEmpty() }
        fun fail(problem: String): Nothing =
            throw CatalogFormatException(fileName, line.number, problem)
        if (fields.size < 6) {
            fail("expected at least 6 tab-separated fields for an ECU record, found ${fields.size}")
        }
        val protocol = fields[5]

        fun definition(address: EcuAddress, builtinFunction: String? = null, catalogKey: String? = null) =
            EcuDefinition(
                modelYear = fields[0],
                vehicle = fields[1],
                group = fields[2],
                name = fields[3],
                systemName = fields[4],
                protocol = protocol,
                address = address,
                builtinFunction = builtinFunction,
                catalogKey = catalogKey,
            )

        return when (protocol) {
            "CAN" -> {
                val marker = fields.getOrNull(6)
                    ?: fail("CAN record is missing its bus or function field")
                if (marker in builtinFunctions) {
                    definition(EcuAddress.None, builtinFunction = marker)
                } else {
                    if (fields.size < 12) {
                        fail("expected 12 tab-separated fields for a CAN record, found ${fields.size}")
                    }
                    val bus = runCatching { CanBus.valueOf(marker) }.getOrElse {
                        fail("unknown CAN bus '$marker'")
                    }
                    val bitRate = fields[7].replace(".", "").toIntOrNull()
                        ?: fail("invalid bit rate '${fields[7]}'")
                    definition(
                        EcuAddress.Can(
                            bus = bus,
                            bitRateTenthsKbps = bitRate,
                            requestId = parseCanId(fields[8]) ?: fail("invalid CAN ID '${fields[8]}'"),
                            secondaryId = parseCanId(fields[9]) ?: fail("invalid CAN ID '${fields[9]}'"),
                            responseId = parseCanId(fields[10]) ?: fail("invalid CAN ID '${fields[10]}'"),
                        ),
                        catalogKey = fields[11].takeUnless { it == NO_KEY },
                    )
                }
            }
            "KW2000", "KW82" -> {
                if (fields.size < 11) {
                    fail("expected 11 tab-separated fields for a K-line record, found ${fields.size}")
                }
                definition(
                    EcuAddress.KLine(
                        baudRate = fields[6].toIntOrNull() ?: fail("invalid baud rate '${fields[6]}'"),
                        address = fields[9].toIntOrNull() ?: fail("invalid ECU address '${fields[9]}'"),
                        initType = fields[10].toIntOrNull() ?: fail("invalid init type '${fields[10]}'"),
                        extra = fields[8].toIntOrNull() ?: fail("invalid field '${fields[8]}'"),
                    ),
                    catalogKey = fields[7].takeUnless { it == NO_KEY },
                )
            }
            else -> definition(EcuAddress.None)
        }
    }

    private fun parseCanId(field: String): Int? =
        field.removePrefix("0x").removePrefix("0X").toIntOrNull(16)
}
