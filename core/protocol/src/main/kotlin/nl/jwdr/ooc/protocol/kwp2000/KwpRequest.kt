package nl.jwdr.ooc.protocol.kwp2000

/**
 * A KWP2000 service request: encodes itself to an ISO-TP payload and decodes
 * the ECU's response payload into a typed [R].
 */
interface KwpRequest<R> {

    /** The full request payload, service id byte included. */
    fun encode(): ByteArray

    /** Decodes a response payload for this request. */
    fun decodeResponse(payload: ByteArray): R
}
