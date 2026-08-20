package nl.jwdr.ooc.transport.opcom

import nl.jwdr.ooc.transport.CanFrame
import nl.jwdr.ooc.transport.ConnectionState
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpComTransportTest {

    @Test
    fun `connect runs the AB, AA, AC init handshake and becomes Ready`() = runTest {
        val link = FakeOpComLink()
        val transport = OpComTransport(link, backgroundScope)

        transport.connect()

        assertEquals(listOf(0xAB, 0xAA, 0xAC), link.writtenCommands)
        assertEquals(ConnectionState.Ready, transport.state.value)
        assertTrue(link.opened)
    }

    @Test
    fun `a silent interface fails connect with a timeout error and closes the link`() = runTest {
        val link = FakeOpComLink()
        link.onSilence(0xAB)
        val transport = OpComTransport(link, backgroundScope)

        val e = runCatching { transport.connect() }.exceptionOrNull()

        assertTrue("expected OpComTimeoutException, got $e", e is OpComTimeoutException)
        assertTrue(transport.state.value is ConnectionState.Error)
        assertTrue(link.closed)
    }

    @Test
    fun `send before connect throws`() = runTest {
        val transport = OpComTransport(FakeOpComLink(), backgroundScope)

        val e = runCatching { transport.send(CanFrame(0x7E0, byteArrayOf(0x02, 0x01, 0x0C))) }
            .exceptionOrNull()

        assertTrue("expected IllegalStateException, got $e", e is IllegalStateException)
    }

    @Test
    fun `send writes a 90 record and completes only after the D0 ack`() = runTest {
        val link = FakeOpComLink()
        val transport = OpComTransport(link, backgroundScope)
        transport.connect()

        transport.send(CanFrame(0x7E0, byteArrayOf(0x02, 0x01, 0x0C)))

        assertEquals(listOf(0xAB, 0xAA, 0xAC, 0x90), link.writtenCommands)
    }

    @Test
    fun `received 91 frames are emitted on incomingFrames`() = runTest {
        val link = FakeOpComLink()
        val transport = OpComTransport(link, backgroundScope)
        val received = mutableListOf<CanFrame>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            transport.incomingFrames.toList(received)
        }
        transport.connect()

        val rxPayload = byteArrayOf(0x91.toByte(), 0x00, 0x00, 0x07, 0xE8.toByte(), 2, 0x41, 0x00, 0, 0, 0, 0, 0, 0)
        link.pushUnsolicited(OpComFrameCodec.encodeRecord(rxPayload))
        yield() // let the background reader coroutine consume the pushed bytes

        assertEquals(listOf(CanFrame(0x7E8, byteArrayOf(0x41, 0x00))), received)
        job.cancel()
    }

    @Test
    fun `keep-alive 7F records are ignored`() = runTest {
        val link = FakeOpComLink()
        val transport = OpComTransport(link, backgroundScope)
        val received = mutableListOf<CanFrame>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            transport.incomingFrames.toList(received)
        }
        transport.connect()

        link.pushUnsolicited(OpComFrameCodec.encodeRecord(byteArrayOf(0x7F)))
        link.pushUnsolicited(OpComFrameCodec.encodeRecord(byteArrayOf(0x91.toByte(), 0, 0, 0x07, 0xE8.toByte(), 1, 0x41, 0, 0, 0, 0, 0, 0)))
        yield()

        assertEquals(listOf(CanFrame(0x7E8, byteArrayOf(0x41))), received)
        job.cancel()
    }

    @Test
    fun `a send timeout is fatal - state becomes Error and the link closes`() = runTest {
        val link = FakeOpComLink()
        val transport = OpComTransport(link, backgroundScope)
        transport.connect()
        link.onSilence(0x90)

        val e = runCatching { transport.send(CanFrame(0x7E0, byteArrayOf(0x02, 0x01, 0x0C))) }
            .exceptionOrNull()

        assertTrue("expected OpComTimeoutException, got $e", e is OpComTimeoutException)
        assertTrue(transport.state.value is ConnectionState.Error)
        assertTrue(link.closed)
    }

    @Test
    fun `disconnect closes the link and drops replayed frames`() = runTest {
        val link = FakeOpComLink()
        val transport = OpComTransport(link, backgroundScope)
        transport.connect()
        link.pushUnsolicited(OpComFrameCodec.encodeRecord(byteArrayOf(0x91.toByte(), 0, 0, 0x07, 0xE8.toByte(), 1, 0x41, 0, 0, 0, 0, 0, 0)))

        transport.disconnect()

        assertEquals(ConnectionState.Disconnected, transport.state.value)
        assertTrue(link.closed)
        val received = mutableListOf<CanFrame>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            transport.incomingFrames.toList(received)
        }
        assertEquals("a new session must not replay old frames", emptyList<CanFrame>(), received)
        val e = runCatching { transport.send(CanFrame(0x7E0, byteArrayOf(0x02, 0x01, 0x0C))) }
            .exceptionOrNull()
        assertTrue("expected IllegalStateException, got $e", e is IllegalStateException)
        job.cancel()
    }

    @Test
    fun `reconnect after a fatal timeout starts from a clean slate`() = runTest {
        val link = FakeOpComLink()
        val transport = OpComTransport(link, backgroundScope)
        transport.connect()
        link.onSilence(0x90)
        runCatching { transport.send(CanFrame(0x7E0, byteArrayOf(0x02, 0x01, 0x0C))) }

        transport.connect()

        assertEquals(ConnectionState.Ready, transport.state.value)
        assertEquals(
            "the fresh session must redo the full handshake",
            listOf(0xAB, 0xAA, 0xAC, 0x90, 0xAB, 0xAA, 0xAC),
            link.writtenCommands,
        )
    }
}
