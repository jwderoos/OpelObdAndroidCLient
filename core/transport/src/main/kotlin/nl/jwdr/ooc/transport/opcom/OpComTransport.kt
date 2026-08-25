package nl.jwdr.ooc.transport.opcom

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import nl.jwdr.ooc.transport.CanFrame
import nl.jwdr.ooc.transport.ConnectionState
import nl.jwdr.ooc.transport.ObdTransport

/**
 * [ObdTransport] over an OP-COM clone USB serial interface, framed per
 * `docs/formats/opcom-debug-capture.md`.
 *
 * Minimal first cut: the `AB`/`AA`/`AC` init handshake, then raw `90`
 * (transmit) / `91` (receive) CAN frame I/O only. Bus/mode selection, RX
 * filter slots, and periodic/cyclic TX are documented but not implemented
 * yet — the protocol layer runs its own session logic on top of raw frames,
 * as it already does for [nl.jwdr.ooc.transport.elm327.Elm327Transport].
 *
 * Unlike the half-duplex ELM327 (one command, wait for its prompt), the
 * interface is full-duplex: `91`/`7F` records can arrive at any time, so a
 * dedicated reader coroutine runs for the life of the connection and
 * dispatches to whichever command is currently awaiting its response.
 */
class OpComTransport(
    private val link: OpComLink,
    private val scope: CoroutineScope,
    private val responseTimeout: Duration = 2.seconds,
    /**
     * Handshake probe: how many times to send the first `AB` command before
     * giving up, and how long to wait for each attempt. Default (1 attempt,
     * [responseTimeout]) is the plain handshake. The app sets a longer
     * retry window while chasing the clone-interface bug where `AB` is
     * answered by a lone `7F` record — see docs/opcom-handshake-handover.md.
     * Every timed-out attempt is reported through [log].
     */
    private val handshakeAttempts: Int = 1,
    private val handshakeAttemptTimeout: Duration = responseTimeout,
    /**
     * Sink for verbose per-record diagnostic logging (raw bytes, decoded
     * records, unmatched responses). No-op unless the caller wires up the
     * app's debug-logging setting — this module has no Android dependency,
     * so it can't read that setting itself; see `AppContainer.buildTransport`.
     */
    private val log: (String) -> Unit = {},
) : ObdTransport {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state

    private val _incomingFrames = MutableSharedFlow<CanFrame>(
        replay = REPLAY_BUFFER,
        extraBufferCapacity = REPLAY_BUFFER,
    )
    override val incomingFrames: Flow<CanFrame> = _incomingFrames

    private val commandMutex = Mutex()
    private var pendingResponse: PendingResponse? = null
    private var readBuffer = ByteArray(0)
    private var readerJob: Job? = null

    override suspend fun connect() {
        if (_state.value == ConnectionState.Ready) return
        _state.value = ConnectionState.Connecting
        commandMutex.withLock {
            try {
                readBuffer = ByteArray(0)
                pendingResponse = null
                _incomingFrames.resetReplayCache()
                link.open()
                readerJob = scope.launch { readLoop() }
                probeHandshake()
                executeCommand(CMD_GET_FIRMWARE_VERSION)
                executeCommand(CMD_INIT, byteArrayOf(0x01))
                _state.value = ConnectionState.Ready
            } catch (e: Exception) {
                _state.value = ConnectionState.Error(e)
                teardown()
                throw e
            }
        }
    }

    override suspend fun disconnect() {
        teardown()
        _state.value = ConnectionState.Disconnected
        commandMutex.withLock {
            readBuffer = ByteArray(0)
            pendingResponse = null
            _incomingFrames.resetReplayCache()
        }
    }

    override suspend fun send(frame: CanFrame) {
        check(_state.value == ConnectionState.Ready) {
            "send() requires ConnectionState.Ready, but transport is ${_state.value}"
        }
        commandMutex.withLock {
            try {
                awaitResponse(CMD_SEND_FRAME) { link.write(OpComFrameCodec.encodeSendFrame(frame)) }
            } catch (e: OpComTimeoutException) {
                _state.value = ConnectionState.Error(e)
                teardown()
                throw e
            }
        }
    }

    /** Caller must hold [commandMutex]. */
    private suspend fun executeCommand(code: Int, args: ByteArray = ByteArray(0)) {
        awaitResponse(code) { link.write(OpComFrameCodec.encodeCommand(code, args)) }
    }

    /**
     * Sends `AB` up to [handshakeAttempts] times, [handshakeAttemptTimeout] each,
     * until an `EB` arrives. Caller must hold [commandMutex].
     */
    private suspend fun probeHandshake() {
        var attempt = 0
        while (true) {
            attempt++
            try {
                awaitResponse(CMD_GET_SERIAL, handshakeAttemptTimeout) {
                    link.write(OpComFrameCodec.encodeCommand(CMD_GET_SERIAL))
                }
                if (attempt > 1) log("handshake: AB answered on attempt $attempt/$handshakeAttempts")
                return
            } catch (e: OpComTimeoutException) {
                log("handshake: AB attempt $attempt/$handshakeAttempts timed out after $handshakeAttemptTimeout")
                if (attempt >= handshakeAttempts) throw e
            }
        }
    }

    /** Caller must hold [commandMutex]: only one command may be outstanding at a time. */
    private suspend fun awaitResponse(
        commandCode: Int,
        timeout: Duration = responseTimeout,
        write: suspend () -> Unit,
    ) {
        val deferred = CompletableDeferred<OpComRecord.Response>()
        pendingResponse = PendingResponse(OpComFrameCodec.responseCodeFor(commandCode), deferred)
        write()
        try {
            withTimeout(timeout) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            throw OpComTimeoutException(
                "timed out after $timeout waiting for the response to command 0x${commandCode.toString(16)}",
            )
        } finally {
            pendingResponse = null
        }
    }

    /**
     * Runs for the life of the connection. [link] may be a blocking synchronous USB read
     * underneath (see [nl.jwdr.ooc.transport.opcom.OpComLink]'s Android implementation): a
     * concurrent [teardown] closing the port races an in-flight [OpComLink.read] rather than
     * cancelling it, so this must treat that failure as an ordinary disconnect, not let it
     * escape uncaught and take the whole app down with it.
     */
    private suspend fun readLoop() {
        try {
            while (true) {
                val chunk = link.read()
                val (records, rest) = OpComFrameCodec.readRecords(readBuffer + chunk)
                log(
                    "read ${chunk.size}B [${chunk.toHex()}] -> " +
                        "${records.size} record(s), ${rest.size}B unconsumed [${rest.toHex()}]",
                )
                readBuffer = rest
                for (payload in records) {
                    val record = OpComFrameCodec.decodeRecord(payload)
                    log("record ${record.describe()} raw=[${payload.toHex()}]")
                    dispatch(record)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = ConnectionState.Error(e)
        }
    }

    private fun dispatch(record: OpComRecord) {
        when (record) {
            is OpComRecord.RxFrame -> _incomingFrames.tryEmit(record.frame)
            OpComRecord.KeepAlive -> Unit
            is OpComRecord.Response -> {
                val pending = pendingResponse
                if (pending != null && pending.code == record.code) {
                    pendingResponse = null
                    pending.deferred.complete(record)
                } else {
                    log(
                        "unmatched response code=0x${record.code.toString(16)} " +
                            "pending=${pending?.code?.let { "0x${it.toString(16)}" } ?: "none"}",
                    )
                }
            }
        }
    }

    private suspend fun teardown() {
        readerJob?.cancel()
        readerJob = null
        runCatching { link.close() }
    }

    private class PendingResponse(val code: Int, val deferred: CompletableDeferred<OpComRecord.Response>)

    private companion object {
        const val REPLAY_BUFFER = 64
        const val CMD_GET_SERIAL = 0xAB
        const val CMD_GET_FIRMWARE_VERSION = 0xAA
        const val CMD_INIT = 0xAC
        const val CMD_SEND_FRAME = 0x90
    }
}

/** Failure reported by or while talking to an OP-COM clone interface. */
open class OpComException(message: String) : Exception(message)

/** No response to a command within the configured timeout. */
class OpComTimeoutException(message: String) : OpComException(message)

/** Formatting helpers for the [OpComTransport] `log` sink. */
private fun ByteArray.toHex(): String = joinToString(" ") { "%02x".format(it) }

private fun OpComRecord.describe(): String = when (this) {
    is OpComRecord.Response -> "Response(code=0x${code.toString(16)}, payload=[${payload.toHex()}])"
    is OpComRecord.RxFrame -> "RxFrame(id=0x${frame.id.toString(16)}, data=[${frame.data.toHex()}])"
    OpComRecord.KeepAlive -> "KeepAlive"
}
