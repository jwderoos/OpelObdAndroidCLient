package nl.jwdr.ooc.catalog

/**
 * How one measuring-block row's value is extracted from a GMLAN DPID's UUDT
 * payload. Recovered per ECU from the vendor tool's `ID=`-dispatched decode
 * handlers (see the OpelObdToolExploration write-up): each ECU hardcodes, per
 * DPID, which byte a row reads and how — the catalog itself carries no byte
 * layout, only labels/units/state names.
 *
 * [dpid] is the packet identifier whose broadcast carries [byte]; the byte is
 * a 0-based offset into that DPID's 1–7 payload bytes.
 */
sealed interface LiveDecodeRule {
    val dpid: Int
    val byte: Int

    /** Physical value = raw byte × [factor] + [offset], shown with the row's unit. */
    data class Numeric(
        override val dpid: Int,
        override val byte: Int,
        val factor: Double,
        val offset: Double = 0.0,
    ) : LiveDecodeRule

    /** State label = `row.states[rawByte]` (the raw byte is a direct index). */
    data class StateByte(override val dpid: Int, override val byte: Int) : LiveDecodeRule

    /**
     * State label for a bit-field: `row.states[(byte & mask) ushr ctz(mask)]`.
     * Covers multi-state rows the vendor encodes as a contiguous group of bits
     * (e.g. a 2-bit "Inactive/Opening/Closing/Invalid" field at `byte & 3`).
     */
    data class MaskedState(override val dpid: Int, override val byte: Int, val mask: Int) : LiveDecodeRule

    /** Two-state: `row.states[1]` when `(byte & mask) == eq`, else `row.states[0]`. */
    data class Flag(override val dpid: Int, override val byte: Int, val mask: Int, val eq: Int) : LiveDecodeRule

    /**
     * Show the raw decimal byte. Used for multi-bit fields whose bit→state
     * combination isn't yet modelled by the ruleset — honest raw beats a
     * wrong label.
     */
    data class RawByte(override val dpid: Int, override val byte: Int) : LiveDecodeRule
}

/**
 * Decodes GMLAN live-data rows using a per-ECU [LiveDecodeRule] set, reading
 * each row's byte from the DPID that carries it. Unlike [MeasuringBlockDecoder]
 * (a positional heuristic), this uses the vendor's real wire layout; rows with
 * no rule, or whose DPID hasn't broadcast yet, read as [MeasuringBlockDecoder.NO_DATA]
 * rather than guessing.
 */
object LiveMeasuringBlockDecoder {

    /**
     * @param firstRowNumber the 1-based catalog row number of `rows[0]` (rules
     *   are keyed by catalog row number, which spans the whole ECU table, not
     *   just this block).
     * @param dpidBytes latest payload seen per DPID id; a missing DPID means no
     *   broadcast yet.
     * @param rules row number → decode rule for this ECU.
     */
    fun decode(
        firstRowNumber: Int,
        rows: List<DataRow>,
        dpidBytes: Map<Int, ByteArray>,
        rules: Map<Int, LiveDecodeRule>,
    ): List<RowReading> = rows.mapIndexed { index, row ->
        val rule = rules[firstRowNumber + index]
        val raw = rule?.let { dpidBytes[it.dpid]?.getOrNull(it.byte)?.toInt()?.and(0xFF) }
        RowReading(row, raw, display(row, rule, raw))
    }

    private fun display(row: DataRow, rule: LiveDecodeRule?, raw: Int?): String {
        if (rule == null || raw == null) return MeasuringBlockDecoder.NO_DATA
        return when (rule) {
            is LiveDecodeRule.Numeric -> formatScaled(raw * rule.factor + rule.offset)
            is LiveDecodeRule.StateByte -> row.states.getOrNull(raw) ?: "0x%02X".format(raw)
            is LiveDecodeRule.MaskedState -> {
                val index = (raw and rule.mask) ushr Integer.numberOfTrailingZeros(rule.mask)
                row.states.getOrNull(index) ?: index.toString()
            }
            is LiveDecodeRule.Flag -> {
                val index = if (raw and rule.mask == rule.eq) 1 else 0
                row.states.getOrNull(index) ?: index.toString()
            }
            is LiveDecodeRule.RawByte -> raw.toString()
        }
    }

    /** Trims a scaled value to at most 3 decimals, dropping a trailing `.0`. */
    private fun formatScaled(value: Double): String {
        val rounded = Math.round(value * 1000.0) / 1000.0
        return if (rounded == Math.floor(rounded)) rounded.toLong().toString()
        else rounded.toString().trimEnd('0').trimEnd('.')
    }
}
