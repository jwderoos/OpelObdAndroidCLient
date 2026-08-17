package nl.jwdr.ooc.protocol.kwp2000

/** writeDataByLocalIdentifier (0x3B): writes [record] behind [localIdentifier] (coding). */
class WriteDataByLocalIdentifier(val localIdentifier: Int, val record: ByteArray) :
    KwpRequest<WriteDataByLocalIdentifier.Response> {

    override fun encode() = byteArrayOf(0x3B, localIdentifier.toByte()) + record

    override fun decodeResponse(payload: ByteArray): Response {
        checkPositiveResponse(0x3B, payload)
        // Recorded ECUs sometimes acknowledge with a bare 0x7B, without
        // echoing the local identifier.
        return Response(
            localIdentifier = if (payload.size >= 2) payload[1].toInt() and 0xFF else null,
        )
    }

    data class Response(val localIdentifier: Int?)
}
