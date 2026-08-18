package nl.jwdr.ooc.catalog

/** Parses `OutputTests/<KEY>.SCR.txt` files. */
object OutputTestParser {

    private val testType = Regex("""\[TESTTYPE=(\w+)]""")
    private val commandRecord = Regex("""(\w+)=\s*(.*)""")

    fun parse(text: String, fileName: String): OutputTestCatalog {
        fun fail(line: Int, problem: String): Nothing =
            throw CatalogFormatException(fileName, line, problem)

        val lines = CatalogText.contentLines(text)
        val tests = mutableListOf<OutputTest>()
        var i = 0
        while (i < lines.size) {
            val titleLine = lines[i]
            val typeLine = lines.getOrNull(i + 1)
                ?: fail(titleLine.number, "test '${titleLine.text}' is missing its [TESTTYPE=…] line")
            val typeMatch = testType.matchEntire(typeLine.text)
                ?: fail(typeLine.number, "expected [TESTTYPE=…], found '${typeLine.text}'")
            val type = runCatching { OutputTestType.valueOf(typeMatch.groupValues[1]) }.getOrElse {
                fail(typeLine.number, "unknown test type '${typeMatch.groupValues[1]}'")
            }
            if (lines.getOrNull(i + 2)?.text != "[begin]") {
                fail(typeLine.number, "test '${titleLine.text}' is missing [begin]")
            }
            i += 3

            val records = mutableMapOf<String, MutableList<CommandRecord>>()
            var closed = false
            while (i < lines.size) {
                val body = lines[i]
                if (body.text == "[end]") { closed = true; i++; break }
                val match = commandRecord.matchEntire(body.text)
                    ?: fail(body.number, "expected a command record, found '${body.text}'")
                val key = match.groupValues[1]
                if (key !in RECORD_KEYS) fail(body.number, "unknown command record key '$key'")
                val bytes = match.groupValues[2].split(',')
                    .filter { it.isNotBlank() }
                    .map {
                        it.trim().removePrefix("0x").removePrefix("0X").toIntOrNull(16)
                            ?: fail(body.number, "invalid command byte '$it'")
                    }
                records.getOrPut(key) { mutableListOf() } += CommandRecord(bytes)
                i++
            }
            if (!closed) fail(titleLine.number, "test '${titleLine.text}' has no [end]")

            tests += OutputTest(
                title = titleLine.text,
                type = type,
                beforeTest = records["BeforeTest"].orEmpty(),
                goActivate = records["GoActivate"].orEmpty(),
                deActivate = records["DeActivate"].orEmpty(),
                afterTest = records["AfterTest"].orEmpty(),
            )
        }
        return OutputTestCatalog(tests)
    }

    private val RECORD_KEYS = setOf("BeforeTest", "GoActivate", "DeActivate", "AfterTest")
}
