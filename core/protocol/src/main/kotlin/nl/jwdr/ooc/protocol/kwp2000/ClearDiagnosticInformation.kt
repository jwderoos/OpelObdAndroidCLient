package nl.jwdr.ooc.protocol.kwp2000

/** clearDiagnosticInformation (0x14): clears the DTCs in [groupOfDtc] (0xFF00 = all). */
data class ClearDiagnosticInformation(val groupOfDtc: Int) : KwpRequest<Unit> {

    override fun encode() = byteArrayOf(
        0x14,
        (groupOfDtc shr 8).toByte(),
        groupOfDtc.toByte(),
    )

    override fun decodeResponse(payload: ByteArray) {
        checkPositiveResponse(0x14, payload)
    }
}
