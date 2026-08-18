package nl.jwdr.ooc.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class MeasuringBlockParserTest {

    private val catalog = MeasuringBlockParser.parse(
        fixture("MeasuringBlocks/EXAMPLIAENGZ99XX.MBF.txt"),
        "EXAMPLIAENGZ99XX.MBF.txt",
    )

    @Test
    fun `parses block definitions`() {
        assertEquals(2, catalog.blocks.size)
        val first = catalog.blocks[0]
        assertEquals(1, first.number)
        assertEquals("Diagnostic Data List 1", first.title)
        assertEquals(listOf(0x04, 0x03, 0x10), first.measData)
        assertEquals(2..4, first.enabledRows)
    }

    @Test
    fun `parses measdata without trailing comma`() {
        assertEquals(listOf(0x04, 0x05), catalog.blocks[1].measData)
    }

    @Test
    fun `captures standalone ecu id verbatim`() {
        assertEquals("00105", catalog.ecuId)
    }

    @Test
    fun `parses unit rows with windows-1252 units`() {
        assertEquals(DataRow(label = "Coolant Temperature", unit = "°C"), catalog.dataRows[1])
    }

    @Test
    fun `parses state rows with trailing tag`() {
        val row = catalog.dataRows[2]
        assertEquals("Fuel Pump Relay", row.label)
        assertNull(row.unit)
        assertEquals(listOf("Inactive", "Active"), row.states)
        assertEquals("TAG1", row.tag)
    }

    @Test
    fun `parses unit rows with trailing tag`() {
        assertEquals(DataRow(label = "Battery Voltage", unit = "V", tag = "TAG2"), catalog.dataRows[3])
    }

    @Test
    fun `resolves enabled rows one-based inclusive`() {
        val oil = catalog.blocks[1]
        assertEquals(
            listOf("Engine Oil Change Warning", "Remaining Oil Life"),
            catalog.rowsFor(oil).map { it.label },
        )
    }

    @Test
    fun `reports block without end marker`() {
        val text = "##MB01=Broken\n[begin]\nMEASDATA=04\n"
        try {
            MeasuringBlockParser.parse(text, "BROKEN.MBF.txt")
            fail("expected CatalogFormatException")
        } catch (e: CatalogFormatException) {
            assertEquals("BROKEN.MBF.txt", e.fileName)
        }
    }

    @Test
    fun `reports enable range pointing outside data table`() {
        val text = """
            ##MB01=Out Of Range
            [begin]
            MEASDATA=04
            DISABLE_ALL
            ENABLE_RANGE=0001-0009
            [end]

            [MEASURING BLOCK DATA]
            Only Row,string,[V]
        """.trimIndent()
        try {
            MeasuringBlockParser.parse(text, "RANGE.MBF.txt")
            fail("expected CatalogFormatException")
        } catch (e: CatalogFormatException) {
            assertEquals("RANGE.MBF.txt", e.fileName)
        }
    }
}
