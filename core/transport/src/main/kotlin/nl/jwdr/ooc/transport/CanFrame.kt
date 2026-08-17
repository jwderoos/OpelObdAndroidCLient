package nl.jwdr.ooc.transport

/**
 * A single normalized CAN frame as exchanged with an OBD adapter.
 *
 * Adapter implementations own their wire codec (ELM327 AT text, USB binary, ...)
 * and translate to/from this representation.
 *
 * @param id CAN identifier (11-bit or 29-bit).
 * @param data 0..8 payload bytes.
 */
data class CanFrame(
    val id: Int,
    val data: ByteArray,
) {
    init {
        require(data.size <= 8) { "CAN payload is at most 8 bytes, got ${data.size}" }
    }

    override fun equals(other: Any?): Boolean =
        other is CanFrame && other.id == id && other.data.contentEquals(data)

    override fun hashCode(): Int = 31 * id + data.contentHashCode()

    override fun toString(): String =
        "CanFrame(id=0x${id.toString(16)}, data=${data.joinToString(" ") { "%02X".format(it) }})"
}
