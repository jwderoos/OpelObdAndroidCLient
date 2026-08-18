package nl.jwdr.ooc.diagnostics

import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import nl.jwdr.ooc.catalog.DataRow
import nl.jwdr.ooc.catalog.MeasuringBlock
import nl.jwdr.ooc.protocol.session.SessionException
import nl.jwdr.ooc.transport.CanFrame
import nl.jwdr.ooc.transport.FakeEcuTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveDataTest {

    private val pad = 0xAA.toByte()

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun frame(id: Int, vararg values: Int): CanFrame {
        val data = bytes(*values)
        return CanFrame(id, if (data.size < 8) data + ByteArray(8 - data.size) { pad } else data)
    }

    /** The single-frame readDataByLocalIdentifier request for one LID. */
    private fun readRequest(requestId: Int, lid: Int) = frame(requestId, 0x02, 0x21, lid)

    private val engine = EcuScanTarget(name = "Engine", requestId = 0x7E0, responseId = 0x7E8)

    private val block = MeasuringBlock(
        number = 1,
        title = "Test Block",
        measData = listOf(0x04, 0x03),
        enabledRows = 1..2,
    )

    private val rows = listOf(
        DataRow(label = "Coolant Temperature", unit = "°C"),
        DataRow(label = "Fuel Pump Relay", states = listOf("Inactive", "Active")),
    )

    @Test
    fun `one reading concatenates the records of all MEASDATA identifiers in order`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(readRequest(0x7E0, 0x04)).respondWith(frame(0x7E8, 0x03, 0x61, 0x04, 0x5A))
        transport.onFrame(readRequest(0x7E0, 0x03)).respondWith(frame(0x7E8, 0x03, 0x61, 0x03, 0x01))
        transport.connect()
        val manager = DiagnosticsManager(transport)

        val reading = manager.pollMeasuringBlock(engine, block, rows, 100.milliseconds).first()

        assertEquals(listOf(0x5A, 0x01), reading.rows.map { it.raw })
        assertEquals(listOf("90", "Active"), reading.rows.map { it.display })
    }

    @Test
    fun `polling emits fresh readings until the collector stops`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        var temperature = 0x50
        transport.onFrame(readRequest(0x7E0, 0x04)).respondBy {
            listOf(frame(0x7E8, 0x03, 0x61, 0x04, temperature++))
        }
        transport.onFrame(readRequest(0x7E0, 0x03)).respondBy {
            listOf(frame(0x7E8, 0x03, 0x61, 0x03, 0x00))
        }
        transport.connect()
        val manager = DiagnosticsManager(transport)

        val readings = manager.pollMeasuringBlock(engine, block, rows, 100.milliseconds)
            .take(3)
            .toList()

        assertEquals(listOf(0x50, 0x51, 0x52), readings.map { it.rows[0].raw })
    }

    @Test
    fun `a negative response fails the poll`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        // 7F 21 11: serviceNotSupported.
        transport.onFrame(readRequest(0x7E0, 0x04))
            .respondWith(frame(0x7E8, 0x03, 0x7F, 0x21, 0x11))
        transport.connect()
        val manager = DiagnosticsManager(transport)

        val e = runCatching {
            manager.pollMeasuringBlock(engine, block, rows, 100.milliseconds).first()
        }.exceptionOrNull()

        assertTrue("expected NegativeResponse, got $e", e is SessionException.NegativeResponse)
    }
}
