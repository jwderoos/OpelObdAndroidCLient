package nl.jwdr.ooc.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayTagBindingsTest {

    // Synthetic fixture (no vehicle data), mirroring the documented MBF
    // structure: MEASDATA = scheduling-rate byte + DPID ids; enabled rows
    // spread over the DPIDs at 7 rows per DPID, one byte per row.
    private val mbfText = """
        ; synthetic fixture
        ##MB01=Synthetic List 1
        [begin]
        MEASDATA=03,10,11
        DISABLE_ALL
        ENABLE_RANGE=0001-0009
        [end]

        ##MB02=Synthetic List 2
        [begin]
        MEASDATA=03,20
        DISABLE_ALL
        ENABLE_RANGE=0010-0012
        [end]

        [MEASURING BLOCK DATA]
        Supply Voltage,string,[V]
        Pump Relay,string,Off,On,**PUMP**
        Row Three,string,[%]
        Row Four,string,[%]
        Row Five,string,[%]
        Row Six,string,[%]
        Row Seven,string,[%]
        Motor State,string,Idle,Moving,**MOTOR**
        Row Nine,string,[%]
        Row Ten,string,[%]
        Row Eleven,string,[%]
        Aux State,string,Closed,Open,**AUX**
    """.trimIndent()

    private val catalog = MeasuringBlockParser.parse(mbfText, "SYNTH.MBF.txt")

    @Test
    fun `resolves a tag to its dpid and byte offset within the first dpid`() {
        val bindings = DisplayTagBindings.resolve(catalog, listOf("PUMP"))

        assertEquals(1, bindings.size)
        // Row position 1 (0-based) in MB01's enabled range -> first DPID
        // (0x10), byte 1.
        assertEquals("PUMP", bindings[0].tag)
        assertEquals(0x10, bindings[0].dpid)
        assertEquals(1, bindings[0].byteIndex)
        assertEquals("Pump Relay", bindings[0].row.label)
    }

    @Test
    fun `rows past the seventh map onto the next dpid`() {
        val bindings = DisplayTagBindings.resolve(catalog, listOf("MOTOR"))

        // Row position 7 (0-based) -> second DPID (0x11), byte 0.
        assertEquals(0x11, bindings.single().dpid)
        assertEquals(0, bindings.single().byteIndex)
    }

    @Test
    fun `tags resolve across blocks and keep the requested order`() {
        val bindings = DisplayTagBindings.resolve(catalog, listOf("AUX", "PUMP"))

        assertEquals(listOf("AUX", "PUMP"), bindings.map { it.tag })
        // AUX is position 2 (0-based) in MB02's range -> DPID 0x20, byte 2.
        assertEquals(0x20, bindings[0].dpid)
        assertEquals(2, bindings[0].byteIndex)
    }

    @Test
    fun `unknown tags are skipped`() {
        val bindings = DisplayTagBindings.resolve(catalog, listOf("NOPE", "PUMP"))

        assertEquals(listOf("PUMP"), bindings.map { it.tag })
    }

    @Test
    fun `an empty tag list resolves to nothing`() {
        assertTrue(DisplayTagBindings.resolve(catalog, emptyList()).isEmpty())
    }
}
