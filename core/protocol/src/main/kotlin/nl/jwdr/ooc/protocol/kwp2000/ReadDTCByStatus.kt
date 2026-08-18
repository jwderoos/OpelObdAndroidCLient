package nl.jwdr.ooc.protocol.kwp2000

/**
 * One reported diagnostic trouble code: the raw 16-bit [code] and the
 * symptom / fault-type byte reported with it (the `-NN` suffix of the
 * displayed fault, per the catalog fault-code files).
 */
data class Dtc(val code: Int, val symptom: Int)

/**
 * readDiagnosticTroubleCodesByStatus (0x18): lists the DTCs in [groupOfDtc]
 * (0xFF00 = all groups) matching the [status] sub-function.
 */
data class ReadDTCByStatus(val status: Int, val groupOfDtc: Int) :
    KwpRequest<ReadDTCByStatus.Response> {

    override fun encode() = byteArrayOf(
        0x18,
        status.toByte(),
        (groupOfDtc shr 8).toByte(),
        groupOfDtc.toByte(),
    )

    override fun decodeResponse(payload: ByteArray): Response {
        checkPositiveResponse(0x18, payload, minLength = 2)
        val count = payload[1].toInt() and 0xFF
        if (payload.size < 2 + count * 3) {
            throw KwpDecodeException(
                "DTC list announces $count entries but carries ${(payload.size - 2) / 3}",
            )
        }
        val dtcs = List(count) { index ->
            val offset = 2 + index * 3
            Dtc(
                code = ((payload[offset].toInt() and 0xFF) shl 8) or
                    (payload[offset + 1].toInt() and 0xFF),
                symptom = payload[offset + 2].toInt() and 0xFF,
            )
        }
        return Response(dtcs)
    }

    data class Response(val dtcs: List<Dtc>)
}
