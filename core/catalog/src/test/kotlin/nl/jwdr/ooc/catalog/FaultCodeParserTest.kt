package nl.jwdr.ooc.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FaultCodeParserTest {

    private val catalog = FaultCodeParser.parse(
        fixture("ErrorCodes/EXAMPLIAENGZ99XX.txt"),
        "EXAMPLIAENGZ99XX.txt",
    )

    @Test
    fun `links to measuring block key`() {
        assertEquals("EXAMPLIAENGZ99XX", catalog.measuringBlockKey)
    }

    @Test
    fun `parses the inline K-line style with code and text on one line`() {
        // 151 real files (e.g. Agila Audio) put the text right after the
        // code, tab-separated, with no symptom sub-lines; symptom is 0.
        val parsed = FaultCodeParser.parse(
            "00011\tControl Panel Not Recognized\n00021\tKey Stuck\n",
            "TEST.txt",
        )
        assertEquals(
            listOf(
                FaultCode("00011", 0, "Control Panel Not Recognized"),
                FaultCode("00021", 0, "Key Stuck"),
            ),
            parsed.codes,
        )
    }

    @Test
    fun `variant-dispatch directives are tolerated and skipped`() {
        // [DEFAFAULT]/[SELECTIVE]/[SUZUKIDIAG] select sub-catalogs by
        // hardware id (e.g. DIS display variants); semantics not implemented
        // yet, but they must not fail the import.
        val parsed = FaultCodeParser.parse(
            "[DEFAFAULT]\tX_TC\n[SELECTIVE]\t0x1101\tX_BID\t\tBID C105\n[SUZUKIDIAG]\n" +
                "B0158\n-00\tText\n",
            "TEST.txt",
        )
        assertEquals(listOf(FaultCode("B0158", 0, "Text")), parsed.codes)
    }

    @Test
    fun `legacy numeric codes run from 2-digit blink codes to 6 digits`() {
        val parsed = FaultCodeParser.parse(
            "13\tO2 Sensor Open Circuit\n000021\tKey Stuck\n",
            "TEST.txt",
        )
        assertEquals(
            listOf(
                FaultCode("13", 0, "O2 Sensor Open Circuit"),
                FaultCode("000021", 0, "Key Stuck"),
            ),
            parsed.codes,
        )
    }

    @Test
    fun `a nibble wildcard expands to all sixteen symptoms`() {
        // `-D?` (three real occurrences) means any low nibble under high
        // nibble D; expanded at parse time so lookups stay exact-match.
        val parsed = FaultCodeParser.parse("P0641\n-D?\t5V Reference Low\n", "TEST.txt")
        assertEquals(16, parsed.codes.size)
        assertEquals(0xD0, parsed.codes.first().symptom)
        assertEquals(0xDF, parsed.codes.last().symptom)
        assertTrue(parsed.codes.all { it.text == "5V Reference Low" && it.code == "P0641" })
    }

    @Test
    fun `a standalone raw hex value line is skipped`() {
        // One real file (Meriva EPS) has a stray `0x0203` line; meaning not
        // established, tolerated and skipped.
        val parsed = FaultCodeParser.parse("0x0203\nC1500\n-1\tInvalid Signal\n", "TEST.txt")
        assertEquals(listOf(FaultCode("C1500", 1, "Invalid Signal")), parsed.codes)
    }

    @Test
    fun `codes may contain hex digits in the low three positions`() {
        // SAE J2012: the low three nibbles are hex — P253F is a real code.
        val parsed = FaultCodeParser.parse("P253F\n-00\tAuxiliary pump\n", "TEST.txt")
        assertEquals(listOf(FaultCode("P253F", 0, "Auxiliary pump")), parsed.codes)
    }

    @Test
    fun `symptom markers are hexadecimal`() {
        // Real files use markers up to -FF (e.g. -E0); the wire symptom is a
        // byte, so the whole marker space is hex.
        // Trailing spaces before the tab occur in real files ("-08   \t").
        val parsed = FaultCodeParser.parse("B0158\n-10  \tText A\n-E0\tText B\n", "TEST.txt")
        assertEquals(
            listOf(FaultCode("B0158", 0x10, "Text A"), FaultCode("B0158", 0xE0, "Text B")),
            parsed.codes,
        )
    }

    @Test
    fun `question-mark symptoms are the any-symptom wildcard`() {
        // Both `-?` and `-??` occur in real files.
        val parsed = FaultCodeParser.parse("B0158\n-?\tShort to battery\n-??\tShort to ground\n", "TEST.txt")
        assertEquals(
            listOf(
                FaultCode("B0158", FaultCode.ANY_SYMPTOM, "Short to battery"),
                FaultCode("B0158", FaultCode.ANY_SYMPTOM, "Short to ground"),
            ),
            parsed.codes,
        )
    }

    @Test
    fun `textFor falls back to the wildcard entry`() {
        val catalogWithWildcard = FaultCodeCatalog(
            measuringBlockKey = null,
            codes = listOf(
                FaultCode("B0158", 0x01, "Exact"),
                FaultCode("B0158", FaultCode.ANY_SYMPTOM, "Fallback"),
            ),
        )
        assertEquals("Exact", catalogWithWildcard.textFor("B0158", 0x01))
        assertEquals("Fallback", catalogWithWildcard.textFor("B0158", 0x7F))
    }

    @Test
    fun `parses code with single symptom`() {
        assertEquals(FaultCode("P0016", 0, "Crankshaft/Camshaft Correlation"), catalog.codes[0])
    }

    @Test
    fun `parses code with multiple symptoms`() {
        assertEquals(
            listOf(
                FaultCode("B1000", 1, "Example Symptom One"),
                FaultCode("B1000", 2, "Example Symptom Two"),
            ),
            catalog.codes.filter { it.code == "B1000" },
        )
    }

    @Test
    fun `reports symptom line without preceding code`() {
        try {
            FaultCodeParser.parse("-00\tOrphan Symptom\n", "ORPHAN.txt")
            fail("expected CatalogFormatException")
        } catch (e: CatalogFormatException) {
            assertEquals("ORPHAN.txt", e.fileName)
            assertEquals(1, e.lineNumber)
        }
    }
}
