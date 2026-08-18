package nl.jwdr.ooc.transport.elm327

import nl.jwdr.ooc.transport.CanFrame

/**
 * Text codec for an ELM327 configured for raw 11-bit CAN frames:
 * ATH1 (headers on), ATS0 (no spaces), ATL0 (no linefeeds), ATCAF0 (no
 * ISO-TP formatting by the adapter). A monitored frame then prints as
 * `<3-hex-digit id><hex payload>` on one line; spaces are tolerated because
 * some clones ignore ATS0.
 */
object Elm327FrameCodec {

    /** Parses a received line into a frame, or null for status/noise lines. */
    fun parseFrameLine(line: String): CanFrame? {
        val hex = line.filterNot { it == ' ' }
        if (hex.length < 5 || hex.length % 2 == 0) return null
        if (!hex.all { it.isHexDigit() }) return null
        val payloadDigits = hex.length - 3
        if (payloadDigits > 16) return null
        val id = hex.take(3).toInt(16)
        val data = ByteArray(payloadDigits / 2) { i ->
            hex.substring(3 + 2 * i, 5 + 2 * i).toInt(16).toByte()
        }
        return CanFrame(id, data)
    }

    /** The payload of [frame] as the bare hex digits the ELM327 transmits raw. */
    fun formatSendPayload(frame: CanFrame): String =
        frame.data.joinToString("") { "%02X".format(it) }

    /** The `ATSH` command selecting the 11-bit transmit header [id]. */
    fun setHeaderCommand(id: Int): String = "ATSH%03X".format(id)

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'A'..'F' || this in 'a'..'f'
}
