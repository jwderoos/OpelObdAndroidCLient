package nl.jwdr.ooc.transport.opcom

import nl.jwdr.ooc.transport.CanFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Codec for the OP-COM clone's USB serial record framing, reverse-engineered
 * in `docs/formats/opcom-debug-capture.md`: `[len: u16 LE][payload][checksum: u8]`,
 * checksum = sum of the length bytes and payload mod 256.
 */
class OpComFrameCodecTest {

    @Test
    fun `encodeRecord wraps the payload with a little-endian length and mod-256 checksum`() {
        val record = OpComFrameCodec.encodeRecord(byteArrayOf(0xAB.toByte()))

        // length = 1 (LE), payload = AB, checksum = (0x01 + 0x00 + 0xAB) % 256
        assertEquals(
            listOf(0x01, 0x00, 0xAB, 0xAC),
            record.map { it.toInt() and 0xFF },
        )
    }

    @Test
    fun `encodeCommand puts the command code as the first payload byte followed by args`() {
        val record = OpComFrameCodec.encodeCommand(0xAC, byteArrayOf(0x01))

        val (records, rest) = OpComFrameCodec.readRecords(record)
        assertEquals(0, rest.size)
        assertEquals(listOf(byteArrayOf(0xAC.toByte(), 0x01)).map { it.toList() }, records.map { it.toList() })
    }

    @Test
    fun `encodeSendFrame encodes the CAN id little-endian, DLC, and zero-padded data`() {
        val frame = CanFrame(0x7E0, byteArrayOf(0x02, 0x01, 0x0C))

        val record = OpComFrameCodec.encodeSendFrame(frame)
        val (records, _) = OpComFrameCodec.readRecords(record)
        val payload = records.single()

        assertEquals(0x90, payload[0].toInt() and 0xFF)
        assertEquals(listOf(0xE0, 0x07, 0x00, 0x00), payload.slice(1..4).map { it.toInt() and 0xFF })
        assertEquals(3, payload[5].toInt() and 0xFF) // DLC
        assertEquals(
            listOf(0x02, 0x01, 0x0C, 0, 0, 0, 0, 0),
            payload.slice(6..13).map { it.toInt() and 0xFF },
        )
    }

    @Test
    fun `readRecords extracts a single complete record and consumes it from the buffer`() {
        val record = OpComFrameCodec.encodeRecord(byteArrayOf(0x7F))

        val (records, rest) = OpComFrameCodec.readRecords(record)

        assertEquals(listOf(byteArrayOf(0x7F)).map { it.toList() }, records.map { it.toList() })
        assertEquals(0, rest.size)
    }

    @Test
    fun `readRecords waits for more data when the buffer holds only a partial record`() {
        val record = OpComFrameCodec.encodeRecord(byteArrayOf(0xAB.toByte(), 0x01, 0x02))
        val partial = record.copyOfRange(0, record.size - 1)

        val (records, rest) = OpComFrameCodec.readRecords(partial)

        assertTrue(records.isEmpty())
        assertEquals(partial.toList(), rest.toList())
    }

    @Test
    fun `readRecords extracts multiple records arriving in one chunk`() {
        val first = OpComFrameCodec.encodeRecord(byteArrayOf(0xAB.toByte()))
        val second = OpComFrameCodec.encodeRecord(byteArrayOf(0x7F))

        val (records, rest) = OpComFrameCodec.readRecords(first + second)

        assertEquals(
            listOf(byteArrayOf(0xAB.toByte()), byteArrayOf(0x7F)).map { it.toList() },
            records.map { it.toList() },
        )
        assertEquals(0, rest.size)
    }

    @Test
    fun `readRecords resyncs past a corrupted checksum by dropping one byte at a time`() {
        val good = OpComFrameCodec.encodeRecord(byteArrayOf(0x7F))
        val corrupted = OpComFrameCodec.encodeRecord(byteArrayOf(0xAB.toByte()))
        corrupted[corrupted.size - 1] = (corrupted.last() + 1).toByte() // wrong checksum

        val (records, rest) = OpComFrameCodec.readRecords(corrupted + good)

        // The corrupted record never validates; only the good one survives.
        assertEquals(listOf(byteArrayOf(0x7F)).map { it.toList() }, records.map { it.toList() })
        assertEquals(0, rest.size)
    }

    @Test
    fun `readRecords treats an implausibly large declared length as corrupt instead of stalling`() {
        // Every 1-byte-shifted reading of this prefix still declares an
        // implausible length, so it must not make the reader wait forever
        // for bytes that will never arrive; it must resync past it instead.
        val garbage = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        val good = OpComFrameCodec.encodeRecord(byteArrayOf(0x7F))

        val (records, rest) = OpComFrameCodec.readRecords(garbage + good)

        assertEquals(listOf(byteArrayOf(0x7F)).map { it.toList() }, records.map { it.toList() })
        assertEquals(0, rest.size)
    }

    @Test
    fun `decodeRecord decodes a 91 record as a received frame with the big-endian id flip`() {
        // Note: 90 (send) is little-endian; 91 (receive) is big-endian per the doc.
        val payload = byteArrayOf(0x91.toByte(), 0x00, 0x00, 0x07, 0xE8.toByte(), 2, 0x41, 0x00, 0, 0, 0, 0, 0, 0)

        val decoded = OpComFrameCodec.decodeRecord(payload)

        assertEquals(OpComRecord.RxFrame(CanFrame(0x7E8, byteArrayOf(0x41, 0x00))), decoded)
    }

    @Test
    fun `decodeRecord decodes 7F as a keep-alive`() {
        assertEquals(OpComRecord.KeepAlive, OpComFrameCodec.decodeRecord(byteArrayOf(0x7F)))
        assertEquals(OpComRecord.KeepAlive, OpComFrameCodec.decodeRecord(byteArrayOf(0x7F, 0x00)))
    }

    @Test
    fun `decodeRecord decodes any other code as a generic response carrying the remaining payload`() {
        val decoded = OpComFrameCodec.decodeRecord(byteArrayOf(0xEB.toByte(), 0x4F, 0x49))

        assertEquals(OpComRecord.Response(0xEB, byteArrayOf(0x4F, 0x49)), decoded)
    }

    @Test
    fun `encodeSetRxFilter encodes the slot byte then the CAN id little-endian`() {
        val record = OpComFrameCodec.encodeSetRxFilter(slot = 3, id = 0x549)

        val (records, _) = OpComFrameCodec.readRecords(record)
        val payload = records.single()

        assertEquals(0x83, payload[0].toInt() and 0xFF)
        assertEquals(3, payload[1].toInt() and 0xFF)
        assertEquals(listOf(0x49, 0x05, 0x00, 0x00), payload.slice(2..5).map { it.toInt() and 0xFF })
    }

    @Test
    fun `encodeSetRxFilter encodes an off slot as FF FF FF FF`() {
        val record = OpComFrameCodec.encodeSetRxFilter(slot = 1, id = -1)

        val (records, _) = OpComFrameCodec.readRecords(record)
        val payload = records.single()

        assertEquals(listOf(0xFF, 0xFF, 0xFF, 0xFF), payload.slice(2..5).map { it.toInt() and 0xFF })
    }

    @Test
    fun `responseCodeFor ORs in the response bit as observed for every documented command`() {
        assertEquals(0xEB, OpComFrameCodec.responseCodeFor(0xAB))
        assertEquals(0xEA, OpComFrameCodec.responseCodeFor(0xAA))
        assertEquals(0xEC, OpComFrameCodec.responseCodeFor(0xAC))
        assertEquals(0xD0, OpComFrameCodec.responseCodeFor(0x90))
        assertEquals(0xC3, OpComFrameCodec.responseCodeFor(0x83))
    }
}
