package nl.jwdr.ooc.transport.elm327

import nl.jwdr.ooc.transport.CanFrame
import nl.jwdr.ooc.transport.ConnectionState
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Elm327TransportTest {

    @Test
    fun `connect runs the raw-CAN init sequence in order and becomes Ready`() = runTest {
        val link = ScriptedElm327Link()
        link.on("ATZ", "\r\rELM327 v1.5\r\r>")
        link.on("ATRV", "12.3V\r\r>")
        val transport = Elm327Transport(link)

        transport.connect()

        assertEquals(
            listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH1", "ATCAF0", "ATCFC0", "ATAL", "ATAT1", "ATSP6", "ATRV"),
            link.written,
        )
        assertEquals(ConnectionState.Ready, transport.state.value)
        assertTrue(link.opened)
    }

    @Test
    fun `a rejected init command fails connect and closes the link`() = runTest {
        val link = ScriptedElm327Link()
        link.on("ATCFC0", "?\r\r>")
        val transport = Elm327Transport(link)

        val e = runCatching { transport.connect() }.exceptionOrNull()

        assertTrue("expected Elm327Exception, got $e", e is Elm327Exception)
        assertTrue("message should name the command", e!!.message!!.contains("ATCFC0"))
        assertTrue(transport.state.value is ConnectionState.Error)
        assertTrue(link.closed)
    }

    @Test
    fun `a silent adapter fails connect with a timeout error`() = runTest {
        val link = ScriptedElm327Link()
        link.onSilence("ATZ")
        val transport = Elm327Transport(link)

        val e = runCatching { transport.connect() }.exceptionOrNull()

        assertTrue("expected Elm327Exception, got $e", e is Elm327Exception)
        assertTrue("message should mention the timeout: $e", e!!.message!!.contains("timed out"))
        assertTrue(transport.state.value is ConnectionState.Error)
        assertTrue(link.closed)
    }

    @Test
    fun `send before connect throws`() = runTest {
        val transport = Elm327Transport(ScriptedElm327Link())

        val e = runCatching { transport.send(CanFrame(0x7E0, byteArrayOf(0x02, 0x01, 0x0C))) }
            .exceptionOrNull()

        assertTrue("expected IllegalStateException, got $e", e is IllegalStateException)
    }

    @Test
    fun `send sets the header once per target id and writes the raw payload`() = runTest {
        val link = ScriptedElm327Link()
        val transport = Elm327Transport(link)
        transport.connect()
        link.written.clear()

        transport.send(CanFrame(0x7E0, byteArrayOf(0x02, 0x01, 0x0C)))
        transport.send(CanFrame(0x7E0, byteArrayOf(0x02, 0x01, 0x0D)))
        transport.send(CanFrame(0x241, byteArrayOf(0x02, 0x10, 0x03.toByte())))

        assertEquals(
            listOf("ATSH7E0", "02010C", "02010D", "ATSH241", "021003"),
            link.written,
        )
    }

    @Test
    fun `frame lines in a chunked response are emitted and noise lines are dropped`() = runTest {
        val link = ScriptedElm327Link()
        // Reply split mid-frame across chunks; wrapped in typical clone noise.
        link.on("02010C", "SEARCHING...\r7E80441", "0C1AF8\rNO DATA\r\r>")
        val transport = Elm327Transport(link)
        val received = mutableListOf<CanFrame>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            transport.incomingFrames.toList(received)
        }
        transport.connect()

        transport.send(CanFrame(0x7E0, byteArrayOf(0x02, 0x01, 0x0C)))

        assertEquals(
            listOf(CanFrame(0x7E8, byteArrayOf(0x04, 0x41, 0x0C, 0x1A, 0xF8.toByte()))),
            received,
        )
        job.cancel()
    }

    @Test
    fun `a CAN ERROR response to a send throws`() = runTest {
        val link = ScriptedElm327Link()
        link.on("02010C", "CAN ERROR\r\r>")
        val transport = Elm327Transport(link)
        transport.connect()

        val e = runCatching { transport.send(CanFrame(0x7E0, byteArrayOf(0x02, 0x01, 0x0C))) }
            .exceptionOrNull()

        assertTrue("expected Elm327Exception, got $e", e is Elm327Exception)
        assertTrue("message should carry the adapter error: $e", e!!.message!!.contains("CAN ERROR"))
    }

    @Test
    fun `a send timeout is fatal - prompt sync is lost, so the link closes and state is Error`() = runTest {
        val link = ScriptedElm327Link()
        link.onSilence("02010C")
        val transport = Elm327Transport(link)
        transport.connect()

        val e = runCatching { transport.send(CanFrame(0x7E0, byteArrayOf(0x02, 0x01, 0x0C))) }
            .exceptionOrNull()

        assertTrue("expected Elm327Exception, got $e", e is Elm327Exception)
        assertTrue(transport.state.value is ConnectionState.Error)
        assertTrue(link.closed)
    }

    @Test
    fun `reconnect after a fatal timeout starts from a clean slate`() = runTest {
        val link = ScriptedElm327Link()
        // A frame line but never a prompt: fatal timeout with text left in
        // the transport's input buffer and a frame in the replay cache.
        link.on("02010C", "7E80341 0C1AF8\r")
        val transport = Elm327Transport(link)
        transport.connect()
        runCatching { transport.send(CanFrame(0x7E0, byteArrayOf(0x02, 0x01, 0x0C))) }
        link.written.clear()

        transport.connect()

        assertEquals(ConnectionState.Ready, transport.state.value)
        assertEquals("stale input must not shift the init prompt framing", "ATZ", link.written.first())
        val received = mutableListOf<CanFrame>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            transport.incomingFrames.toList(received)
        }
        assertEquals("no frame from the dead session may replay", emptyList<CanFrame>(), received)

        transport.send(CanFrame(0x7E0, byteArrayOf(0x02, 0x01, 0x0D)))

        assertEquals("the header must be re-selected on the fresh connection", "ATSH7E0", link.written[link.written.size - 2])
        job.cancel()
    }

    @Test
    fun `disconnect closes the link and drops replayed frames`() = runTest {
        val link = ScriptedElm327Link()
        link.on("02010C", "7E80341 0C1AF8\r\r>")
        val transport = Elm327Transport(link)
        transport.connect()
        transport.send(CanFrame(0x7E0, byteArrayOf(0x02, 0x01, 0x0C)))

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
}
