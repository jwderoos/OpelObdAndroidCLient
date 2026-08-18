package nl.jwdr.ooc.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DtcDisplayTest {

    @Test
    fun `formats powertrain codes with the P prefix`() {
        assertEquals("P0016", DtcCode.format(0x0016))
        assertEquals("P1234", DtcCode.format(0x1234))
    }

    @Test
    fun `top two bits select the code letter`() {
        assertEquals("C0123", DtcCode.format(0x4123))
        assertEquals("B1000", DtcCode.format(0x9000))
        assertEquals("U2100", DtcCode.format(0xE100))
    }

    @Test
    fun `hex digits keep their hex form`() {
        assertEquals("P0A2F", DtcCode.format(0x0A2F))
    }

    private val catalog = FaultCodeCatalog(
        codes = listOf(
            FaultCode("P0016", 0, "Crankshaft/Camshaft Correlation"),
            FaultCode("B1000", 1, "Example Symptom One"),
            FaultCode("B1000", 2, "Example Symptom Two"),
        ),
    )

    @Test
    fun `finds the text for a code and symptom pair`() {
        assertEquals("Example Symptom Two", catalog.textFor("B1000", 2))
    }

    @Test
    fun `an unknown code has no text`() {
        assertNull(catalog.textFor("P9999", 0))
    }

    @Test
    fun `a known code with an unlisted symptom has no text`() {
        assertNull(catalog.textFor("B1000", 9))
    }
}
