package nl.jwdr.ooc.transport

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayTransportTest {

    private val request = CanFrame(0x246, byteArrayOf(0x02, 0x21, 0x05))
    private val response1 = CanFrame(0x646, byteArrayOf(0x10, 0x0E, 0x61))
    private val response2 = CanFrame(0x646, byteArrayOf(0x21, 0x22, 0x33))
    private val wakeup = CanFrame(0x100, byteArrayOf(0x01))

    /** wakeup rx at t=0, request tx gate at t=100, two rx responses at t=110/120. */
    private val log = CanLog.parse(
        """
        # ooc-canlog v1
        0 rx 100 01
        100 tx 246 02 21 05
        110 rx 646 10 0E 61
        120 rx 646 21 22 33
        """.trimIndent(),
    )

    @Test
    fun `initial state is Disconnected`() = runTest {
        val transport = ReplayTransport(log, ReplayMode.FastForward, backgroundScope)

        assertEquals(ConnectionState.Disconnected, transport.state.value)
    }

    @Test
    fun `connect transitions through Connecting to Ready`() = runTest {
        val transport = ReplayTransport(log, ReplayMode.FastForward, backgroundScope)
        val states = mutableListOf<ConnectionState>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            transport.state.toList(states)
        }

        transport.connect()
        advanceUntilIdle()

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
        val transport = ReplayTransport(log, ReplayMode.FastForward, backgroundScope)

        val e = runCatching { transport.send(request) }.exceptionOrNull()

        assertTrue("expected IllegalStateException, got $e", e is IllegalStateException)
    }

    @Test
    fun `fast-forward emits leading rx frames without any delay`() = runTest {
        val transport = ReplayTransport(log, ReplayMode.FastForward, backgroundScope)
        transport.connect()
        advanceUntilIdle()

        val received = transport.incomingFrames.take(1).toList()

        assertEquals(listOf(wakeup), received)
    }

    @Test
    fun `rx frames after a tx gate are held until the matching send`() = runTest {
        val transport = ReplayTransport(log, ReplayMode.FastForward, backgroundScope)
        val received = mutableListOf<CanFrame>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            transport.incomingFrames.toList(received)
        }
        transport.connect()
        // Playback runs in the background scope, which only progresses while
        // the test body is suspended — advanceUntilIdle() deliberately skips
        // background-only work, so yield() instead.
        yield()

        assertEquals(listOf(wakeup), received)

        transport.send(request)
        yield()

        assertEquals(listOf(wakeup, response1, response2), received)
        job.cancel()
    }

    @Test
    fun `mismatched send throws and moves state to Error`() = runTest {
        val transport = ReplayTransport(log, ReplayMode.FastForward, backgroundScope)
        transport.connect()
        advanceUntilIdle()

        val wrong = CanFrame(0x246, byteArrayOf(0x01, 0x3E.toByte()))
        val e = runCatching { transport.send(wrong) }.exceptionOrNull()

        assertTrue("expected IllegalStateException, got $e", e is IllegalStateException)
        assertTrue("message should name expected and actual frames", e!!.message!!.contains("expected"))
        assertTrue(transport.state.value is ConnectionState.Error)
    }

    @Test
    fun `send after the script is exhausted throws`() = runTest {
        val transport = ReplayTransport(log, ReplayMode.FastForward, backgroundScope)
        transport.connect()
        transport.send(request)
        yield()

        val e = runCatching { transport.send(request) }.exceptionOrNull()

        assertTrue("expected IllegalStateException, got $e", e is IllegalStateException)
    }

    @Test
    fun `original timing delays rx frames per timestamp delta`() = runTest {
        val transport = ReplayTransport(log, ReplayMode.OriginalTiming, backgroundScope)
        val received = mutableListOf<CanFrame>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            transport.incomingFrames.toList(received)
        }
        transport.connect()
        yield()

        assertEquals("wakeup at t=0 arrives immediately", listOf(wakeup), received)

        transport.send(request)
        advanceTimeBy(9)
        assertEquals("response1 is due at 10ms after the gate", listOf(wakeup), received)
        advanceTimeBy(2)
        assertEquals(listOf(wakeup, response1), received)
        advanceTimeBy(10)
        assertEquals(listOf(wakeup, response1, response2), received)
        job.cancel()
    }

    @Test
    fun `disconnect returns to Disconnected and stops playback`() = runTest {
        val transport = ReplayTransport(log, ReplayMode.OriginalTiming, backgroundScope)
        transport.connect()
        transport.send(request)

        transport.disconnect()
        advanceUntilIdle()

        assertEquals(ConnectionState.Disconnected, transport.state.value)
        val e = runCatching { transport.send(request) }.exceptionOrNull()
        assertTrue("expected IllegalStateException, got $e", e is IllegalStateException)
    }
}
