package nl.jwdr.ooc.protocol.isotp

import nl.jwdr.ooc.transport.CanFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IsoTpFrameTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    // --- encoding ---

    @Test
    fun `single frame encodes length nibble and pads to 8 bytes`() {
        val frame = IsoTpFrame.Single(bytes(0x3E, 0x01)).toCanFrame(id = 0x241, padByte = 0xAA.toByte())

        assertEquals(0x241, frame.id)
        assertArrayEquals(bytes(0x02, 0x3E, 0x01, 0xAA, 0xAA, 0xAA, 0xAA, 0xAA), frame.data)
    }

    @Test
    fun `single frame without padding keeps minimal length`() {
        val frame = IsoTpFrame.Single(bytes(0x3E)).toCanFrame(id = 0x241, padByte = null)

        assertArrayEquals(bytes(0x01, 0x3E), frame.data)
    }

    @Test
    fun `first frame encodes 12-bit total length and first six payload bytes`() {
        val frame = IsoTpFrame.First(totalLength = 0x123, data = bytes(1, 2, 3, 4, 5, 6))
            .toCanFrame(id = 0x241, padByte = 0xAA.toByte())

        assertArrayEquals(bytes(0x11, 0x23, 1, 2, 3, 4, 5, 6), frame.data)
    }

    @Test
    fun `consecutive frame encodes sequence number nibble`() {
        val frame = IsoTpFrame.Consecutive(sequenceNumber = 5, data = bytes(7, 8))
            .toCanFrame(id = 0x241, padByte = 0xAA.toByte())

        assertArrayEquals(bytes(0x25, 7, 8, 0xAA, 0xAA, 0xAA, 0xAA, 0xAA), frame.data)
    }

    @Test
    fun `flow control encodes status block size and separation time`() {
        val frame = IsoTpFrame.FlowControl(
            status = IsoTpFrame.FlowStatus.CLEAR_TO_SEND,
            blockSize = 8,
            separationTimeRaw = 0x14,
        ).toCanFrame(id = 0x641, padByte = 0xAA.toByte())

        assertArrayEquals(bytes(0x30, 0x08, 0x14, 0xAA, 0xAA, 0xAA, 0xAA, 0xAA), frame.data)
    }

    // --- decoding ---

    @Test
    fun `decodes single frame using its length nibble, ignoring padding`() {
        val decoded = CanFrame(0x641, bytes(0x03, 0x7E, 0x01, 0x02, 0xAA, 0xAA, 0xAA, 0xAA)).toIsoTpFrame()

        assertEquals(IsoTpFrame.Single(bytes(0x7E, 0x01, 0x02)), decoded)
    }

    @Test
    fun `decodes first frame with total length and six data bytes`() {
        val decoded = CanFrame(0x641, bytes(0x10, 0x0A, 1, 2, 3, 4, 5, 6)).toIsoTpFrame()

        assertEquals(IsoTpFrame.First(totalLength = 10, data = bytes(1, 2, 3, 4, 5, 6)), decoded)
    }

    @Test
    fun `decodes consecutive frame with sequence number and remaining bytes`() {
        val decoded = CanFrame(0x641, bytes(0x21, 7, 8, 9, 10, 11, 12, 13)).toIsoTpFrame()

        assertEquals(IsoTpFrame.Consecutive(sequenceNumber = 1, data = bytes(7, 8, 9, 10, 11, 12, 13)), decoded)
    }

    @Test
    fun `decodes flow control wait status`() {
        val decoded = CanFrame(0x641, bytes(0x31, 0x00, 0x00, 0xAA, 0xAA, 0xAA, 0xAA, 0xAA)).toIsoTpFrame()

        assertEquals(
            IsoTpFrame.FlowControl(
                status = IsoTpFrame.FlowStatus.WAIT,
                blockSize = 0,
                separationTimeRaw = 0x00,
            ),
            decoded,
        )
    }

    @Test
    fun `single frame with zero length nibble is invalid`() {
        assertNull(CanFrame(0x641, bytes(0x00, 0xAA, 0xAA)).toIsoTpFrame())
    }

    @Test
    fun `single frame whose length exceeds the frame payload is invalid`() {
        assertNull(CanFrame(0x641, bytes(0x05, 0x7E)).toIsoTpFrame())
    }

    @Test
    fun `unknown pci type is invalid`() {
        assertNull(CanFrame(0x641, bytes(0x40, 0x00)).toIsoTpFrame())
    }

    @Test
    fun `empty frame is invalid`() {
        assertNull(CanFrame(0x641, ByteArray(0)).toIsoTpFrame())
    }

    @Test
    fun `flow control with unknown status is invalid`() {
        assertNull(CanFrame(0x641, bytes(0x33, 0x00, 0x00)).toIsoTpFrame())
    }

    // --- round trip ---

    @Test
    fun `frames survive an encode-decode round trip`() {
        val frames = listOf(
            IsoTpFrame.Single(bytes(0x10, 0x89)),
            IsoTpFrame.First(totalLength = 4095, data = bytes(1, 2, 3, 4, 5, 6)),
            // A padded consecutive frame cannot round-trip: its payload length is
            // not encoded, so only a full 7-byte CF is padding-free.
            IsoTpFrame.Consecutive(sequenceNumber = 15, data = bytes(9, 10, 11, 12, 13, 14, 15)),
            IsoTpFrame.FlowControl(IsoTpFrame.FlowStatus.OVERFLOW, blockSize = 0, separationTimeRaw = 0x7F),
        )

        for (frame in frames) {
            assertEquals(frame, frame.toCanFrame(id = 0x241, padByte = 0xAA.toByte()).toIsoTpFrame())
        }
    }
}
