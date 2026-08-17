package nl.jwdr.ooc.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CanFrameTest {
    @Test
    fun `frames with equal id and payload are equal`() {
        assertEquals(
            CanFrame(0x7E0, byteArrayOf(0x01, 0x02)),
            CanFrame(0x7E0, byteArrayOf(0x01, 0x02)),
        )
    }

    @Test
    fun `payload longer than 8 bytes is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            CanFrame(0x7E0, ByteArray(9))
        }
    }
}
