package nl.jwdr.ooc.transport.opcom

/**
 * A raw byte pipe to an OP-COM clone interface (USB serial, ...). The
 * transport owns all record framing and command semantics — links only move
 * bytes.
 */
interface OpComLink {
    suspend fun open()

    suspend fun close()

    /** Writes [data] verbatim (already record-framed by the caller). */
    suspend fun write(data: ByteArray)

    /** Returns the next received chunk of bytes; suspends until data arrives. */
    suspend fun read(): ByteArray
}
