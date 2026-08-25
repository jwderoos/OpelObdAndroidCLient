package nl.jwdr.ooc.diagnostics

import nl.jwdr.ooc.catalog.CanBus

/** One ECU to probe during a bus scan: display identity plus its CAN channel. */
data class EcuScanTarget(
    val name: String,
    val requestId: Int,
    val responseId: Int,
    /**
     * UUDT broadcast id for GMLAN periodic data and DTC reads (issue #31);
     * null when unknown (OBD-II fallback).
     */
    val secondaryId: Int? = null,
    /**
     * The physical CAN bus this ECU sits on, from the catalog; null when
     * unknown (OBD-II fallback, which has no catalog entry to read it from).
     * Drives [DiagnosticsManager]'s OP-COM bus-select/RX-filter step
     * (issue #30) — a null value simply skips it, same as today's no-op for
     * every other transport.
     */
    val bus: CanBus? = null,
)

/** Outcome of probing one [EcuScanTarget]. */
sealed interface EcuScanStatus {

    /**
     * The ECU answered the probe. [dtcCount] is the number of stored fault
     * codes, or null when the ECU answered with a negative response (alive,
     * but its fault status could not be read).
     */
    data class Present(val dtcCount: Int?) : EcuScanStatus

    /** The probe timed out: nothing at this address. */
    data object Absent : EcuScanStatus
}

/** One per-ECU result emitted by [DiagnosticsManager.scanEcus]. */
data class EcuScanResult(
    val target: EcuScanTarget,
    val status: EcuScanStatus,
)
