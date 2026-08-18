package nl.jwdr.ooc.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MeasuringBlockParserTest {

    private val catalog = MeasuringBlockParser.parse(
        fixture("MeasuringBlocks/EXAMPLIAENGZ99XX.MBF.txt"),
        "EXAMPLIAENGZ99XX.MBF.txt",
    )

    @Test
    fun `parses block definitions`() {
        assertEquals(2, catalog.blocks.size)
        val first = catalog.blocks[0]
        assertEquals(1, first.number)
        assertEquals("Diagnostic Data List 1", first.title)
        assertEquals(listOf(0x04, 0x03, 0x10), first.measData)
        assertEquals(2..4, first.enabledRows)
    }

    @Test
    fun `tolerates a standalone SM metadata line`() {
        // Real MBF files (e.g. ABS ones) carry `SM=00007` next to `ID=`;
        // meaning not yet established, so it is skipped, not fatal.
        val parsed = MeasuringBlockParser.parse(
            "ID=00117\nSM=00007\n[MEASURING BLOCK DATA]\nBattery Voltage,string,[V]\n",
            "AGILAABS.MBF.txt",
        )
        assertEquals("00117", parsed.ecuId)
        assertEquals(1, parsed.dataRows.size)
    }

    @Test
    fun `preserves PRE_MEAS setup commands`() {
        // Real blocks (e.g. Antara ABS) send dynamicallyDefineLocalIdentifier
        // setup commands before reading; keep the bytes verbatim.
        val parsed = MeasuringBlockParser.parse(
            "##MB01=Setup Block\n[begin]\nPRE_MEAS=04,2C,F5,80,19\nPRE_MEAS=04,2C,F7,80,15\n" +
                "MEASDATA=F5,\nDISABLE_ALL\nENABLE_RANGE=0001-0001\n[end]\n" +
                "[MEASURING BLOCK DATA]\nBattery Voltage,string,[V]\n",
            "TEST.MBF.txt",
        )
        val block = parsed.blocks.single()
        assertEquals(
            listOf(listOf(0x04, 0x2C, 0xF5, 0x80, 0x19), listOf(0x04, 0x2C, 0xF7, 0x80, 0x15)),
            block.preMeas,
        )
        assertEquals(listOf(0xF5), block.measData)
    }

    @Test
    fun `a raw-command block without MEASDATA keeps its commands`() {
        // Old K-line engine files define blocks as raw MEASBLOCKCMD frames
        // (0x-prefixed, trailing comma) with no MEASDATA at all.
        val parsed = MeasuringBlockParser.parse(
            "##MB01=Engine Data 1\n[begin]\nMEASBLOCKCMD=0x82,0x11,0xF1,0x21,0x01,0xA6,\n" +
                "DISABLE_ALL\nENABLE_RANGE=0001-0001\n[end]\n" +
                "[MEASURING BLOCK DATA]\nBattery Voltage,string,[V]\n",
            "TEST.MBF.txt",
        )
        val block = parsed.blocks.single()
        assertEquals(emptyList<Int>(), block.measData)
        assertEquals(listOf(listOf(0x82, 0x11, 0xF1, 0x21, 0x01, 0xA6)), block.rawCommands)
    }

    @Test
    fun `an enabled range reaching into trailing blank rows is clamped to the table`() {
        // Real files end their data table with blank line(s) that ranges may
        // still reference (e.g. rows 64-144 over 143 surviving rows); blank
        // rows never occur mid-table, so clamping is lossless.
        val parsed = MeasuringBlockParser.parse(
            "##MB01=Block\n[begin]\nMEASDATA=04,\nDISABLE_ALL\nENABLE_RANGE=0001-0003\n[end]\n" +
                "[MEASURING BLOCK DATA]\nRow A,string,[V]\nRow B,string,[V]\n",
            "TEST.MBF.txt",
        )
        assertEquals(1..2, parsed.blocks.single().enabledRows)
    }

    @Test
    fun `a headerless file with one top-level MEASDATA becomes a single implicit block`() {
        // 21 real files (e.g. Antara TCCM) have no ##MB blocks at all — just
        // MEASDATA + ID + the data table; all rows belong to one block.
        val parsed = MeasuringBlockParser.parse(
            "MEASDATA=03,01,02,\nID=00078\n[MEASURING BLOCK DATA]\n" +
                "Vehicle Speed,string,[km/h]\nWheel Speed,string,[km/h]\n",
            "TEST.MBF.txt",
        )
        val block = parsed.blocks.single()
        assertEquals(listOf(0x03, 0x01, 0x02), block.measData)
        assertEquals(1..2, block.enabledRows)
        assertEquals("00078", parsed.ecuId)
    }

    @Test
    fun `unknown bracketed sections are skipped`() {
        // One real file (WFS IMMO2) has a `[SUPPORTED IDETIFIERS]` (sic)
        // section with SID= lines; unknown sections are skipped wholesale.
        val parsed = MeasuringBlockParser.parse(
            "ID=00000\n[SUPPORTED IDETIFIERS]\nSID=0101\n" +
                "[MEASURING BLOCK DATA]\nIgnition Status,string,Off,On\n",
            "TEST.MBF.txt",
        )
        assertEquals(1, parsed.dataRows.size)
    }

    @Test
    fun `TABLE sections of old K-line engine files are skipped`() {
        // 38 real files carry [TABLEnnn] scaling-lookup sections; K-line
        // decoding is out of scope, so they are tolerated and skipped.
        val parsed = MeasuringBlockParser.parse(
            "ID=00002\n[TABLE001]\n147,143,140\n[TABLE002]\n185,184,183\n" +
                "[MEASURING BLOCK DATA]\nBattery Voltage,string,[V]\n",
            "TEST.MBF.txt",
        )
        assertEquals("00002", parsed.ecuId)
        assertEquals(1, parsed.dataRows.size)
        assertEquals("Battery Voltage", parsed.dataRows.single().label)
    }

    @Test
    fun `a bare begin group continues the previous block`() {
        // One real file (Vectra-C DDM) follows a block with a headerless
        // [begin] group holding the same MEASDATA and the next row range;
        // it extends the previous block.
        val parsed = MeasuringBlockParser.parse(
            "##MB01=Immobiliser Status\n[begin]\nMEASDATA=03,01\nDISABLE_ALL\nENABLE_RANGE=0001-0002\n[end]\n" +
                "[begin]\nMEASDATA=03,01\nDISABLE_ALL\nENABLE_RANGE=0003-0004\n[end]\n" +
                "[MEASURING BLOCK DATA]\nRow 1,string\nRow 2,string\nRow 3,string\nRow 4,string\n",
            "TEST.MBF.txt",
        )
        val block = parsed.blocks.single()
        assertEquals("Immobiliser Status", block.title)
        assertEquals(1..4, block.enabledRows)
    }

    @Test
    fun `trailing spaces on structural lines are ignored`() {
        // Real files carry trailing spaces after markers like `[begin]`.
        val parsed = MeasuringBlockParser.parse(
            "##MB01=Block\n[begin]   \nMEASDATA=04,\nDISABLE_ALL\nENABLE_RANGE=0001-0001\n[end]  \n" +
                "[MEASURING BLOCK DATA]\nRow A,string,[V]\n",
            "TEST.MBF.txt",
        )
        assertEquals(1, parsed.blocks.size)
    }

    @Test
    fun `preamble text missing its comment marker is skipped`() {
        // One real file starts with ` Vectra-C` (comment without `;`). Junk
        // is tolerated only before the first structural line.
        val parsed = MeasuringBlockParser.parse(
            " Vectra-C\n##MB01=Block\n[begin]\nMEASDATA=04,\nDISABLE_ALL\nENABLE_RANGE=0001-0001\n[end]\n" +
                "[MEASURING BLOCK DATA]\nRow A,string,[V]\n",
            "TEST.MBF.txt",
        )
        assertEquals(1, parsed.blocks.size)
    }

    @Test
    fun `a stub file without a data table keeps its blocks with empty row ranges`() {
        // Real stub files (e.g. Antara IPC) define blocks but ship no
        // [MEASURING BLOCK DATA] section at all.
        val parsed = MeasuringBlockParser.parse(
            "##MB01=Stub\n[begin]\nMEASDATA=03,01\nDISABLE_ALL\nENABLE_RANGE=0001-0014\n[end]\n",
            "STUB.MBF.txt",
        )
        assertTrue(parsed.blocks.single().enabledRows.isEmpty())
    }

    @Test
    fun `a range starting past the table still fails`() {
        try {
            MeasuringBlockParser.parse(
                "##MB01=Block\n[begin]\nMEASDATA=04,\nDISABLE_ALL\nENABLE_RANGE=0005-0009\n[end]\n" +
                    "[MEASURING BLOCK DATA]\nRow A,string,[V]\n",
                "TEST.MBF.txt",
            )
            fail("expected CatalogFormatException")
        } catch (e: CatalogFormatException) {
            assertTrue("message should name the block: ${e.message}", e.message!!.contains("##MB1"))
        }
    }

    @Test
    fun `parses measdata without trailing comma`() {
        assertEquals(listOf(0x04, 0x05), catalog.blocks[1].measData)
    }

    @Test
    fun `captures standalone ecu id verbatim`() {
        assertEquals("00105", catalog.ecuId)
    }

    @Test
    fun `parses unit rows with windows-1252 units`() {
        assertEquals(DataRow(label = "Coolant Temperature", unit = "°C"), catalog.dataRows[1])
    }

    @Test
    fun `parses state rows with trailing tag`() {
        val row = catalog.dataRows[2]
        assertEquals("Fuel Pump Relay", row.label)
        assertNull(row.unit)
        assertEquals(listOf("Inactive", "Active"), row.states)
        assertEquals("TAG1", row.tag)
    }

    @Test
    fun `parses unit rows with trailing tag`() {
        assertEquals(DataRow(label = "Battery Voltage", unit = "V", tag = "TAG2"), catalog.dataRows[3])
    }

    @Test
    fun `resolves enabled rows one-based inclusive`() {
        val oil = catalog.blocks[1]
        assertEquals(
            listOf("Engine Oil Change Warning", "Remaining Oil Life"),
            catalog.rowsFor(oil).map { it.label },
        )
    }

    @Test
    fun `reports block without end marker`() {
        val text = "##MB01=Broken\n[begin]\nMEASDATA=04\n"
        try {
            MeasuringBlockParser.parse(text, "BROKEN.MBF.txt")
            fail("expected CatalogFormatException")
        } catch (e: CatalogFormatException) {
            assertEquals("BROKEN.MBF.txt", e.fileName)
        }
    }

}
