package nl.jwdr.ooc.protocol.kwp2000

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NegativeResponseTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun decodeError(vararg payload: Int): KwpError {
        val thrown = assertThrows(KwpNegativeResponseException::class.java) {
            StartDiagnosticSession(diagnosticMode = 0x81).decodeResponse(bytes(*payload))
        }
        assertEquals(0x10, thrown.serviceId)
        return thrown.error
    }

    @Test
    fun `maps known negative response codes`() {
        assertEquals(KwpError.GeneralReject, decodeError(0x7F, 0x10, 0x10))
        assertEquals(KwpError.ServiceNotSupported, decodeError(0x7F, 0x10, 0x11))
        assertEquals(KwpError.SubFunctionNotSupported, decodeError(0x7F, 0x10, 0x12))
        assertEquals(KwpError.BusyRepeatRequest, decodeError(0x7F, 0x10, 0x21))
        assertEquals(KwpError.ConditionsNotCorrect, decodeError(0x7F, 0x10, 0x22))
        assertEquals(KwpError.RoutineNotComplete, decodeError(0x7F, 0x10, 0x23))
        assertEquals(KwpError.RequestOutOfRange, decodeError(0x7F, 0x10, 0x31))
        assertEquals(KwpError.SecurityAccessDenied, decodeError(0x7F, 0x10, 0x33))
        assertEquals(KwpError.InvalidKey, decodeError(0x7F, 0x10, 0x35))
        assertEquals(KwpError.ExceededNumberOfAttempts, decodeError(0x7F, 0x10, 0x36))
        assertEquals(KwpError.RequiredTimeDelayNotExpired, decodeError(0x7F, 0x10, 0x37))
        assertEquals(KwpError.ResponsePending, decodeError(0x7F, 0x10, 0x78))
        assertEquals(
            KwpError.ServiceNotSupportedInActiveSession,
            decodeError(0x7F, 0x10, 0x80),
        )
    }

    @Test
    fun `maps an unlisted negative response code to Unknown`() {
        assertEquals(KwpError.Unknown(0x42), decodeError(0x7F, 0x10, 0x42))
    }

    @Test
    fun `rejects a response for a different service`() {
        assertThrows(KwpDecodeException::class.java) {
            // 0x5A = positive ReadECUIdentification, not startDiagnosticSession.
            StartDiagnosticSession(diagnosticMode = 0x81).decodeResponse(bytes(0x5A, 0x81))
        }
    }

    @Test
    fun `rejects an empty response payload`() {
        assertThrows(KwpDecodeException::class.java) {
            StartDiagnosticSession(diagnosticMode = 0x81).decodeResponse(ByteArray(0))
        }
    }

    @Test
    fun `rejects a truncated negative response`() {
        assertThrows(KwpDecodeException::class.java) {
            StartDiagnosticSession(diagnosticMode = 0x81).decodeResponse(bytes(0x7F, 0x10))
        }
    }
}
