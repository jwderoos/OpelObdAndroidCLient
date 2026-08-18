package nl.jwdr.ooc.catalog

import org.junit.Assert.assertEquals
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
