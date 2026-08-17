# Opel OBD Client — Application Design

Date: 2026-08-17
Status: approved outline design

## Purpose

An Android application for diagnostics on Opel vehicles: reading and clearing
fault codes, live measuring-block data, output tests, and ECU coding. It uses
diagnostic definitions (ECU address maps, measuring blocks, fault code texts,
output tests, coding tables) that the user imports themselves, produced by the
separate OP-COM data file decoder project from files they legally possess.

**This repository is public and ships no vendor data, no keys, and no decoded
catalogs.** Only algorithms, format documentation (facts), and synthetic test
fixtures are committed. Recorded vehicle logs used for protocol development
stay local and git-ignored.

## Constraints and decisions

- **Data source:** user imports decoded plaintext catalogs (desktop decoder
  output) via the Storage Access Framework. No on-device decryption.
- **Hardware:** TBD. The transport layer is a generic abstraction; first
  implementations are replay/mock only. ELM327-style adapters are the
  anticipated first real hardware; the design must not preclude other serial
  bridges (USB OTG, custom ESP32, etc.).
- **Protocol target:** KWP2000-over-CAN / GMLAN (~2004+ Opel: Astra H, Vectra
  C, Corsa D era) first. K-line is out of scope for now.
- **Features:** read/clear DTCs, measuring blocks / live data, output tests,
  ECU coding. Security access (seed/key) must be supported in the final
  version even though the available logs do not exercise it.
- **Stack:** Kotlin, Jetpack Compose, Material3, min SDK 26, single-activity +
  Navigation Compose. Light multi-module Gradle layout.

## Module map

```
:app                 Compose UI, navigation, ViewModels, DiagnosticsManager
                     facade, Room persistence, SAF import, Android services
                     (adapter discovery, foreground connection holder), DI.
:core:transport      Pure Kotlin. ObdTransport interface, CanFrame model,
                     connection state machine. Implementations:
                     FakeEcuTransport, ReplayTransport (ELM327/USB later).
:core:protocol       Pure Kotlin. GMLAN/KWP2000-over-CAN protocol stack.
                     Depends only on :core:transport interfaces.
:core:catalog        Pure Kotlin. Parser for the decoded catalog format and
                     the diagnostic domain model.
```

Dependency direction: `:app → :core:protocol → :core:transport`;
`:app → :core:catalog`. Protocol and catalog are independent of each other:
protocol moves bytes and services; the catalog supplies meaning (ECU
addresses, local IDs, scaling, texts). The `DiagnosticsManager` facade in
`:app` composes them ("read measuring block 5 of the ABS ECU" = catalog lookup
+ protocol call + scaling).

## Transport layer (:core:transport)

- `ObdTransport`: suspend-friendly — `connect()`, `disconnect()`,
  `send(CanFrame)`, incoming frames as a `Flow<CanFrame>`.
- The interface carries normalized `CanFrame(id, data)` messages; each adapter
  implementation owns its codec (ELM327 AT text, OP-COM USB binary, …).
- Connection state machine (Disconnected / Connecting / Ready / Error) exposed
  as `StateFlow` for the UI.
- First implementations:
  - `FakeEcuTransport` — scriptable request→response map for unit tests.
  - `ReplayTransport` — replays recorded log files (original timing or
    fast-forward). The replay log file format is defined in this project.

## Protocol layer (:core:protocol)

Three layers:

1. **ISO-TP / GMLAN transport:** single-frame and multi-frame (first /
   consecutive / flow control) segmentation over `CanFrame`s. Request/response
   CAN ID pairs per ECU come from the imported catalog's address map.
2. **KWP2000 services:** typed request/response classes per service —
   StartDiagnosticSession, TesterPresent, ReadECUIdentification,
   ReadDTCByStatus, ClearDiagnosticInformation, ReadDataByLocalIdentifier
   (measuring blocks), SecurityAccess (seed/key), InputOutputControlByLocalId
   (output tests), WriteDataByLocalIdentifier (coding). Negative response
   codes map to a sealed error type.
3. **Session orchestration:** one `DiagnosticSession` per ECU — session setup,
   tester-present keep-alive scheduling, one request in flight, timeout and
   retry policy.

Security access uses a pluggable `SeedKeyAlgorithm` interface. If concrete
algorithms turn out to be proprietary they are not committed to this repo;
they are user-supplied, like the catalogs.

Protocol behavior is derived from and verified against recorded OP-COM
sessions replayed in JVM tests (logs local-only).

## Catalog import and storage (:core:catalog + :app)

- Import flow: desktop decoder → plaintext catalog files → copy to phone →
  SAF picker in the app → parser validates → domain model → Room DB.
- Domain model: `EcuDefinition`, `MeasuringBlock`, `FaultCode`, `OutputTest`,
  `CodingTable`.
- The exact decoded-format schema is documented as a first task (format facts
  only; no vendor data).
- Versioning: store source hash + import date; re-import replaces.
- Fallback: a generic OBD-II mode (standard PIDs, P0xxx codes) works with no
  catalog, so the app is usable and publicly demoable out of the box.

## UI (:app)

Screens:

1. **Home / vehicle** — connection status, adapter picker, catalog status,
   "scan all ECUs".
2. **ECU list** — catalog ECUs with presence/fault status after a bus scan.
3. **Fault codes** — per-ECU DTCs with catalog text, freeze frame, clear.
4. **Live data** — selected measuring blocks as list/gauges + simple chart,
   CSV logging.
5. **Output tests** — actuator tests behind explicit safety confirmations
   with preconditions shown.
6. **Coding** — read/edit/write coding values; behind security access, expert
   mode, and strong confirmation. Last milestone.
7. **Settings / import** — catalog import, adapter config, replay-log
   selection (debug).

ViewModels talk to the `DiagnosticsManager` facade. Live sessions survive
navigation via a foreground-service-backed connection holder (not needed in
replay mode; later milestone).

## Safety and error handling

- All writes (clear DTCs, output tests, coding) require explicit confirmation
  dialogs stating consequences; coding additionally requires an expert-mode
  toggle.
- Typed protocol failures (timeout, negative response with code, transport
  lost) → user-readable messages; sessions recover via tester-present or drop
  cleanly to disconnected.
- Replay/mock mode is clearly badged in the UI.

## Testing

- `:core:protocol`, `:core:catalog`: plain JUnit on the JVM.
- Protocol: `FakeEcuTransport` scripts + replayed real logs. Tests needing
  local logs skip when the logs are absent (clean-room pattern from the
  decoder repo).
- Catalog parser: small synthetic, hand-written fixture committed to the repo.
- Compose UI tests: later, not part of the initial issues.

## Milestones (GitHub issues)

- **M1 Foundation:** multi-module restructure + CI; transport abstractions +
  state machine; FakeEcuTransport + ReplayTransport (incl. log format).
- **M2 Protocol core:** ISO-TP segmentation; KWP2000 service types; session
  orchestration; log-derived conformance test suite.
- **M3 Catalog:** document decoded format; parser + domain model + fixture;
  Room persistence + SAF import + onboarding.
- **M4 Read-only features:** ECU scan/list; DTC read screen; live data +
  CSV; generic OBD-II fallback.
- **M5 Write features & hardware:** clear DTCs; output tests; SecurityAccess
  plugin interface; coding; ELM327 transport; foreground connection holder.
- Plus README (purpose, no-vendor-data policy, decoder repo link).
