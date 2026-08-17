package nl.jwdr.ooc.protocol.kwp2000

/** readECUIdentification (0x1A): reads the record behind [identificationOption]. */
data class ReadECUIdentification(val identificationOption: Int) :
    KwpRequest<ReadECUIdentification.Response> {

    override fun encode() = byteArrayOf(0x1A, identificationOption.toByte())

    override fun decodeResponse(payload: ByteArray): Response {
        checkPositiveResponse(0x1A, payload, minLength = 2)
        return Response(
            identificationOption = payload[1].toInt() and 0xFF,
            record = payload.copyOfRange(2, payload.size),
        )
    }

    class Response(val identificationOption: Int, val record: ByteArray)
}
