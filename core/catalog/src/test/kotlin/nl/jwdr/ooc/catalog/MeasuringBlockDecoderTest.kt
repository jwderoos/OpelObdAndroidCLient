package nl.jwdr.ooc.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeasuringBlockDecoderTest {

    private val catalog = MeasuringBlockParser.parse(
        fixture("MeasuringBlocks/EXAMPLIAENGZ99XX.MBF.txt"),
        "EXAMPLIAENGZ99XX.MBF.txt",
    )

    // Block 1: enabled rows 2-4 = Coolant Temperature [°C], Fuel Pump Relay
    // (Inactive/Active), Battery Voltage [V].
    private val block = catalog.blocks[0]
    private val rows = catalog.rowsFor(block)

    @Test
    fun `maps one record byte to each enabled row in order`() {
        val reading = MeasuringBlockDecoder.decode(block, rows, byteArrayOf(0x5A, 0x01, 0x0E))
        assertEquals(3, reading.rows.size)
        assertEquals(0x5A, reading.rows[0].raw)
        assertEquals(0x01, reading.rows[1].raw)
        assertEquals(0x0E, reading.rows[2].raw)
    }

    @Test
    fun `numeric rows display the decimal raw value`() {
        val reading = MeasuringBlockDecoder.decode(block, rows, byteArrayOf(0x5A, 0x01, 0x0E))
        assertEquals("90", reading.rows[0].display)
        assertEquals("14", reading.rows[2].display)
    }

    @Test
    fun `state rows display the catalog state label`() {
        val reading = MeasuringBlockDecoder.decode(block, rows, byteArrayOf(0x5A, 0x01, 0x0E))
        assertEquals("Active", reading.rows[1].display)
    }

    @Test
    fun `state value outside catalog range falls back to hex`() {
        val reading = MeasuringBlockDecoder.decode(block, rows, byteArrayOf(0x5A, 0x07, 0x0E))
        assertEquals("0x07", reading.rows[1].display)
    }

    @Test
    fun `missing record bytes leave trailing rows without data`() {
        val reading = MeasuringBlockDecoder.decode(block, rows, byteArrayOf(0x5A))
        assertEquals(0x5A, reading.rows[0].raw)
        assertNull(reading.rows[1].raw)
        assertNull(reading.rows[2].raw)
        assertEquals("—", reading.rows[1].display)
    }

    @Test
    fun `surplus record bytes are surfaced as unmapped`() {
        val reading = MeasuringBlockDecoder.decode(
            block,
            rows,
            byteArrayOf(0x5A, 0x01, 0x0E, 0x22, 0x33.toByte()),
        )
        assertEquals(listOf(0x22, 0x33), reading.unmappedBytes.map { it.toInt() and 0xFF })
    }

    @Test
    fun `a nullable record decodes gaps as no-data between present bytes`() {
        val reading = MeasuringBlockDecoder.decode(block, rows, listOf(0x5A, null, 0x0E))
        assertEquals(0x5A, reading.rows[0].raw)
        assertNull(reading.rows[1].raw)
        assertEquals("—", reading.rows[1].display)
        assertEquals(0x0E, reading.rows[2].raw)
        assertEquals("14", reading.rows[2].display)
    }

    @Test
    fun `surplus nullable record bytes surface only the present values`() {
        val reading = MeasuringBlockDecoder.decode(block, rows, listOf(0x5A, 0x01, 0x0E, 0x22, null))
        assertEquals(listOf(0x22), reading.unmappedBytes.map { it.toInt() and 0xFF })
    }

    @Test
    fun `raw values are unsigned`() {
        val reading = MeasuringBlockDecoder.decode(block, rows, byteArrayOf(0xF0.toByte(), 0x00, 0x00))
        assertEquals(0xF0, reading.rows[0].raw)
        assertEquals("240", reading.rows[0].display)
    }
}
