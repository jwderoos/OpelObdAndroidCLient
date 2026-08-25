package nl.jwdr.ooc.transport.opcom

import kotlinx.coroutines.channels.Channel

/**
 * Scripted [OpComLink] replaying canned interface responses in tests.
 *
 * Each written record is matched by its command code (the first payload
 * byte) against a script; the scripted reply chunks are queued for [read].
 * An unscripted command gets a default empty-payload success response
 * (`code + 0x40`, no data) so the init handshake doesn't need a script line
 * per command. Use [pushUnsolicited] to inject `91`/`7F` records
 * independent of any write.
 */
class FakeOpComLink : OpComLink {

    var opened = false
        private set
    var closed = false
        private set

    /** How often [open] was called; reopening after [close] is supported. */
    var openCount = 0
        private set

    /** Every command code written by the transport, in order. */
    val writtenCommands = mutableListOf<Int>()

    /** Every full record payload (code byte + args) written by the transport, in order. */
    val writtenPayloads = mutableListOf<ByteArray>()

    private val scripts = mutableMapOf<Int, List<ByteArray>>()
    private var incoming = Channel<ByteArray>(Channel.UNLIMITED)

    /** Scripts the raw record [chunks] delivered after a command with [code] is written. */
    fun on(code: Int, vararg chunks: ByteArray) {
        scripts[code] = chunks.toList()
    }

    /** Scripts [code] to get no reply at all (timeout tests). */
    fun onSilence(code: Int) {
        scripts[code] = emptyList()
    }

    /** Pushes bytes into the link with no corresponding write, e.g. an unsolicited `91`/`7F` record. */
    fun pushUnsolicited(bytes: ByteArray) {
        incoming.trySend(bytes)
    }

    /**
     * Simulates the underlying link reporting an I/O error on whatever [read] call is
     * currently suspended (or the next one) — e.g. a USB port that was closed out from under
     * an in-flight blocking read, rather than the reader coroutine being cancelled.
     */
    fun failPendingRead(error: Throwable) {
        incoming.close(error)
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

    override suspend fun write(data: ByteArray) {
        val (records, _) = OpComFrameCodec.readRecords(data)
        val payload = records.single()
        val code = payload[0].toInt() and 0xFF
        writtenCommands += code
        writtenPayloads += payload
        val chunks = scripts[code]
            ?: listOf(OpComFrameCodec.encodeRecord(byteArrayOf(OpComFrameCodec.responseCodeFor(code).toByte())))
        chunks.forEach { incoming.trySend(it) }
    }

    override suspend fun read(): ByteArray = incoming.receive()
}
