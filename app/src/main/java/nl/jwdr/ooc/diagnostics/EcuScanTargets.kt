package nl.jwdr.ooc.diagnostics

import nl.jwdr.ooc.catalog.CanBus
import nl.jwdr.ooc.catalog.EcuAddress
import nl.jwdr.ooc.catalog.EcuDefinition

/**
 * The ECU's CAN address if it is a real, diagnosable target on a bus the
 * OP-COM interface can select, or null otherwise (issue #32).
 *
 * The catalog carries menu-only placeholder rows as `CAN` records with a zero
 * address and a `VIRTUAL`/`CHCAN` bus (e.g. "Anti Theft Warning System",
 * "Central Door Lock"). They must never reach a scan or a fault-code read:
 * probing them wastes a full session timeout each, and `VIRTUAL`/`CHCAN` have
 * no captured bus-select sequence so [CanBus.toOpComBus] throws on them.
 */
fun EcuDefinition.diagnosableCanAddress(): EcuAddress.Can? =
    (address as? EcuAddress.Can)?.takeIf { it.requestId != 0 && it.bus.isSelectable() }

/** True for buses with a confirmed OP-COM bus-select sequence (issue #30). */
private fun CanBus.isSelectable(): Boolean = when (this) {
    CanBus.HSCAN, CanBus.SWCAN, CanBus.MSCAN -> true
    CanBus.CHCAN, CanBus.VIRTUAL -> false
}

/** Builds a scan target from a diagnosable CAN ECU, or null if it isn't one. */
fun EcuDefinition.toScanTarget(): EcuScanTarget? =
    diagnosableCanAddress()?.let { address ->
        EcuScanTarget(
            name = name,
            requestId = address.requestId,
            responseId = address.responseId,
            // 0 in catalog records that carry no broadcast id.
            secondaryId = address.secondaryId.takeIf { it != 0 },
            bus = address.bus,
        )
    }
