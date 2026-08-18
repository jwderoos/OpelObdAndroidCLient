package nl.jwdr.ooc.catalog

/** Parses `opeldata.txt` — the vehicle / ECU address map. */
object OpelDataParser {

    private val builtinFunctions = setOf("IDENT", "GETECULIST", "GETERRORCODESLIST")
    private const val NO_KEY = "????"

    fun parse(text: String, fileName: String = "opeldata.txt"): List<EcuDefinition> =
        CatalogText.contentLines(text).map { line -> parseRecord(line, fileName) }

    private fun parseRecord(line: CatalogText.Line, fileName: String): EcuDefinition {
        val rawFields = line.text.split('\t').map { it.trim() }
        val fields = rawFields.dropLastWhile { it.isEmpty() }
        fun fail(problem: String): Nothing =
            throw CatalogFormatException(fileName, line.number, problem)
        // Menu-only rows (e.g. AFL) have an empty protocol field and nothing
        // after it: 6+ raw fields collapsing to 5. They stay in as
        // unaddressable placeholders; anything shorter is malformed.
        if (fields.size < 6 && !(fields.size == 5 && rawFields.size >= 6)) {
            fail("expected at least 6 tab-separated fields for an ECU record, found ${fields.size}")
        }
        val protocol = fields.getOrNull(5).orEmpty()

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
                // Real catalogs contain K-line rows that don't fit the
                // numeric schema: '????' baud rates and comma-list init
                // types like '2,1'. K-line is out of scope for this app, so
                // such rows stay in as unaddressable entries rather than
                // failing the import.
                val address = run {
                    EcuAddress.KLine(
                        baudRate = fields[6].toIntOrNull() ?: return@run EcuAddress.None,
                        address = fields[9].toIntOrNull() ?: return@run EcuAddress.None,
                        initType = fields[10].toIntOrNull() ?: return@run EcuAddress.None,
                        extra = fields[8].toIntOrNull() ?: return@run EcuAddress.None,
                    )
                }
                definition(address, catalogKey = fields[7].takeUnless { it == NO_KEY })
            }
            else -> definition(EcuAddress.None)
        }
    }

    private fun parseCanId(field: String): Int? =
        field.removePrefix("0x").removePrefix("0X").toIntOrNull(16)
}
