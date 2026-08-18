package nl.jwdr.ooc.protocol.session

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.jwdr.ooc.protocol.kwp2000.KwpError
import nl.jwdr.ooc.protocol.kwp2000.StartDiagnosticSession
import nl.jwdr.ooc.transport.CanFrame
import nl.jwdr.ooc.transport.FakeEcuTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticSessionTest {

    private val address = nl.jwdr.ooc.protocol.isotp.IsoTpAddress(requestId = 0x241, responseId = 0x641)
    private val pad = 0xAA.toByte()

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun request(vararg values: Int) = CanFrame(0x241, padded(bytes(*values)))

    private fun response(vararg values: Int) = CanFrame(0x641, padded(bytes(*values)))

    private fun padded(data: ByteArray) =
        if (data.size < 8) data + ByteArray(8 - data.size) { pad } else data

    private fun CanFrame.serviceId() = data[1].toInt() and 0xFF

    private fun session(
        transport: FakeEcuTransport,
        scope: CoroutineScope,
        config: SessionConfig = SessionConfig(),
    ) = DiagnosticSession(transport, address, config = config, scope = scope)

    // --- execute ---

    @Test
    fun `execute returns the decoded response`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(request(0x02, 0x10, 0x81)).respondWith(response(0x02, 0x50, 0x81))
        transport.connect()
        val session = session(transport, backgroundScope)

        val result = session.execute(StartDiagnosticSession(0x81))

        assertEquals(StartDiagnosticSession.Response(0x81), result)
    }

    @Test
    fun `negative response surfaces as a typed session failure`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(request(0x02, 0x10, 0x81)).respondWith(response(0x03, 0x7F, 0x10, 0x31))
        transport.connect()
        val session = session(transport, backgroundScope)

        val e = runCatching { session.execute(StartDiagnosticSession(0x81)) }.exceptionOrNull()

        assertTrue("expected NegativeResponse, got $e", e is SessionException.NegativeResponse)
        assertEquals(KwpError.RequestOutOfRange, (e as SessionException.NegativeResponse).error)
        assertEquals(0x10, e.serviceId)
    }

    @Test
    fun `a stale response for another service is not taken as the reply`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        // A leftover readDTCByStatus reply (0x58) precedes the real reply,
        // as when a previous exchange's frame is still buffered.
        transport.onFrame(request(0x02, 0x10, 0x81)).respondWith(
            response(0x05, 0x58, 0x01, 0x00, 0x16, 0x00),
            response(0x02, 0x50, 0x81),
        )
        transport.connect()
        val session = session(transport, backgroundScope)

        val result = session.execute(StartDiagnosticSession(0x81))

        assertEquals(StartDiagnosticSession.Response(0x81), result)
    }

    @Test
    fun `a stale negative response for another service is not taken as the reply`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        // 7F 18 11: a leftover rejection of readDTCByStatus, not of us.
        transport.onFrame(request(0x02, 0x10, 0x81)).respondWith(
            response(0x03, 0x7F, 0x18, 0x11),
            response(0x02, 0x50, 0x81),
        )
        transport.connect()
        val session = session(transport, backgroundScope)

        val result = session.execute(StartDiagnosticSession(0x81))

        assertEquals(StartDiagnosticSession.Response(0x81), result)
    }

    // --- timeout and retry ---

    @Test
    fun `an unanswered request is retried and can succeed on a later attempt`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        var attempts = 0
        transport.onMatch { it.id == 0x241 && it.serviceId() == 0x10 && ++attempts == 2 }
            .respondWith(response(0x02, 0x50, 0x81))
        transport.connect()
        val session = session(transport, backgroundScope)

        val result = session.execute(StartDiagnosticSession(0x81))

        assertEquals(StartDiagnosticSession.Response(0x81), result)
        assertEquals(2, transport.sentFrames.size)
    }

    @Test
    fun `throws ResponseTimeout when retries are exhausted`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.connect()
        val session = session(transport, backgroundScope, SessionConfig(maxRetries = 2))

        val e = runCatching { session.execute(StartDiagnosticSession(0x81)) }.exceptionOrNull()

        assertTrue("expected ResponseTimeout, got $e", e is SessionException.ResponseTimeout)
        // Original attempt plus two retries.
        assertEquals(3, transport.sentFrames.size)
    }

    @Test
    fun `busyRepeatRequest is retried`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        var calls = 0
        transport.onMatch { it.id == 0x241 && it.serviceId() == 0x10 && ++calls == 1 }
            .respondWith(response(0x03, 0x7F, 0x10, 0x21))
        transport.onMatch { it.id == 0x241 && it.serviceId() == 0x10 }
            .respondWith(response(0x02, 0x50, 0x81))
        transport.connect()
        val session = session(transport, backgroundScope)

        val result = session.execute(StartDiagnosticSession(0x81))

        assertEquals(StartDiagnosticSession.Response(0x81), result)
        assertEquals(2, transport.sentFrames.size)
    }

    // --- responsePending (0x78) ---

    @Test
    fun `responsePending defers the response deadline`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(request(0x02, 0x10, 0x81)).respondWith(
            0.milliseconds to response(0x03, 0x7F, 0x10, 0x78),
            // Past responseTimeout, within pendingTimeout.
            3.seconds to response(0x02, 0x50, 0x81),
        )
        transport.connect()
        val session = session(transport, backgroundScope, SessionConfig(maxRetries = 0))

        val result = session.execute(StartDiagnosticSession(0x81))

        assertEquals(StartDiagnosticSession.Response(0x81), result)
        assertEquals(1, transport.sentFrames.size)
    }

    @Test
    fun `responsePending followed by silence still times out`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(request(0x02, 0x10, 0x81))
            .respondWith(response(0x03, 0x7F, 0x10, 0x78))
        transport.connect()
        val session = session(transport, backgroundScope, SessionConfig(maxRetries = 0))

        val e = runCatching { session.execute(StartDiagnosticSession(0x81)) }.exceptionOrNull()

        assertTrue("expected ResponseTimeout, got $e", e is SessionException.ResponseTimeout)
    }

    // --- open and keep-alive ---

    @Test
    fun `open starts the diagnostic session and activates the session`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(request(0x02, 0x10, 0x81)).respondWith(response(0x02, 0x50, 0x81))
        transport.connect()
        val session = session(transport, backgroundScope)

        val result = session.open(0x81)

        assertEquals(StartDiagnosticSession.Response(0x81), result)
        assertEquals(SessionState.Active, session.state.value)
        assertEquals(listOf(request(0x02, 0x10, 0x81)), transport.sentFrames)
    }

    @Test
    fun `keep-alive sends testerPresent after the idle interval`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(request(0x02, 0x10, 0x81)).respondWith(response(0x02, 0x50, 0x81))
        transport.onMatch { it.id == 0x241 && it.serviceId() == 0x3E }
            .respondWith(response(0x01, 0x7E))
        transport.connect()
        val session = session(transport, backgroundScope, SessionConfig(testerPresentInterval = 2.seconds))
        session.open(0x81)

        advanceTimeBy(2.seconds + 1.milliseconds)

        assertEquals(1, transport.sentFrames.count { it.serviceId() == 0x3E })
    }

    @Test
    fun `keep-alive timer resets on request traffic`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(request(0x02, 0x10, 0x81)).respondWith(response(0x02, 0x50, 0x81))
        transport.onMatch { it.id == 0x241 && it.serviceId() == 0x3E }
            .respondWith(response(0x01, 0x7E))
        transport.connect()
        val session = session(transport, backgroundScope, SessionConfig(testerPresentInterval = 2.seconds))
        session.open(0x81)

        advanceTimeBy(1500.milliseconds)
        session.execute(StartDiagnosticSession(0x81))
        advanceTimeBy(1500.milliseconds)

        // 3s after open, but only 1.5s since the last exchange.
        assertEquals(0, transport.sentFrames.count { it.serviceId() == 0x3E })

        advanceTimeBy(600.milliseconds)
        assertEquals(1, transport.sentFrames.count { it.serviceId() == 0x3E })
    }

    @Test
    fun `a failed keep-alive marks the session lost`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(request(0x02, 0x10, 0x81)).respondWith(response(0x02, 0x50, 0x81))
        // No rule for testerPresent: the keep-alive times out.
        transport.connect()
        val session = session(
            transport,
            backgroundScope,
            SessionConfig(maxRetries = 0, testerPresentInterval = 2.seconds),
        )
        session.open(0x81)

        advanceTimeBy(2.seconds + SessionConfig().responseTimeout + 1.milliseconds)

        assertEquals(SessionState.Lost, session.state.value)
    }

    // --- one request in flight ---

    @Test
    fun `requests are serialized one in flight`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(request(0x02, 0x10, 0x81))
            .respondWith(listOf(response(0x02, 0x50, 0x81)), delay = 100.milliseconds)
        transport.onFrame(request(0x02, 0x10, 0x76)).respondWith(response(0x02, 0x50, 0x76))
        transport.connect()
        val session = session(transport, backgroundScope)

        launch { session.execute(StartDiagnosticSession(0x81)) }
        launch { session.execute(StartDiagnosticSession(0x76)) }
        runCurrent()

        advanceTimeBy(50.milliseconds)
        assertEquals(listOf(request(0x02, 0x10, 0x81)), transport.sentFrames)

        advanceUntilIdle()
        assertEquals(
            listOf(request(0x02, 0x10, 0x81), request(0x02, 0x10, 0x76)),
            transport.sentFrames,
        )
    }

    // --- transport loss and teardown ---

    @Test
    fun `transport loss fails an in-flight request`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.connect()
        val session = session(transport, backgroundScope)

        val result = async { runCatching { session.execute(StartDiagnosticSession(0x81)) } }
        runCurrent()
        transport.disconnect()
        runCurrent()

        val e = result.await().exceptionOrNull()
        assertTrue("expected TransportLost, got $e", e is SessionException.TransportLost)
    }

    @Test
    fun `session goes Lost when the transport drops and rejects further requests`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.connect()
        val session = session(transport, backgroundScope)

        transport.disconnect()
        runCurrent()

        assertEquals(SessionState.Lost, session.state.value)
        val e = runCatching { session.execute(StartDiagnosticSession(0x81)) }.exceptionOrNull()
        assertTrue("expected TransportLost, got $e", e is SessionException.TransportLost)
    }

    @Test
    fun `execute after close throws SessionClosed`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.connect()
        val session = session(transport, backgroundScope)

        session.close()

        assertEquals(SessionState.Closed, session.state.value)
        val e = runCatching { session.execute(StartDiagnosticSession(0x81)) }.exceptionOrNull()
        assertTrue("expected SessionClosed, got $e", e is SessionException.SessionClosed)
    }

    @Test
    fun `close stops the keep-alive`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(request(0x02, 0x10, 0x81)).respondWith(response(0x02, 0x50, 0x81))
        transport.onMatch { it.id == 0x241 && it.serviceId() == 0x3E }
            .respondWith(response(0x01, 0x7E))
        transport.connect()
        val session = session(transport, backgroundScope, SessionConfig(testerPresentInterval = 2.seconds))
        session.open(0x81)

        session.close()
        advanceTimeBy(10.seconds)

        assertEquals(0, transport.sentFrames.count { it.serviceId() == 0x3E })
    }
}
