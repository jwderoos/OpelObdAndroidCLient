package nl.jwdr.ooc.ui.shell

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import nl.jwdr.ooc.diagnostics.DiagnosticsManager
import nl.jwdr.ooc.transport.ConnectionState
import nl.jwdr.ooc.transport.FakeEcuTransport
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
        val viewModel = ShellViewModel(DiagnosticsManager(FakeEcuTransport(backgroundScope)))

        viewModel.toggleConnection()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(ConnectionState.Ready, viewModel.connectionState.value)
    }

    @Test
    fun `toggleConnection disconnects when ready`() = runTest(dispatcher.scheduler) {
        val manager = DiagnosticsManager(FakeEcuTransport(backgroundScope))
        manager.connect()
        val viewModel = ShellViewModel(manager)

        viewModel.toggleConnection()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(ConnectionState.Disconnected, viewModel.connectionState.value)
    }

    @Test
    fun `exposes the simulated flag for the badge`() = runTest(dispatcher.scheduler) {
        val viewModel = ShellViewModel(DiagnosticsManager(FakeEcuTransport(backgroundScope)))

        assertTrue(viewModel.isSimulated)
    }
}
