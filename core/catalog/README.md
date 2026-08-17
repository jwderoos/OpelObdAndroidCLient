# :core:catalog

Pure-Kotlin (JVM) module. Parses user-imported, decoded diagnostic catalogs
(produced by the separate OP-COM data file decoder from files the user legally
possesses) into the app's domain model: ECU definitions, measuring blocks,
fault codes, output tests, coding tables.

**No vendor data is committed to this repository** — only the parser, format
documentation (facts), and small synthetic test fixtures. The exact decoded
format is documented as part of the M3 GitHub issues.
