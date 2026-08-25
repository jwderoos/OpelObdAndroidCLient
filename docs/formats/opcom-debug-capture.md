# OP-COM debug capture files — reverse-engineered format notes

OP-COM's "debug capturing" option writes one file per diagnostic screen
visit into its `DebugFiles` directory, named
`<vehicle>_<platform>_<group>__<screen> NNNNN` (5-digit counter per screen).
Files may be empty (screen opened, no traffic). These notes document only
format *facts*, derived from captures of a 2009 Astra H; they contain no
vendor data. The captures themselves stay local and are never committed —
convert them with `tools/opcom-debug-to-canlog.py` into git-ignored
`/logs/`.

## Container

The whole file is a single **zlib** stream (raw `deflate` with the standard
2-byte `0x78 ..` header). Decompressed, it is a concatenation of records:

```
[length: u16 LE] [payload: `length` bytes] [checksum: u8]
```

`checksum` = sum of the two length bytes **and** the payload, mod 256.

The record stream is the USB serial conversation between the PC software
and the interface, in order, with no timestamps. The first payload byte is
a command/response code; a response to command `0xNN` uses code
`0xNN | 0x40` (e.g. `0x90` → `0xD0`, `0x83` → `0xC3`). `0x91` (received CAN
frame) and `0x7F` (keep-alive/status) arrive unsolicited.

## Observed commands

| Code | Direction | Payload after code | Meaning |
|---|---|---|---|
| `AB` | PC→IF | — | Get serial; `EB` response is ASCII serial (e.g. `OI…`) |
| `AA` | PC→IF | — | Get firmware version; `EA` response e.g. `01 99` |
| `AC` | PC→IF | `01` | Unknown init; `EC 01 00` |
| `74` | PC→IF | — | Unknown; response `B4 75 00` |
| `73` | PC→IF | subcmd + args | Init/config, subcommands `01`–`04` observed |
| `8E`,`84`,`82`,`20` | PC→IF | 1 byte | Bus/mode selection (`82 02` also used as a poll); `20` seen with `22`/`23`/`24` |
| `81` | PC→IF | 1 or 6 bytes | Bus parameters; 1 byte for HSCAN, 6 for SWCAN/MSCAN — see issue #30 |
| `83` | PC→IF | slot(1) + CAN id (u32 LE) | Set RX filter slot 1–8; `FF FF FF FF` = slot off |
| `71`,`72` | PC→IF | CAN id (u32 LE) + DLC + 8 data | Configure periodic message (tester present etc., sent by interface hardware — not visible as `90`/`91` records) |
| `9F` | PC→IF | CAN id (u32 LE) + DLC + 8 data | Define cyclic TX message (seen carrying KWP `21 xx` local-id list on the engine ECU) |
| `90` | PC→IF | CAN id (u32 **LE**) + DLC + 8 data | Transmit CAN frame; acked by `D0 <id-low> 00` |
| `91` | IF→PC | CAN id (u32 **BE**) + DLC + 8 data | Received CAN frame (note the endianness flip vs `90`) |
| `7F` | IF→PC | `7F 00` / `7F 7F` | Keep-alive/status |

Bytes beyond DLC in `90`/`91`/`71`/`72`/`9F` are padding.

## Traffic content

Frames carry standard diagnostics, so everything after the container layer
is covered by the protocol stack this project already implements:

- HS-CAN ECUs (e.g. engine at `7E0`/`7E8`): KWP2000-over-ISO-TP —
  `10`/`21…`/`30` transport frames, services `1A` ReadEcuIdentification,
  `21` ReadDataByLocalIdentifier, `7F` negative responses.
- MS-CAN / GMLAN body ECUs use 11-bit request ids like `245`/`251` with
  responses on `545`/`645`/`551` etc., matching the `83` filter slots set
  up beforehand.
- `fe 01 3e` broadcast on id `101` is the GMLAN all-nodes tester present.

## Conversion to ooc-canlog

`tools/opcom-debug-to-canlog.py` turns `90`/`91` records into `tx`/`rx`
frame lines (see [canlog.md](canlog.md)). The source has no timestamps, so
all frames get `t_ms = 0` and the logs are only meaningful in fast-forward
replay. Non-CAN records are preserved as `## opcom: …` comment lines
(double `#` so they can never be picked up as `# key: value` metadata).
