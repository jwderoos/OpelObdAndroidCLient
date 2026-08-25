package nl.jwdr.ooc.diagnostics

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import nl.jwdr.ooc.catalog.CanBus
import nl.jwdr.ooc.catalog.OutputTest
import nl.jwdr.ooc.catalog.OutputTestType
import nl.jwdr.ooc.transport.CanFrame
import nl.jwdr.ooc.transport.ConnectionState
import nl.jwdr.ooc.transport.FakeEcuTransport
import nl.jwdr.ooc.transport.ObdTransport
import nl.jwdr.ooc.transport.RecordingTransport
import nl.jwdr.ooc.transport.SwitchableObdTransport
import nl.jwdr.ooc.transport.opcom.BusSelectable
import nl.jwdr.ooc.transport.opcom.OpComBus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies [DiagnosticsManager] drives [BusSelectable.configureBus] before
 * opening a session (issue #30) — including through the [SwitchableObdTransport]
 * / [RecordingTransport] wrapping the production transport stack actually uses
 * (`OocApplication.buildTransport`).
 */
class DiagnosticsManagerBusConfigTest {

    private data class ConfigureBusCall(val bus: OpComBus, val requestId: Int, val secondaryId: Int, val responseId: Int)

    /** A [FakeEcuTransport] that also implements [BusSelectable], recording every call. */
    private class RecordingBusSelectableTransport(scope: CoroutineScope) : ObdTransport, BusSelectable {
        val fake = FakeEcuTransport(scope)
        val configureBusCalls = mutableListOf<ConfigureBusCall>()

        override val state: StateFlow<ConnectionState> get() = fake.state
        override val incomingFrames: Flow<CanFrame> get() = fake.incomingFrames
        override suspend fun connect() = fake.connect()
        override suspend fun disconnect() = fake.disconnect()
        override suspend fun send(frame: CanFrame) = fake.send(frame)

        override suspend fun configureBus(bus: OpComBus, requestId: Int, secondaryId: Int, responseId: Int) {
            configureBusCalls += ConfigureBusCall(bus, requestId, secondaryId, responseId)
        }
    }

    private val hsCanTarget = EcuScanTarget(
        name = "Engine",
        requestId = 0x7E0,
        responseId = 0x7E8,
        secondaryId = 0x549,
        bus = CanBus.HSCAN,
    )

    @Test
    fun `readDtcs configures the bus before opening the session`() = runTest {
        val transport = RecordingBusSelectableTransport(backgroundScope)
        transport.connect()
        val manager = DiagnosticsManager(transport)

        runCatching { manager.readDtcs(hsCanTarget) }

        assertEquals(
            listOf(ConfigureBusCall(OpComBus.HSCAN, 0x7E0, 0x549, 0x7E8)),
            transport.configureBusCalls,
        )
    }

    @Test
    fun `a target with no bus (OBD-II fallback) skips configureBus`() = runTest {
        val transport = RecordingBusSelectableTransport(backgroundScope)
        transport.connect()
        val manager = DiagnosticsManager(transport)
        val obd2Target = EcuScanTarget(name = "0x7E0", requestId = 0x7E0, responseId = 0x7E8)

        runCatching { manager.readDtcs(obd2Target) }

        assertTrue(transport.configureBusCalls.isEmpty())
    }

    @Test
    fun `a target with a bus but no secondary id configures filter slot 3 as 0`() = runTest {
        val transport = RecordingBusSelectableTransport(backgroundScope)
        transport.connect()
        val manager = DiagnosticsManager(transport)
        val noBroadcastId = hsCanTarget.copy(secondaryId = null)

        runCatching { manager.readDtcs(noBroadcastId) }

        assertEquals(
            listOf(ConfigureBusCall(OpComBus.HSCAN, 0x7E0, 0, 0x7E8)),
            transport.configureBusCalls,
        )
    }

    @Test
    fun `a plain transport without BusSelectable is unaffected`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.connect()
        val manager = DiagnosticsManager(transport)

        val e = runCatching { manager.scanEcus(listOf(hsCanTarget)) }.exceptionOrNull()

        assertTrue("must not throw just because the target carries a bus", e == null)
    }

    @Test
    fun `configureBus is resolved through SwitchableObdTransport and RecordingTransport, as production wires it`() = runTest {
        val inner = RecordingBusSelectableTransport(backgroundScope)
        val wrapped = SwitchableObdTransport(RecordingTransport(inner, openSink = { null }, scope = backgroundScope))
        wrapped.connect()
        val manager = DiagnosticsManager(wrapped)

        runCatching { manager.readDtcs(hsCanTarget) }

        assertEquals(
            listOf(ConfigureBusCall(OpComBus.HSCAN, 0x7E0, 0x549, 0x7E8)),
            inner.configureBusCalls,
        )
    }

    @Test
    fun `startOutputTest configures the bus before running the before-test records`() = runTest {
        val transport = RecordingBusSelectableTransport(backgroundScope)
        transport.connect()
        val manager = DiagnosticsManager(transport)
        val test = OutputTest(
            title = "Test",
            type = OutputTestType.ONOFF,
            beforeTest = emptyList(),
            goActivate = emptyList(),
            deActivate = emptyList(),
            afterTest = emptyList(),
        )

        val run = manager.startOutputTest(hsCanTarget, test)
        run.finish()

        assertEquals(
            listOf(ConfigureBusCall(OpComBus.HSCAN, 0x7E0, 0x549, 0x7E8)),
            transport.configureBusCalls,
        )
    }
}
