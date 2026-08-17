package nl.jwdr.ooc.protocol.kwp2000

/**
 * inputOutputControlByLocalIdentifier (0x30): actuates the output behind
 * [localIdentifier] with [controlParameter] and optional [controlState] bytes.
 */
class InputOutputControlByLocalIdentifier(
    val localIdentifier: Int,
    val controlParameter: Int,
    val controlState: ByteArray = ByteArray(0),
) : KwpRequest<InputOutputControlByLocalIdentifier.Response> {

    override fun encode() =
        byteArrayOf(0x30, localIdentifier.toByte(), controlParameter.toByte()) + controlState

    override fun decodeResponse(payload: ByteArray): Response {
        checkPositiveResponse(0x30, payload, minLength = 2)
        return Response(
            localIdentifier = payload[1].toInt() and 0xFF,
            record = payload.copyOfRange(2, payload.size),
        )
    }

    class Response(val localIdentifier: Int, val record: ByteArray)
}
