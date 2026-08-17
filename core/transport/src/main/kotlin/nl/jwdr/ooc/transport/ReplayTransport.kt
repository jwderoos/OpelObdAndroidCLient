package nl.jwdr.ooc.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Playback pacing for [ReplayTransport]. */
enum class ReplayMode {
    /** Emits each rx frame after the recorded delay since the previous frame. */
    OriginalTiming,

    /** Emits rx frames as fast as possible; tx gating still applies. */
    FastForward,
}

/**
 * [ObdTransport] that replays a recorded [CanLog] session.
 *
 * Playback walks the log in order. `rx` entries are emitted on
 * [incomingFrames] (paced per [mode]). `tx` entries gate playback: it pauses
 * until the client [send]s a frame equal to the recorded one, keeping the
 * protocol stack honest against the recorded session. A mismatched or
 * script-exhausted send throws and, for a mismatch, moves [state] to
 * [ConnectionState.Error].
 *
 * Buffering contract matches [FakeEcuTransport]: [incomingFrames] replays up
 * to [REPLAY_BUFFER] frames to late collectors, and the cache is cleared on
 * [disconnect].
 *
 * @param scope Scope playback runs in (pass `backgroundScope` inside
 *   `runTest` so [ReplayMode.OriginalTiming] delays run on virtual time).
 */
class ReplayTransport(
    private val log: CanLog,
    private val mode: ReplayMode,
    private val scope: CoroutineScope,
) : ObdTransport {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state

    private val _incomingFrames = MutableSharedFlow<CanFrame>(
        replay = REPLAY_BUFFER,
        extraBufferCapacity = REPLAY_BUFFER,
    )
    override val incomingFrames: Flow<CanFrame> = _incomingFrames

    private sealed interface Phase {
        /** Playback is emitting rx frames (or has not reached a tx entry yet). */
        data object Streaming : Phase

        /** Playback is paused until the client sends [expected]. */
        data class AwaitingTx(val expected: CanFrame) : Phase {
            val released = CompletableDeferred<Unit>()
        }

        /** All log entries have been replayed. */
        data object Finished : Phase
    }

    private val phase = MutableStateFlow<Phase>(Phase.Streaming)
    private var playback: Job? = null

    override suspend fun connect() {
        if (_state.value == ConnectionState.Ready) return
        _state.value = ConnectionState.Connecting
        phase.value = Phase.Streaming
        playback = scope.launch { play() }
        _state.value = ConnectionState.Ready
    }

    override suspend fun disconnect() {
        playback?.cancel()
        playback = null
        _incomingFrames.resetReplayCache()
        _state.value = ConnectionState.Disconnected
    }

    override suspend fun send(frame: CanFrame) {
        check(_state.value == ConnectionState.Ready) {
            "send() requires ConnectionState.Ready, but transport is ${_state.value}"
        }
        when (val gate = phase.first { it !is Phase.Streaming }) {
            is Phase.AwaitingTx -> {
                if (frame != gate.expected) {
                    val e = IllegalStateException("replay expected send of ${gate.expected}, got $frame")
                    _state.value = ConnectionState.Error(e)
                    throw e
                }
                gate.released.complete(Unit)
            }
            Phase.Finished -> throw IllegalStateException("send of $frame after the replay script ended")
            Phase.Streaming -> error("unreachable")
        }
    }

    private suspend fun play() {
        var lastTimestampMs = 0L
        for (entry in log.frames) {
            when (entry.direction) {
                Direction.RX -> {
                    if (mode == ReplayMode.OriginalTiming) {
                        delay(entry.timestampMs - lastTimestampMs)
                    }
                    _incomingFrames.emit(entry.frame)
                }
                Direction.TX -> {
                    val gate = Phase.AwaitingTx(entry.frame)
                    phase.value = gate
                    gate.released.await()
                    phase.value = Phase.Streaming
                }
            }
            lastTimestampMs = entry.timestampMs
        }
        phase.value = Phase.Finished
    }

    private companion object {
        const val REPLAY_BUFFER = 64
    }
}
