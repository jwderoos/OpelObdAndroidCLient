package nl.jwdr.ooc.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OpelDataParserTest {

    private val definitions = OpelDataParser.parse(fixture("opeldata.txt"))

    @Test
    fun `skips comments and blank lines`() {
        assertEquals(7, definitions.size)
    }

    @Test
    fun `parses a CAN record`() {
        val engine = definitions.single { it.catalogKey == "EXAMPLIAENGZ99XX" }
        assertEquals("2010 (A)", engine.modelYear)
        assertEquals("Examplia-A", engine.vehicle)
        assertEquals("Engine", engine.group)
        assertEquals("Z 99 XX", engine.name)
        assertEquals("Motronic X", engine.systemName)
        assertEquals("CAN", engine.protocol)
        val address = engine.address as EcuAddress.Can
        assertEquals(CanBus.HSCAN, address.bus)
        assertEquals(5000, address.bitRateTenthsKbps)
        assertEquals(0x7E0, address.requestId)
        assertEquals(0x5E8, address.secondaryId)
        assertEquals(0x7E8, address.responseId)
    }

    @Test
    fun `parses an MSCAN record with fractional bit rate`() {
        val dis = definitions.single { it.catalogKey == "EXAMPLIADIS" }
        val address = dis.address as EcuAddress.Can
        assertEquals(CanBus.MSCAN, address.bus)
        assertEquals(956, address.bitRateTenthsKbps)
        assertEquals(0x246, address.requestId)
    }

    @Test
    fun `virtual rows have no catalog key`() {
        val virtual = definitions.single { (it.address as? EcuAddress.Can)?.bus == CanBus.VIRTUAL }
        assertNull(virtual.catalogKey)
        assertEquals("Central Door Lock", virtual.name)
    }

    @Test
    fun `parses a K-line record`() {
        val airbag = definitions.single { it.catalogKey == "EXAMPLIASRS" }
        assertEquals("KW2000", airbag.protocol)
        val address = airbag.address as EcuAddress.KLine
        assertEquals(10400, address.baudRate)
        assertEquals(89, address.address)
        assertEquals(3, address.initType)
        assertEquals(7, address.extra)
    }

    @Test
    fun `parses built-in function pseudo entries`() {
        val ident = definitions.single { it.builtinFunction == "IDENT" }
        assertEquals(EcuAddress.None, ident.address)
        assertNull(ident.catalogKey)
    }

    @Test
    fun `parses identkw2000 pseudo entry`() {
        val ident = definitions.single { it.protocol == "IDENTKW2000" }
        assertEquals(EcuAddress.None, ident.address)
    }

    @Test
    fun `reports malformed record with file and line`() {
        try {
            OpelDataParser.parse("2010 (A)\tExamplia-A\tEngine\n", "opeldata.txt")
            fail("expected CatalogFormatException")
        } catch (e: CatalogFormatException) {
            assertEquals("opeldata.txt", e.fileName)
            assertEquals(1, e.lineNumber)
            assertTrue("message should be user-presentable: ${e.message}",
                e.message!!.startsWith("opeldata.txt line 1:"))
        }
    }
}
