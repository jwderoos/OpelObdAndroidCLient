package nl.jwdr.ooc.catalog

import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogTextTest {

    @Test
    fun `decodes ascii bytes unchanged`() {
        assertEquals("MEASDATA=04", CatalogText.decode("MEASDATA=04".toByteArray(Charsets.US_ASCII)))
    }

    @Test
    fun `decodes windows-1252 degree sign`() {
        val bytes = byteArrayOf('['.code.toByte(), 0xB0.toByte(), 'C'.code.toByte(), ']'.code.toByte())
        assertEquals("[°C]", CatalogText.decode(bytes))
    }
}
