package nl.jwdr.ooc.protocol.obd2

import nl.jwdr.ooc.protocol.kwp2000.KwpRequest
import nl.jwdr.ooc.protocol.kwp2000.checkPositiveResponse

/**
 * Generic OBD-II (SAE J1979 / ISO 15031-5) services for the no-catalog
 * fallback mode. They share KWP2000's response framing (+0x40 positive,
 * 0x7F negative), so they run through the same session executor — without
 * any diagnostic-session setup, which OBD-II does not use.
 */

/** Mode 01: read one current-data PID. */
data class ReadCurrentData(val pid: Int) : KwpRequest<ReadCurrentData.Response> {

    override fun encode() = byteArrayOf(0x01, pid.toByte())

    override fun decodeResponse(payload: ByteArray): Response {
        checkPositiveResponse(0x01, payload, minLength = 2)
        return Response(
            pid = payload[1].toInt() and 0xFF,
            data = payload.copyOfRange(2, payload.size),
        )
    }

    class Response(val pid: Int, val data: ByteArray)
}

/** Mode 03: read the stored emission-related DTCs. */
object ReadStoredDtcs : KwpRequest<ReadStoredDtcs.Response> {

    override fun encode() = byteArrayOf(0x03)

    override fun decodeResponse(payload: ByteArray): Response {
        checkPositiveResponse(0x03, payload, minLength = 1)
        val codes = mutableListOf<Int>()
        // Byte 1 is the DTC count; two-byte codes follow. 0x0000 is padding.
        var i = 2
        while (i + 1 < payload.size) {
            val code = ((payload[i].toInt() and 0xFF) shl 8) or (payload[i + 1].toInt() and 0xFF)
            if (code != 0) codes += code
            i += 2
        }
        return Response(codes)
    }

    class Response(val codes: List<Int>)
}

/** Mode 04: clear emission-related DTCs and stored data. Destructive. */
object ClearEmissionData : KwpRequest<Unit> {

    override fun encode() = byteArrayOf(0x04)

    override fun decodeResponse(payload: ByteArray) {
        checkPositiveResponse(0x04, payload)
    }
}
