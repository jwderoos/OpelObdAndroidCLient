package nl.jwdr.ooc.protocol.kwp2000

/** readDataByLocalIdentifier (0x21): reads a measuring block or other record. */
data class ReadDataByLocalIdentifier(val localIdentifier: Int) :
    KwpRequest<ReadDataByLocalIdentifier.Response> {

    override fun encode() = byteArrayOf(0x21, localIdentifier.toByte())

    override fun decodeResponse(payload: ByteArray): Response {
        checkPositiveResponse(0x21, payload, minLength = 2)
        return Response(
            localIdentifier = payload[1].toInt() and 0xFF,
            record = payload.copyOfRange(2, payload.size),
        )
    }

    class Response(val localIdentifier: Int, val record: ByteArray)
}
