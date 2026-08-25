package nl.jwdr.ooc.protocol.gmlan

import nl.jwdr.ooc.protocol.kwp2000.KwpNegativeResponseException
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class GmlanDtcRequestsTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `ReadDiagnosticInformation encodes service, sub-function, and status mask`() {
        val request = ReadDiagnosticInformation(statusMask = 0x12)

        assertArrayEquals(bytes(0xA9, 0x81, 0x12), request.encode())
    }

    @Test
    fun `ReturnToNormalMode encodes the bare service id`() {
        assertArrayEquals(bytes(0x20), ReturnToNormalMode.encode())
    }

    @Test
    fun `ReturnToNormalMode accepts a positive response`() {
        ReturnToNormalMode.decodeResponse(bytes(0x60))
    }

    @Test(expected = KwpNegativeResponseException::class)
    fun `ReturnToNormalMode rejects a negative response`() {
        ReturnToNormalMode.decodeResponse(bytes(0x7F, 0x20, 0x11))
    }
}
