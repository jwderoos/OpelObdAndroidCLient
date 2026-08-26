package nl.jwdr.ooc.diagnostics

import java.io.InputStream
import nl.jwdr.ooc.catalog.LiveDecodeRule
import org.json.JSONObject

/**
 * Loads the bundled GMLAN live-data decode ruleset (`assets/live_decode_rules.json`,
 * generated from the vendor tool's per-ECU handlers) into [LiveDecodeRule]s
 * keyed by catalog key then catalog row number.
 *
 * The asset lists only ECUs whose handler rows aligned 1:1 with the catalog;
 * an unlisted ECU (or row) simply has no rule, and live data falls back to
 * raw/no-data rather than a guess.
 */
class LiveDecodeRuleStore(open: () -> InputStream) {

    private val byCatalog: Map<String, Map<Int, LiveDecodeRule>> by lazy { parse(open) }

    /** Decode rules for [catalogKey] (e.g. `HASTRAREC`), by catalog row number; empty when unknown. */
    fun rulesFor(catalogKey: String): Map<Int, LiveDecodeRule> = byCatalog[catalogKey].orEmpty()

    private companion object {
        fun parse(open: () -> InputStream): Map<String, Map<Int, LiveDecodeRule>> {
            val text = open().bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            val result = mutableMapOf<String, Map<Int, LiveDecodeRule>>()
            for (key in root.keys()) {
                val rows = root.getJSONArray(key)
                val byRow = mutableMapOf<Int, LiveDecodeRule>()
                for (i in 0 until rows.length()) {
                    val o = rows.getJSONObject(i)
                    byRow[o.getInt("row")] = o.toRule()
                }
                result[key] = byRow
            }
            return result
        }

        fun JSONObject.toRule(): LiveDecodeRule {
            val dpid = getInt("dpid")
            val byte = getInt("byte")
            return when (val t = getString("t")) {
                "num" -> LiveDecodeRule.Numeric(dpid, byte, getDouble("factor"))
                "state" -> LiveDecodeRule.StateByte(dpid, byte)
                "flag" -> LiveDecodeRule.Flag(dpid, byte, getInt("mask"), getInt("eq"))
                "raw" -> LiveDecodeRule.RawByte(dpid, byte)
                else -> error("unknown decode rule type '$t'")
            }
        }
    }
}
