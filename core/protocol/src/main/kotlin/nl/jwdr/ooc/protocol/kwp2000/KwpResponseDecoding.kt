package nl.jwdr.ooc.protocol.kwp2000

private const val NEGATIVE_RESPONSE = 0x7F
private const val POSITIVE_OFFSET = 0x40

/**
 * Validates [payload] as a response to service [serviceId] and returns it with
 * the leading positive-response byte still in place.
 *
 * @throws KwpNegativeResponseException on a negative response
 * @throws KwpDecodeException on anything that is not a response to [serviceId]
 *   or is shorter than [minLength]
 */
internal fun checkPositiveResponse(serviceId: Int, payload: ByteArray, minLength: Int = 1): ByteArray {
    if (payload.isEmpty()) throw KwpDecodeException("empty response payload")
    val first = payload[0].toInt() and 0xFF
    if (first == NEGATIVE_RESPONSE) {
        if (payload.size < 3) {
            throw KwpDecodeException("negative response of ${payload.size} bytes, expected 3")
        }
        throw KwpNegativeResponseException(
            serviceId = payload[1].toInt() and 0xFF,
            error = KwpError.fromCode(payload[2].toInt() and 0xFF),
        )
    }
    if (first != serviceId + POSITIVE_OFFSET) {
        throw KwpDecodeException(
            "expected positive response 0x%02X to service 0x%02X, got 0x%02X"
                .format(serviceId + POSITIVE_OFFSET, serviceId, first),
        )
    }
    if (payload.size < minLength) {
        throw KwpDecodeException(
            "response to service 0x%02X is ${payload.size} bytes, expected at least $minLength"
                .format(serviceId),
        )
    }
    return payload
}
