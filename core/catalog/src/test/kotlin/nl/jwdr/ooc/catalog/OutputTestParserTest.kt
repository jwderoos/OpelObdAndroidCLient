package nl.jwdr.ooc.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class OutputTestParserTest {

    private val catalog = OutputTestParser.parse(
        fixture("OutputTests/EXAMPLIAABSESP.SCR.txt"),
        "EXAMPLIAABSESP.SCR.txt",
    )

    @Test
    fun `parses all tests`() {
        assertEquals(listOf("Return Pump Relay Test", "Fan Test"), catalog.tests.map { it.title })
        assertEquals(
            listOf(OutputTestType.ONOFF, OutputTestType.UPDOWN),
            catalog.tests.map { it.type },
        )
    }

    @Test
    fun `parses command records in order`() {
        val test = catalog.tests[0]
        assertEquals(2, test.beforeTest.size)
        assertEquals(
            CommandRecord(listOf(0x04, 0xAE, 0x03, 0x08, 0x10, 0x00, 0x00, 0x00)),
            test.goActivate.single(),
        )
        assertEquals(1, test.deActivate.size)
        assertEquals(1, test.afterTest.size)
    }

    @Test
    fun `command record exposes significant bytes via length prefix`() {
        assertEquals(
            listOf(0xAE, 0x03, 0x08, 0x10),
            catalog.tests[0].goActivate.single().significantBytes,
        )
    }

    @Test
    fun `reports unknown record key`() {
        val text = "T\n[TESTTYPE=ONOFF]\n[begin]\nBogusKey=\t0x01,0x00,0x00,0x00,0x00,0x00,0x00,0x00,\n[end]\n"
        try {
            OutputTestParser.parse(text, "BOGUS.SCR.txt")
            fail("expected CatalogFormatException")
        } catch (e: CatalogFormatException) {
            assertEquals("BOGUS.SCR.txt", e.fileName)
            assertEquals(4, e.lineNumber)
        }
    }

    @Test
    fun `reports unknown test type`() {
        try {
            OutputTestParser.parse("T\n[TESTTYPE=WOBBLE]\n[begin]\n[end]\n", "WOBBLE.SCR.txt")
            fail("expected CatalogFormatException")
        } catch (e: CatalogFormatException) {
            assertEquals(2, e.lineNumber)
        }
    }
}
