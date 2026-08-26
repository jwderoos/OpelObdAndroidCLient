package nl.jwdr.ooc.ui.shell

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import nl.jwdr.ooc.diagnostics.DiagnosticsManager
import nl.jwdr.ooc.transport.ConnectionState
import nl.jwdr.ooc.transport.FakeEcuTransport
import nl.jwdr.ooc.transport.elm327.Elm327Transport
import nl.jwdr.ooc.transport.elm327.ScriptedElm327Link
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShellViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `toggleConnection connects when disconnected`() = runTest(dispatcher.scheduler) {
        val viewModel = ShellViewModel(DiagnosticsManager(FakeEcuTransport(backgroundScope)), MutableStateFlow(false))

        viewModel.toggleConnection()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(ConnectionState.Ready, viewModel.connectionState.value)
    }

    @Test
    fun `toggleConnection disconnects when ready`() = runTest(dispatcher.scheduler) {
        val manager = DiagnosticsManager(FakeEcuTransport(backgroundScope))
        manager.connect()
        val viewModel = ShellViewModel(manager, MutableStateFlow(false))

        viewModel.toggleConnection()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(ConnectionState.Disconnected, viewModel.connectionState.value)
    }

    @Test
    fun `a failing connect surfaces as Error state instead of crashing`() = runTest(dispatcher.scheduler) {
        // A real adapter that is off or out of range: connect() throws after
        // moving the transport to Error. The ViewModel must swallow the throw
        // (the state drives the UI) — uncaught, it would kill the process.
        val link = ScriptedElm327Link()
        link.on("ATZ", "?\r\r>")
        val viewModel = ShellViewModel(DiagnosticsManager(Elm327Transport(link)), MutableStateFlow(false))

        viewModel.toggleConnection()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.connectionState.value is ConnectionState.Error)
    }

    @Test
    fun `exposes the simulated flag for the badge`() = runTest(dispatcher.scheduler) {
        val viewModel = ShellViewModel(DiagnosticsManager(FakeEcuTransport(backgroundScope)), MutableStateFlow(false))

        assertTrue(viewModel.isSimulated.value)
    }
}
