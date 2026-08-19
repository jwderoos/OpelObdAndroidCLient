package nl.jwdr.ooc.protocol.kwp2000

/**
 * A pre-encoded diagnostic request sent verbatim, service id byte included.
 * Used for catalog-supplied command records (output-test scripts), whose
 * payloads are stored complete in the catalog rather than constructed here.
 * The response is validated as positive for the payload's service id but
 * otherwise returned undecoded.
 */
class RawRequest(private val payload: ByteArray) : KwpRequest<RawRequest.Response> {

    init {
        require(payload.isNotEmpty()) { "raw request payload must not be empty" }
    }

    private val serviceId = payload[0].toInt() and 0xFF

    init {
        // 0xC0 and up have no representable positive response (id + 0x40
        // overflows a byte): the request would actuate and always time out.
        require(serviceId < 0xC0) {
            "service id 0x%02X cannot have a positive response".format(serviceId)
        }
    }

    override fun encode() = payload

    override fun decodeResponse(payload: ByteArray) =
        Response(checkPositiveResponse(serviceId, payload))

    /** The verbatim positive-response payload, response id byte included. */
    class Response(val payload: ByteArray)
}
