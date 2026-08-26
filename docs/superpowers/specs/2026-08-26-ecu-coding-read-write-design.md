# ECU coding read/write (raw bytes) + expert mode

Status: approved design, 2026-08-26.
Design for issue #18, the M5/last-milestone screen in
`docs/superpowers/specs/2026-08-17-opel-obd-client-design.md`.

## Context

Issue #17 (`docs/superpowers/specs/2026-08-19-security-access-unlock-design.md`)
built `DiagnosticSession.unlock()` as a prerequisite for this work and
explicitly deferred the facade method, plugin registration, expert-mode UI,
and any concrete algorithm to #18.

`:core:catalog` already parses `CANVARCODING/<KEY>.0x<DID>.txt` files
(`CodingTable`, `CodingTableParser`) into `didEntries: List<DidEntry>`
(`hexId,count` pairs) and `rows: List<CodingRow>`. `docs/catalog-format.md`
("Open questions") flags the `[DID_begin]` pair-to-row mapping as **not yet
established** — there is no known way to say "row 2 (Board Computer) is bit
3 of DID-entry 0x44" today. This scopes the milestone: raw hex read/write
per DID-entry, not semantic per-row editing. `CodingRow`/`CodingValue`
remain parsed and stored but are not consumed by this UI; the row-mapping
question is left for future work (likely partly reverse-engineered in the
sibling OpelObdDataFileDecoder repo, the way the live-data ruleset was).

### Protocol evidence

`logs/2009-9-astra-h-body-uec-underhood-electrical-centre-00002.canlog`
(local, not committed) captures a real coding write on the UEC body ECU.
Reading it settles the read/write mechanics:

- A coding table's record is **not** one read/write against its filename's
  2-byte DID. It is split across multiple single-byte local identifiers —
  this capture used 0x2F, 0x41, 0x43, 0x44, 0x45, 0x46, 0x47, 0x50, 0x60 —
  each read via `ReadECUIdentification` (SID 0x1A, already implemented)
  and written via `WriteDataByLocalIdentifier` (SID 0x3B, already
  implemented). This matches the shape of `CodingTable.didEntries` exactly:
  `id` is the local-identifier byte, `count` its record length.
- Flow: read every entry -> write every entry -> re-read every entry to
  verify. Each write is first NAK'd with `KwpError.ResponsePending` (0x78)
  before the real positive ack; `DiagnosticSession` already retries on this
  (`DiagnosticSession.kt:215`), so no new protocol-level handling is needed.
- **No SID 0x27 (`SecurityAccess`) frames appear anywhere**, despite the
  user having entered a security code on OP-COM's "Security Code (CarPass)"
  screen for this session (see `[[opcom-security-access-capture-gap]]`
  memory). Working theory: CarPass is a separate, proprietary Opel/GM
  anti-theft check that never touches the diagnostic bus, distinct from
  generic KWP2000 `SecurityAccess`.

**Decision:** `writeCoding` calls `DiagnosticSession.unlock()` anyway before
writing — harmless if the ECU doesn't require it (`AlreadyUnlocked` just
passes through) — but the real safety gate for this feature is the in-app
confirmation dialog plus the expert-mode toggle, not the wire-level unlock.
CarPass itself (and any concrete seed/key algorithm) stays out of scope,
per the no-vendor-data policy already applied to `SecurityAccess`.

## `:core:protocol`

No new code. Reused as-is: `ReadECUIdentification`,
`WriteDataByLocalIdentifier`, `DiagnosticSession.unlock`, and the existing
`ResponsePending` auto-retry.

New test: `conformance/CodingConformanceTest.kt`, replaying
`logs/2009-9-astra-h-body-uec-underhood-electrical-centre-00002.canlog`
(skips without `/logs/`, same as `RecordedLogConformanceTest`). Asserts the
read-all / write-all / re-read-all sequence for a subset of the recorded
DID entries, including a `ResponsePending`-then-ack write.

## `:core:catalog`

No changes.

## `:app`

### `CatalogRepository`

Two additions mirroring the existing `measuringBlockKeys()`/
`outputTestKeys()` pair:

```kotlin
/** Catalog keys that have a coding file (i.e. offer ECU coding). */
suspend fun codingTableKeys(): Set<String> =
    dao.fileKeysFor(CatalogFileKind.CODING.name).toSet()

/** Every coding table for [catalogKey] — one per `.0x<DID>.txt` file. */
suspend fun codingTablesFor(catalogKey: String): List<CodingTable> =
    dao.filesFor(CatalogFileKind.CODING.name, catalogKey).map {
        CodingTableParser.parse(CatalogText.decode(it.content), it.fileName)
    }
```

Unlike `measuringBlocksFor`/`outputTestsFor` ("first file wins" among
variant files of the same kind), every coding file for a key is a distinct
DID table, not a variant of the others — `codingTablesFor` returns all of
them.

### `DiagnosticsManager`

```kotlin
suspend fun readCoding(target: EcuScanTarget, table: CodingTable): CodingReadResult

suspend fun writeCoding(
    target: EcuScanTarget,
    table: CodingTable,
    edits: Map<Int, ByteArray>,
): CodingWriteResult
```

Both use the existing `withSession` helper, same pattern as
`readDtcs`/`clearDtcs`.

```kotlin
data class CodingEntryRead(val id: Int, val bytes: ByteArray)
data class CodingReadResult(val entries: List<CodingEntryRead>)

sealed interface CodingEntryOutcome {
    data class Written(val id: Int, val verifiedBytes: ByteArray) : CodingEntryOutcome
    data class NotAttempted(val id: Int) : CodingEntryOutcome
    data class Failed(val id: Int, val reason: String) : CodingEntryOutcome
    data class VerificationMismatch(
        val id: Int,
        val expected: ByteArray,
        val actual: ByteArray,
    ) : CodingEntryOutcome
}
data class CodingWriteResult(val outcomes: List<CodingEntryOutcome>)
```

`readCoding`: `ReadECUIdentification` per `table.didEntries`, in order.
Propagates `SessionException` on failure like `readDtcs` does — no wrapped
per-entry result, since the partial-state risk that motivates
`CodingWriteResult`'s outcome list only applies to writes.

`writeCoding` requires every key in `edits` to be one of `table.didEntries`'
ids (the UI only ever produces edits from rows it rendered, so this is an
`IllegalArgumentException` precondition, not a runtime outcome to display):

1. `session.unlock(...)` once. On `SessionException.UnlockFailed`, return a
   result with every edited id as `Failed` (nothing written) rather than
   throwing past the caller — the UI needs to show this like any other
   write failure, not crash.
2. Walk `table.didEntries` in file order. For each id present in `edits`,
   send `WriteDataByLocalIdentifier`. On the **first** failure (negative
   response or transport error), stop; every remaining edited id becomes
   `NotAttempted`. This is the core safety property: a half-applied coding
   record is the real risk here, not a single bad value, so nothing after
   a failure is attempted silently.
3. Re-read every entry in the table (edited or not) via
   `ReadECUIdentification`. For edited ids, compare the re-read bytes to
   the intended write; a mismatch becomes `VerificationMismatch` instead of
   `Written` — a write that appears to ack but doesn't stick must not be
   reported as success.

### UI — new `ui/coding/` package

`CodingScreen.kt` / `CodingViewModel.kt`, structured like
`ui/faultcodes`/`ui/outputtests`:

- ECU picker filtered to `codingTableKeys()` (same convention as the Live
  Data and Output Tests pickers).
- Table picker when an ECU has more than one coding file.
- Row list: one row per `DidEntry`, showing id, byte count, and current hex
  (from `readCoding`, fetched on table open).
- Editing a row opens a raw hex input for just that entry.
- A "Review changes" panel lists old -> new hex for every edited row before
  the confirm dialog is reachable.
- `WriteCodingConfirmationDialog`, modeled on `FaultCodesScreen`'s
  `ClearConfirmationDialog`: states the consequence (which ids will be
  rewritten), requires explicit confirm.
- Result display renders each `CodingEntryOutcome` distinctly — Written /
  Not attempted / Failed / Verification mismatch — never collapses them
  into a single pass/fail.

The Coding entry point in navigation is shown only when expert mode is on;
the confirm path checks it again as defense in depth (same "expert mode
toggle" convention the design doc already calls for on this screen).

### Expert mode setting

Mirrors the existing `verboseOpComLogging` pattern in `AppContainer`
(`OocApplication.kt`) — plain `SharedPreferences`, not the debug-capture
prefs (this is a safety setting, not diagnostic instrumentation):

```kotlin
private val expertPrefs by lazy {
    appContext.getSharedPreferences("expert", Context.MODE_PRIVATE)
}
private val _expertMode by lazy {
    MutableStateFlow(expertPrefs.getBoolean(PREF_EXPERT_MODE, false))
}
val expertMode: StateFlow<Boolean> by lazy { _expertMode }
fun setExpertMode(enabled: Boolean) {
    _expertMode.value = enabled
    expertPrefs.edit().putBoolean(PREF_EXPERT_MODE, enabled).apply()
}
```

Default off. Exposed via a small `ExpertModeViewModel` (same shape as
`DebugViewModel`) and a new `ExpertModeSection.kt` composable in
`SettingsScreen`, alongside `AdapterSection`/`DebugSection`.

## Testing

- `:core:protocol`: `CodingConformanceTest` off the UEC 00002 log (skips
  without `/logs/`).
- `:app`: `FakeEcuTransport`-scripted tests for `readCoding`/`writeCoding`
  — happy path; unlock failure (nothing written); first-entry-fails stops
  the batch (remaining `NotAttempted`); a write acks but re-read disagrees
  (`VerificationMismatch`).
- No `:core:catalog` test changes — the parser is unchanged.

## Out of scope

- Semantic row-level editing/labels (blocked on the unresolved DID -> row
  mapping; tracked as a follow-up, not part of #18).
- The CarPass algorithm, or any concrete `SeedKeyAlgorithm` implementation
  (proprietary; no-vendor-data policy, same reasoning as #17).
- Solving the DID -> row mapping itself.
