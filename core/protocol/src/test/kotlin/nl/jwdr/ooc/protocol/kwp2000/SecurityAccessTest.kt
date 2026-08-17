package nl.jwdr.ooc.protocol.kwp2000

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SecurityAccessTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `RequestSeed encodes an odd access mode and decodes the seed`() {
        val request = SecurityAccess.RequestSeed(accessMode = 0x01)

        assertArrayEquals(bytes(0x27, 0x01), request.encode())

        val response = request.decodeResponse(bytes(0x67, 0x01, 0xDE, 0xAD))

        assertArrayEquals(bytes(0xDE, 0xAD), response.seed)
    }

    @Test
    fun `RequestSeed reports an already unlocked ECU as an all-zero seed`() {
        val request = SecurityAccess.RequestSeed(accessMode = 0x01)

        val response = request.decodeResponse(bytes(0x67, 0x01, 0x00, 0x00))

        assertEquals(true, response.alreadyUnlocked)
    }

    @Test
    fun `RequestSeed rejects an even access mode`() {
        assertThrows(IllegalArgumentException::class.java) {
            SecurityAccess.RequestSeed(accessMode = 0x02)
        }
    }

    @Test
    fun `SendKey encodes the following even access mode and the key`() {
        val request = SecurityAccess.SendKey(accessMode = 0x02, key = bytes(0xBE, 0xEF))

        assertArrayEquals(bytes(0x27, 0x02, 0xBE, 0xEF), request.encode())
        request.decodeResponse(bytes(0x67, 0x02))
    }

    @Test
    fun `SendKey rejects an odd access mode`() {
        assertThrows(IllegalArgumentException::class.java) {
            SecurityAccess.SendKey(accessMode = 0x01, key = bytes(0xBE))
        }
    }

    @Test
    fun `an invalid key maps to the sealed error`() {
        val request = SecurityAccess.SendKey(accessMode = 0x02, key = bytes(0xBE, 0xEF))

        val thrown = assertThrows(KwpNegativeResponseException::class.java) {
            request.decodeResponse(bytes(0x7F, 0x27, 0x35))
        }

        assertEquals(KwpError.InvalidKey, thrown.error)
    }
}
