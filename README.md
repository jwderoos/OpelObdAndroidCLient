# Opel OBD Client

An Android diagnostics app for Opel vehicles of the KWP2000-over-CAN / GMLAN
era (~2004+: Astra H, Vectra C, Corsa D, and similar). It reads and clears
fault codes, shows live measuring-block data, runs output tests, and — behind
explicit safety gates — supports ECU coding.

The app itself ships **no vehicle-specific diagnostic data**. It gets its
meaning (ECU addresses, measuring-block scaling, fault-code texts, output
tests, coding tables) from decoded catalogs that **you import yourself**,
produced from files you legally possess with the separate
[OpelObdDataFileDecoder](https://github.com/jwderoos/OpelObdDataFileDecoder)
project. Without a catalog, a generic OBD-II fallback mode (standard PIDs,
P0xxx codes) still works out of the box.

## No-vendor-data policy

This repository is public and is kept clean-room:

- **No vendor data, no keys, no decoded catalogs** are committed — only
  algorithms, format documentation (facts), and small synthetic test
  fixtures.
- Recorded vehicle logs used for protocol development stay local and
  git-ignored. Tests that need them skip automatically when the logs are
  absent.
- If concrete security-access seed/key algorithms turn out to be
  proprietary, they are not committed either; they are user-supplied through
  a plugin interface, like the catalogs.

## Architecture

Light multi-module Gradle layout; Kotlin, Jetpack Compose, Material3,
min SDK 26, single-activity + Navigation Compose.

```
:app               Compose UI, navigation, ViewModels, DiagnosticsManager
                   facade, Room persistence, SAF catalog import, Android
                   services, DI.
:core:transport    Pure Kotlin. ObdTransport interface, CanFrame model,
                   connection state machine. Implementations:
                   FakeEcuTransport, ReplayTransport (ELM327/USB later).
:core:protocol     Pure Kotlin. GMLAN/KWP2000-over-CAN protocol stack:
                   ISO-TP segmentation, KWP2000 service types, session
                   orchestration. Depends only on :core:transport.
:core:catalog      Pure Kotlin. Parser for the decoded catalog format and
                   the diagnostic domain model.
```

Dependency direction: `:app → :core:protocol → :core:transport` and
`:app → :core:catalog`. Protocol and catalog are independent of each other:
the protocol moves bytes and services, the catalog supplies meaning, and the
`DiagnosticsManager` facade in `:app` composes them.

Protocol behavior is derived from and verified against recorded OP-COM
sessions replayed in JVM tests (see `docs/formats/` for the
[canlog replay format](docs/formats/canlog.md) and the
[OP-COM debug-capture notes](docs/formats/opcom-debug-capture.md)).
Hardware notes for a module-based OBD2 interface live in
[docs/hardware.md](docs/hardware.md).

The full design lives in
[docs/superpowers/specs/2026-08-17-opel-obd-client-design.md](docs/superpowers/specs/2026-08-17-opel-obd-client-design.md).

## Building

Requires JDK 17 (the Android SDK is only needed for `:app`; the `:core:*`
modules are plain Kotlin/JVM).

```sh
./gradlew build                 # build everything and run unit tests
./gradlew :core:protocol:test   # protocol tests only
```

CI runs `./gradlew build` on every push and pull request.

## Safety disclaimer

This software talks to safety-relevant vehicle systems. Use it at your own
risk, on your own vehicle, with the ignition state and environment the
respective function calls for.

- All write operations (clearing fault codes, output tests, coding) require
  explicit in-app confirmation; coding additionally requires an expert-mode
  toggle.
- Output tests actuate real components (fans, pumps, valves, relays). Never
  run them while driving; observe the preconditions the app shows.
- Incorrect ECU coding can leave a vehicle immobile or behaving
  unpredictably. Only write values you understand.

The authors accept no liability for damage to vehicles, property, or persons
resulting from use of this software.
