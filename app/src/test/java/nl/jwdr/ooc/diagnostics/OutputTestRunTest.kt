package nl.jwdr.ooc.diagnostics

import kotlinx.coroutines.test.runTest
import nl.jwdr.ooc.catalog.CommandRecord
import nl.jwdr.ooc.catalog.OutputTest
import nl.jwdr.ooc.catalog.OutputTestType
import nl.jwdr.ooc.protocol.session.SessionException
import nl.jwdr.ooc.transport.CanFrame
import nl.jwdr.ooc.transport.FakeEcuTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputTestRunTest {

    private val pad = 0xAA.toByte()

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun frame(id: Int, vararg values: Int): CanFrame {
        val data = bytes(*values)
        return CanFrame(id, if (data.size < 8) data + ByteArray(8 - data.size) { pad } else data)
    }

    /** An 8-byte catalog command record: first byte counts the significant bytes. */
    private fun record(vararg significant: Int) =
        CommandRecord(listOf(significant.size) + significant.toList() + List(7 - significant.size) { 0 })

    private val rec = EcuScanTarget(name = "REC", requestId = 0x241, responseId = 0x641)

    private val test = OutputTest(
        title = "Return Pump Relay Test",
        type = OutputTestType.ONOFF,
        beforeTest = listOf(record(0xAE, 0x01, 0x00)),
        goActivate = listOf(record(0xAE, 0x02, 0x02, 0x00, 0x00, 0x00)),
        deActivate = listOf(record(0xAE, 0x02, 0x00, 0x00, 0x00, 0x00)),
        afterTest = listOf(record(0xAE, 0x01, 0x0C)),
    )

    private val beforeFrame = frame(0x241, 0x03, 0xAE, 0x01, 0x00)
    private val activateFrame = frame(0x241, 0x06, 0xAE, 0x02, 0x02, 0x00, 0x00, 0x00)
    private val deactivateFrame = frame(0x241, 0x06, 0xAE, 0x02, 0x00, 0x00, 0x00, 0x00)
    private val afterFrame = frame(0x241, 0x03, 0xAE, 0x01, 0x0C)

    private fun scriptedTransport(scope: kotlinx.coroutines.CoroutineScope): FakeEcuTransport {
        val transport = FakeEcuTransport(scope)
        transport.onFrame(beforeFrame).respondWith(frame(0x641, 0x02, 0xEE, 0x01))
        transport.onFrame(activateFrame).respondWith(frame(0x641, 0x02, 0xEE, 0x02))
        transport.onFrame(deactivateFrame).respondWith(frame(0x641, 0x02, 0xEE, 0x02))
        transport.onFrame(afterFrame).respondWith(frame(0x641, 0x02, 0xEE, 0x01))
        return transport
    }

    @Test
    fun `start runs the before-test records, controls send their records, finish runs teardown`() = runTest {
        val transport = scriptedTransport(backgroundScope)
        transport.connect()
        val manager = DiagnosticsManager(transport)

        val run = manager.startOutputTest(rec, test)
        assertTrue(transport.sentFrames.contains(beforeFrame))

        run.activate()
        assertTrue(transport.sentFrames.contains(activateFrame))

        run.deactivate()
        assertTrue(transport.sentFrames.contains(deactivateFrame))

        run.finish()
        assertTrue(transport.sentFrames.contains(afterFrame))
    }

    @Test
    fun `activate can be repeated`() = runTest {
        val transport = scriptedTransport(backgroundScope)
        transport.connect()
        val manager = DiagnosticsManager(transport)

        val run = manager.startOutputTest(rec, test)
        run.activate()
        run.activate()
        run.finish()

        assertEquals(2, transport.sentFrames.count { it == activateFrame })
    }

    @Test
    fun `an idle run broadcasts the all-nodes tester present`() = runTest {
        // Recorded sessions keep the test mode alive with the GMLAN all-nodes
        // testerPresent frame (0x101 FE 01 3E), answered by 7E on the
        // diagnostic response id — not a per-ECU ISO-TP testerPresent.
        val transport = scriptedTransport(backgroundScope)
        transport.connect()
        val manager = DiagnosticsManager(transport)
        val keepAliveFrame = CanFrame(
            0x101,
            bytes(0xFE, 0x01, 0x3E, 0x00, 0x00, 0x00, 0x00, 0x00),
        )

        val run = manager.startOutputTest(rec, test)
        testScheduler.advanceTimeBy(5_000)
        testScheduler.runCurrent()
        run.finish()

        assertTrue(
            "expected the all-nodes tester present on the bus while idle",
            transport.sentFrames.count { it == keepAliveFrame } >= 2,
        )
    }

    @Test
    fun `empty command records are skipped instead of crashing`() = runTest {
        // Catalog files may carry zero-count or blank records; they carry no
        // payload and must not abort the sequence (worst case: mid-teardown).
        val transport = scriptedTransport(backgroundScope)
        transport.connect()
        val manager = DiagnosticsManager(transport)
        val withEmptyRecords = test.copy(
            beforeTest = listOf(CommandRecord(listOf(0, 0, 0, 0, 0, 0, 0, 0))) + test.beforeTest,
            afterTest = listOf(CommandRecord(emptyList())) + test.afterTest,
        )

        val run = manager.startOutputTest(rec, withEmptyRecords)
        run.finish()

        assertTrue(transport.sentFrames.contains(beforeFrame))
        assertTrue(transport.sentFrames.contains(afterFrame))
    }

    @Test
    fun `a negative response to a before-test record fails the start`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        // 7F AE 11: serviceNotSupported.
        transport.onFrame(beforeFrame).respondWith(frame(0x641, 0x03, 0x7F, 0xAE, 0x11))
        transport.connect()
        val manager = DiagnosticsManager(transport)

        val e = runCatching { manager.startOutputTest(rec, test) }.exceptionOrNull()

        assertTrue("expected NegativeResponse, got $e", e is SessionException.NegativeResponse)
    }

    @Test
    fun `finish runs teardown even after a failed activation`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(beforeFrame).respondWith(frame(0x641, 0x02, 0xEE, 0x01))
        transport.onFrame(activateFrame).respondWith(frame(0x641, 0x03, 0x7F, 0xAE, 0x22))
        transport.onFrame(afterFrame).respondWith(frame(0x641, 0x02, 0xEE, 0x01))
        transport.connect()
        val manager = DiagnosticsManager(transport)

        val run = manager.startOutputTest(rec, test)
        val e = runCatching { run.activate() }.exceptionOrNull()
        assertTrue("expected NegativeResponse, got $e", e is SessionException.NegativeResponse)

        run.finish()

        assertTrue(transport.sentFrames.contains(afterFrame))
    }
}
