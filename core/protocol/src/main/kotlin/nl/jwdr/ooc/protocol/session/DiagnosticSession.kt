package nl.jwdr.ooc.protocol.session

import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import nl.jwdr.ooc.protocol.isotp.IsoTpAddress
import nl.jwdr.ooc.protocol.isotp.IsoTpChannel
import nl.jwdr.ooc.protocol.isotp.IsoTpConfig
import nl.jwdr.ooc.protocol.kwp2000.KwpError
import nl.jwdr.ooc.protocol.kwp2000.KwpNegativeResponseException
import nl.jwdr.ooc.protocol.kwp2000.KwpRequest
import nl.jwdr.ooc.protocol.kwp2000.StartDiagnosticSession
import nl.jwdr.ooc.protocol.kwp2000.TesterPresent
import nl.jwdr.ooc.transport.ConnectionState
import nl.jwdr.ooc.transport.ObdTransport

/** Lifecycle of one [DiagnosticSession]. */
enum class SessionState {
    /** Created; requests are accepted but no keep-alive runs yet. */
    Idle,

    /** [DiagnosticSession.open] succeeded; keep-alive is running. */
    Active,

    /** The transport dropped or the keep-alive failed; the session is dead. */
    Lost,

    /** [DiagnosticSession.close] was called. */
    Closed,
}

/**
 * The diagnostic conversation with one ECU: serializes requests (one in
 * flight), applies the timeout/retry policy, honors responsePending, and
 * keeps an opened session alive with testerPresent.
 *
 * Construct one per ECU over a Ready transport. [execute] works from [SessionState.Idle]
 * for one-shot requests; [open] switches the ECU's diagnostic mode and starts
 * the keep-alive. Failures surface as [SessionException]s; malformed payloads
 * still throw [nl.jwdr.ooc.protocol.kwp2000.KwpDecodeException].
 *
 * @param scope scope for the frame collector, keep-alive, and transport
 *   watcher; cancel it when the connection ends.
 */
class DiagnosticSession(
    private val transport: ObdTransport,
    address: IsoTpAddress,
    isoTpConfig: IsoTpConfig = IsoTpConfig(),
    private val config: SessionConfig = SessionConfig(),
    private val scope: CoroutineScope,
) {

    private val channel = IsoTpChannel(transport, address, isoTpConfig, scope)
    private val requestLock = Mutex()
    private val idleReset = Channel<Unit>(Channel.CONFLATED)
    private var keepAlive: Job? = null

    private val _state = MutableStateFlow(SessionState.Idle)
    val state: StateFlow<SessionState> = _state

    init {
        // Sampled synchronously: the watcher coroutine may first run after a
        // disconnect, and would then mistake it for a not-yet-connected state.
        val readyAtConstruction = transport.state.value is ConnectionState.Ready
        scope.launch {
            val states =
                if (readyAtConstruction) transport.state
                else transport.state.dropWhile { it !is ConnectionState.Ready }
            states.first { it !is ConnectionState.Ready }
            markLost()
        }
    }

    /**
     * Switches the ECU into [diagnosticMode] and starts the keep-alive.
     *
     * @throws SessionException
     */
    suspend fun open(diagnosticMode: Int): StartDiagnosticSession.Response {
        val response = execute(StartDiagnosticSession(diagnosticMode))
        _state.value = SessionState.Active
        startKeepAlive()
        return response
    }

    /**
     * Sends [request] and returns its decoded response. Callers are
     * serialized: exactly one request is on the bus at a time.
     *
     * @throws SessionException
     */
    suspend fun <R> execute(request: KwpRequest<R>): R {
        ensureUsable()
        requestLock.withLock {
            ensureUsable()
            try {
                return performWithRetries(request)
            } finally {
                idleReset.trySend(Unit)
            }
        }
    }

    /** Stops the keep-alive and rejects further requests. */
    fun close() {
        keepAlive?.cancel()
        _state.value = SessionState.Closed
    }

    private fun ensureUsable() {
        when (_state.value) {
            SessionState.Closed -> throw SessionException.SessionClosed()
            SessionState.Lost -> throw SessionException.TransportLost()
            SessionState.Idle, SessionState.Active -> Unit
        }
    }

    private suspend fun <R> performWithRetries(request: KwpRequest<R>): R {
        val payload = request.encode()
        val serviceId = payload[0].toInt() and 0xFF
        var retries = 0
        while (true) {
            try {
                return request.decodeResponse(exchangeOnce(request, payload))
            } catch (e: AttemptLost) {
                if (retries++ >= config.maxRetries) throw SessionException.ResponseTimeout(serviceId)
            } catch (e: KwpNegativeResponseException) {
                if (e.error != KwpError.BusyRepeatRequest || retries++ >= config.maxRetries) {
                    throw SessionException.NegativeResponse(e.serviceId, e.error)
                }
            }
        }
    }

    private suspend fun exchangeOnce(request: KwpRequest<*>, requestPayload: ByteArray): ByteArray {
        val serviceId = requestPayload[0].toInt() and 0xFF
        try {
            channel.send(requestPayload)
        } catch (e: IllegalStateException) {
            // The transport refuses to send when it is no longer Ready.
            throw SessionException.TransportLost()
        }
        var deadline = config.responseTimeout
        while (true) {
            val response = receiveOrNull(deadline) ?: throw AttemptLost()
            // Stale payloads of an earlier exchange (still buffered, or a
            // late arrival) are not our reply; keep waiting.
            if (!response.isReplyTo(serviceId)) continue
            if (!request.isExpectedReply(response)) continue
            if (response.isResponsePending()) {
                deadline = config.pendingTimeout
                continue
            }
            return response
        }
    }

    private fun ByteArray.isReplyTo(serviceId: Int): Boolean = when {
        isEmpty() -> false
        (this[0].toInt() and 0xFF) == serviceId + 0x40 -> true
        else -> size >= 2 &&
            (this[0].toInt() and 0xFF) == 0x7F &&
            (this[1].toInt() and 0xFF) == serviceId
    }

    /**
     * One payload from the channel, or null after [timeout]. Fails fast with
     * [SessionException.TransportLost] instead of waiting out the timeout
     * when the transport drops mid-wait.
     */
    private suspend fun receiveOrNull(timeout: Duration): ByteArray? =
        withTimeoutOrNull(timeout) {
            coroutineScope {
                val watcher = launch {
                    transport.state.first { it !is ConnectionState.Ready }
                    throw SessionException.TransportLost()
                }
                try {
                    channel.receive()
                } finally {
                    watcher.cancel()
                }
            }
        }

    private fun ByteArray.isResponsePending(): Boolean =
        size >= 3 &&
            (this[0].toInt() and 0xFF) == 0x7F &&
            (this[2].toInt() and 0xFF) == KwpError.ResponsePending.code

    private fun startKeepAlive() {
        keepAlive?.cancel()
        keepAlive = scope.launch {
            while (true) {
                // A completed request resets the idle timer via idleReset.
                if (withTimeoutOrNull(config.testerPresentInterval) { idleReset.receive() } != null) continue
                try {
                    execute(TesterPresent())
                } catch (e: SessionException) {
                    markLost()
                    return@launch
                }
            }
        }
    }

    private fun markLost() {
        if (_state.value == SessionState.Closed) return
        _state.value = SessionState.Lost
        keepAlive?.cancel()
    }

    /** A single request attempt got no response in time; retry policy applies. */
    private class AttemptLost : Exception()
}
