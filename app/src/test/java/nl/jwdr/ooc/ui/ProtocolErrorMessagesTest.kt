package nl.jwdr.ooc.ui

import nl.jwdr.ooc.R
import nl.jwdr.ooc.protocol.kwp2000.KwpError
import nl.jwdr.ooc.protocol.session.SessionException
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtocolErrorMessagesTest {

    @Test
    fun `timeout maps to the timeout message`() {
        val message = userMessageFor(SessionException.ResponseTimeout(serviceId = 0x1A))

        assertEquals(R.string.error_response_timeout, message.resId)
    }

    @Test
    fun `negative response carries the kwp error description as argument`() {
        val message = userMessageFor(
            SessionException.NegativeResponse(serviceId = 0x1A, error = KwpError.SecurityAccessDenied),
        )

        assertEquals(R.string.error_negative_response, message.resId)
        assertEquals(listOf("SecurityAccessDenied(0x33)"), message.formatArgs)
    }

    @Test
    fun `transport lost maps to the connection lost message`() {
        val message = userMessageFor(SessionException.TransportLost())

        assertEquals(R.string.error_transport_lost, message.resId)
    }

    @Test
    fun `session closed maps to the session closed message`() {
        val message = userMessageFor(SessionException.SessionClosed())

        assertEquals(R.string.error_session_closed, message.resId)
    }

    @Test
    fun `any other failure maps to the generic communication error`() {
        val message = userMessageFor(IllegalStateException("boom"))

        assertEquals(R.string.error_generic_communication, message.resId)
    }
}
