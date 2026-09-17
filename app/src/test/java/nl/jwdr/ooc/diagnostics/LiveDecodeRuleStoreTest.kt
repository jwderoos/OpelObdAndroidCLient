package nl.jwdr.ooc.diagnostics

import java.io.File
import nl.jwdr.ooc.catalog.LiveDecodeRule
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveDecodeRuleStoreTest {

    private val json = """
        {"HASTRAREC":[
          {"row":1,"dpid":1,"t":"flag","byte":0,"mask":1,"eq":1},
          {"row":11,"dpid":1,"t":"num","byte":3,"factor":0.1},
          {"row":39,"dpid":126,"t":"state","byte":0},
          {"row":12,"dpid":2,"t":"raw","byte":0},
          {"row":4,"dpid":4,"t":"mstate","byte":0,"mask":3}
        ]}
    """.trimIndent()

    private val store = LiveDecodeRuleStore { json.byteInputStream() }

    @Test
    fun `parses each rule kind keyed by catalog then row number`() {
        val rec = store.rulesFor("HASTRAREC")
        assertEquals(LiveDecodeRule.Flag(dpid = 1, byte = 0, mask = 1, eq = 1), rec[1])
        assertEquals(LiveDecodeRule.Numeric(dpid = 1, byte = 3, factor = 0.1), rec[11])
        assertEquals(LiveDecodeRule.StateByte(dpid = 126, byte = 0), rec[39])
        assertEquals(LiveDecodeRule.RawByte(dpid = 2, byte = 0), rec[12])
        assertEquals(LiveDecodeRule.MaskedState(dpid = 4, byte = 0, mask = 3), rec[4])
    }

    @Test
    fun `an unknown catalog has no rules`() {
        assertTrue(store.rulesFor("NOPE").isEmpty())
    }

    @Test
    fun `parses the extended numeric fields and lookup tables`() {
        val extended = LiveDecodeRuleStore {
            """
            {"HASTRAIPC":[
              {"row":1,"dpid":16,"t":"num","byte":0,"width":2,"endian":"be","factor":0.25},
              {"row":2,"dpid":16,"t":"num","byte":2,"width":2,"endian":"le","factor":0.125,"offset":-40.0},
              {"row":3,"dpid":16,"t":"num","byte":5,"factor":1.0,"mask":127},
              {"row":4,"dpid":16,"t":"num","byte":6,"factor":1.0,"shift":4},
              {"row":5,"dpid":24,"t":"num","byte":0,"factor":-0.7421875,"signed":true},
              {"row":6,"dpid":1,"t":"table","byte":2,"mask":31,"map":{"0":0,"1":4,"31":5}}
            ]}
            """.trimIndent().byteInputStream()
        }.rulesFor("HASTRAIPC")

        assertEquals(
            LiveDecodeRule.Numeric(dpid = 16, byte = 0, factor = 0.25, width = 2, bigEndian = true),
            extended[1],
        )
        assertEquals(
            LiveDecodeRule.Numeric(
                dpid = 16, byte = 2, factor = 0.125, offset = -40.0, width = 2, bigEndian = false,
            ),
            extended[2],
        )
        assertEquals(LiveDecodeRule.Numeric(dpid = 16, byte = 5, factor = 1.0, mask = 127), extended[3])
        assertEquals(LiveDecodeRule.Numeric(dpid = 16, byte = 6, factor = 1.0, shift = 4), extended[4])
        assertEquals(LiveDecodeRule.Numeric(dpid = 24, byte = 0, factor = -0.7421875, signed = true), extended[5])
        assertEquals(
            LiveDecodeRule.Table(dpid = 1, byte = 2, mask = 31, map = mapOf(0 to 0, 1 to 4, 31 to 5)),
            extended[6],
        )
    }

    @Test
    fun `a plain numeric rule keeps the single unsigned byte defaults`() {
        val rule = store.rulesFor("HASTRAREC")[11] as LiveDecodeRule.Numeric
        assertEquals(1, rule.width)
        assertTrue(rule.bigEndian)
        assertEquals(null, rule.mask)
        assertEquals(0, rule.shift)
        assertFalse(rule.signed)
    }

    /**
     * The shipped asset is machine-generated in a separate repo, so the app
     * must fail loudly here rather than at runtime on a car: an unknown rule
     * type throws out of [LiveDecodeRuleStore.parse] and would take the whole
     * Live Data screen down. Guards the generator's whole output surface, not
     * one hand-picked row.
     */
    @Test
    fun `the shipped asset parses completely and every rule is well formed`() {
        val asset = File("src/main/assets/live_decode_rules.json")
        assertTrue("asset not found at ${asset.absolutePath}", asset.isFile)
        val json = JSONObject(asset.readText())
        val catalogs = json.keys().asSequence().toList()
        assertTrue("expected the full generated ruleset, got ${catalogs.size} catalogs", catalogs.size >= 133)

        val shipped = LiveDecodeRuleStore { asset.inputStream() }
        var ruleCount = 0
        for (key in catalogs) {
            // Catalog keys are the vendor file stems the importer stores as
            // EcuDefinition.catalogKey; a stray extension would silently match
            // nothing at runtime.
            assertFalse("catalog key '$key' still carries a file extension", key.contains('.'))
            val declared = json.getJSONArray(key)
            val rules = shipped.rulesFor(key)
            assertEquals("every row of '$key' must survive parsing", declared.length(), rules.size)
            ruleCount += rules.size

            for (i in 0 until declared.length()) {
                val row = declared.getJSONObject(i).getInt("row")
                assertTrue("'$key' row $row must be 1-based", row >= 1)
                when (val rule = rules.getValue(row)) {
                    // A DPID payload is at most 7 bytes; byte 7 would always
                    // read as no-data, which means a generator bug.
                    is LiveDecodeRule.StateByte -> assertByte(key, row, rule.byte)
                    is LiveDecodeRule.RawByte -> assertByte(key, row, rule.byte)
                    is LiveDecodeRule.MaskedState -> {
                        assertByte(key, row, rule.byte)
                        // ushr ctz(0) would shift by 32 and read every value as 0.
                        assertTrue("'$key' row $row: masked state needs a non-zero mask", rule.mask != 0)
                    }
                    is LiveDecodeRule.Flag -> {
                        assertByte(key, row, rule.byte)
                        assertTrue("'$key' row $row: flag needs a non-zero mask", rule.mask != 0)
                    }
                    is LiveDecodeRule.Table -> {
                        assertByte(key, row, rule.byte)
                        assertTrue("'$key' row $row: table needs a non-zero mask", rule.mask != 0)
                        assertTrue("'$key' row $row: table needs a non-empty map", rule.map.isNotEmpty())
                        assertTrue(
                            "'$key' row $row: table keys must be non-negative state indices",
                            rule.map.all { (k, v) -> k >= 0 && v >= 0 },
                        )
                    }
                    is LiveDecodeRule.Numeric -> {
                        assertByte(key, row, rule.byte)
                        assertTrue("'$key' row $row: width ${rule.width} outside 1..3", rule.width in 1..3)
                        // A field must fit inside the 7-byte payload whole;
                        // a partial read would silently decode a wrong number.
                        assertTrue(
                            "'$key' row $row: field ends past the payload",
                            rule.byte + rule.width <= 7,
                        )
                        assertTrue("'$key' row $row: negative shift", rule.shift >= 0)
                        assertTrue("'$key' row $row: zero mask reads every value as 0", rule.mask != 0)
                        // The vendor routines sign only single bytes, so the
                        // decoder's 8-bit two's complement is exact. If the
                        // generator ever widens that, this fires before a car
                        // sees a wrong negative value.
                        if (rule.signed) {
                            assertEquals("'$key' row $row: signed field wider than a byte", 1, rule.width)
                            assertTrue(
                                "'$key' row $row: signed combined with mask/shift is untested",
                                rule.mask == null && rule.shift == 0,
                            )
                        }
                    }
                }
            }
        }
        assertEquals("the generator's rule total", 9141, ruleCount)
    }

    private fun assertByte(key: String, row: Int, byte: Int) =
        assertTrue("'$key' row $row: byte $byte outside a DPID payload's 0..6", byte in 0..6)
}
