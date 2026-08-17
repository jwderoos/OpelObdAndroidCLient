package nl.jwdr.ooc.transport

import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Scriptable request→response [ObdTransport] used as a fake ECU in unit tests.
 *
 * Rules map an outgoing request frame to zero or more scripted response frames:
 *
 * ```
 * val transport = FakeEcuTransport(backgroundScope)
 * transport.onFrame(request).respondWith(first, consecutive1, consecutive2)
 * transport.onId(0x7E0).respondWith(listOf(pending), delay = 50.milliseconds)
 * transport.onMatch { it.data[1] == 0x3E.toByte() }.respondNothing()
 * ```
 *
 * Matching rules are tried in registration order; the first match wins. A
 * request that matches no rule is silently dropped, like an unanswered frame
 * on a real bus — use it (or an explicit [ResponseBuilder.respondNothing]
 * rule) to exercise timeout paths.
 *
 * Buffering contract: [incomingFrames] is a shared flow with a replay buffer
 * of [REPLAY_BUFFER] frames, so responses emitted before a collector attaches
 * are not lost — tests may `send(...)` first and collect afterwards. The
 * replay cache is cleared on [disconnect], so a new session never observes
 * frames from a previous one.
 *
 * @param scope Scope in which delayed responses are emitted (pass
 *   `backgroundScope` inside `runTest` so scripted delays run on virtual
 *   time). Zero-delay responses are emitted synchronously inside [send].
 */
class FakeEcuTransport(
    private val scope: CoroutineScope,
) : ObdTransport {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state

    private val _incomingFrames = MutableSharedFlow<CanFrame>(
        replay = REPLAY_BUFFER,
        extraBufferCapacity = REPLAY_BUFFER,
    )
    override val incomingFrames: Flow<CanFrame> = _incomingFrames

    private val _sentFrames = mutableListOf<CanFrame>()

    /** Every frame passed to [send] while Ready, in transmission order. */
    val sentFrames: List<CanFrame> get() = _sentFrames.toList()

    private val rules = mutableListOf<Rule>()
    private val pendingResponses = mutableListOf<Job>()

    /** Scripts a response for requests exactly equal to [request]. */
    fun onFrame(request: CanFrame): ResponseBuilder = onMatch { it == request }

    /** Scripts a response for any request with CAN identifier [id]. */
    fun onId(id: Int): ResponseBuilder = onMatch { it.id == id }

    /** Scripts a response for any request satisfying [predicate]. */
    fun onMatch(predicate: (CanFrame) -> Boolean): ResponseBuilder = ResponseBuilder(predicate)

    inner class ResponseBuilder internal constructor(
        private val predicate: (CanFrame) -> Boolean,
    ) {
        /** Responds immediately with [frames], in order. */
        fun respondWith(vararg frames: CanFrame) = respondWith(frames.toList(), Duration.ZERO)

        /** Responds with [frames] in order, after an optional artificial [delay]. */
        fun respondWith(frames: List<CanFrame>, delay: Duration = Duration.ZERO) {
            rules += Rule(predicate, frames.map { delay to it })
        }

        /** Responds with each frame after its own delay, measured from the request. */
        fun respondWith(vararg timedFrames: Pair<Duration, CanFrame>) {
            rules += Rule(predicate, timedFrames.toList())
        }

        /** Explicitly consumes matching requests without responding (timeout tests). */
        fun respondNothing() {
            rules += Rule(predicate, emptyList())
        }
    }

    override suspend fun connect() {
        if (_state.value == ConnectionState.Ready) return
        _state.value = ConnectionState.Connecting
        _state.value = ConnectionState.Ready
    }

    override suspend fun disconnect() {
        pendingResponses.forEach(Job::cancel)
        pendingResponses.clear()
        _incomingFrames.resetReplayCache()
        _state.value = ConnectionState.Disconnected
    }

    override suspend fun send(frame: CanFrame) {
        check(_state.value == ConnectionState.Ready) {
            "send() requires ConnectionState.Ready, but transport is ${_state.value}"
        }
        _sentFrames += frame

        val rule = rules.firstOrNull { it.predicate(frame) } ?: return
        for ((delay, response) in rule.responses) {
            if (delay == Duration.ZERO) {
                _incomingFrames.tryEmit(response)
            } else {
                pendingResponses += scope.launch {
                    delay(delay)
                    _incomingFrames.tryEmit(response)
                }
            }
        }
    }

    private class Rule(
        val predicate: (CanFrame) -> Boolean,
        val responses: List<Pair<Duration, CanFrame>>,
    )

    private companion object {
        const val REPLAY_BUFFER = 64
    }
}
