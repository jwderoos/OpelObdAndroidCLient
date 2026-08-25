# ooc-canlog v1 — recorded CAN session format

Line-based UTF-8 text format for recorded CAN sessions, replayed by
`ReplayTransport` in `:core:transport`. It is deliberately trivial: diffable,
greppable, and hand-writable for synthetic test fixtures.

Recorded **real-vehicle logs stay local and git-ignored** (`/logs/`); only
this spec and synthetic samples are committed. See
`core/transport/src/test/resources/canlog/synthetic-session.canlog`.

## Example

```
# ooc-canlog v1
# vehicle: Astra H
# adapter: none (hand-written sample)

0   rx 100 01
100 tx 246 02 10 92
112 rx 646 02 50 92
```

## Structure

1. **Header (required).** The first line must be exactly `# ooc-canlog v1`
   (surrounding whitespace ignored). Any other first line is a parse error.
2. **Metadata (optional).** Comment lines of the shape `# key: value` are
   collected as string metadata. Keys are free-form; unknown keys are
   preserved but not interpreted. Suggested keys: `vehicle`, `adapter`,
   `note`.
3. **Comments and blank lines.** Any other `#` line and any blank line is
   ignored, anywhere in the file.

   One comment shape has a convention (still ignored by the parser): the
   app's on-device session capture writes `# event <t_ms>: <text>` lines
   marking what the user did (`# event 4210: readDtcs ecu=Engine req=0x7E0
   resp=0x7E8`) so frames can be correlated with actions after the fact.
4. **Frame lines.** Everything else must be a frame line:

   ```
   <t_ms> <tx|rx> <id_hex> <byte> <byte> ...
   ```

   | Field | Meaning |
   |---|---|
   | `t_ms` | Decimal milliseconds since session start. Non-decreasing. |
   | `tx`/`rx` | Direction from the tester's point of view: `tx` = tester → bus, `rx` = bus → tester. |
   | `id_hex` | CAN identifier in hex, no `0x` prefix (11-bit or 29-bit). |
   | payload | 0–8 hex bytes, whitespace-separated, at most two digits each. |

Malformed lines are hard parse errors reported with their 1-based line
number — never silently skipped.

## Replay semantics

`ReplayTransport` walks the frames in order:

- `rx` frames are emitted to the client. In *original timing* mode each is
  delayed by its timestamp delta to the previous frame; in *fast-forward*
  mode delays are skipped.
- `tx` frames **gate** playback: it pauses until the client sends a frame
  byte-identical to the recorded one. A mismatched send is an error (and
  moves the transport to the `Error` state); a send after the script has
  ended is an error. This keeps the protocol stack honest against recorded
  sessions and is what the conformance suite (issue #7) builds on.

## On-device capture

With the debug toggle **Record sessions to file** on, the app writes every
connect→disconnect to `files/captures/<yyyyMMdd-HHmmss>/` (issue #29):

- `session.canlog` — this format, recorded by `RecordingTransport` around the
  active adapter transport, metadata `transport` / `app` / `device`.
- `usb.trace` — raw OP-COM link events, `<t_ms> <text>` per line (FTDI init
  timeline, every bulk write/read as hex). Empty for non-USB adapters.

Both are flushed per line, so a crash or unplugged dongle keeps everything
up to that point. **Share last capture** in the debug section zips the newest
directory. Real-vehicle captures are vendor/vehicle data: keep them under the
git-ignored `/logs/`, never commit them.

## Provenance note

The format is intentionally **not** based on OP-COM's own debug files —
those are zlib-compressed, vendor-internal structures (documented in
[opcom-debug-capture.md](opcom-debug-capture.md)). The local-only converter
is `tools/opcom-debug-to-canlog.py`; converted logs go to git-ignored
`/logs/`.
