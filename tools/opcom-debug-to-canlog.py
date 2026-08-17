#!/usr/bin/env python3
"""Local-only converter: OP-COM debug capture files -> ooc-canlog v1.

OP-COM's "debug capturing" option writes one zlib-compressed file per
diagnostic screen visit into its DebugFiles directory. Each file is a
transcript of the USB serial conversation between the PC software and the
interface. The record format is documented in
docs/formats/opcom-debug-capture.md; the output format in
docs/formats/canlog.md.

Only CAN frames (interface commands 0x90 tx / 0x91 rx) become frame lines.
The source has no timestamps, so every frame gets t_ms = 0 (fast-forward
replay only). All non-CAN records are preserved as `#` comment lines so no
information is lost.

Usage:
  tools/opcom-debug-to-canlog.py DebugFiles/*            # -> logs/<name>.canlog
  tools/opcom-debug-to-canlog.py --out-dir logs FILE...
  tools/opcom-debug-to-canlog.py --dump FILE             # transcript to stdout

Converted logs contain real-vehicle data: keep them in git-ignored /logs/.
"""

import argparse
import pathlib
import re
import sys
import zlib


class ParseError(Exception):
    pass


def parse_records(data: bytes):
    """Yield payload bytes per record: [len u16 LE][payload][checksum]."""
    i = 0
    while i < len(data):
        if i + 2 > len(data):
            raise ParseError(f"truncated length header at offset {i}")
        length = int.from_bytes(data[i:i + 2], "little")
        end = i + 2 + length
        if end + 1 > len(data):
            raise ParseError(f"truncated record at offset {i}")
        payload = data[i + 2:end]
        checksum = data[end]
        calc = sum(data[i:end]) & 0xFF
        if checksum != calc:
            raise ParseError(
                f"bad checksum at offset {i}: {checksum:02x} != {calc:02x}")
        yield payload
        i = end + 1


def describe(payload: bytes) -> str:
    """One-line human description of a non-CAN record."""
    cmd, arg = payload[0], payload[1:]
    known = {
        0xAB: "get serial",
        0xEB: f"serial: {arg.decode('ascii', 'replace')}",
        0xAA: "get firmware version",
        0xEA: f"firmware version: {arg.hex(' ')}",
        0x83: "set rx filter slot "
              + (f"{arg[0]} = "
                 + ("off" if arg[1:5] == b"\xff\xff\xff\xff"
                    else f"{int.from_bytes(arg[1:5], 'little'):03X}")
                 if len(arg) >= 5 else arg.hex(" ")),
        0x71: "periodic msg A: " + _idframe(arg),
        0x72: "periodic msg B: " + _idframe(arg),
        0x9F: "cyclic tx msg: " + _idframe(arg),
        0xDF: "cyclic tx msg ack",
        0x7F: "keep-alive/status",
    }
    if cmd in known:
        return known[cmd]
    return f"cmd {cmd:02X}: {arg.hex(' ')}" if arg else f"cmd {cmd:02X}"


def _idframe(arg: bytes) -> str:
    if len(arg) < 5:
        return arg.hex(" ")
    can_id = int.from_bytes(arg[0:4], "little")
    return f"id {can_id:03X} dlc {arg[4]} data {arg[5:].hex(' ')}"


def convert(payloads, source_name: str):
    """Yield ooc-canlog lines for one capture."""
    yield "# ooc-canlog v1"
    yield f"# source: OP-COM debug capture '{source_name}'"
    yield "# note: source has no timestamps; fast-forward replay only"
    yield ""
    for p in payloads:
        cmd = p[0]
        if cmd == 0x90:  # tester -> bus, little-endian id
            can_id = int.from_bytes(p[1:5], "little")
            dlc = p[5]
            yield f"0 tx {can_id:X} " + " ".join(f"{b:02x}" for b in p[6:6 + dlc])
        elif cmd == 0x91:  # bus -> tester, big-endian id
            can_id = int.from_bytes(p[1:5], "big")
            dlc = p[5]
            yield f"0 rx {can_id:X} " + " ".join(f"{b:02x}" for b in p[6:6 + dlc])
        elif cmd == 0xD0:  # ack of 0x90, no information
            continue
        else:
            # "##" so lines with colons can never match the parser's
            # `# key: value` metadata pattern.
            yield f"## opcom: {describe(p)}"


def dump(payloads):
    for p in payloads:
        cmd = p[0]
        if cmd == 0x90:
            print(f"TX  {int.from_bytes(p[1:5], 'little'):03X}  {p[6:6 + p[5]].hex(' ')}")
        elif cmd == 0x91:
            print(f"RX  {int.from_bytes(p[1:5], 'big'):03X}  {p[6:6 + p[5]].hex(' ')}")
        elif cmd == 0xD0:
            continue
        else:
            print(f"--  {describe(p)}")


def slug(name: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("files", nargs="+", type=pathlib.Path)
    ap.add_argument("--out-dir", type=pathlib.Path, default=pathlib.Path("logs"))
    ap.add_argument("--dump", action="store_true",
                    help="print a human-readable transcript instead of converting")
    args = ap.parse_args()

    status = 0
    for f in args.files:
        raw = f.read_bytes()
        if not raw:
            print(f"skip (empty): {f}", file=sys.stderr)
            continue
        try:
            payloads = list(parse_records(zlib.decompress(raw)))
        except (zlib.error, ParseError) as e:
            print(f"skip ({e}): {f}", file=sys.stderr)
            status = 1
            continue
        if args.dump:
            print(f"##### {f}")
            dump(payloads)
        else:
            args.out_dir.mkdir(parents=True, exist_ok=True)
            out = args.out_dir / (slug(f.name) + ".canlog")
            out.write_text("\n".join(convert(payloads, f.name)) + "\n")
            frames = sum(p[0] in (0x90, 0x91) for p in payloads)
            print(f"{out}  ({frames} frames)")
    return status


if __name__ == "__main__":
    sys.exit(main())
