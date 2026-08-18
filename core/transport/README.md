# :core:transport

Pure-Kotlin (JVM) module. Defines how the rest of the app talks to adapter
hardware, without knowing which hardware.

- `CanFrame` — normalized CAN frame; adapter implementations own their wire codec.
- `ObdTransport` — connect/disconnect/send + incoming frame `Flow` + `ConnectionState`.

Implementations:

- `FakeEcuTransport` — scriptable request→response fake for tests and demo mode.
- `ReplayTransport` — recorded `.canlog` playback.
- `elm327/Elm327Transport` — ELM327-style adapters driven as a raw 11-bit CAN
  frame pipe (`ATCAF0`/`ATCFC0`, headers on), so the protocol layer's own
  ISO-TP stack runs unchanged. Talks to hardware through the tiny
  `Elm327Link` character-pipe interface; the Android Bluetooth SPP link lives
  in `:app`. Half-duplex by nature: only listens between a command and the
  next prompt, so request→response only. Multi-frame responses depend on the
  adapter's listen window (`ATAT1` adaptive timing is set explicitly); if a
  clone still truncates them, tune `ATST` next. Known-but-unhandled clone
  quirks, on purpose until observed on real hardware: prompt-before-banner
  after `ATZ`, and `STOPPED` (treated as noise — the session timeout covers
  it).
- `SwitchableObdTransport` — delegating transport whose backing
  implementation is swapped by adapter selection in settings.
