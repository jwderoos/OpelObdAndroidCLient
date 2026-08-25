package nl.jwdr.ooc.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * [ObdTransport] decorator that records every frame crossing it to a
 * [CanLogSink], plus caller-supplied [note]s, so a real-vehicle session can be
 * replayed later with [ReplayTransport].
 *
 * [openSink] is consulted on every [connect]; returning `null` disables
 * recording for that session (the app reads its debug toggle there). The sink
 * is closed on [disconnect]. Timestamps are milliseconds since [connect].
 *
 * Received frames are recorded by a private collector in [scope], not by
 * decorating [incomingFrames]: the protocol stack has several independent
 * collectors, and decorating the flow would record each frame once per
 * collector.
 */
class RecordingTransport(
    /** The wrapped transport; exposed so callers can inspect its kind (e.g. "is this simulated?"). */
    val delegate: ObdTransport,
    private val openSink: () -> CanLogSink?,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) : ObdTransport {

    override val state: StateFlow<ConnectionState> = delegate.state
    override val incomingFrames: Flow<CanFrame> = delegate.incomingFrames

    private var sink: CanLogSink? = null
    private var sessionStart = 0L
    private var rxJob: Job? = null

    override suspend fun connect() {
        sessionStart = clock()
        sink = openSink()
        if (sink != null) {
            rxJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                delegate.incomingFrames.collect { record(Direction.RX, it) }
            }
        }
        try {
            delegate.connect()
        } catch (e: Throwable) {
            closeSink()
            throw e
        }
    }

    override suspend fun disconnect() {
        try {
            delegate.disconnect()
        } finally {
            closeSink()
        }
    }

    override suspend fun send(frame: CanFrame) {
        delegate.send(frame)
        record(Direction.TX, frame)
    }

    /** Annotates the recording with what the app is doing (e.g. `read DTC ecu=Engine`). */
    fun note(text: String) {
        sink?.event(elapsed(), text)
    }

    private fun record(direction: Direction, frame: CanFrame) {
        sink?.frame(LoggedFrame(elapsed(), direction, frame))
    }

    private fun elapsed(): Long = clock() - sessionStart

    private fun closeSink() {
        rxJob?.cancel()
        rxJob = null
        sink?.close()
        sink = null
    }
}
