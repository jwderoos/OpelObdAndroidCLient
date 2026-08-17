package nl.jwdr.ooc.protocol.isotp

import nl.jwdr.ooc.protocol.isotp.IsoTpFrame.Consecutive
import nl.jwdr.ooc.protocol.isotp.IsoTpFrame.First
import nl.jwdr.ooc.protocol.isotp.IsoTpFrame.FlowControl
import nl.jwdr.ooc.protocol.isotp.IsoTpFrame.Single
import nl.jwdr.ooc.transport.CanFrame

/**
 * The four ISO 15765-2 (ISO-TP) protocol frames, as used by GMLAN diagnostics.
 *
 * The frame type lives in the high nibble of the first payload byte (the PCI
 * byte); the interpretation of the low nibble and the following bytes depends
 * on the type. Encode with [toCanFrame], decode with [CanFrame.toIsoTpFrame].
 */
sealed class IsoTpFrame {

    /** A complete payload of 1..7 bytes in one frame. PCI low nibble = length. */
    class Single(val data: ByteArray) : IsoTpFrame() {
        init {
            require(data.size in 1..7) { "single frame carries 1..7 bytes, got ${data.size}" }
        }

        override fun equals(other: Any?) = other is Single && other.data.contentEquals(data)

        override fun hashCode() = data.contentHashCode()

        override fun toString() = "Single(${data.toHex()})"
    }

    /**
     * Opens a multi-frame message: 12-bit [totalLength] of the full payload
     * plus its first six bytes.
     */
    class First(val totalLength: Int, val data: ByteArray) : IsoTpFrame() {
        init {
            require(totalLength in 8..4095) { "multi-frame payload is 8..4095 bytes, got $totalLength" }
            require(data.size == 6) { "first frame carries exactly 6 bytes, got ${data.size}" }
        }

        override fun equals(other: Any?) =
            other is First && other.totalLength == totalLength && other.data.contentEquals(data)

        override fun hashCode() = 31 * totalLength + data.contentHashCode()

        override fun toString() = "First(totalLength=$totalLength, ${data.toHex()})"
    }

    /** Continues a multi-frame message. [sequenceNumber] cycles 1..15, 0, 1, ... */
    class Consecutive(val sequenceNumber: Int, val data: ByteArray) : IsoTpFrame() {
        init {
            require(sequenceNumber in 0..15) { "sequence number is a nibble, got $sequenceNumber" }
            require(data.size in 1..7) { "consecutive frame carries 1..7 bytes, got ${data.size}" }
        }

        override fun equals(other: Any?) =
            other is Consecutive && other.sequenceNumber == sequenceNumber && other.data.contentEquals(data)

        override fun hashCode() = 31 * sequenceNumber + data.contentHashCode()

        override fun toString() = "Consecutive(seq=$sequenceNumber, ${data.toHex()})"
    }

    /**
     * The receiver's pacing answer to a [First] frame or a completed block.
     *
     * @param blockSize consecutive frames allowed before the next flow control;
     *   0 means "send everything".
     * @param separationTimeRaw the STmin byte as transmitted: 0x00..0x7F is
     *   milliseconds, 0xF1..0xF9 is 100..900 microseconds.
     */
    data class FlowControl(
        val status: FlowStatus,
        val blockSize: Int,
        val separationTimeRaw: Int,
    ) : IsoTpFrame()

    enum class FlowStatus(val nibble: Int) {
        CLEAR_TO_SEND(0x0),
        WAIT(0x1),
        OVERFLOW(0x2),
    }

    /**
     * Encodes this frame into a [CanFrame] with identifier [id]. When
     * [padByte] is non-null the payload is padded to 8 bytes with it, as GMLAN
     * buses expect; null emits the minimal frame length.
     */
    fun toCanFrame(id: Int, padByte: Byte?): CanFrame {
        val payload = when (this) {
            is Single -> byteArrayOf((PCI_SINGLE or data.size).toByte()) + data
            is First -> byteArrayOf(
                (PCI_FIRST or (totalLength ushr 8)).toByte(),
                (totalLength and 0xFF).toByte(),
            ) + data
            is Consecutive -> byteArrayOf((PCI_CONSECUTIVE or sequenceNumber).toByte()) + data
            is FlowControl -> byteArrayOf(
                (PCI_FLOW_CONTROL or status.nibble).toByte(),
                blockSize.toByte(),
                separationTimeRaw.toByte(),
            )
        }
        val padded = if (padByte != null && payload.size < 8) {
            payload + ByteArray(8 - payload.size) { padByte }
        } else {
            payload
        }
        return CanFrame(id, padded)
    }

    private companion object {
        const val PCI_SINGLE = 0x00
        const val PCI_FIRST = 0x10
        const val PCI_CONSECUTIVE = 0x20
        const val PCI_FLOW_CONTROL = 0x30
    }
}

/**
 * Decodes an ISO-TP frame from this CAN frame's payload, or null when the
 * payload is not a well-formed ISO-TP frame.
 */
fun CanFrame.toIsoTpFrame(): IsoTpFrame? {
    if (data.isEmpty()) return null
    val pci = data[0].toInt() and 0xFF
    return when (pci ushr 4) {
        0x0 -> {
            val length = pci and 0x0F
            if (length in 1..7 && data.size > length) Single(data.copyOfRange(1, 1 + length)) else null
        }
        0x1 -> {
            if (data.size < 8) return null
            val totalLength = ((pci and 0x0F) shl 8) or (data[1].toInt() and 0xFF)
            if (totalLength < 8) return null
            First(totalLength, data.copyOfRange(2, 8))
        }
        0x2 -> {
            if (data.size < 2) return null
            Consecutive(pci and 0x0F, data.copyOfRange(1, data.size))
        }
        0x3 -> {
            if (data.size < 3) return null
            val status = IsoTpFrame.FlowStatus.entries.firstOrNull { it.nibble == pci and 0x0F } ?: return null
            FlowControl(status, data[1].toInt() and 0xFF, data[2].toInt() and 0xFF)
        }
        else -> null
    }
}

private fun ByteArray.toHex() = joinToString(" ") { "%02X".format(it) }
