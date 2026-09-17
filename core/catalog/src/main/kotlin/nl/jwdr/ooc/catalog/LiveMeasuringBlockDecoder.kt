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

    /**
     * Physical value = field × [factor] + [offset], shown with the row's unit.
     *
     * The field is read from [width] payload bytes starting at [byte] (ordered
     * by [bigEndian] when wider than one), then narrowed by [mask], [shift] and
     * [signed] in that order — the same order the vendor's own routines apply.
     * The defaults reproduce a plain unsigned single byte.
     */
    data class Numeric(
        override val dpid: Int,
        override val byte: Int,
        val factor: Double,
        val offset: Double = 0.0,
        /** Payload bytes making up the field, 1–3. */
        val width: Int = 1,
        /** Byte order when [width] > 1; ignored for a single byte. */
        val bigEndian: Boolean = true,
        /** AND-mask applied before [shift]; null means take every bit. */
        val mask: Int? = null,
        /** Right shift applied after [mask]. */
        val shift: Int = 0,
        /**
         * Two's complement over **8 bits**, matching the vendor routines, which
         * only ever sign single-byte fields. Not combined with [mask]/[shift]
         * or a wider [width] by the generator.
         */
        val signed: Boolean = false,
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

    /**
     * State label for a bit-field whose value is *not* its own label index:
     * `(byte & mask) ushr ctz(mask)` looks up [map] to get the index into the
     * row's state list. Covers the vendor's non-identity enums, e.g. a 5-bit
     * field with 32 possible values collapsing onto 6 labels. An index [map]
     * doesn't cover reads as the raw index rather than a guessed label.
     */
    data class Table(
        override val dpid: Int,
        override val byte: Int,
        val mask: Int,
        val map: Map<Int, Int>,
    ) : LiveDecodeRule
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
        val raw = rule?.let { fieldValue(it, dpidBytes[it.dpid]) }
        RowReading(row, raw, display(row, rule, raw))
    }

    /**
     * The integer field [rule] reads out of one DPID's [payload], or null when
     * the DPID hasn't broadcast yet or is too short to hold the whole field —
     * a partial multi-byte read would be a wrong number, so it reads as no-data.
     */
    private fun fieldValue(rule: LiveDecodeRule, payload: ByteArray?): Int? {
        if (payload == null) return null
        val numeric = rule as? LiveDecodeRule.Numeric
        val width = numeric?.width ?: 1
        if (rule.byte < 0 || width < 1 || rule.byte + width > payload.size) return null

        fun byteAt(i: Int) = payload[rule.byte + i].toInt() and 0xFF
        var value = 0
        if (numeric?.bigEndian == false) {
            for (i in 0 until width) value = value or (byteAt(i) shl (8 * i))
        } else {
            for (i in 0 until width) value = (value shl 8) or byteAt(i)
        }

        if (numeric != null) {
            numeric.mask?.let { value = value and it }
            value = value ushr numeric.shift
            // Byte-width two's complement, as the vendor routines apply it.
            if (numeric.signed && value > 0x7F) value -= 0x100
        }
        return value
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
            is LiveDecodeRule.Table -> {
                val index = (raw and rule.mask) ushr Integer.numberOfTrailingZeros(rule.mask)
                val label = rule.map[index]?.let { row.states.getOrNull(it) }
                label ?: index.toString()
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
