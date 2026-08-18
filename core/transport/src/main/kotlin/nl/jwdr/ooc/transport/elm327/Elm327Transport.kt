package nl.jwdr.ooc.transport.elm327

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import nl.jwdr.ooc.transport.CanFrame
import nl.jwdr.ooc.transport.ConnectionState
import nl.jwdr.ooc.transport.ObdTransport

/**
 * [ObdTransport] over an ELM327-style adapter driven as a raw 11-bit CAN
 * frame pipe: ATCAF0 (no adapter-side ISO-TP) and ATCFC0 (no adapter-side
 * flow control) so the protocol layer's own ISO-TP stack runs unchanged and
 * controls STmin itself — important because cheap clones drop back-to-back
 * consecutive frames their slow UART can't relay at 500 kbps.
 *
 * The ELM327 is half-duplex: it only listens to the bus between a command
 * and the next `>` prompt, so unsolicited/broadcast traffic is invisible.
 * Request→response diagnostics (the only current use) are unaffected.
 */
class Elm327Transport(
    private val link: Elm327Link,
    private val commandTimeout: Duration = 5.seconds,
) : ObdTransport {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state

    private val _incomingFrames = MutableSharedFlow<CanFrame>(
        replay = REPLAY_BUFFER,
        extraBufferCapacity = REPLAY_BUFFER,
    )
    override val incomingFrames: Flow<CanFrame> = _incomingFrames

    private val commandMutex = Mutex()
    private var pendingInput = StringBuilder()
    private var currentHeader: Int? = null

    override suspend fun connect() {
        if (_state.value == ConnectionState.Ready) return
        _state.value = ConnectionState.Connecting
        // One mutex hold for the whole init: no send() or concurrent connect()
        // may interleave, and leftovers of a dead session (unterminated input,
        // cached header, replayed frames) must not leak into this one.
        commandMutex.withLock {
            try {
                pendingInput = StringBuilder()
                currentHeader = null
                _incomingFrames.resetReplayCache()
                link.open()
                for (command in INIT_SEQUENCE) {
                    executeCommand(command)
                }
                _state.value = ConnectionState.Ready
            } catch (e: Exception) {
                _state.value = ConnectionState.Error(e)
                runCatching { link.close() }
                throw e
            }
        }
    }

    override suspend fun disconnect() {
        // Close outside the mutex: a send() blocked in a socket read only
        // unblocks when the link closes and holds the mutex until then.
        runCatching { link.close() }
        _state.value = ConnectionState.Disconnected
        commandMutex.withLock {
            pendingInput = StringBuilder()
            currentHeader = null
            _incomingFrames.resetReplayCache()
        }
    }

    override suspend fun send(frame: CanFrame) {
        check(_state.value == ConnectionState.Ready) {
            "send() requires ConnectionState.Ready, but transport is ${_state.value}"
        }
        commandMutex.withLock {
            try {
                if (currentHeader != frame.id) {
                    executeCommand(Elm327FrameCodec.setHeaderCommand(frame.id))
                    currentHeader = frame.id
                }
                executeCommand(Elm327FrameCodec.formatSendPayload(frame))
            } catch (e: Elm327TimeoutException) {
                // Prompt synchronization is unrecoverable after a timeout: a
                // late reply would be misattributed to the next command. Kill
                // the connection; a zombie socket read also only unblocks on
                // close.
                _state.value = ConnectionState.Error(e)
                runCatching { link.close() }
                throw e
            }
        }
    }

    /**
     * Writes [command] and consumes adapter output up to the `>` prompt,
     * emitting every line that parses as a CAN frame. Caller must hold
     * [commandMutex] — the ELM327 is strictly one command at a time.
     */
    private suspend fun executeCommand(command: String) {
        link.write(command + "\r")
        val response = readUntilPrompt()
        for (line in response.split('\r')) {
            val trimmed = line.trim()
            if (trimmed == "?") throw Elm327Exception("adapter rejected \"$command\"")
            if (trimmed in ADAPTER_ERRORS) throw Elm327Exception("adapter reported $trimmed for \"$command\"")
            Elm327FrameCodec.parseFrameLine(trimmed)?.let { _incomingFrames.tryEmit(it) }
        }
    }

    private suspend fun readUntilPrompt(): String {
        while (true) {
            val promptIndex = pendingInput.indexOf(PROMPT)
            if (promptIndex >= 0) {
                val response = pendingInput.substring(0, promptIndex)
                pendingInput.delete(0, promptIndex + 1)
                return response
            }
            val chunk = try {
                withTimeout(commandTimeout) { link.read() }
            } catch (e: TimeoutCancellationException) {
                throw Elm327TimeoutException("timed out after $commandTimeout waiting for the adapter prompt")
            }
            pendingInput.append(chunk)
        }
    }

    private companion object {
        const val PROMPT = ">"
        val ADAPTER_ERRORS = setOf("CAN ERROR", "BUFFER FULL", "BUS ERROR", "DATA ERROR", "FB ERROR", "RX ERROR")
        const val REPLAY_BUFFER = 64

        /**
         * Reset; echo/linefeeds/spaces off; headers on; CAN auto-formatting
         * and adapter flow control off; allow long monitored messages;
         * adaptive response timing explicitly on (the power-on default, but
         * clones vary); ISO 15765-4 11-bit 500 kbps; battery voltage as a
         * liveness check.
         */
        val INIT_SEQUENCE = listOf(
            "ATZ", "ATE0", "ATL0", "ATS0", "ATH1", "ATCAF0", "ATCFC0", "ATAL", "ATAT1", "ATSP6", "ATRV",
        )
    }
}

/** Failure reported by or while talking to an ELM327 adapter. */
open class Elm327Exception(message: String) : Exception(message)

/** The adapter never returned its prompt; the command framing is desynced. */
class Elm327TimeoutException(message: String) : Elm327Exception(message)
