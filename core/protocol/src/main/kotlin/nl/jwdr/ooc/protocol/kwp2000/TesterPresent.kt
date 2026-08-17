package nl.jwdr.ooc.protocol.kwp2000

/**
 * testerPresent (0x3E): keeps the diagnostic session alive. [responseType] is
 * the optional response-type sub-parameter; omitted when null.
 */
data class TesterPresent(val responseType: Int? = null) : KwpRequest<Unit> {

    override fun encode() = when (responseType) {
        null -> byteArrayOf(0x3E)
        else -> byteArrayOf(0x3E, responseType.toByte())
    }

    override fun decodeResponse(payload: ByteArray) {
        checkPositiveResponse(0x3E, payload)
    }
}
