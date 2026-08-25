package nl.jwdr.ooc.diagnostics

import nl.jwdr.ooc.catalog.CanBus
import nl.jwdr.ooc.transport.opcom.OpComBus

/**
 * Maps the catalog's bus type onto the OP-COM dongle's narrower, wire-format
 * one. `CHCAN`/`VIRTUAL` have no captured vendor bus-select sequence (issue
 * #30) — failing loudly beats silently skipping bus-select against a live
 * vehicle.
 */
fun CanBus.toOpComBus(): OpComBus = when (this) {
    CanBus.HSCAN -> OpComBus.HSCAN
    CanBus.SWCAN -> OpComBus.SWCAN
    CanBus.MSCAN -> OpComBus.MSCAN
    CanBus.CHCAN, CanBus.VIRTUAL ->
        throw IllegalArgumentException("no confirmed OP-COM bus-select sequence for $this")
}
