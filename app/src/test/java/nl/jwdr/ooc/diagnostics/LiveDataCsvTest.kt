package nl.jwdr.ooc.diagnostics

import nl.jwdr.ooc.catalog.BlockReading
import nl.jwdr.ooc.catalog.DataRow
import nl.jwdr.ooc.catalog.MeasuringBlock
import nl.jwdr.ooc.catalog.MeasuringBlockDecoder
import nl.jwdr.ooc.catalog.RowReading
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveDataCsvTest {

    private val block = MeasuringBlock(1, "Data List 1", listOf(0x04), 1..2)

    private fun reading(vararg rows: RowReading) =
        BlockReading(block, rows.toList(), ByteArray(0))

    @Test
    fun `header names the columns`() {
        assertEquals("timestamp_ms,ecu,block,label,value,unit", LiveDataCsv.HEADER)
    }

    @Test
    fun `formats one line per row`() {
        val lines = LiveDataCsv.lines(
            timestampMs = 1500,
            ecuName = "Engine",
            reading = reading(
                RowReading(DataRow("Coolant Temperature", unit = "°C"), 90, "90"),
                RowReading(DataRow("Fuel Pump Relay", states = listOf("Inactive", "Active")), 1, "Active"),
            ),
        )

        assertEquals(
            listOf(
                "1500,Engine,Data List 1,Coolant Temperature,90,°C",
                "1500,Engine,Data List 1,Fuel Pump Relay,Active,",
            ),
            lines,
        )
    }

    @Test
    fun `quotes fields containing commas or quotes`() {
        val lines = LiveDataCsv.lines(
            timestampMs = 0,
            ecuName = "Engine",
            reading = reading(
                RowReading(DataRow("Boost, \"absolute\"", unit = "kPa"), 42, "42"),
            ),
        )

        assertEquals(
            listOf("0,Engine,Data List 1,\"Boost, \"\"absolute\"\"\",42,kPa"),
            lines,
        )
    }

    @Test
    fun `rows without data have an empty value`() {
        val lines = LiveDataCsv.lines(
            timestampMs = 0,
            ecuName = "Engine",
            reading = reading(
                RowReading(DataRow("Coolant Temperature", unit = "°C"), null, MeasuringBlockDecoder.NO_DATA),
            ),
        )

        assertEquals(listOf("0,Engine,Data List 1,Coolant Temperature,,°C"), lines)
    }

    @Test
    fun `formats OBD-II readings with a fixed block label`() {
        val pid = requireNotNull(nl.jwdr.ooc.protocol.obd2.Obd2Pids.byId(0x05))
        val lines = LiveDataCsv.obd2Lines(
            timestampMs = 1500,
            ecuName = "0x7E0",
            values = listOf(Obd2Value(pid, 50.0, "50")),
        )
        assertEquals(listOf("1500,0x7E0,OBD-II,Engine coolant temperature,50,°C"), lines)
    }
}
