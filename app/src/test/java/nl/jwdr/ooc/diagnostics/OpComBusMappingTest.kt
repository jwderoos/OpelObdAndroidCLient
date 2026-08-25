package nl.jwdr.ooc.diagnostics

import nl.jwdr.ooc.catalog.CanBus
import nl.jwdr.ooc.transport.opcom.OpComBus
import org.junit.Assert.assertEquals
import org.junit.Test

class OpComBusMappingTest {

    @Test
    fun `HSCAN, SWCAN and MSCAN map onto their OP-COM equivalents`() {
        assertEquals(OpComBus.HSCAN, CanBus.HSCAN.toOpComBus())
        assertEquals(OpComBus.SWCAN, CanBus.SWCAN.toOpComBus())
        assertEquals(OpComBus.MSCAN, CanBus.MSCAN.toOpComBus())
    }

    @Test
    fun `CHCAN has no confirmed vendor sequence and throws`() {
        val e = runCatching { CanBus.CHCAN.toOpComBus() }.exceptionOrNull()
        assert(e is IllegalArgumentException) { "expected IllegalArgumentException, got $e" }
    }

    @Test
    fun `VIRTUAL has no confirmed vendor sequence and throws`() {
        val e = runCatching { CanBus.VIRTUAL.toOpComBus() }.exceptionOrNull()
        assert(e is IllegalArgumentException) { "expected IllegalArgumentException, got $e" }
    }
}
