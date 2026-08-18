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
import nl.jwdr.ooc.transport.ReplayMode
import nl.jwdr.ooc.transport.ReplayTransport
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
    fun `fake and replay transports are flagged as simulated`() = runTest {
        assertTrue(DiagnosticsManager(FakeEcuTransport(backgroundScope)).isSimulated)
        val emptyLog = CanLog(metadata = emptyMap(), frames = emptyList())
        assertTrue(
            DiagnosticsManager(
                ReplayTransport(emptyLog, ReplayMode.FastForward, backgroundScope),
            ).isSimulated,
        )
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

        assertFalse(DiagnosticsManager(real).isSimulated)
    }
}
