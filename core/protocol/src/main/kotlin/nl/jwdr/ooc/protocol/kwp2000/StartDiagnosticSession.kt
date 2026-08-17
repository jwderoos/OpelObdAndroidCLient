package nl.jwdr.ooc.protocol.kwp2000

/** startDiagnosticSession (0x10): switches the ECU into [diagnosticMode]. */
data class StartDiagnosticSession(val diagnosticMode: Int) :
    KwpRequest<StartDiagnosticSession.Response> {

    override fun encode() = byteArrayOf(0x10, diagnosticMode.toByte())

    override fun decodeResponse(payload: ByteArray): Response {
        checkPositiveResponse(0x10, payload, minLength = 2)
        return Response(diagnosticMode = payload[1].toInt() and 0xFF)
    }

    data class Response(val diagnosticMode: Int)
}
