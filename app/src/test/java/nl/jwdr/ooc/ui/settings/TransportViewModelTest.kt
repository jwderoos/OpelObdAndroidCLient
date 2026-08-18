package nl.jwdr.ooc.ui.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import nl.jwdr.ooc.diagnostics.TransportSelection
import nl.jwdr.ooc.transport.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportViewModelTest {

    private val elm = TransportSelection.Elm327Bluetooth("00:11:22:AA:BB:CC", "OBDII")

    @Test
    fun `select applies the choice while disconnected`() = runTest {
        var applied: TransportSelection? = null
        val viewModel = TransportViewModel(
            selection = MutableStateFlow(TransportSelection.Demo),
            connectionState = MutableStateFlow(ConnectionState.Disconnected),
            applySelection = { applied = it },
        )

        viewModel.select(elm)

        assertEquals(elm, applied)
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun `a rejected switch surfaces an error instead of crashing`() = runTest {
        val viewModel = TransportViewModel(
            selection = MutableStateFlow(TransportSelection.Demo),
            connectionState = MutableStateFlow(ConnectionState.Ready),
            applySelection = { throw IllegalStateException("cannot switch while Ready") },
        )

        viewModel.select(elm)

        assertNotNull(viewModel.errorMessage.value)
    }

    @Test
    fun `an adapter setup failure is not misreported as switch-while-connected`() = runTest {
        val viewModel = TransportViewModel(
            selection = MutableStateFlow(TransportSelection.Demo),
            connectionState = MutableStateFlow(ConnectionState.Disconnected),
            applySelection = { throw IllegalArgumentException("device has no Bluetooth adapter") },
        )

        viewModel.select(elm)

        val message = viewModel.errorMessage.value
        assertNotNull(message)
        assertFalse("must not claim a disconnect is needed: $message", message!!.contains("Disconnect"))
    }

    @Test
    fun `switching is only offered while disconnected or errored`() = runTest {
        fun canSwitch(state: ConnectionState) = TransportViewModel(
            selection = MutableStateFlow(TransportSelection.Demo),
            connectionState = MutableStateFlow(state),
            applySelection = {},
        ).canSwitch.value

        assertTrue(canSwitch(ConnectionState.Disconnected))
        assertTrue(canSwitch(ConnectionState.Error(RuntimeException())))
        assertFalse(canSwitch(ConnectionState.Connecting))
        assertFalse(canSwitch(ConnectionState.Ready))
    }
}
