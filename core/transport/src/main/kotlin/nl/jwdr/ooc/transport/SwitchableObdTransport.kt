package nl.jwdr.ooc.transport

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest

/**
 * An [ObdTransport] whose backing implementation can be swapped at runtime
 * (adapter selection in settings), so consumers hold one stable transport for
 * the app's lifetime. Swapping is only allowed while the active transport is
 * not connected.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SwitchableObdTransport(
    initial: ObdTransport,
) : ObdTransport {

    private val _active = MutableStateFlow(initial)

    /** The transport currently backing this one. */
    val active: StateFlow<ObdTransport> = _active

    override val state: StateFlow<ConnectionState> = object : StateFlow<ConnectionState> {
        override val value: ConnectionState get() = _active.value.state.value
        override val replayCache: List<ConnectionState> get() = listOf(value)
        override suspend fun collect(collector: FlowCollector<ConnectionState>): Nothing {
            _active.flatMapLatest { it.state }.distinctUntilChanged().collect(collector)
            error("state flows never complete")
        }
    }

    override val incomingFrames: Flow<CanFrame> = _active.flatMapLatest { it.incomingFrames }

    /** Makes [transport] the active one. Only allowed while disconnected. */
    fun switchTo(transport: ObdTransport) {
        val current = _active.value.state.value
        check(current == ConnectionState.Disconnected || current is ConnectionState.Error) {
            "cannot switch transports while $current — disconnect first"
        }
        _active.value = transport
    }

    override suspend fun connect() = _active.value.connect()

    override suspend fun disconnect() = _active.value.disconnect()

    override suspend fun send(frame: CanFrame) = _active.value.send(frame)
}
