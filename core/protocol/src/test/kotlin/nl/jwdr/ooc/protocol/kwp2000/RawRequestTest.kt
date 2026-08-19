package nl.jwdr.ooc.protocol.kwp2000

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RawRequestTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `encodes the given payload verbatim`() {
        val request = RawRequest(bytes(0xAE, 0x02, 0x02, 0x00, 0x00, 0x00))

        assertArrayEquals(bytes(0xAE, 0x02, 0x02, 0x00, 0x00, 0x00), request.encode())
    }

    @Test
    fun `accepts the matching positive response and returns its payload`() {
        val request = RawRequest(bytes(0xAE, 0x02, 0x02, 0x00, 0x00, 0x00))

        val response = request.decodeResponse(bytes(0xEE, 0x02))

        assertArrayEquals(bytes(0xEE, 0x02), response.payload)
    }

    @Test
    fun `rejects negative responses through the shared mapping`() {
        val request = RawRequest(bytes(0xAE, 0x02, 0x02))

        val thrown = assertThrows(KwpNegativeResponseException::class.java) {
            request.decodeResponse(bytes(0x7F, 0xAE, 0x31))
        }

        assertEquals(0xAE, thrown.serviceId)
        assertEquals(KwpError.RequestOutOfRange, thrown.error)
    }

    @Test
    fun `rejects a positive response to a different service`() {
        val request = RawRequest(bytes(0xAE, 0x02, 0x02))

        assertThrows(KwpDecodeException::class.java) {
            request.decodeResponse(bytes(0x6A, 0x02))
        }
    }

    @Test
    fun `rejects an empty payload`() {
        assertThrows(IllegalArgumentException::class.java) {
            RawRequest(ByteArray(0))
        }
    }

    @Test
    fun `rejects a service id that cannot have a positive response`() {
        // 0xC0 + 0x40 overflows a byte: no reply could ever match, so the
        // request would actuate and then always end in a timeout.
        assertThrows(IllegalArgumentException::class.java) {
            RawRequest(bytes(0xC0, 0x01))
        }
    }
}
