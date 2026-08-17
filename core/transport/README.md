# :core:transport

Pure-Kotlin (JVM) module. Defines how the rest of the app talks to adapter
hardware, without knowing which hardware.

- `CanFrame` — normalized CAN frame; adapter implementations own their wire codec.
- `ObdTransport` — connect/disconnect/send + incoming frame `Flow` + `ConnectionState`.

Planned implementations (see GitHub issues): `FakeEcuTransport` (scriptable
request→response for tests), `ReplayTransport` (recorded log playback),
ELM327 (when hardware is chosen).
