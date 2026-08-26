package nl.jwdr.ooc.catalog

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Decodes GMLAN live data with the per-ECU wire ruleset (byte offset / bitmask
 * / scale extracted from the vendor tool's per-ID handlers). Fixture bytes are
 * REC (ID 14) DPID payloads captured from a real Astra-H.
 */
class LiveMeasuringBlockDecoderTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    // Real REC UUDT payloads (first byte = DPID id stripped): DPID 1,2,126.
    private val dpidBytes = mapOf(
        1 to bytes(0x03, 0x14, 0x6a, 0x7a, 0x00, 0x00, 0x00),
        2 to bytes(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
        126 to bytes(0x0a, 0x19, 0x02, 0x01, 0x01, 0x00, 0x00),
    )

    @Test
    fun `scale rule yields the physical value with the row unit`() {
        val row = DataRow("REC System Voltage", unit = "V")
        val readings = LiveMeasuringBlockDecoder.decode(
            firstRowNumber = 11,
            rows = listOf(row),
            dpidBytes = dpidBytes,
            rules = mapOf(11 to LiveDecodeRule.Numeric(dpid = 1, byte = 3, factor = 0.1)),
        )
        // 0x7a = 122, x0.1 = 12.2 V (verified against the captured session).
        assertEquals("12.2", readings.single().display)
        assertEquals(122, readings.single().raw)
    }

    @Test
    fun `flag rule selects state 1 when the masked bits equal eq, else state 0`() {
        val door = DataRow("Driver Door", states = listOf("Door Closed", "Door Open"))
        val rules = mapOf(1 to LiveDecodeRule.Flag(dpid = 1, byte = 0, mask = 1, eq = 1))
        // DPID1 byte0 = 0x03, bit0 set -> "Door Open".
        assertEquals(
            "Door Open",
            LiveMeasuringBlockDecoder.decode(1, listOf(door), dpidBytes, rules).single().display,
        )
        // A clear bit -> state 0.
        val rearRight = DataRow("Rear Right Door", states = listOf("Door Closed", "Door Open"))
        val rules2 = mapOf(1 to LiveDecodeRule.Flag(dpid = 1, byte = 0, mask = 8, eq = 8))
        assertEquals(
            "Door Closed",
            LiveMeasuringBlockDecoder.decode(1, listOf(rearRight), dpidBytes, rules2).single().display,
        )
    }

    @Test
    fun `state-byte rule indexes the row's state list by the raw byte`() {
        val secWait = DataRow(
            "Security Wait Time",
            states = listOf(
                "Inactive", "Invalid", "21:20:00", "10:40:00", "5:20:00", "2:40:00",
                "1:20:00", "0:40:00", "0:20:00", "0:10:00", "0:00:10", "Active",
            ),
        )
        // DPID 126 byte0 = 0x0a = 10 -> states[10] = "0:00:10".
        val r = LiveMeasuringBlockDecoder.decode(
            39, listOf(secWait), dpidBytes, mapOf(39 to LiveDecodeRule.StateByte(dpid = 126, byte = 0)),
        ).single()
        assertEquals("0:00:10", r.display)
    }

    @Test
    fun `a row without a rule reads as no-data, not a wrong guess`() {
        val tank = DataRow("Tank Sensor", unit = "Steps")
        val r = LiveMeasuringBlockDecoder.decode(10, listOf(tank), dpidBytes, emptyMap()).single()
        assertEquals(MeasuringBlockDecoder.NO_DATA, r.display)
        assertEquals(null, r.raw)
    }

    @Test
    fun `a rule whose DPID has not broadcast yet reads as no-data`() {
        val row = DataRow("X", unit = "V")
        val r = LiveMeasuringBlockDecoder.decode(
            1, listOf(row), dpidBytes = emptyMap(),
            rules = mapOf(1 to LiveDecodeRule.Numeric(dpid = 1, byte = 3, factor = 0.1)),
        ).single()
        assertEquals(MeasuringBlockDecoder.NO_DATA, r.display)
    }

    @Test
    fun `raw-byte rule shows the decimal byte (multi-bit fields not yet modelled)`() {
        val tail = DataRow("Tail Light", states = listOf("All OFF", "Left ON", "Right ON", "All ON"))
        val r = LiveMeasuringBlockDecoder.decode(
            12, listOf(tail), dpidBytes, mapOf(12 to LiveDecodeRule.RawByte(dpid = 2, byte = 0)),
        ).single()
        assertEquals("0", r.display)
        assertEquals(0, r.raw)
    }

    @Test
    fun `a whole-number scaled value prints without a trailing point`() {
        val row = DataRow("Speed", unit = "km/h")
        // byte0 = 0x03, factor 1.0 -> "3", not "3.0"
        val r = LiveMeasuringBlockDecoder.decode(
            1, listOf(row), dpidBytes, mapOf(1 to LiveDecodeRule.Numeric(dpid = 1, byte = 0, factor = 1.0)),
        ).single()
        assertEquals("3", r.display)
    }
}
