package nl.jwdr.ooc.transport.opcom

/**
 * The CAN buses the OP-COM clone's bus-select command block distinguishes
 * (`docs/formats/opcom-debug-capture.md`'s `20`/`81`/`84` commands). Deliberately
 * narrower than the catalog's `CanBus` (which also has `CHCAN`/`VIRTUAL`, for
 * which no vendor sequence has been captured) — this module has no dependency
 * on `:core:catalog`, so callers map their own bus type onto this one.
 */
enum class OpComBus { HSCAN, SWCAN, MSCAN }
