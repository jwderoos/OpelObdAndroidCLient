# :core:protocol

Pure-Kotlin (JVM) module. The GMLAN / KWP2000-over-CAN protocol stack for
~2004+ Opel vehicles. Depends only on `:core:transport` interfaces.

Three layers (see the design spec in `docs/superpowers/specs/`):

1. ISO-TP/GMLAN segmentation (single + multi frame, flow control) over `CanFrame`s.
2. Typed KWP2000 services (sessions, tester present, DTCs, measuring blocks,
   security access, output tests, coding) with negative-response mapping.
3. `DiagnosticSession` orchestration per ECU: keep-alive, one request in
   flight, timeouts, retries.

No vendor data lives here: ECU addresses and IDs come from the user-imported
catalog at runtime. Implementation is tracked in the M2 GitHub issues.
