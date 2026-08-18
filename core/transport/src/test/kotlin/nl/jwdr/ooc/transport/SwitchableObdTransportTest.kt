package nl.jwdr.ooc.transport

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SwitchableObdTransportTest {

    private val frame = CanFrame(0x7E0, byteArrayOf(0x02, 0x01, 0x00))

    @Test
    fun `delegates connect, send and state to the active transport`() = runTest {
        val fake = FakeEcuTransport(backgroundScope)
        val switchable = SwitchableObdTransport(fake)

        switchable.connect()
        switchable.send(frame)

        assertEquals(ConnectionState.Ready, switchable.state.value)
        assertEquals(listOf(frame), fake.sentFrames)
    }

    @Test
    fun `incoming frames of the active transport flow through`() = runTest {
        val fake = FakeEcuTransport(backgroundScope)
        val response = CanFrame(0x7E8, byteArrayOf(0x41, 0x00))
        fake.onFrame(frame).respondWith(response)
        val switchable = SwitchableObdTransport(fake)
        val received = mutableListOf<CanFrame>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            switchable.incomingFrames.toList(received)
        }
        switchable.connect()

        switchable.send(frame)

        assertEquals(listOf(response), received)
        job.cancel()
    }

    @Test
    fun `switchTo swaps the active transport while disconnected`() = runTest {
        val first = FakeEcuTransport(backgroundScope)
        val second = FakeEcuTransport(backgroundScope)
        val switchable = SwitchableObdTransport(first)

        switchable.switchTo(second)
        switchable.connect()
        switchable.send(frame)

        assertEquals(second, switchable.active.value)
        assertEquals(listOf(frame), second.sentFrames)
        assertEquals(emptyList<CanFrame>(), first.sentFrames)
    }

    @Test
    fun `switchTo while connected throws and keeps the active transport`() = runTest {
        val first = FakeEcuTransport(backgroundScope)
        val second = FakeEcuTransport(backgroundScope)
        val switchable = SwitchableObdTransport(first)
        switchable.connect()

        val e = runCatching { switchable.switchTo(second) }.exceptionOrNull()

        assertTrue("expected IllegalStateException, got $e", e is IllegalStateException)
        assertEquals(first, switchable.active.value)
    }

    @Test
    fun `state follows the transport swapped in`() = runTest {
        val first = FakeEcuTransport(backgroundScope)
        val second = FakeEcuTransport(backgroundScope)
        second.connect() // Ready before the swap; the switchable must reflect it.
        val switchable = SwitchableObdTransport(first)
        assertEquals(ConnectionState.Disconnected, switchable.state.value)

        switchable.switchTo(second)

        assertEquals(ConnectionState.Ready, switchable.state.value)
    }
}
