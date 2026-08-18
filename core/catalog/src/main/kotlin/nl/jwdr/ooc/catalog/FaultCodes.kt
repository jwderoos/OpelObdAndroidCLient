package nl.jwdr.ooc.catalog

/** One fault-code text: DTC plus symptom/failure-type suffix. */
data class FaultCode(
    /** DTC as written, e.g. `P0016` (legacy files may use numeric codes). */
    val code: String,
    /** Symptom / failure-type suffix, e.g. 0 for `-00`. */
    val symptom: Int,
    val text: String,
)

/** Parsed content of one `ErrorCodes/<KEY>.txt` file. */
data class FaultCodeCatalog(
    /** `[MB]` link to the measuring-blocks key (freeze-frame source), if present. */
    val measuringBlockKey: String? = null,
    val codes: List<FaultCode>,
) {
    /** The display text for [code] + [symptom], or null when not listed. */
    fun textFor(code: String, symptom: Int): String? =
        codes.firstOrNull { it.code == code && it.symptom == symptom }?.text
}

/** Formats raw 16-bit DTC values as the code strings the catalog uses. */
object DtcCode {

    /**
     * SAE J2012 encoding: the top two bits select the system letter, the
     * next two the first digit; the low three nibbles are hex digits.
     * `0x0016` -> `P0016`, `0x4123` -> `C0123`.
     */
    fun format(raw: Int): String {
        val letter = "PCBU"[(raw shr 14) and 0x3]
        return "%c%d%03X".format(letter, (raw shr 12) and 0x3, raw and 0xFFF)
    }
}
