package nl.jwdr.ooc.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class CodingTableParserTest {

    private val table = CodingTableParser.parse(
        fixture("CANVARCODING/EXAMPLIADIS.0x1201.txt"),
        "EXAMPLIADIS.0x1201.txt",
    )

    @Test
    fun `takes data identifier from file name`() {
        assertEquals(0x1201, table.dataIdentifier)
    }

    @Test
    fun `preamble lines before the first section are skipped`() {
        // One real file has an uncommented `REF 13` where its siblings write
        // `;REF 01`; junk is tolerated only before the first section.
        val parsed = CodingTableParser.parse(
            "REF 13\n[DID_begin]\n5B,11\n[DID_end]\n[VARIANT CODING DATA]\nRow,string,A,B\n",
            "TEST.0x010B.txt",
        )
        assertEquals(1, parsed.didEntries.size)
    }

    @Test
    fun `a DID entry with an extra third field keeps id and first count`() {
        // Two real files write `42,13,14`; the third value's meaning is not
        // established, so it is ignored.
        val parsed = CodingTableParser.parse(
            "[DID_begin]\n42,13,14\n[DID_end]\n[VARIANT CODING DATA]\nRow,string,A,B\n",
            "TEST.0x0102.txt",
        )
        assertEquals(listOf(DidEntry(id = 0x42, count = 13)), parsed.didEntries)
    }

    @Test
    fun `an MBA section is tolerated and skipped`() {
        // 4 real files carry an [MBA_begin]..[MBA_end] section (same
        // hexId,count shape as DID); meaning not yet established.
        val parsed = CodingTableParser.parse(
            "[DID_begin]\n5B,04\n[DID_end]\n[MBA_begin]\n38,02\n69,06\n[MBA_end]\n" +
                "[VARIANT CODING DATA]\nDriver Location,string,Left,Right\n",
            "TEST.0x0102.txt",
        )
        assertEquals(1, parsed.didEntries.size)
        assertEquals(1, parsed.rows.size)
    }

    @Test
    fun `preserves did entries verbatim`() {
        assertEquals(listOf(DidEntry(0x44, 7), DidEntry(0x4C, 4)), table.didEntries)
    }

    @Test
    fun `parses coding rows`() {
        val language = table.rows[0]
        assertEquals("Language", language.label)
        assertEquals(
            listOf(CodingValue("German"), CodingValue("English"), CodingValue("Spanish")),
            language.values,
        )
    }

    @Test
    fun `marks disabled value slots unselectable`() {
        val checkControl = table.rows.single { it.label == "Check Control" }
        assertEquals(
            listOf(
                CodingValue("Not Present"),
                CodingValue("Present"),
                CodingValue("**DISABLED**", selectable = false),
                CodingValue("**DISABLED**", selectable = false),
            ),
            checkControl.values,
        )
    }

    @Test
    fun `reports file name without data identifier`() {
        try {
            CodingTableParser.parse("[DID_begin]\n[DID_end]\n", "NODID.txt")
            fail("expected CatalogFormatException")
        } catch (e: CatalogFormatException) {
            assertEquals("NODID.txt", e.fileName)
        }
    }
}
