package nl.jwdr.ooc.diagnostics

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import nl.jwdr.ooc.transport.CanFrame
import nl.jwdr.ooc.transport.CanLog
import nl.jwdr.ooc.transport.ConnectionState
import nl.jwdr.ooc.transport.FakeEcuTransport
import nl.jwdr.ooc.transport.ObdTransport
import nl.jwdr.ooc.transport.RecordingTransport
import nl.jwdr.ooc.transport.ReplayMode
import nl.jwdr.ooc.transport.ReplayTransport
import nl.jwdr.ooc.transport.SwitchableObdTransport
import nl.jwdr.ooc.transport.elm327.Elm327Transport
import nl.jwdr.ooc.transport.elm327.ScriptedElm327Link
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsManagerTest {

    @Test
    fun `starts disconnected`() = runTest {
        val manager = DiagnosticsManager(FakeEcuTransport(backgroundScope))

        assertEquals(ConnectionState.Disconnected, manager.connectionState.value)
    }

    @Test
    fun `connect brings the transport to Ready`() = runTest {
        val manager = DiagnosticsManager(FakeEcuTransport(backgroundScope))

        manager.connect()

        assertEquals(ConnectionState.Ready, manager.connectionState.value)
    }

    @Test
    fun `disconnect returns to Disconnected`() = runTest {
        val manager = DiagnosticsManager(FakeEcuTransport(backgroundScope))
        manager.connect()

        manager.disconnect()

        assertEquals(ConnectionState.Disconnected, manager.connectionState.value)
    }

    @Test
    fun `a recording wrapper around a simulated transport is still simulated`() = runTest {
        val recorded = RecordingTransport(FakeEcuTransport(backgroundScope), { null }, backgroundScope)

        assertTrue(DiagnosticsManager(recorded).isSimulated.value)
        assertTrue(DiagnosticsManager(SwitchableObdTransport(recorded)).isSimulated.value)
    }

    @Test
    fun `fake and replay transports are flagged as simulated`() = runTest {
        assertTrue(DiagnosticsManager(FakeEcuTransport(backgroundScope)).isSimulated.value)
        val emptyLog = CanLog(metadata = emptyMap(), frames = emptyList())
        assertTrue(
            DiagnosticsManager(
                ReplayTransport(emptyLog, ReplayMode.FastForward, backgroundScope),
            ).isSimulated.value,
        )
    }

    @Test
    fun `the simulated flag follows transport switches`() = runTest {
        val switchable = SwitchableObdTransport(FakeEcuTransport(backgroundScope))
        val manager = DiagnosticsManager(switchable)
        assertTrue(manager.isSimulated.value)

        switchable.switchTo(Elm327Transport(ScriptedElm327Link()))

        assertFalse(manager.isSimulated.value)
    }

    @Test
    fun `a real transport is not flagged as simulated`() {
        val real = object : ObdTransport {
            override val state: StateFlow<ConnectionState> =
                MutableStateFlow(ConnectionState.Disconnected)
            override val incomingFrames: Flow<CanFrame> = emptyFlow()
            override suspend fun connect() = Unit
            override suspend fun disconnect() = Unit
            override suspend fun send(frame: CanFrame) = Unit
        }

        assertFalse(DiagnosticsManager(real).isSimulated.value)
    }
}
