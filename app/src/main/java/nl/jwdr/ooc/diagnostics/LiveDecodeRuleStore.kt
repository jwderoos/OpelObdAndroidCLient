package nl.jwdr.ooc.diagnostics

import java.io.InputStream
import nl.jwdr.ooc.catalog.LiveDecodeRule
import org.json.JSONObject

/**
 * Loads the bundled GMLAN live-data decode ruleset (`assets/live_decode_rules.json`,
 * generated from the vendor tool's per-ECU handlers) into [LiveDecodeRule]s
 * keyed by catalog key then catalog row number.
 *
 * Each rule's catalog row is read straight off the handler's assignment target
 * (the vendor's row table is a fixed-stride array, so every assign names its
 * row), not inferred from row counts — see `docs/live-decode-ruleset.md` for
 * provenance and how the asset is regenerated.
 *
 * Coverage is per row, not per ECU: a catalog is usually *partially* covered,
 * because a row whose decode still can't be expressed here is deliberately
 * withheld rather than guessed. An unlisted ECU or row simply has no rule and
 * reads as no-data.
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
                "num" -> LiveDecodeRule.Numeric(
                    dpid = dpid,
                    byte = byte,
                    factor = getDouble("factor"),
                    offset = optDouble("offset", 0.0),
                    width = optInt("width", 1),
                    // Only meaningful with a width; "be" is the generator's default.
                    bigEndian = optString("endian", "be") != "le",
                    mask = if (has("mask")) getInt("mask") else null,
                    shift = optInt("shift", 0),
                    signed = optBoolean("signed", false),
                )
                "state" -> LiveDecodeRule.StateByte(dpid, byte)
                "mstate" -> LiveDecodeRule.MaskedState(dpid, byte, getInt("mask"))
                "flag" -> LiveDecodeRule.Flag(dpid, byte, getInt("mask"), getInt("eq"))
                "raw" -> LiveDecodeRule.RawByte(dpid, byte)
                "table" -> LiveDecodeRule.Table(dpid, byte, getInt("mask"), stateMap(getJSONObject("map")))
                else -> error("unknown decode rule type '$t'")
            }
        }

        /** A `table` rule's `{"<field value>": <state index>}` object. */
        fun stateMap(json: JSONObject): Map<Int, Int> {
            val map = mutableMapOf<Int, Int>()
            for (key in json.keys()) {
                map[key.toInt()] = json.getInt(key)
            }
            return map
        }
    }
}
