package nl.jwdr.ooc.transport.opcom

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
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
 * The `AB`/`AA`/`AC` init handshake, then raw `90` (transmit) / `91`
 * (receive) CAN frame I/O; the protocol layer runs its own session logic on
 * top of raw frames, as it already does for [nl.jwdr.ooc.transport.elm327.Elm327Transport].
 * Periodic/cyclic TX (`71`/`72`/`9F`) is documented but not implemented yet.
 *
 * Unlike the half-duplex ELM327 (one command, wait for its prompt), the
 * interface is full-duplex: `91`/`7F` records can arrive at any time, so a
 * dedicated reader coroutine runs for the life of the connection and
 * dispatches to whichever command is currently awaiting its response.
 *
 * [BusSelectable.configureBus] additionally replays the vendor's fixed
 * post-handshake bus-select + RX-filter block (issue #30) — without it the
 * interface acks every command but never forwards a single ECU response.
 */
class OpComTransport(
    private val link: OpComLink,
    private val scope: CoroutineScope,
    private val responseTimeout: Duration = 2.seconds,
    /**
     * How many times to send the first `AB` command before giving up, and
     * how long to wait for each attempt. Default (1 attempt, [responseTimeout])
     * is the plain handshake; the app allows a few retries as cheap robustness.
     * Every timed-out attempt is reported through [log].
     */
    private val handshakeAttempts: Int = 1,
    private val handshakeAttemptTimeout: Duration = responseTimeout,
    /**
     * `82 02` ("is the bus awake?") is polled up to this many times,
     * [busAwakePollTimeout] each, inside [configureBus] — unlike every other
     * command in the vendor init block, going unanswered here is an expected
     * outcome (no car / ignition off), not a transport fault. The vendor
     * itself gave up after 10 polls in a no-car reference session.
     */
    private val busAwakeAttempts: Int = 10,
    private val busAwakePollTimeout: Duration = 200.milliseconds,
    /**
     * Sink for verbose per-record diagnostic logging (raw bytes, decoded
     * records, unmatched responses). No-op unless the caller wires up the
     * app's debug-logging setting — this module has no Android dependency,
     * so it can't read that setting itself; see `AppContainer.buildTransport`.
     */
    private val log: (String) -> Unit = {},
) : ObdTransport, BusSelectable {

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

    /** The (bus, ECU) [configureBus] last successfully programmed the interface for, or null before the first call this connection. */
    private var configuredBus: ConfiguredBus? = null

    override suspend fun connect() {
        if (_state.value == ConnectionState.Ready) return
        _state.value = ConnectionState.Connecting
        commandMutex.withLock {
            try {
                readBuffer = ByteArray(0)
                pendingResponse = null
                configuredBus = null
                _incomingFrames.resetReplayCache()
                link.open()
                readerJob = scope.launch { readLoop() }
                openInterface()
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
            configuredBus = null
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

    /**
     * Puts the interface on [bus] with RX filters for one ECU, replaying the
     * vendor's fixed post-`AC 01` init block. A no-op if already configured
     * for the same (bus, requestId, secondaryId, responseId) this connection.
     *
     * Every step except the `82 02` bus-awake poll is expected to ack
     * promptly (≤31 ms in every reference session): a timeout there is
     * treated like any other handshake failure ([send]'s pattern) — fatal,
     * tearing down the link. `82 02` going unanswered is not: no car / no
     * ignition is an ordinary outcome, so it throws [OpComBusNotAwakeException]
     * and leaves the connection [ConnectionState.Ready] for the caller to retry.
     */
    override suspend fun configureBus(bus: OpComBus, requestId: Int, secondaryId: Int, responseId: Int) {
        check(_state.value == ConnectionState.Ready) {
            "configureBus() requires ConnectionState.Ready, but transport is ${_state.value}"
        }
        val target = ConfiguredBus(bus, requestId, secondaryId, responseId)
        if (configuredBus == target) return
        commandMutex.withLock {
            try {
                // A switch from a previously-configured ECU re-opens the
                // interface first (issue #34); the first configuration after
                // connect() rides on connect()'s open.
                if (configuredBus != null) openInterface()
                executeCommand(CMD_INIT_CONTINUE)
                executeCommand(CMD_CONFIGURE, byteArrayOf(0x01, 0x00, 0xF6.toByte()))
                executeCommand(CMD_CONFIGURE, byteArrayOf(0x02, 0x30, 0xEC.toByte()))
                executeCommand(CMD_CONFIGURE, byteArrayOf(0x03))
                executeCommand(CMD_SET_MODE, byteArrayOf(0x02))
                executeCommand(CMD_SET_BUS_TYPE, byteArrayOf(0x02))
                for ((code, args) in busSelectSequence(bus)) executeCommand(code, args)
                pollBusAwake()
                setRxFilter(1, -1)
                setRxFilter(2, -1)
                setRxFilter(3, secondaryId)
                setRxFilter(4, 0)
                setRxFilter(5, responseId)
                setRxFilter(6, 0)
                setRxFilter(7, 0)
                setRxFilter(8, 0)
                executeCommand(CMD_BUS_STATE, byteArrayOf(0x01))
                configuredBus = target
            } catch (e: OpComBusNotAwakeException) {
                throw e
            } catch (e: Exception) {
                _state.value = ConnectionState.Error(e)
                teardown()
                throw e
            }
        }
    }

    /** The bus-specific block sent between the constant preamble and the `82 02` bus-awake poll. */
    private fun busSelectSequence(bus: OpComBus): List<Pair<Int, ByteArray>> = when (bus) {
        OpComBus.HSCAN -> listOf(
            CMD_SELECT_BUS to byteArrayOf(0x22),
            CMD_SELECT_BUS to byteArrayOf(0x23),
            CMD_SET_MODE to byteArrayOf(0x01),
            CMD_SET_BUS_PARAMS to byteArrayOf(0x02),
        )
        OpComBus.SWCAN -> listOf(
            CMD_SELECT_BUS to byteArrayOf(0x21),
            CMD_SET_BUS_TYPE to byteArrayOf(0x03),
            CMD_SET_BUS_PARAMS to byteArrayOf(0x08, 0x04, 0x3C, 0x03, 0x03, 0x03),
        )
        OpComBus.MSCAN -> listOf(
            CMD_SELECT_BUS to byteArrayOf(0x22),
            CMD_SELECT_BUS to byteArrayOf(0x24),
            CMD_SET_BUS_PARAMS to byteArrayOf(0x08, 0x02, 0x35, 0x01, 0x01, 0x01),
        )
    }

    /**
     * `82 02` ("is the bus awake?"), polled up to [busAwakeAttempts] times.
     * Caller must hold [commandMutex]. Unlike [probeHandshake]'s retry, a
     * still-unanswered poll after every attempt is not rethrown as a plain
     * timeout — it means no car, not a broken link.
     */
    private suspend fun pollBusAwake() {
        var attempt = 0
        while (true) {
            attempt++
            try {
                executeCommand(CMD_BUS_STATE, byteArrayOf(0x02), busAwakePollTimeout)
                return
            } catch (e: OpComTimeoutException) {
                log("configureBus: 82 02 (bus awake?) attempt $attempt/$busAwakeAttempts timed out after $busAwakePollTimeout")
                if (attempt >= busAwakeAttempts) {
                    throw OpComBusNotAwakeException(
                        "bus not awake: no response to 82 02 after $busAwakeAttempts attempts",
                    )
                }
            }
        }
    }

    /** Caller must hold [commandMutex]. */
    private suspend fun setRxFilter(slot: Int, id: Int) {
        awaitResponse(CMD_SET_RX_FILTER) { link.write(OpComFrameCodec.encodeSetRxFilter(slot, id)) }
    }

    private data class ConfiguredBus(val bus: OpComBus, val requestId: Int, val secondaryId: Int, val responseId: Int)

    /** Caller must hold [commandMutex]. */
    private suspend fun executeCommand(code: Int, args: ByteArray = ByteArray(0), timeout: Duration = responseTimeout) {
        awaitResponse(code, timeout) { link.write(OpComFrameCodec.encodeCommand(code, args)) }
    }

    /**
     * The interface open the vendor runs before talking to any ECU: `AB`
     * (serial) / `AA` (firmware) / `AC 01` (init). Re-run per bus switch as
     * well as at connect, because the genuine software re-opens for every
     * module (issue #34) and this resets a stale/asleep CAN state that would
     * otherwise leave `82 02` unanswered until a manual reconnect. Caller must
     * hold [commandMutex].
     */
    private suspend fun openInterface() {
        probeHandshake()
        executeCommand(CMD_GET_FIRMWARE_VERSION)
        executeCommand(CMD_INIT, byteArrayOf(0x01))
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

        // configureBus's vendor init block (docs/formats/opcom-debug-capture.md); names are
        // best guesses, not documented semantics — only the byte-for-byte sequence is confirmed.
        const val CMD_INIT_CONTINUE = 0x74
        const val CMD_CONFIGURE = 0x73
        const val CMD_SET_MODE = 0x8E
        const val CMD_SET_BUS_TYPE = 0x84
        const val CMD_SELECT_BUS = 0x20
        const val CMD_SET_BUS_PARAMS = 0x81
        const val CMD_BUS_STATE = 0x82
        const val CMD_SET_RX_FILTER = 0x83
    }
}

/** Failure reported by or while talking to an OP-COM clone interface. */
open class OpComException(message: String) : Exception(message)

/** No response to a command within the configured timeout. */
class OpComTimeoutException(message: String) : OpComException(message)

/** [BusSelectable.configureBus]'s `82 02` poll went unanswered: no car, or ignition off — not a link fault. */
class OpComBusNotAwakeException(message: String) : OpComException(message)

/** Formatting helpers for the [OpComTransport] `log` sink. */
private fun ByteArray.toHex(): String = joinToString(" ") { "%02x".format(it) }

private fun OpComRecord.describe(): String = when (this) {
    is OpComRecord.Response -> "Response(code=0x${code.toString(16)}, payload=[${payload.toHex()}])"
    is OpComRecord.RxFrame -> "RxFrame(id=0x${frame.id.toString(16)}, data=[${frame.data.toHex()}])"
    OpComRecord.KeepAlive -> "KeepAlive"
}
