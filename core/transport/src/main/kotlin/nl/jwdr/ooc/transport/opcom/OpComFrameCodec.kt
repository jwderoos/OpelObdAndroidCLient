package nl.jwdr.ooc.transport.opcom

import nl.jwdr.ooc.transport.CanFrame

/**
 * Codec for the OP-COM clone's USB serial record framing, reverse-engineered
 * from real captures in `docs/formats/opcom-debug-capture.md`:
 *
 * ```
 * [length: u16 LE] [payload: `length` bytes] [checksum: u8]
 * ```
 *
 * `checksum` = sum of the two length bytes and the payload, mod 256. The
 * first payload byte is a command/response code; a response to command
 * `0xNN` uses code `0xNN + 0x40` (arithmetic; OR is wrong for 0x73/0x74). `91` (received CAN frame, big-endian id —
 * note the flip vs `90`'s little-endian id) and `7F` (keep-alive) arrive
 * unsolicited.
 */
object OpComFrameCodec {

    /** Commands/responses observed in practice stay well under this; a larger declared length is corrupt framing. */
    private const val MAX_PAYLOAD_LENGTH = 64

    private const val CODE_RX_FRAME = 0x91
    private const val CODE_KEEP_ALIVE = 0x7F
    private const val CODE_SEND_FRAME = 0x90
    private const val CODE_SEND_CONSECUTIVE_FRAME = 0x9F
    private const val CODE_SET_RX_FILTER = 0x83
    private const val ISOTP_PCI_CONSECUTIVE_FRAME = 0x2

    /** Wraps [payload] in the length-prefixed, checksummed record framing. */
    fun encodeRecord(payload: ByteArray): ByteArray {
        require(payload.size <= 0xFFFF) { "payload too large: ${payload.size} bytes" }
        val length = payload.size
        val lengthBytes = byteArrayOf((length and 0xFF).toByte(), ((length shr 8) and 0xFF).toByte())
        val checksum = checksumOf(lengthBytes, payload)
        return lengthBytes + payload + byteArrayOf(checksum.toByte())
    }

    /** Builds a command record: code byte followed by [args]. */
    fun encodeCommand(code: Int, args: ByteArray = ByteArray(0)): ByteArray =
        encodeRecord(byteArrayOf(code.toByte()) + args)

    /** Builds an `83` (set RX filter) record: slot number, then the CAN id little-endian. `id = -1` (`FF FF FF FF`) turns the slot off. */
    fun encodeSetRxFilter(slot: Int, id: Int): ByteArray =
        encodeRecord(byteArrayOf(CODE_SET_RX_FILTER.toByte(), slot.toByte()) + intToLe32(id))

    /**
     * The command code [encodeSendFrame] uses for [frame]: `9F` for an ISO-TP
     * Consecutive Frame continuation (PCI nibble `2`), `90` for everything
     * else (Single/First Frame, or non-ISO-TP payloads). The dongle acks each
     * with its own `+0x40` code (`DF`/`D0`) — callers must await the code
     * this returns, not assume `90`.
     */
    fun sendFrameCommand(frame: CanFrame): Int =
        if (frame.data.isNotEmpty() && ((frame.data[0].toInt() and 0xFF) ushr 4) == ISOTP_PCI_CONSECUTIVE_FRAME) {
            CODE_SEND_CONSECUTIVE_FRAME
        } else {
            CODE_SEND_FRAME
        }

    /** Builds a `90`/`9F` (transmit CAN frame) record: little-endian id, DLC, data padded to 8 bytes. */
    fun encodeSendFrame(frame: CanFrame): ByteArray {
        val idBytes = intToLe32(frame.id)
        val padded = ByteArray(8)
        frame.data.copyInto(padded)
        val payload = byteArrayOf(sendFrameCommand(frame).toByte()) + idBytes + byteArrayOf(frame.data.size.toByte()) + padded
        return encodeRecord(payload)
    }

    /**
     * Extracts every complete, checksum-valid record from [buffer].
     *
     * Returns the decoded payloads (checksum and length framing stripped) in
     * order, plus the unconsumed tail of [buffer] to prepend to the next
     * chunk. A record whose checksum fails to validate is dropped and the
     * buffer resyncs one byte at a time; a declared length beyond
     * [MAX_PAYLOAD_LENGTH] is treated as corrupt immediately rather than
     * waiting for bytes that will never arrive.
     */
    fun readRecords(buffer: ByteArray): Pair<List<ByteArray>, ByteArray> {
        val records = mutableListOf<ByteArray>()
        var offset = 0
        while (true) {
            if (buffer.size - offset < 2) break
            val length = (buffer[offset].toInt() and 0xFF) or ((buffer[offset + 1].toInt() and 0xFF) shl 8)
            if (length > MAX_PAYLOAD_LENGTH) {
                offset += 1
                continue
            }
            val recordSize = 2 + length + 1
            if (buffer.size - offset < recordSize) break
            val lengthBytes = buffer.copyOfRange(offset, offset + 2)
            val payload = buffer.copyOfRange(offset + 2, offset + 2 + length)
            val checksum = buffer[offset + 2 + length].toInt() and 0xFF
            if (checksum == checksumOf(lengthBytes, payload)) {
                records += payload
                offset += recordSize
            } else {
                offset += 1
            }
        }
        return records to buffer.copyOfRange(offset, buffer.size)
    }

    /** Decodes a checksum-validated record payload (as returned by [readRecords]) into an [OpComRecord]. */
    fun decodeRecord(payload: ByteArray): OpComRecord {
        require(payload.isNotEmpty()) { "a record payload always has at least a code byte" }
        return when (val code = payload[0].toInt() and 0xFF) {
            CODE_RX_FRAME -> OpComRecord.RxFrame(decodeCanFrame(payload))
            CODE_KEEP_ALIVE -> OpComRecord.KeepAlive
            else -> OpComRecord.Response(code, payload.copyOfRange(1, payload.size))
        }
    }

    /** The response code the interface uses for [command], e.g. `0x90` -> `0xD0`, `0xAB` -> `0xEB`. */
    fun responseCodeFor(command: Int): Int = (command + 0x40) and 0xFF

    private fun decodeCanFrame(payload: ByteArray): CanFrame {
        val id = beToInt32(payload.copyOfRange(1, 5))
        val dlc = payload[5].toInt() and 0xFF
        val data = payload.copyOfRange(6, 6 + dlc)
        return CanFrame(id, data)
    }

    private fun checksumOf(lengthBytes: ByteArray, payload: ByteArray): Int {
        var sum = 0
        for (b in lengthBytes) sum += b.toInt() and 0xFF
        for (b in payload) sum += b.toInt() and 0xFF
        return sum % 256
    }

    private fun intToLe32(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )

    private fun beToInt32(bytes: ByteArray): Int =
        ((bytes[0].toInt() and 0xFF) shl 24) or
            ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or
            (bytes[3].toInt() and 0xFF)
}
