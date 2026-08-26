package nl.jwdr.ooc.transport.opcom

import nl.jwdr.ooc.transport.CanFrame
import nl.jwdr.ooc.transport.ConnectionState
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
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
    fun `send writes a 9F record for an ISO-TP Consecutive Frame and completes on its DF ack`() = runTest {
        val link = FakeOpComLink()
        val transport = OpComTransport(link, backgroundScope)
        transport.connect()

        // PCI 0x21 = Consecutive Frame, sequence 1.
        transport.send(CanFrame(0x7E0, byteArrayOf(0x21, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77)))

        assertEquals(listOf(0xAB, 0xAA, 0xAC, 0x9F), link.writtenCommands)
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
    fun `a read error on the background reader does not crash - state becomes Error`() = runTest {
        val link = FakeOpComLink()
        val transport = OpComTransport(link, backgroundScope)
        transport.connect()

        // Simulates the real UsbSerialOpComLink: the underlying port is closed (e.g. by a
        // concurrent teardown) while the reader coroutine is blocked inside a non-cancellable
        // synchronous read, so it observes an IOException rather than a CancellationException.
        link.failPendingRead(IOException("Connection closed"))
        yield() // let the reader coroutine hit the failure

        assertTrue(transport.state.value is ConnectionState.Error)
    }

    @Test
    fun `configureBus requires ConnectionState Ready`() = runTest {
        val transport = OpComTransport(FakeOpComLink(), backgroundScope)

        val e = runCatching { transport.configureBus(OpComBus.HSCAN, 0x7E0, 0, 0x7E8) }.exceptionOrNull()

        assertTrue("expected IllegalStateException, got $e", e is IllegalStateException)
    }

    @Test
    fun `configureBus HSCAN runs the full vendor init block after the handshake`() = runTest {
        val link = FakeOpComLink()
        val transport = OpComTransport(link, backgroundScope)
        transport.connect()

        transport.configureBus(OpComBus.HSCAN, requestId = 0x7E0, secondaryId = 0, responseId = 0x7E8)

        assertEquals(
            listOf(0xAB, 0xAA, 0xAC) +
                listOf(0x74, 0x73, 0x73, 0x73, 0x8E, 0x84) + // constant post-handshake block
                listOf(0x20, 0x20, 0x8E, 0x81) + // HSCAN bus-select block
                listOf(0x82) + // 82 02 poll bus awake
                List(8) { 0x83 } + // 8 RX filter slots
                listOf(0x82), // 82 01 open bus
            link.writtenCommands,
        )
    }

    @Test
    fun `configureBus programs RX filter slots 1,2 off, 3=secondaryId, 5=responseId, others 0`() = runTest {
        val link = FakeOpComLink()
        val transport = OpComTransport(link, backgroundScope)
        transport.connect()

        transport.configureBus(OpComBus.HSCAN, requestId = 0x7E0, secondaryId = 0x549, responseId = 0x649)

        val filterWrites = link.writtenPayloads.filter { (it[0].toInt() and 0xFF) == 0x83 }
        fun idOf(payload: ByteArray) = (0..3).sumOf { (payload[2 + it].toInt() and 0xFF) shl (it * 8) }
        assertEquals(8, filterWrites.size)
        assertEquals(-1, idOf(filterWrites[0])) // slot 1 off
        assertEquals(-1, idOf(filterWrites[1])) // slot 2 off
        assertEquals(0x549, idOf(filterWrites[2])) // slot 3 = secondaryId
        assertEquals(0, idOf(filterWrites[3]))
        assertEquals(0x649, idOf(filterWrites[4])) // slot 5 = responseId
        assertEquals(0, idOf(filterWrites[5]))
        assertEquals(0, idOf(filterWrites[6]))
        assertEquals(0, idOf(filterWrites[7]))
    }

    @Test
    fun `configureBus SWCAN sends the SWCAN-specific bus-select bytes`() = runTest {
        val link = FakeOpComLink()
        val transport = OpComTransport(link, backgroundScope)
        transport.connect()

        transport.configureBus(OpComBus.SWCAN, requestId = 0x249, secondaryId = 0x549, responseId = 0x649)

        val busSelect = link.writtenCommands.drop(3 + 6).take(3) // after handshake + constant block
        assertEquals(listOf(0x20, 0x84, 0x81), busSelect)
        val eightyOne = link.writtenPayloads.first { (it[0].toInt() and 0xFF) == 0x81 }
        assertEquals(
            listOf(0x08, 0x04, 0x3c, 0x03, 0x03, 0x03),
            eightyOne.drop(1).map { it.toInt() and 0xFF },
        )
    }

    @Test
    fun `configureBus MSCAN sends the MSCAN-specific bus-select bytes`() = runTest {
        val link = FakeOpComLink()
        val transport = OpComTransport(link, backgroundScope)
        transport.connect()

        transport.configureBus(OpComBus.MSCAN, requestId = 0x249, secondaryId = 0x549, responseId = 0x649)

        val busSelect = link.writtenCommands.drop(3 + 6).take(4) // after handshake + constant block
        assertEquals(listOf(0x20, 0x20, 0x8E, 0x81), busSelect)
        val eightyOne = link.writtenPayloads.first { (it[0].toInt() and 0xFF) == 0x81 }
        assertEquals(listOf(0x06), eightyOne.drop(1).map { it.toInt() and 0xFF })
    }

    @Test
    fun `configureBus is a no-op when called again for the same bus and ECU`() = runTest {
        val link = FakeOpComLink()
        val transport = OpComTransport(link, backgroundScope)
        transport.connect()
        transport.configureBus(OpComBus.HSCAN, requestId = 0x7E0, secondaryId = 0, responseId = 0x7E8)
        val afterFirst = link.writtenCommands.size

        transport.configureBus(OpComBus.HSCAN, requestId = 0x7E0, secondaryId = 0, responseId = 0x7E8)

        assertEquals(afterFirst, link.writtenCommands.size)
    }

    @Test
    fun `configureBus re-opens the interface (AB AA AC) then the full block when the target ECU changes`() = runTest {
        val link = FakeOpComLink()
        val transport = OpComTransport(link, backgroundScope)
        transport.connect()
        transport.configureBus(OpComBus.HSCAN, requestId = 0x7E0, secondaryId = 0, responseId = 0x7E8)
        val afterFirst = link.writtenCommands.size

        transport.configureBus(OpComBus.HSCAN, requestId = 0x241, secondaryId = 0, responseId = 0x641)

        // A switch re-runs the vendor's per-module open (AB/AA/AC) before the
        // 74... block, matching the real capture and un-wedging a stale bus (#34).
        val secondConfigure = link.writtenCommands.drop(afterFirst)
        assertEquals(
            listOf(0xAB, 0xAA, 0xAC) +
                listOf(0x74, 0x73, 0x73, 0x73, 0x8E, 0x84) +
                listOf(0x20, 0x20, 0x8E, 0x81) +
                listOf(0x82) +
                List(8) { 0x83 } +
                listOf(0x82),
            secondConfigure,
        )
    }

    @Test
    fun `configureBus gives up after unanswered 82 02 polls with a bus-not-awake error, sending no filters`() = runTest {
        val link = FakeOpComLink()
        link.onSilence(0x82)
        val transport = OpComTransport(link, backgroundScope, busAwakeAttempts = 3, busAwakePollTimeout = 10.milliseconds)
        transport.connect()

        val e = runCatching {
            transport.configureBus(OpComBus.HSCAN, requestId = 0x7E0, secondaryId = 0, responseId = 0x7E8)
        }.exceptionOrNull()

        assertTrue("expected OpComBusNotAwakeException, got $e", e is OpComBusNotAwakeException)
        assertEquals(ConnectionState.Ready, transport.state.value)
        assertTrue("must not proceed to write RX filters", link.writtenPayloads.none { (it[0].toInt() and 0xFF) == 0x83 })
    }

    @Test
    fun `a command timeout during configureBus outside the bus-awake poll is fatal`() = runTest {
        val link = FakeOpComLink()
        val transport = OpComTransport(link, backgroundScope)
        transport.connect()
        link.onSilence(0x73)

        val e = runCatching {
            transport.configureBus(OpComBus.HSCAN, requestId = 0x7E0, secondaryId = 0, responseId = 0x7E8)
        }.exceptionOrNull()

        assertTrue("expected OpComTimeoutException, got $e", e is OpComTimeoutException)
        assertTrue(transport.state.value is ConnectionState.Error)
        assertTrue(link.closed)
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
