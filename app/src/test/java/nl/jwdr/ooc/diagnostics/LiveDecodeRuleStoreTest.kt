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
        assertTrue("expected the full generated ruleset, got ${catalogs.size} catalogs", catalogs.size >= 130)

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
                    is LiveDecodeRule.Numeric -> assertByte(key, row, rule.byte)
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
                }
            }
        }
        assertEquals("the generator's rule total", 7610, ruleCount)
    }

    private fun assertByte(key: String, row: Int, byte: Int) =
        assertTrue("'$key' row $row: byte $byte outside a DPID payload's 0..6", byte in 0..6)
}
