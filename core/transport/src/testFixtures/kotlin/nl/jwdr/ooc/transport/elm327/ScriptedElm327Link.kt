package nl.jwdr.ooc.transport.elm327

import kotlinx.coroutines.channels.Channel

/**
 * Scripted [Elm327Link] replaying canned adapter dialogue in tests.
 *
 * Each write is matched (trimmed, without the trailing CR) against scripted
 * commands; the scripted reply chunks are queued for [read]. Unscripted
 * commands get a default `OK` reply so init sequences don't need a script
 * line per AT command. Reply text is queued exactly as scripted — including
 * the terminating prompt — so tests control chunking and quirks precisely.
 */
class ScriptedElm327Link : Elm327Link {

    var opened = false
        private set
    var closed = false
        private set

    /** How often [open] was called; reopening after [close] is supported. */
    var openCount = 0
        private set

    /** Every command written by the transport, in order, CR stripped. */
    val written = mutableListOf<String>()

    private val scripts = mutableMapOf<String, List<String>>()
    private var incoming = Channel<String>(Channel.UNLIMITED)

    /** Scripts the reply [chunks] delivered after [command] is written. */
    fun on(command: String, vararg chunks: String) {
        scripts[command] = chunks.toList()
    }

    /** Scripts [command] to get no reply at all (timeout tests). */
    fun onSilence(command: String) {
        scripts[command] = emptyList()
    }

    override suspend fun open() {
        opened = true
        openCount++
        incoming = Channel(Channel.UNLIMITED)
    }

    override suspend fun close() {
        closed = true
        incoming.close()
    }

    override suspend fun write(data: String) {
        val command = data.trimEnd('\r', '\n', ' ')
        written += command
        val chunks = scripts[command] ?: listOf("OK\r\r>")
        chunks.forEach { incoming.trySend(it) }
    }

    override suspend fun read(): String = incoming.receive()
}
