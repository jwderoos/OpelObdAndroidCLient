package nl.jwdr.ooc.catalog

/** CAN bus a diagnosable ECU sits on. */
enum class CanBus { HSCAN, MSCAN, SWCAN, VIRTUAL }

/** How an ECU is reached, per its `opeldata.txt` record. */
sealed interface EcuAddress {

    /** KWP2000-over-CAN / GMLAN. Bit rate in tenths of kbit/s as written (`0500.0` -> 5000). */
    data class Can(
        val bus: CanBus,
        val bitRateTenthsKbps: Int,
        val requestId: Int,
        val secondaryId: Int,
        val responseId: Int,
    ) : EcuAddress

    /** K-line (KW2000 / KW82). Out of protocol scope, still parsed. */
    data class KLine(
        val baudRate: Int,
        val address: Int,
        val initType: Int,
        val extra: Int,
    ) : EcuAddress

    /** Pseudo entries (car identification, built-in functions, virtual menu rows). */
    data object None : EcuAddress
}

/** One record of `opeldata.txt`: an ECU (or pseudo entry) of one vehicle. */
data class EcuDefinition(
    val modelYear: String,
    val vehicle: String,
    val group: String,
    val name: String,
    val systemName: String,
    val protocol: String,
    val address: EcuAddress,
    /** Built-in function marker (`IDENT`, `GETECULIST`, ...) for pseudo entries. */
    val builtinFunction: String? = null,
    /** Key into the per-ECU catalog files; null when absent or `????`. */
    val catalogKey: String? = null,
)
