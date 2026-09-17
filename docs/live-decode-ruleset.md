# The GMLAN live-data decode ruleset

`app/src/main/assets/live_decode_rules.json` tells the app which byte of which
DPID broadcast a measuring-block row reads, and how to turn that byte into a
value. Without it the app can show live data only as raw bytes.

## Why the app needs it at all

The decoded catalog carries a row's label, unit and state names, but no byte
layout. The mapping from a DPID's UUDT payload to a row lives only in the
vendor tool, as hand-written per-ECU routines dispatched on the catalog's
numeric `ID=`. Each routine hardcodes, per DPID, which byte a row reads and
whether it is a bit test, a state index or a scaled number. Byte offsets are
arbitrary per ECU, so no positional rule can recover them.

## What is shipped, and what is not

Only numbers: `row`, `dpid`, `byte`, a rule type, and the arithmetic
(`factor`, `offset`, `mask`, `eq`). No label text, no symbol names, no catalog
strings — the labels come from the catalog the user imports themselves. The
conversion arithmetic is ordinary OBD scaling, not vendor content, which is
what keeps this repo inside its no-vendor-data policy.

| rule type | meaning |
|---|---|
| `num` | value = `field × factor + offset` |
| `state` | the raw byte indexes the row's state list |
| `mstate` | `(byte & mask) >> tz(mask)` indexes the row's state list |
| `table` | `(byte & mask) >> tz(mask)` looks up `map` to get a state index |
| `flag` | two-state: true when `(byte & mask) == eq` |
| `raw` | show the raw byte; no decode claimed |

A `num` rule's field is one unsigned byte unless it says otherwise. It may
instead span `width` bytes (2 or 3) starting at `byte`, ordered by `endian`,
and may be narrowed by `mask`, then `shift`, then `signed`. That order is the
one the vendor's own routines apply, and `signed` is a two's complement over
eight bits because the vendor signs only single bytes. A field that would run
past the end of its DPID payload reads as no-data rather than as a partial
value.

## Provenance

Generated in the separate `OpelObdToolExploration` project, which decompiles
the vendor binary and is kept private for that reason. Current asset comes
from its commits `73019b6` and `8dd4028` (2026-09-17): **9141 rules across
133 catalogs**, merged from the two files that project emits — its
`live_decode_rules.json` plus the `live_decode_rules_extended.json` rows that
need the wider `num` fields and `table` lookups described above.

Rows are bound to their catalog row *explicitly*, not by counting. The
vendor's row table is a fixed-stride array, so once the decompiler is told the
string-assignment helper's real register signature, every assignment names the
row slot it writes. This matters because handlers do not lay rows out in
catalog `MEASDATA` order — engine modules interleave one DPID's rows across
the whole table — so the earlier count-based approach could never place them.

Regenerating the asset is documented in that repo's
`reverse-engineering/decompiled/measblock_handlers_SUMMARY.md`
("Update 2026-09-17"), which also lists per-catalog coverage and every
assumption the rules make. Copy its `live_decode_rules.json` over the asset
here; nothing in this repo transforms it.

## Coverage is per row, not per ECU

Most covered catalogs are *partially* covered. A row is withheld rather than
guessed when its decode still does not fit the schema above — what remains is
mostly two payload bytes combined arithmetically, range tests on words, and
values the decompiler could not type. 59 catalogs are withheld whole, most
because they are K-line modules that never reach this code path, or because
the only handler under that numeric ID belongs to a different variant.

Withholding is deliberate: a raw byte shown next to a unit reads as a
measurement, and a wrong measurement is worse than a dash in a diagnostics
tool.

## Verification

The generator's alignment check proves *which row* a byte feeds. It does not
prove the scale is right — a float-width bug once passed that check while
reporting 9.6 V for a 12.6 V battery. Treat any catalog the coverage table
marks `gate-passed, unverified` as exactly that, and confirm on real hardware
before trusting a reading (issue #48).

The reliable smoke test for any voltage row: it should sit near 12.6 V with
the engine off and rise to about 14 V once the alternator is charging.

