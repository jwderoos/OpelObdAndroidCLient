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
    fun `numeric rule applies an affine offset`() {
        val temp = DataRow("Coolant", unit = "C")
        // byte0 = 0x03 -> 3 * 2.0 + (-40.0) = -34
        val r = LiveMeasuringBlockDecoder.decode(
            1, listOf(temp), dpidBytes,
            mapOf(1 to LiveDecodeRule.Numeric(dpid = 1, byte = 0, factor = 2.0, offset = -40.0)),
        ).single()
        assertEquals("-34", r.display)
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
    fun `masked-state rule shifts the masked bits down and indexes the state list`() {
        val hardtopSwitch = DataRow(
            "Hardtop Switch", states = listOf("Inactive", "Opening", "Closing", "Invalid"),
        )
        // DPID1 byte0 = 0x03; low 2 bits (mask 3) = 3 -> "Invalid".
        val low = LiveMeasuringBlockDecoder.decode(
            1, listOf(hardtopSwitch), dpidBytes, mapOf(1 to LiveDecodeRule.MaskedState(dpid = 1, byte = 0, mask = 3)),
        ).single()
        assertEquals("Invalid", low.display)
        // A high 2-bit field (mask 0x60) is shifted down before indexing:
        // byte1 = 0x14 -> (0x14 & 0x60) >> 5 = 0 -> "Inactive".
        val high = LiveMeasuringBlockDecoder.decode(
            1, listOf(hardtopSwitch), dpidBytes, mapOf(1 to LiveDecodeRule.MaskedState(dpid = 1, byte = 1, mask = 0x60)),
        ).single()
        assertEquals("Inactive", high.display)
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

    // ---- extended rule schema (issue #47) ----

    /** DPID 16: a payload with room for 16- and 24-bit fields. */
    private val wideBytes = mapOf(
        16 to bytes(0x01, 0x02, 0x03, 0x04, 0x05, 0xF0, 0x9C),
    )

    @Test
    fun `a big-endian 16-bit field combines both bytes high-first`() {
        val row = DataRow("Engine Speed", unit = "rpm")
        val r = LiveMeasuringBlockDecoder.decode(
            1, listOf(row), wideBytes,
            mapOf(1 to LiveDecodeRule.Numeric(dpid = 16, byte = 0, factor = 0.25, width = 2)),
        ).single()
        // 0x0102 = 258; 258 * 0.25 = 64.5
        assertEquals(258, r.raw)
        assertEquals("64.5", r.display)
    }

    @Test
    fun `a little-endian 16-bit field combines both bytes low-first`() {
        val row = DataRow("Intake Temp", unit = "C")
        val r = LiveMeasuringBlockDecoder.decode(
            1, listOf(row), wideBytes,
            mapOf(
                1 to LiveDecodeRule.Numeric(
                    dpid = 16, byte = 0, factor = 1.0, width = 2, bigEndian = false,
                ),
            ),
        ).single()
        // 0x0201 = 513, not 258 — the byte order is the whole point.
        assertEquals(513, r.raw)
        assertEquals("513", r.display)
    }

    @Test
    fun `a 24-bit field combines three bytes big-endian`() {
        val row = DataRow("Odometer", unit = "km")
        val r = LiveMeasuringBlockDecoder.decode(
            1, listOf(row), wideBytes,
            mapOf(1 to LiveDecodeRule.Numeric(dpid = 16, byte = 1, factor = 1.0, width = 3)),
        ).single()
        // 0x020304 = 131844
        assertEquals(131844, r.raw)
    }

    @Test
    fun `a signed byte above 0x7F decodes as a negative value`() {
        val row = DataRow("Fuel Trim", unit = "%")
        val r = LiveMeasuringBlockDecoder.decode(
            1, listOf(row), wideBytes,
            mapOf(1 to LiveDecodeRule.Numeric(dpid = 16, byte = 5, factor = 0.5, signed = true)),
        ).single()
        // 0xF0 = 240 unsigned, -16 as two's complement; -16 * 0.5 = -8
        assertEquals(-16, r.raw)
        assertEquals("-8", r.display)
    }

    @Test
    fun `a mask narrows a numeric field before scaling`() {
        val row = DataRow("Load", unit = "%")
        val r = LiveMeasuringBlockDecoder.decode(
            1, listOf(row), wideBytes,
            mapOf(1 to LiveDecodeRule.Numeric(dpid = 16, byte = 6, factor = 1.0, mask = 0x7F)),
        ).single()
        // 0x9C & 0x7F = 0x1C = 28 — the top bit belongs to another row.
        assertEquals(28, r.raw)
    }

    @Test
    fun `a shift moves a high nibble down before scaling`() {
        val row = DataRow("Gear")
        val r = LiveMeasuringBlockDecoder.decode(
            1, listOf(row), wideBytes,
            mapOf(1 to LiveDecodeRule.Numeric(dpid = 16, byte = 6, factor = 1.0, shift = 4)),
        ).single()
        // 0x9C ushr 4 = 9
        assertEquals(9, r.raw)
    }

    @Test
    fun `a table rule maps the masked field through to a state label`() {
        val row = DataRow("Gear Position", states = listOf("P", "R", "N", "D", "M", "Invalid"))
        // byte6 = 0x9C; (0x9C & 0x1F) = 0x1C = 28 -> map 28 -> state 4 = "M".
        val r = LiveMeasuringBlockDecoder.decode(
            1, listOf(row), wideBytes,
            mapOf(1 to LiveDecodeRule.Table(dpid = 16, byte = 6, mask = 0x1F, map = mapOf(28 to 4))),
        ).single()
        assertEquals("M", r.display)
    }

    @Test
    fun `a table rule shows the raw index when the map has no entry for it`() {
        val row = DataRow("Gear Position", states = listOf("P", "R", "N"))
        val r = LiveMeasuringBlockDecoder.decode(
            1, listOf(row), wideBytes,
            mapOf(1 to LiveDecodeRule.Table(dpid = 16, byte = 6, mask = 0x1F, map = mapOf(0 to 1))),
        ).single()
        // Honest raw index beats guessing a label for an unmapped state.
        assertEquals("28", r.display)
    }

    @Test
    fun `a multi-byte field running past the payload reads as no-data`() {
        val row = DataRow("Odometer", unit = "km")
        val short = mapOf(16 to bytes(0x01, 0x02))
        val r = LiveMeasuringBlockDecoder.decode(
            1, listOf(row), short,
            mapOf(1 to LiveDecodeRule.Numeric(dpid = 16, byte = 1, factor = 1.0, width = 3)),
        ).single()
        // A partial read would be a confidently wrong number.
        assertEquals(MeasuringBlockDecoder.NO_DATA, r.display)
        assertEquals(null, r.raw)
    }
}
