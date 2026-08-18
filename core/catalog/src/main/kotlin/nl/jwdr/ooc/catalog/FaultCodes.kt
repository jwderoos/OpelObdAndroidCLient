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
)
