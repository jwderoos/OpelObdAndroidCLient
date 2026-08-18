package nl.jwdr.ooc.diagnostics

import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import nl.jwdr.ooc.protocol.session.SessionException
import nl.jwdr.ooc.transport.CanFrame
import nl.jwdr.ooc.transport.FakeEcuTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Obd2FallbackTest {

    private val pad = 0xAA.toByte()

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun frame(id: Int, vararg values: Int): CanFrame {
        val data = bytes(*values)
        return CanFrame(id, if (data.size < 8) data + ByteArray(8 - data.size) { pad } else data)
    }

    /** The functional mode 01 PID 00 probe. */
    private fun functionalProbe() = frame(0x7DF, 0x02, 0x01, 0x00)

    private val engine = EcuScanTarget(name = "0x7E0", requestId = 0x7E0, responseId = 0x7E8)

    @Test
    fun `discovery finds each responding OBD-II ECU`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(functionalProbe()).respondWith(
            frame(0x7E8, 0x06, 0x41, 0x00, 0xBE, 0x1F, 0xA8, 0x13),
            frame(0x7EA, 0x06, 0x41, 0x00, 0x80, 0x00, 0x00, 0x00),
        )
        transport.connect()
        val manager = DiagnosticsManager(transport)

        val targets = manager.discoverObd2Ecus()

        assertEquals(
            listOf(
                EcuScanTarget("0x7E0", 0x7E0, 0x7E8),
                EcuScanTarget("0x7E2", 0x7E2, 0x7EA),
            ),
            targets,
        )
    }

    @Test
    fun `discovery of a silent bus finds nothing`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.connect()
        val manager = DiagnosticsManager(transport)

        assertEquals(emptyList<EcuScanTarget>(), manager.discoverObd2Ecus())
    }

    @Test
    fun `supported PIDs are the known PIDs of all reported ranges`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        // Range 0x00: PIDs 0x05, 0x0C supported; chains to 0x20.
        transport.onFrame(frame(0x7E0, 0x02, 0x01, 0x00))
            .respondWith(frame(0x7E8, 0x06, 0x41, 0x00, 0x08, 0x10, 0x00, 0x01))
        // Range 0x20: PID 0x2F supported, no further chaining.
        transport.onFrame(frame(0x7E0, 0x02, 0x01, 0x20))
            .respondWith(frame(0x7E8, 0x06, 0x41, 0x20, 0x00, 0x02, 0x00, 0x00))
        transport.connect()
        val manager = DiagnosticsManager(transport)

        val pids = manager.obd2SupportedPids(engine)

        assertEquals(listOf(0x05, 0x0C, 0x2F), pids.map { it.id })
    }

    @Test
    fun `polling emits scaled PID values until the collector stops`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        var speed = 0x70
        transport.onFrame(frame(0x7E0, 0x02, 0x01, 0x0D)).respondBy {
            listOf(frame(0x7E8, 0x03, 0x41, 0x0D, speed++))
        }
        transport.onFrame(frame(0x7E0, 0x02, 0x01, 0x05)).respondBy {
            listOf(frame(0x7E8, 0x03, 0x41, 0x05, 0x5A))
        }
        transport.connect()
        val manager = DiagnosticsManager(transport)
        val pids = listOf(0x05, 0x0D).map { requireNotNull(nl.jwdr.ooc.protocol.obd2.Obd2Pids.byId(it)) }

        val readings = manager.pollObd2Pids(engine, pids, 100.milliseconds).take(2).toList()

        assertEquals(listOf("50", "112"), readings[0].map { it.display })
        assertEquals("113", readings[1][1].display)
        assertEquals(113.0, readings[1][1].value, 0.0)
        assertEquals("km/h", readings[1][1].pid.unit)
    }

    @Test
    fun `stored emission DTCs are read as raw codes`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(frame(0x7E0, 0x01, 0x03))
            .respondWith(frame(0x7E8, 0x06, 0x43, 0x02, 0x01, 0x43, 0xC1, 0x23))
        transport.connect()
        val manager = DiagnosticsManager(transport)

        assertEquals(listOf(0x0143, 0xC123), manager.obd2ReadDtcs(engine))
    }

    @Test
    fun `clearing emission data re-reads the remaining DTCs`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        var cleared = false
        transport.onFrame(frame(0x7E0, 0x01, 0x04)).respondBy {
            cleared = true
            listOf(frame(0x7E8, 0x01, 0x44))
        }
        transport.onFrame(frame(0x7E0, 0x01, 0x03)).respondBy {
            if (cleared) {
                listOf(frame(0x7E8, 0x02, 0x43, 0x00))
            } else {
                listOf(frame(0x7E8, 0x04, 0x43, 0x01, 0x01, 0x43))
            }
        }
        transport.connect()
        val manager = DiagnosticsManager(transport)

        assertEquals(emptyList<Int>(), manager.obd2ClearDtcs(engine))
        assertTrue(cleared)
    }

    @Test
    fun `a negative mode 03 response fails the read`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        // 7F 03 11: serviceNotSupported.
        transport.onFrame(frame(0x7E0, 0x01, 0x03))
            .respondWith(frame(0x7E8, 0x03, 0x7F, 0x03, 0x11))
        transport.connect()
        val manager = DiagnosticsManager(transport)

        val e = runCatching { manager.obd2ReadDtcs(engine) }.exceptionOrNull()

        assertTrue("expected NegativeResponse, got $e", e is SessionException.NegativeResponse)
    }
}
