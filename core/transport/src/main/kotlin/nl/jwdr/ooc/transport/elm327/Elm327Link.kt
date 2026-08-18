package nl.jwdr.ooc.transport.elm327

/**
 * A raw character pipe to an ELM327-style adapter (Bluetooth SPP socket,
 * TCP socket, ...). The transport owns all ELM semantics — links only move
 * text and know nothing about AT commands or prompts.
 */
interface Elm327Link {
    suspend fun open()

    suspend fun close()

    /** Writes [data] verbatim (commands include their trailing CR). */
    suspend fun write(data: String)

    /** Returns the next received chunk of characters; suspends until data arrives. */
    suspend fun read(): String
}
