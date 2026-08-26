package nl.jwdr.ooc.diagnostics

import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import nl.jwdr.ooc.catalog.DataRow
import nl.jwdr.ooc.catalog.MeasuringBlock
import nl.jwdr.ooc.catalog.MeasuringBlockDecoder
import nl.jwdr.ooc.transport.CanFrame
import nl.jwdr.ooc.transport.FakeEcuTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Live-data polling per the recorded OP-COM sessions (issue #25): one GMLAN
 * readDataByPacketIdentifier request schedules the block's MEASDATA verbatim
 * (rate byte + DPID ids), values arrive as UUDT broadcasts on the secondary
 * CAN id at seven data bytes per DPID, and leaving live data stops the
 * schedule with `AA 00`. No readDataByLocalIdentifier is involved.
 */
class LiveDataTest {

    private val pad = 0xAA.toByte()

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun frame(id: Int, vararg values: Int): CanFrame {
        val data = bytes(*values)
        return CanFrame(id, if (data.size < 8) data + ByteArray(8 - data.size) { pad } else data)
    }

    /** The single-frame `AA <rate> <dpids…>` schedule request, MEASDATA verbatim. */
    private val scheduleRequest = frame(0x7E0, 0x04, 0xAA, 0x03, 0x01, 0x02)

    /** The `AA 00` stop-scheduling request sent when the poll ends. */
    private val stopRequest = frame(0x7E0, 0x02, 0xAA, 0x00)

    /** One UUDT broadcast: DPID id plus seven data bytes. */
    private fun dpidFrame(dpid: Int, vararg data: Int) = frame(0x5E8, dpid, *data)

    private val engine = EcuScanTarget(
        name = "Engine",
        requestId = 0x7E0,
        responseId = 0x7E8,
        secondaryId = 0x5E8,
    )

    // Rate 0x03, DPIDs 0x01 and 0x02.
    private val block = MeasuringBlock(
        number = 1,
        title = "Test Block",
        measData = listOf(0x03, 0x01, 0x02),
        enabledRows = 1..9,
    )

    // Rows 1..7 decode DPID 0x01's bytes, rows 8..9 the first two of DPID 0x02.
    private val rows = listOf(
        DataRow(label = "Coolant Temperature", unit = "°C"),
        DataRow(label = "Fuel Pump Relay", states = listOf("Inactive", "Active")),
    ) + (3..7).map { DataRow(label = "Filler $it") } + listOf(
        DataRow(label = "Battery Voltage", unit = "V"),
        DataRow(label = "Engine Speed", unit = "rpm"),
    )

    @Test
    fun `with a decode ruleset each row reads its own DPID byte, scaled`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(scheduleRequest).respondWith(
            dpidFrame(0x01, 0x03, 0x14, 0x6a, 0x7a, 0x00, 0x00, 0x00),
            dpidFrame(0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
        )
        transport.connect()
        val manager = DiagnosticsManager(transport)
        val ruleRows = listOf(
            DataRow(label = "Driver Door", states = listOf("Closed", "Open")),
            DataRow(label = "System Voltage", unit = "V"),
        )
        val decodeRules = mapOf(
            1 to nl.jwdr.ooc.catalog.LiveDecodeRule.Flag(dpid = 1, byte = 0, mask = 1, eq = 1),
            2 to nl.jwdr.ooc.catalog.LiveDecodeRule.Numeric(dpid = 1, byte = 3, factor = 0.1),
        )

        val reading = manager.pollMeasuringBlock(
            engine, block.copy(enabledRows = 1..2), ruleRows, 100.milliseconds, decodeRules,
        ).first()

        assertEquals("Open", reading.rows[0].display) // byte0=0x03 bit0 set
        assertEquals("12.2", reading.rows[1].display) // byte3=0x7a=122 x0.1
    }

    @Test
    fun `a reading decodes the scheduled DPID broadcasts at seven bytes per DPID`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(scheduleRequest).respondWith(
            dpidFrame(0x01, 0x5A, 0x01, 0x13, 0x14, 0x15, 0x16, 0x17),
            dpidFrame(0x02, 0x0E, 0x20, 0x21, 0x22, 0x23, 0x24, 0x25),
        )
        transport.connect()
        val manager = DiagnosticsManager(transport)

        val reading = manager.pollMeasuringBlock(engine, block, rows, 100.milliseconds).first()

        assertEquals(9, reading.rows.size)
        assertEquals(0x5A, reading.rows[0].raw)
        assertEquals("90", reading.rows[0].display)
        assertEquals("Active", reading.rows[1].display)
        assertEquals(0x17, reading.rows[6].raw)
        assertEquals(0x0E, reading.rows[7].raw)
        assertEquals(0x20, reading.rows[8].raw)
    }

    @Test
    fun `polling emits fresh broadcast values until the collector stops`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(scheduleRequest).respondWith(
            0.milliseconds to dpidFrame(0x01, 0x50, 0, 0, 0, 0, 0, 0),
            0.milliseconds to dpidFrame(0x02, 0x0E, 0, 0, 0, 0, 0, 0),
            150.milliseconds to dpidFrame(0x01, 0x51, 0, 0, 0, 0, 0, 0),
        )
        transport.connect()
        val manager = DiagnosticsManager(transport)

        val readings = manager.pollMeasuringBlock(engine, block, rows, 100.milliseconds)
            .take(2)
            .toList()

        assertEquals(listOf(0x50, 0x51), readings.map { it.rows[0].raw })
    }

    @Test
    fun `rows of a DPID not yet broadcast read as no-data`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(scheduleRequest).respondWith(
            dpidFrame(0x01, 0x5A, 0x01, 0x13, 0x14, 0x15, 0x16, 0x17),
        )
        transport.connect()
        val manager = DiagnosticsManager(transport)

        val reading = manager.pollMeasuringBlock(engine, block, rows, 100.milliseconds).first()

        assertEquals(0x5A, reading.rows[0].raw)
        assertNull(reading.rows[7].raw)
        assertEquals(MeasuringBlockDecoder.NO_DATA, reading.rows[7].display)
    }

    @Test
    fun `stopping the poll stops the schedule with AA 00`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(scheduleRequest).respondWith(
            dpidFrame(0x01, 0x5A, 0x01, 0x13, 0x14, 0x15, 0x16, 0x17),
            dpidFrame(0x02, 0x0E, 0x20, 0x21, 0x22, 0x23, 0x24, 0x25),
        )
        transport.connect()
        val manager = DiagnosticsManager(transport)

        manager.pollMeasuringBlock(engine, block, rows, 100.milliseconds).first()

        assertTrue(
            "expected the AA 00 stop request, sent: ${transport.sentFrames}",
            transport.sentFrames.contains(stopRequest),
        )
    }

    @Test
    fun `live data never uses readDataByLocalIdentifier`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.onFrame(scheduleRequest).respondWith(
            dpidFrame(0x01, 0x5A, 0x01, 0x13, 0x14, 0x15, 0x16, 0x17),
            dpidFrame(0x02, 0x0E, 0x20, 0x21, 0x22, 0x23, 0x24, 0x25),
        )
        transport.connect()
        val manager = DiagnosticsManager(transport)

        manager.pollMeasuringBlock(engine, block, rows, 100.milliseconds).first()

        assertTrue(
            "readDataByLocalIdentifier request found in: ${transport.sentFrames}",
            transport.sentFrames.none { it.data.size > 1 && it.data[1].toInt() == 0x21 },
        )
    }

    @Test
    fun `polling a GMLAN block without a secondary CAN id fails`() = runTest {
        val transport = FakeEcuTransport(backgroundScope)
        transport.connect()
        val manager = DiagnosticsManager(transport)
        val target = engine.copy(secondaryId = null)

        val e = runCatching {
            manager.pollMeasuringBlock(target, block, rows, 100.milliseconds).first()
        }.exceptionOrNull()

        assertTrue("expected IllegalArgumentException, got $e", e is IllegalArgumentException)
    }
}
