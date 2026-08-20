package nl.jwdr.ooc.transport.opcom

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
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
                executeCommand(CMD_GET_SERIAL)
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

    /** Caller must hold [commandMutex]: only one command may be outstanding at a time. */
    private suspend fun awaitResponse(commandCode: Int, write: suspend () -> Unit) {
        val deferred = CompletableDeferred<OpComRecord.Response>()
        pendingResponse = PendingResponse(OpComFrameCodec.responseCodeFor(commandCode), deferred)
        write()
        try {
            withTimeout(responseTimeout) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            throw OpComTimeoutException(
                "timed out after $responseTimeout waiting for the response to command 0x${commandCode.toString(16)}",
            )
        } finally {
            pendingResponse = null
        }
    }

    private suspend fun readLoop() {
        while (true) {
            val chunk = link.read()
            val (records, rest) = OpComFrameCodec.readRecords(readBuffer + chunk)
            readBuffer = rest
            for (payload in records) {
                dispatch(OpComFrameCodec.decodeRecord(payload))
            }
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
