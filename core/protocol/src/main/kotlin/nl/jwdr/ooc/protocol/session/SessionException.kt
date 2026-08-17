package nl.jwdr.ooc.protocol.session

import nl.jwdr.ooc.protocol.kwp2000.KwpError

/** A typed failure of a [DiagnosticSession] request. */
sealed class SessionException(message: String) : Exception(message) {

    /** No response within the configured timeout, retries included. */
    class ResponseTimeout(val serviceId: Int) :
        SessionException("service 0x%02X: no response within timeout".format(serviceId))

    /** The ECU rejected the request with [error]. */
    class NegativeResponse(val serviceId: Int, val error: KwpError) :
        SessionException("service 0x%02X rejected: %s".format(serviceId, error))

    /** The underlying transport left the Ready state. */
    class TransportLost : SessionException("transport connection lost")

    /** The session was closed; no further requests are accepted. */
    class SessionClosed : SessionException("session is closed")
}
