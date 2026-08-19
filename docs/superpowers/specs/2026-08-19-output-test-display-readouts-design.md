# Live display-tag readouts during output tests (issue #24)

Status: approved design, 2026-08-19.
Follow-up to the control-only output-test v1 (issue #16).

## Goal

While a catalog output test runs, show live values for the test's `**TAG**`
display tags, decoded from GMLAN periodic-data (DPID) broadcasts that arrive
on the ECU's secondary CAN ID, interleaved with the 0xAE control traffic.

Non-goal: constructing periodic-data requests. The output-test scripts
already carry them verbatim (`AA 03 <dpids…>` in BeforeTest, `AA 00` stop in
AfterTest); the app keeps sending records byte-for-byte and only gains the
ability to *decode* what comes back.

## Protocol evidence

From the recorded AHL/AFL session (`logs/2009-9-astra-h-body-ahl-afl-…`)
correlated with the decoded Astra-H AFL catalog (`HASTRAAFL.SCR` / `.MBF`,
local, not committed):

- The tester sends `AA 03 01 04 05` on the request ID (0x249). `0xAA` is
  GMLAN ReadDataByPacketIdentifier; `0x03` a scheduling rate; the rest are
  DPID ids.
- There is **no ISO-TP/USDT positive response** to $AA. The reply is a
  stream of raw single frames (UUDT) on the secondary CAN ID (0x549):
  byte 0 = DPID id, bytes 1–7 = data. Example: `04 02 0e 00 00 00 00 00`.
- `AA 00` stops the broadcasts.
- The secondary CAN ID is the middle of the three IDs in the opeldata
  vehicle list (e.g. 0x249 / 0x549 / 0x649) and is already parsed into
  `EcuDefinition.Address.secondaryId`.

### Tag → DPID mapping rule

Established from the AFL catalog/log correlation, to be re-verified against
the other recorded logs (REC/IPC/UEC) in conformance tests:

- `MEASDATA` in an MBF block = one scheduling-rate byte (0x03 observed)
  followed by the block's DPID ids.
- The block's enabled rows (`ENABLE_RANGE`, in table order) spread across
  those DPIDs at **7 rows per DPID, one byte per row** — the same
  one-byte-per-row convention `MeasuringBlockDecoder` already uses.
- A row at position `i` (0-based) within the enabled range maps to
  `dpid = dpids[i / 7]`, `byteIndex = i % 7` (0-based within the 7 data
  bytes).

Hand-verified: `**AFL1**`–`**AFL3**` are positions 4–6 (0-based) of MB02
(`MEASDATA=03,04,05,06`, rows 9–19), so they land at bytes 4–6 of DPID 04's
data — matching the recorded DPID 04 frames.

## v1 bug fixed by this work

`startOutputTest` / `OutputTestRun.send` push every command record through
`session.execute(RawRequest(...))`, which waits for a positive response.
$AA never sends one, so any test containing an `AA` record (all three AFL
tests, for example) retries and fails with `ResponseTimeout` today. The
dispatch below fixes this.

## Design

### `:core:protocol`

Two additions, no changes to existing behavior:

1. `DiagnosticSession.sendWithoutResponse(payload: ByteArray)` — takes the
   request lock, checks the session is usable, sends via the ISO-TP channel,
   resets the keep-alive idle timer, and returns without waiting for a
   reply. For services whose reply is out-of-band.
2. New `gmlan/` sub-package with `PeriodicDataMonitor`: wraps
   `ObdTransport.incomingFrames`, filters on one CAN id (the secondary/UUDT
   id), and emits `DpidRecord(dpid: Int, bytes: ByteArray /* 7 */)`.
   Stateless cold flow. It coexists with the `IsoTpChannel` because both
   only observe `incomingFrames`; neither consumes the other's frames.

The knowledge "$AA has no USDT response" is applied at the record-dispatch
site in `:app` (below); `RawRequest`'s contract is untouched.

### `:core:catalog`

New `DisplayTagBindings` resolver: given a `MeasuringBlockCatalog` and a
test's display tags, produce one `TagBinding(tag, row, dpid, byteIndex)`
per tag, using the mapping rule above. Tags with no matching tagged data
row resolve to nothing (skipped).

Display formatting of a raw byte reuses `MeasuringBlockDecoder`'s existing
state-label / numeric / placeholder logic, exposed rather than duplicated.

Recorded observation, **out of scope**: `pollMeasuringBlock` currently
treats `measData[0]` as a ReadDataByLocalIdentifier LID; under the
rate-byte reading that first read is questionable. Filed as a follow-up
issue instead of touching live data here.

### `:app`

- `EcuScanTarget` gains a nullable `secondaryId`; ViewModels already hold
  the `EcuDefinition` addresses that carry it. The OBD-II fallback passes
  null.
- `OutputTestsViewModel` resolves the running test's tag bindings from the
  ECU's measuring-block catalog and passes them to
  `DiagnosticsManager.startOutputTest`.
- The record-send loop (both the before-test loop in `startOutputTest` and
  `OutputTestRun.send`) dispatches on the record's service byte:
  `0xAA` → `sendWithoutResponse`, everything else →
  `execute(RawRequest(...))` as today.
- `OutputTestRun` gains `val readouts: StateFlow<List<TagReadout>>`
  (`TagReadout` = binding + raw byte + display string, initially the
  no-data placeholder). A collector coroutine in the run's scope feeds it
  from a `PeriodicDataMonitor` on the target's `secondaryId`.
- Degradation: no display tags, no secondary id, or no measuring-block
  catalog → `readouts` stays empty and the run behaves exactly as v1.

### UI

The output-test run panel (`OutputTestsScreen`) shows one readout row per
tag — label plus live value, styled like the live-data screen rows —
visible from before-test through finish. No new screens or navigation.

## Testing

TDD, replay-first, per project convention:

- **Protocol conformance** (real logs, skip without `/logs/`): replay the
  AHL session — the `AA` record is sent verbatim without retry/timeout and
  `PeriodicDataMonitor` emits the expected `DpidRecord`s from the 0x549
  lines. Cross-check the mapping rule against the REC/IPC/UEC logs where
  they contain $AA traffic.
- **Committed synthetic fixtures** (no vendor data): a hand-written
  `.canlog` plus invented MBF/SCR content mirroring the AFL *structure*
  for unit tests of the monitor, the binding resolver, and the end-to-end
  readout flow.
- **Regression**: an output test containing an `AA` record completes
  without `ResponseTimeout`.
- **App layer**: `DiagnosticsManager` test over `ReplayTransport`
  asserting `readouts` updates while the test runs.

## Out of scope

- Migrating the live-data screen to DPID streaming.
- The `measData[0]`-as-LID question in `pollMeasuringBlock` (follow-up
  issue).
- Interpreting scheduling rates beyond passing records through verbatim.
- ELM327 hardware validation: the adapter init sets no receive filter, so
  secondary-ID frames reach the app, but how continuously a real ELM327
  forwards unsolicited frames between request windows is a
  hardware-validation risk that replay cannot answer.
