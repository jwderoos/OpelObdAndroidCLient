package nl.jwdr.ooc.diagnostics

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import nl.jwdr.ooc.transport.CanFrame
import nl.jwdr.ooc.protocol.session.SessionException
import nl.jwdr.ooc.catalog.CanBus
import nl.jwdr.ooc.transport.ConnectionState
import nl.jwdr.ooc.transport.ObdTransport
import nl.jwdr.ooc.transport.FakeEcuTransport
import nl.jwdr.ooc.transport.opcom.BusSelectable
import nl.jwdr.ooc.transport.opcom.OpComBus
import nl.jwdr.ooc.transport.opcom.OpComBusNotAwakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EcuScanTest {

    private val pad = 0xAA.toByte()

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun frame(id: Int, vararg values: Int): CanFrame {
        val data = bytes(*values)
        return CanFrame(id, if (data.size < 8) data + ByteArray(8 - data.size) { pad } else data)
    }

    /** The single-frame readDTCByStatus probe the scan sends. */
    private fun probeRequest(requestId: Int) = frame(requestId, 0x04, 0x18, 0x02, 0xFF, 0x00)

    private val engine = EcuScanTarget(name = "Engine", requestId = 0x7E0, responseId = 0x7E8)

    @Test
    fun `a responding ECU is present with its DTC count`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(probeRequest(0x7E0))
            .respondWith(frame(0x7E8, 0x05, 0x58, 0x01, 0x01, 0x70, 0xE1))
        transport.connect()
        val manager = DiagnosticsManager(transport)

        val results = manager.scanEcus(listOf(engine)).toList()

        assertEquals(
            listOf(EcuScanResult(engine, EcuScanStatus.Present(dtcCount = 1))),
            results,
        )
    }

    @Test
    fun `a negative response still counts as present, with unknown fault status`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        // 7F 18 11: serviceNotSupported.
        transport.onFrame(probeRequest(0x7E0))
            .respondWith(frame(0x7E8, 0x03, 0x7F, 0x18, 0x11))
        transport.connect()
        val manager = DiagnosticsManager(transport)

        val results = manager.scanEcus(listOf(engine)).toList()

        assertEquals(
            listOf(EcuScanResult(engine, EcuScanStatus.Present(dtcCount = null))),
            results,
        )
    }

    @Test
    fun `an unanswered probe reports the ECU absent after a single attempt`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.connect()
        val manager = DiagnosticsManager(transport)

        val results = manager.scanEcus(listOf(engine)).toList()

        assertEquals(listOf(EcuScanResult(engine, EcuScanStatus.Absent)), results)
        assertEquals("a scan probe must not retry", 1, transport.sentFrames.size)
    }

    @Test
    fun `targets are probed sequentially and reported in order`() = runTest {
        val abs = EcuScanTarget(name = "ABS", requestId = 0x241, responseId = 0x641)
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(probeRequest(0x241))
            .respondWith(frame(0x641, 0x02, 0x58, 0x00))
        transport.connect()
        val manager = DiagnosticsManager(transport)

        val results = manager.scanEcus(listOf(engine, abs)).toList()

        assertEquals(
            listOf(
                EcuScanResult(engine, EcuScanStatus.Absent),
                EcuScanResult(abs, EcuScanStatus.Present(dtcCount = 0)),
            ),
            results,
        )
    }

    @Test
    fun `a scan over a disconnected transport fails with TransportLost`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        val manager = DiagnosticsManager(transport)

        val e = runCatching { manager.scanEcus(listOf(engine)).toList() }.exceptionOrNull()

        assertTrue("expected TransportLost, got $e", e is SessionException.TransportLost)
    }
    /**
     * A [FakeEcuTransport] that also answers [BusSelectable], failing
     * [configureBus] for any request id in [asleepRequestIds] so a scan's
     * bus-not-awake handling can be exercised (issue #33).
     */
    private class BusSelectableFake(
        private val inner: FakeEcuTransport,
        private val asleepRequestIds: Set<Int>,
    ) : ObdTransport by inner, BusSelectable {
        override suspend fun configureBus(bus: OpComBus, requestId: Int, secondaryId: Int, responseId: Int) {
            if (requestId in asleepRequestIds) {
                throw OpComBusNotAwakeException("bus not awake for 0x${requestId.toString(16)}")
            }
        }
    }

    @Test
    fun `a bus-not-awake ECU is reported unreachable and the scan continues`() = runTest {
        val inner = FakeEcuTransport(backgroundScope)
        inner.onFrame(probeRequest(0x7E0))
            .respondWith(frame(0x7E8, 0x05, 0x58, 0x01, 0x01, 0x70, 0xE1))
        val transport = BusSelectableFake(inner, asleepRequestIds = setOf(0x251))
        transport.connect()
        val manager = DiagnosticsManager(transport)
        val asleep = EcuScanTarget("REC", requestId = 0x251, responseId = 0x651, secondaryId = 0x551, bus = CanBus.SWCAN)
        val engineHs = engine.copy(bus = CanBus.HSCAN)

        val results = manager.scanEcus(listOf(asleep, engineHs)).toList()

        assertEquals(
            listOf(
                EcuScanResult(asleep, EcuScanStatus.Unreachable),
                EcuScanResult(engineHs, EcuScanStatus.Present(dtcCount = 1)),
            ),
            results,
        )
    }
    @Test
    fun `a scan keeps the bus awake with periodic all-nodes tester-present`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.connect()
        val manager = DiagnosticsManager(transport)
        // Ten absent ECUs: each probe times out after the scan timeout, so the
        // scan spans several seconds of virtual time — long enough for the
        // keep-alive to fire between probes (issue #35).
        val targets = List(10) { i ->
            EcuScanTarget("e$i", requestId = 0x700 + i, responseId = 0x708 + i, bus = CanBus.SWCAN)
        }

        manager.scanEcus(targets).toList()

        val testerPresent = CanFrame(
            0x101,
            bytes(0xFE, 0x01, 0x3E, 0x00, 0x00, 0x00, 0x00, 0x00),
        )
        assertTrue(
            "expected at least one keep-alive tester-present during the scan",
            transport.sentFrames.any { it == testerPresent },
        )
    }
}
