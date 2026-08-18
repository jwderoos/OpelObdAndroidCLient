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
