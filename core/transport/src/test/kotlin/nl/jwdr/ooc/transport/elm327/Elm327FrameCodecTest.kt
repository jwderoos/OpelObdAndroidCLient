package nl.jwdr.ooc.transport.elm327

import nl.jwdr.ooc.transport.CanFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Codec for the ELM327 configured with ATH1 (headers on), ATS0 (no spaces),
 * ATL0 (no linefeeds), ATCAF0 (raw frames): a monitored frame prints as
 * `<3-hex-digit id><hex payload>` on one line.
 */
class Elm327FrameCodecTest {

    @Test
    fun `parses an 11-bit frame line with headers on and spaces off`() {
        assertEquals(
            CanFrame(0x7E8, byteArrayOf(0x06, 0x41, 0x00, 0xBE.toByte(), 0x3E, 0xB8.toByte(), 0x11)),
            Elm327FrameCodec.parseFrameLine("7E80641 00BE3EB811".replace(" ", "")),
        )
    }

    @Test
    fun `parses a frame line with spaces the adapter left in anyway`() {
        // Some clones ignore ATS0; be liberal in what we accept.
        assertEquals(
            CanFrame(0x7E8, byteArrayOf(0x03, 0x41, 0x0C, 0x1A, 0xF8.toByte())),
            Elm327FrameCodec.parseFrameLine("7E8 03 41 0C 1A F8"),
        )
    }

    @Test
    fun `rejects status lines and prompts`() {
        for (line in listOf("SEARCHING...", "OK", "NO DATA", "CAN ERROR", "BUFFER FULL", "STOPPED", "?", "", ">", "12.3V", "ELM327 v1.5")) {
            assertNull("expected null for \"$line\"", Elm327FrameCodec.parseFrameLine(line))
        }
    }

    @Test
    fun `rejects lines with an odd number of payload hex digits`() {
        assertNull(Elm327FrameCodec.parseFrameLine("7E80641 0"))
    }

    @Test
    fun `rejects frame lines with more than 8 payload bytes`() {
        assertNull(Elm327FrameCodec.parseFrameLine("7E8" + "AA".repeat(9)))
    }

    @Test
    fun `formats a send payload as bare hex digits`() {
        assertEquals(
            "02010C",
            Elm327FrameCodec.formatSendPayload(CanFrame(0x7E0, byteArrayOf(0x02, 0x01, 0x0C))),
        )
    }

    @Test
    fun `formats the set-header command for an 11-bit id`() {
        assertEquals("ATSH7E0", Elm327FrameCodec.setHeaderCommand(0x7E0))
    }

    @Test
    fun `formats the set-header command for a low id with leading zeros`() {
        assertEquals("ATSH024", Elm327FrameCodec.setHeaderCommand(0x24))
    }
}
