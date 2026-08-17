package nl.jwdr.ooc.transport

import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeEcuTransportTest {

    private val request = CanFrame(0x7E0, byteArrayOf(0x02, 0x10, 0x03.toByte()))
    private val response = CanFrame(0x7E8, byteArrayOf(0x02, 0x50, 0x03.toByte()))

    @Test
    fun `initial state is Disconnected`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)

        assertEquals(ConnectionState.Disconnected, transport.state.value)
    }

    @Test
    fun `connect transitions through Connecting to Ready`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        val states = mutableListOf<ConnectionState>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            transport.state.toList(states)
        }

        transport.connect()

        assertEquals(
            listOf(
                ConnectionState.Disconnected,
                ConnectionState.Connecting,
                ConnectionState.Ready,
            ),
            states,
        )
        job.cancel()
    }

    @Test
    fun `send before connect throws`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)

        // Nested runTest itself throws IllegalStateException, so the throw
        // must be caught inside the test coroutine to assert anything real.
        val e = runCatching { transport.send(request) }.exceptionOrNull()

        assertTrue("expected IllegalStateException, got $e", e is IllegalStateException)
    }

    @Test
    fun `exact frame match emits the scripted response`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(request).respondWith(response)
        transport.connect()

        transport.send(request)

        assertEquals(response, transport.incomingFrames.first())
    }

    @Test
    fun `multi-frame response is emitted in order`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        val first = CanFrame(0x7E8, byteArrayOf(0x10, 0x0B, 0x62))
        val second = CanFrame(0x7E8, byteArrayOf(0x21, 0x01, 0x02))
        val third = CanFrame(0x7E8, byteArrayOf(0x22, 0x03, 0x04))
        transport.onFrame(request).respondWith(first, second, third)
        transport.connect()

        val collected = mutableListOf<CanFrame>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            transport.incomingFrames.toList(collected)
        }
        transport.send(request)

        assertEquals(listOf(first, second, third), collected)
        job.cancel()
    }

    @Test
    fun `match by CAN id responds regardless of payload`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.onId(0x7E0).respondWith(response)
        transport.connect()

        transport.send(CanFrame(0x7E0, byteArrayOf(0x01)))

        assertEquals(response, transport.incomingFrames.first())
    }

    @Test
    fun `match by predicate inspects the frame`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.onMatch { it.data.size >= 2 && it.data[1] == 0x10.toByte() }
            .respondWith(response)
        transport.connect()

        transport.send(request)

        assertEquals(response, transport.incomingFrames.first())
    }

    @Test
    fun `first matching rule wins`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        val other = CanFrame(0x7E8, byteArrayOf(0x7F))
        transport.onFrame(request).respondWith(response)
        transport.onId(0x7E0).respondWith(other)
        transport.connect()

        transport.send(request)

        assertEquals(response, transport.incomingFrames.first())
    }

    @Test
    fun `unmatched request produces no response`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.connect()

        val collected = mutableListOf<CanFrame>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            transport.incomingFrames.toList(collected)
        }
        transport.send(request)
        advanceUntilIdle()

        assertTrue(collected.isEmpty())
        job.cancel()
    }

    @Test
    fun `explicit no-response rule stays silent`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(request).respondNothing()
        transport.onId(0x7E0).respondWith(response)
        transport.connect()

        val collected = mutableListOf<CanFrame>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            transport.incomingFrames.toList(collected)
        }
        transport.send(request)
        advanceUntilIdle()

        assertTrue(collected.isEmpty())
        job.cancel()
    }

    @Test
    fun `delayed response arrives only after the scripted delay`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(request).respondWith(listOf(response), delay = 100.milliseconds)
        transport.connect()

        val collected = mutableListOf<CanFrame>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            transport.incomingFrames.toList(collected)
        }
        transport.send(request)

        advanceTimeBy(50.milliseconds)
        assertTrue(collected.isEmpty())

        advanceTimeBy(51.milliseconds)
        assertEquals(listOf(response), collected)
        job.cancel()
    }

    @Test
    fun `staggered responses arrive each after their own delay`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        val pending = CanFrame(0x7E8, byteArrayOf(0x03, 0x7F, 0x10, 0x78))
        transport.onFrame(request).respondWith(
            0.milliseconds to pending,
            200.milliseconds to response,
        )
        transport.connect()

        val collected = mutableListOf<CanFrame>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            transport.incomingFrames.toList(collected)
        }
        transport.send(request)

        assertEquals(listOf(pending), collected)

        advanceTimeBy(201.milliseconds)
        assertEquals(listOf(pending, response), collected)
        job.cancel()
    }

    @Test
    fun `late collector still receives earlier responses via replay`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(request).respondWith(response)
        transport.connect()

        transport.send(request)

        // No collector was attached during send; replay must hand it over.
        assertEquals(response, transport.incomingFrames.first())
    }

    @Test
    fun `sent frames are recorded in order`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        val second = CanFrame(0x7E0, byteArrayOf(0x30, 0x00, 0x00))
        transport.connect()

        transport.send(request)
        transport.send(second)

        assertEquals(listOf(request, second), transport.sentFrames)
    }

    @Test
    fun `disconnect returns to Disconnected and send throws again`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.connect()

        transport.disconnect()

        assertEquals(ConnectionState.Disconnected, transport.state.value)
        val e = runCatching { transport.send(request) }.exceptionOrNull()
        assertTrue("expected IllegalStateException, got $e", e is IllegalStateException)
    }

    @Test
    fun `disconnect clears replayed responses from the previous session`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(request).respondWith(response)
        transport.connect()
        transport.send(request)

        transport.disconnect()
        transport.connect()

        val collected = mutableListOf<CanFrame>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            transport.incomingFrames.toList(collected)
        }
        advanceUntilIdle()

        assertTrue(collected.isEmpty())
        job.cancel()
    }
}
