# Decoded catalog format

This document describes the **plaintext output** of the
[OpelObdDataFileDecoder](https://github.com/jwderoos/OpelObdDataFileDecoder)
project — the files a user imports into this app. It records **format facts
only**: line grammar, section syntax, and field meanings. All examples below
are synthetic; no vendor data, texts, or identifiers are reproduced.

The container format and decryption pipeline that *produce* these files are
documented in the decoder repo (`docs/FORMAT.md` there). This app never sees
the encrypted originals — only the decoded plaintext tree.

## The catalog tree

The decoder mirrors the vendor's `LangData/<LANG>/` layout, appending `.txt`
to every decoded file:

```
<catalog root>/
  opeldata.txt                     vehicle / ECU address map (the entry point)
  ECULIST.txt                      legacy KW81/KW82 ECU list (pre-CAN, unused here)
  MeasuringBlocks/<KEY>.MBF.txt    live-data definitions, one file per ECU variant
  ErrorCodes/<KEY>.txt             fault-code texts, one file per ECU variant
  OutputTests/<KEY>.SCR.txt        actuator-test scripts, one file per ECU variant
  CANVARCODING/<KEY>.0x<DID>.txt   variant-coding tables (CAN ECUs)
  Programming/…  ProgrammingData/…  Special/…  SPECIAL_SCS/…   (out of scope, M5+)
```

`<KEY>` is the **catalog key**: an uppercase identifier (e.g. a synthetic
`EXAMPLIAENGZ99XX`) that links an `opeldata.txt` row to the per-ECU files.
Suffixed variants exist (`<KEY>_0x0801`, `<KEY>_GEN`, and decoder collision
suffixes like `.MBF_1.txt`).

## Common lexical rules

- Line-based text. Lines are terminated with CR LF or LF; parsers must accept
  both and ignore trailing whitespace (files often end with padding spaces).
- `;` at the start of a line begins a comment. Comments carry free-form notes
  (dates, authorship, vehicle names) and, by convention, the **first comment
  line names the protocol** of the ECU the file targets: `;KW2000`, `;KWCAN`,
  `;KW82`. Treat this as advisory metadata, not a reliable schema marker.
- Encoding is a single-byte extended-ASCII code page, **not UTF-8**. English
  catalogs are ASCII plus occasional `°` (0xB0); comments in other languages
  use Windows-125x code points. Decode as Windows-1252 with replacement on
  error; never assume UTF-8.
- Fields within a line are separated by TAB (`opeldata.txt`, fault-code
  symptom lines) or by comma (data-row tables). Empty trailing fields occur.

## `opeldata.txt` — the vehicle / ECU address map

The entry point: which vehicles exist, which ECUs each has, how to reach each
ECU, and which per-ECU catalog files apply. One record per line, TAB-separated.
Records are grouped by model year under banner comments; year headers repeat
per generation.

Common leading fields for every record:

| # | Field | Meaning |
|---|-------|---------|
| 1 | Model year | e.g. `2010 (A)` — year plus a letter tag |
| 2 | Vehicle | model name |
| 3 | Group | `Engine`, `Transmission`, `Chassis`, `Body`, `Infotainment System`, `Vehicle`, `Car Identification` |
| 4 | ECU name | display name (often includes engine code) |
| 5 | ECU system name | controller family / second display name |
| 6 | Protocol | `CAN`, `KW2000`, `KW82`, `IDENTKW2000`, or a pseudo-entry (below) |

The remaining fields depend on field 6:

**`CAN` records** (KWP2000-over-CAN / GMLAN — this app's target):

| # | Field | Meaning |
|---|-------|---------|
| 7 | Bus | `HSCAN`, `MSCAN`, `SWCAN`, or `VIRTUAL` |
| 8 | Bit rate | kbit/s as `dddd.d` — `0500.0` = 500 kbit/s, `0095.6` = 95.6 kbit/s, `0033.3` = 33.3 kbit/s |
| 9 | Request CAN ID | 32-bit hex, e.g. `0x000007E0` — tester → ECU |
| 10 | Secondary CAN ID | e.g. `0x000005E8`; observed as request ID + 0x400 on the response side of a paired scheme. Exact role (periodic/broadcast vs. diagnostic) is not yet established from the data alone. |
| 11 | Response CAN ID | e.g. `0x000007E8` — ECU → tester (request + 0x008 on the 0x7Ex scheme, request + 0x400 on the 0x2xx scheme) |
| 12 | Catalog key | links to `MeasuringBlocks/`, `ErrorCodes/`, `OutputTests/`, `CANVARCODING/` files. `????` = placeholder, no files exist |

`VIRTUAL` bus rows carry all-zero IDs and a `????` key: the entry is a menu
placeholder, not a reachable ECU. Special protocol values `IDENT`,
`GETECULIST`, `GETERRORCODESLIST` (in field 7 position after `CAN`) mark
built-in functions rather than addressable ECUs.

**`KW2000` / `KW82` records** (K-line, out of scope for this app):

| # | Field | Meaning |
|---|-------|---------|
| 7 | Baud rate | e.g. `10400`, `9600` |
| 8 | Catalog key | as above |
| 9 | Unknown numeric | small integer (7, 8, 12 observed) |
| 10 | ECU address | decimal K-line address |
| 11 | Init type | `1` = 5 bps, `2` = 200 bps(?), `3` = 10400 bps fast/slow init — per the file's own header comment |

**`IDENTKW2000`** rows have no further fields (car-identification pseudo-ECU).

Synthetic example:

```
2010 (A)	Examplia-A	Engine	Z 99 XX	Motronic X	CAN	HSCAN	0500.0	0x000007E0	0x000005E8	0x000007E8	EXAMPLIAENGZ99XX
2010 (A)	Examplia-A	Chassis	ABS/ESP	ABS/ESP	CAN	HSCAN	0500.0	0x00000243	0x00000543	0x00000643	EXAMPLIAABSESP
2010 (A)	Examplia-A	Body	Airbag	Airbag	KW2000	10400	EXAMPLIASRS	7	89	3
```

## `MeasuringBlocks/<KEY>.MBF.txt` — live data definitions

Two parts: a list of **measuring blocks** (screens of live values the user can
open), then one **data-row table** that all blocks index into.

### Block definitions

```
##MB01=Diagnostic Data List 1
[begin]
MEASDATA=04,03,04,10,11,
DISABLE_ALL
ENABLE_RANGE=0018-0050
[end]
```

- `##MB<nn>=<title>` — block number (two digits, 1-based) and display title.
- `[begin]` / `[end]` bracket the block body.
- `MEASDATA=` — comma-separated hex bytes (no `0x` prefix), with a trailing
  comma allowed. These are the identifier bytes the tester requests to
  populate the block; observed values fit KWP2000
  ReadDataByLocalIdentifier local IDs. The exact request framing is
  established per-protocol from recorded sessions, not from this file.
- `DISABLE_ALL` — flag line: start with every data row hidden.
- `ENABLE_RANGE=<from>-<to>` — 4-digit, 1-based, inclusive row range into the
  data table below: these rows are shown for this block.

### ECU identifier

A standalone `ID=<value>` line (e.g. `ID=00105`) may follow the blocks —
an ECU/variant identifier. Fault-code files carry a similar value in a
comment (`; ID=0X0A35`). Its use is not yet established; parsers should
store it verbatim.

### Data-row table

```
[MEASURING BLOCK DATA]
Coolant Temperature,string,[°C]
Fuel Pump Relay,string,Inactive,Active,**TAG1**
Battery Voltage,string,[V],**TAG2**
```

Comma-separated rows, **1-based line position is the row's identity** (what
`ENABLE_RANGE` points at):

| Field | Meaning |
|-------|---------|
| 1 | Display label |
| 2 | Type — only `string` observed |
| 3+ | Either a unit in square brackets (`[km/h]`, `[V]`, `[°C]`, `[%]`, `[RPM]`, `[ms]`, `[mV]`, `[kPa]`, `[°CA]`) for numeric values, or an enumeration of state labels (`Inactive,Active`, …) indexed by the raw value |
| last (optional) | `**NAME**` — an internal tag marking rows with special handling in the original software; parsers should preserve it but need not interpret it |

Duplicate labels across rows are normal (the same physical quantity appears
in several blocks at different row indices).

## `ErrorCodes/<KEY>.txt` — fault-code texts

```
;KWCAN
; ID=0X0A35
[MB]	EXAMPLIAENGZ99XX
P0016
-00	Crankshaft/Camshaft Correlation
B1000
-01	Some Symptom Text
-02	Another Symptom Text
```

- Optional `[MB]<TAB><key>` line links to the measuring-blocks file
  (freeze-frame data source).
- A **code line** is a bare DTC: letter `P`/`C`/`B`/`U` + 4 digits (5-digit
  numeric codes appear in legacy K-line files).
- Each code line is followed by one or more **symptom lines**:
  `-<symptom><TAB><text>` — a symptom/failure-type suffix (decimal, `-0`,
  `-00`…`-99` observed) and its display text. The full displayed fault is
  code + symptom, e.g. `P0016 00`.
- The symptom byte corresponds to the fault-type byte reported alongside the
  DTC by the ECU (KWP2000 ReadDTCByStatus).

## `OutputTests/<KEY>.SCR.txt` — actuator-test scripts

A sequence of named tests. Each test:

```
;KW2000
Return Pump Relay Test
[TESTTYPE=ONOFF]
[begin]
BeforeTest=	0x05,0xAA,0x03,0x01,0x03,0x04,0x00,0x00,
GoActivate=	0x04,0xAE,0x03,0x08,0x10,0x00,0x00,0x00,
DeActivate=	0x04,0xAE,0x03,0x08,0x00,0x00,0x00,0x00,
AfterTest=	0x02,0xAA,0x00,0x00,0x00,0x00,0x00,0x00,
[end]
```

- A non-comment, non-bracketed line before `[TESTTYPE=…]` is the **test
  title**.
- `[TESTTYPE=<type>]` — interaction model. Observed values:
  `ONOFF` (toggle on/off), `UPDOWN` (increase/decrease), `REPEAT`
  (re-triggerable pulse).
- The `[begin]`/`[end]` body holds **command records**, each a key and eight
  comma-separated `0x`-prefixed hex bytes (trailing comma allowed):
  - `BeforeTest=` — setup commands, in order (may repeat)
  - `GoActivate=` — command sent to activate
  - `DeActivate=` — command sent to deactivate
  - `AfterTest=` — teardown commands, in order (may repeat)
- Observed record shape: first byte = number of significant bytes that
  follow; remainder zero-padded to 8. The significant bytes are the raw
  diagnostic payload (e.g. `0xAE` matches KWP2000
  InputOutputControlByLocalIdentifier's service range). Byte-level semantics
  are protocol work, verified against recorded sessions — parsers store the
  records verbatim.

These are the four record keys observed across the entire corpus; parsers
should reject others (likely a corrupt file) rather than guess.

## `CANVARCODING/<KEY>.0x<DID>.txt` — variant-coding tables

Filename carries the coding **data identifier** (e.g. `.0x1201.`) the table
applies to. Structure:

```
;Examplia-A, DIS Variant, 0x1201
[DID_begin]
44,07
4C,04
42,01
[DID_end]

[VARIANT CODING DATA]
Language,string,German,English,Spanish
Board Computer,string,Not Present,Present
Check Control,string,Not Present,Present,**DISABLED**,**DISABLED**
```

- `[DID_begin]`/`[DID_end]` — list of `hexId,count` pairs (both without `0x`;
  count is two-digit decimal). Each pair names a sub-identifier within the
  coding block and a count associated with it. The exact mapping between
  these pairs and the data rows below (bit fields vs. row groups) is **not
  yet established**; parsers must preserve the list verbatim.
- `[VARIANT CODING DATA]` — comma-separated rows in the same shape as
  measuring-block data rows: label, `string`, then value labels indexed by
  the raw coded value. `**DISABLED**` entries are placeholder value slots
  that must not be offered for selection.

## Real-data variances (observed in a full OP-COM 08-2010 EN catalog)

Verified by `LocalCatalogConformanceTest` (point `OOC_CATALOG_DIR` at a local
decoded catalog; skips clean-room). Parsers tolerate all of these:

- Files end with stray NUL bytes after the final CRLF; structural lines may
  carry trailing spaces. A trailing TAB, however, delimits an empty field.
- `opeldata.txt`: 3 menu-only rows (AFL) with an empty protocol field and
  nothing after it; K-line rows with `????` baud rates or comma-list init
  types (`2,1`) — kept as unaddressable entries; `CHCAN` is a fifth bus value
  (Astra-J chassis expansion, 9 rows).
- MBF: standalone `SM=` metadata next to `ID=`; `PRE_MEAS=` setup commands
  (dynamicallyDefineLocalIdentifier) preserved per block; `MEASBLOCKCMD=` raw
  K-line frames for blocks without `MEASDATA`; a headerless variant (21
  files) with one top-level `MEASDATA` forming a single implicit block;
  `[TABLEnnn]` scaling-lookup sections (skipped, K-line only); one bare
  `[begin]` continuation group extending the previous block; enable ranges
  counting the table's trailing blank line(s) (clamped); stub files with
  blocks but no data table; one file whose preamble comment is missing `;`.
- ErrorCodes: an inline style (151 files) with `code<TAB>text` and no symptom
  sub-lines (symptom 0); symptom markers are HEX bytes (`-E0`), with `-?` /
  `-??` as any-symptom wildcards and `-D?` a low-nibble wildcard; codes span
  SAE letter codes with hex digits (`P253F`) and 2–6 digit legacy numerics;
  `[DEFAFAULT]` / `[SELECTIVE]` / `[SUZUKIDIAG]` variant-dispatch directives
  (skipped — semantics live in "Open questions"); one stray `0x0203` line.
- SCR: annotation lines inside blocks — `**display tag**`, `##pre-test
  instruction##`, `$$active label$$`, `@@post-test instruction@@` — preserved
  on `OutputTest` (the instructions are the safety preconditions issue #16
  must show).
- CANVARCODING: an `[MBA_begin]`..`[MBA_end]` section (4 files, skipped);
  one uncommented `REF 13` preamble; DID entries with a third field
  (`42,13,14`, ignored).

## Versioning and identity

Decoded files carry no schema version. This app stores, per import: the
source file's hash and import date; re-import replaces the stored catalog
(design doc, "Catalog import and storage").

## Open questions (to resolve against recorded sessions)

- Exact role of the secondary CAN ID (field 10) in `opeldata.txt`.
- Semantics of the `MEASDATA` byte list beyond "identifiers to request".
- Meaning of `ID=` values in MBF / error-code files.
- The `[DID_begin]` pair-to-row mapping in variant-coding tables.
- Field 9 of K-line records in `opeldata.txt`.
- `SM=` values in MBF files; the `[MBA]` section and third DID field in
  coding tables; the `[SELECTIVE]`/`[DEFAFAULT]` dispatch semantics
  (hardware-id → sub-catalog selection for display variants).

Parsers must treat these as opaque and preserve them, so nothing is lost for
later milestones.
