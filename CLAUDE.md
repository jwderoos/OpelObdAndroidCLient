# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Working rules

- **Do not use git worktrees** (no `EnterWorktree`, no `isolation: "worktree"` for agents, no manual `git worktree` commands). Work directly in this checkout.
- **No-vendor-data policy (this repo is public, kept clean-room):** never commit vendor data, keys, decoded catalogs, recorded real-vehicle logs, or proprietary seed/key algorithms. Only algorithms, format documentation, and small synthetic test fixtures are committed. `/logs/` and `/DebugFiles/` are git-ignored on purpose — leave them local.

## Commands

Requires JDK 17. The `:core:*` modules are plain Kotlin/JVM (no Android SDK needed); only `:app` needs the Android SDK.

```sh
./gradlew build                          # build everything + all unit tests (what CI runs)
./gradlew :core:protocol:test            # one module's tests
./gradlew :core:protocol:test --tests "nl.jwdr.ooc.protocol.isotp.IsoTpChannelTest"   # single test class
./gradlew :app:testDebugUnitTest         # app JVM unit tests
./gradlew :app:assembleDebug             # build the APK
```

Tests that replay recorded real-vehicle logs (e.g. `RecordedLogConformanceTest`) skip automatically when `/logs/` is absent — that is expected on clean checkouts and in CI.

## Architecture

Android diagnostics app for KWP2000-over-CAN / GMLAN era Opel vehicles (~2004+). Kotlin, Jetpack Compose, Material3, min SDK 26, single-activity + Navigation Compose. Package root: `nl.jwdr.ooc`.

Multi-module Gradle layout with a strict dependency direction:

- `:core:transport` — pure Kotlin. `ObdTransport` interface, `CanFrame`, connection state machine, `FakeEcuTransport` and `ReplayTransport` (replays `.canlog` files). No dependencies on other modules.
- `:core:protocol` — pure Kotlin. GMLAN/KWP2000 stack: ISO-TP segmentation (`isotp/`), KWP2000 service types (`kwp2000/`), session orchestration (`session/`). Depends only on `:core:transport`.
- `:core:catalog` — pure Kotlin. Parser for the decoded catalog format plus the diagnostic domain model (fault codes, measuring blocks, output tests, coding tables). Independent of transport/protocol.
- `:app` — Compose UI, ViewModels, Room persistence of imported catalogs (`catalogstore/`), SAF catalog import, and the `DiagnosticsManager` facade (`diagnostics/`) that composes protocol + catalog: the protocol moves bytes, the catalog supplies meaning.

The app ships no vehicle-specific data; users import decoded catalogs produced by the separate [OpelObdDataFileDecoder](https://github.com/jwderoos/OpelObdDataFileDecoder) project. Without a catalog a generic OBD-II fallback mode works.

### Protocol verification via replay

Protocol behavior is derived from and verified against recorded OP-COM sessions replayed in JVM tests (`core/protocol/src/test/.../conformance/`). Key references:

- `docs/formats/canlog.md` — the line-based `.canlog` replay format (hand-writable for synthetic fixtures)
- `docs/formats/opcom-debug-capture.md` + `tools/opcom-debug-to-canlog.py` — converting raw OP-COM debug captures (local `/DebugFiles/`) into `.canlog` files (local `/logs/`)
- `docs/catalog-format.md` — the decoded catalog format `:core:catalog` parses
- `docs/superpowers/specs/2026-08-17-opel-obd-client-design.md` — the full design document

## Safety-relevant conventions

All write operations toward the vehicle (clearing fault codes, output tests, coding) require explicit in-app confirmation; coding additionally sits behind an expert-mode toggle. Preserve these gates when adding features.
