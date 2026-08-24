# OP-COM USB handshake — investigation handover

Status as of 2026-08-24 (see "Update 2026-08-24" section below for the
latest). Goal: get `UsbSerialOpComLink`/`OpComTransport.connect()`
past the initial `AB`/`AA`/`AC` init handshake with the real OP-COM clone
(firmware 1.99, custom FTDI VID:PID `0403:4F50`) over USB-OTG from an Android
phone (Samsung SM-S908B). Currently still fails: every `connect()` attempt
gets a `ConnectionState.Error` (timeout), never `Ready`.

This is a separate, still-open issue from the app-crash fix (already
committed: `OpComTransport.readLoop()` now catches exceptions from a closed
port instead of crashing the app — see git log, commit
"Don't crash the app when the OP-COM reader hits a closed port"). That fix is
done, tested, and not part of what's described below.

## What's confirmed correct (don't re-litigate these)

All of the following were verified against a **real USB packet capture** of
the actual vendor software (`OP-COM.exe`) talking to the same physical
interface — not guesswork:

- **Baud rate is exactly 500000.** Confirmed two ways: public docs for this
  VID:PID, and the real capture's `SET_BAUD_RATE` control transfer
  (`wValue=0x0006` → divisor 6 → 3,000,000 / 6 = 500,000 exactly).
- **Record framing and checksum algorithm are exactly right.** The real
  capture's `AB` → `EB` response (a real device serial number, 14-byte
  payload) validates byte-for-byte against
  `OpComFrameCodec.encodeRecord`/`checksumOf` (sum of length bytes + payload,
  mod 256). Not the bug.
- **The `AB`/`AA`/`AC` handshake commands themselves are correct** — matches
  `docs/formats/opcom-debug-capture.md` and what `OpComTransport.kt` already
  sends.
- **DTR and RTS must be asserted** — `usb-serial-for-android` leaves both
  disabled by default and the interface stays silent otherwise. Confirmed by
  a real capture showing explicit `MODEM_CTRL` requests: **RTS asserted
  first, then DTR** as two separate control transfers (not combined).
- **`FtdiSerialDriver.FtdiSerialPort.purgeHwBuffers(a, b)` parameter order is
  `(purgeWrite/TX, purgeRead/RX)`** — confirmed by decompiling the library's
  bytecode directly (its own internal error strings say "purge write buffer
  failed" / "purge read buffer failed" for params 1/2 respectively). This is
  the **opposite** of the natural RX/TX-first assumption — easy to get
  backwards, already got it backwards once this session.
- The real vendor software's own init sequence, in order, once past Windows
  driver-level enumeration: `SET_LATENCY_TIMER=1ms` → `SET_BAUD_RATE=500000`
  → `SET_LATENCY_TIMER=1ms` again → `MODEM_CTRL` RTS=1 → `MODEM_CTRL` DTR=1 →
  purge TX **6 times** → purge RX **once** → **wait ~1.03 seconds** → first
  real command (`AB`) written **one byte at a time** (4 separate USB bulk-OUT
  transfers, not one 4-byte write).
- Every USB IN transfer carries FTDI's standard 2-byte modem-status header
  (`01 60`/`01 70` observed) even mid-logical-message; `usb-serial-for-android`
  is responsible for stripping this and appears to do so correctly based on
  what our own captures show.

All of the above is now implemented in
`app/src/main/java/nl/jwdr/ooc/diagnostics/UsbSerialOpComLink.kt` (current
working tree, **uncommitted**) — latency timer, RTS-then-DTR, 6x TX + 1x RX
purge, and a 1.1s settle delay after purging and before returning from
`open()`. That file also still has **TEMPORARY** `Log.i(TAG, ...)` calls in
`write()`/`read()` tracing raw hex bytes, plus a `TAG` constant marked
TEMPORARY — strip these once the issue is resolved (or gate them properly).

## The actual unsolved mystery

Despite implementing every one of the above, **every single connect attempt
still reads back the exact same 6 bytes on the first read**:

```
write [01 00 ab ac]
read  [03 00 7f 7f 00 01]
```

This exact byte sequence has been **byte-for-byte identical across every
variant tried this session**: the original (broken) sequence, after adding
DTR/RTS, across a ~30-candidate baud sweep (480000–2000000), after adding a
100ms settle delay, and after implementing the full vendor-matched sequence
above with a 1.1s delay. Nothing controllable from the Android side has
changed it by a single bit.

It's one bit off from what would be a validly-checksummed `7F 7F` (keep-alive)
record: `02 00 7f 7f 00` (length=2, payload=`7F 7F`, checksum=0x00) vs. the
observed `03 00 7f 7f 00 01` — first byte off by one, plus a trailing `01`
that doesn't fit either interpretation.

**Leading theory, untested** *(superseded — see "Update 2026-08-24" below;
kept verbatim for the record)*: given software-side changes have had zero
effect, this may be a hardware/electrical incompatibility between this
specific phone's USB-OTG host controller and this specific clone interface,
rather than a remaining code bug. **Next step (in progress when this session
ended): test the same APK against a second, different Android device.** If
the identical corruption reproduces there too, it's very likely a real
software bug (worth revisiting `usb-serial-for-android`'s `FtdiSerialDriver`
read path, or bypassing the library for a raw `UsbDeviceConnection` bulk
transfer to isolate where the corruption is introduced). If it does *not*
reproduce, it's phone-specific and the fix is elsewhere (different
cable/hub, or accept this phone can't run this interface).

## Update 2026-08-24 — the "corrupted" read isn't corrupted

Cross-referencing an external research doc (`HANDOVER-C-android-usb-protocol.md`
from the sibling `OpelObdToolExploration` repo — an independently-derived
description of the genuine dongle's record framing) against this repo's own
`docs/formats/opcom-debug-capture.md` overturns the "one bit off / corrupted"
read above. Applying the documented framing —
`[length: u16 LE] [payload: length bytes] [checksum: u8]`,
checksum = sum(length bytes + payload) mod 256 — to the observed bytes:

```
03 00 7f 7f 00 01
length = 0x0003 = 3
payload (3 bytes) = 7f 7f 00
checksum = (3 + 0 + 0x7f + 0x7f + 0x00) & 0xFF = 0x01   <- matches the trailing byte exactly
```

**This is a validly checksummed record, not garbage.** It decodes to
code=`7F`, payload=`7F 00` — exactly the `7F` keep-alive/status record already
documented at `docs/formats/opcom-debug-capture.md:44`
(`7F 00` / `7F 7F` → keep-alive/status, arrives unsolicited). The write
`01 00 ab ac` also decodes cleanly as a correctly-framed `AB` command
(length=1, payload=`ab`, checksum=`(1+0+0xab)&0xFF=0xAC`).

Implication: the hardware-corruption theory above was built on a wrong
premise. The interface is talking correctly the whole time; it just isn't
producing anything besides this one keep-alive record.

**Also checked: `OpComTransport.kt`/`OpComFrameCodec.kt` already handle this
correctly.** `readLoop()` doesn't treat a single `link.read()` as "the
answer" — it loops indefinitely, and `dispatch()` explicitly discards
`OpComRecord.KeepAlive` without touching `pendingResponse`, so a stray
keep-alive can't be mistaken for the real `EB` response. There is no
read-past-the-keep-alive bug to fix in the transport layer. Given `connect()`
still times out on every attempt, the real fact this establishes is starker:
**the interface sends exactly one legitimate keep-alive and then nothing else
ever follows `AB`** — no `EB`, ever.

**One remaining oddity worth chasing, raised by the same cross-reference**:
the handover doc reports this identical 6-byte keep-alive across a baud
sweep from 480000–2000000. A real UART misconfigured at wildly different
rates should decode as different garbage at each rate, not the same
validly-checksummed record every time. That's more consistent with the
physical link never actually changing rate regardless of what
`UsbSerialOpComLink.open()` requests — i.e. `serialPort.setParameters(...)`
may be a no-op on this custom VID:PID (`0403:4F50`) the way
`usb-serial-for-android`'s `FtdiSerialDriver` applies it, rather than baud
genuinely not mattering.

### Instrumentation added (uncommitted, TEMPORARY — strip once resolved)

- `OpComTransport.kt` `readLoop()`: logs every raw chunk read, how many
  records were decoded from it, and any unconsumed tail bytes (a growing
  tail = the codec is silently resyncing past bad framing). Each decoded
  record is logged with type/code/payload before dispatch.
- `OpComTransport.kt` `dispatch()`: logs when a `Response` arrives that
  doesn't match the currently pending command (would otherwise look
  identical to "no response").
- `UsbSerialOpComLink.kt` `open()`: logs the requested baud/latency
  right before `setParameters()`, and logs when the boot-settle delay
  elapses, so raw byte dumps can be lined up against exactly what was
  configured and when.

All tagged the same way as the existing TEMPORARY `Log.i` calls in that file.

### Plan for next session

1. Run on real hardware, capture logcat filtered on `OpComTransport` /
   `UsbSerialOpComLink` during a `connect()` attempt. Confirm directly
   whether *anything* other than repeated `KeepAlive` records is ever
   decoded after `AB`.
2. Baud rate isn't exposed in the UI (by design, at least for now) — to
   re-run the sweep, edit the `BAUD_RATE` constant in
   `UsbSerialOpComLink.kt` directly and rebuild for each value tested. Only
   need a couple of widely-separated values (e.g. 500000 vs. one far outside
   any plausible divisor) since the question is binary: does the raw byte
   dump actually change at all, or is it byte-identical regardless.
   - If the raw bytes never change: `setParameters()`/`FtdiSerialDriver` is
     very likely not applying the requested baud on this custom VID:PID —
     dig into whether the `SET_BAUD_RATE` control transfer is even being
     issued (compare against the real capture's `wValue=0x0006` divisor) and
     whether the FTDI driver's chip-type detection recognizes this clone the
     same way it would a stock device.
   - If the raw bytes do change with baud but still never show more than a
     keep-alive: the baud negotiation is fine and the real gap is
     `HANDOVER-C`'s still-unresolved §4 — a connect/mode-select command that
     must precede `AB`/`AA`/`AC` and was never identified (that doc suggests
     reading `SerialCAN.pas`'s `OnCreate`/timer handlers as the highest-value
     next step if no fresh capture is available).
3. Only fall back to the "different phone" hardware test from the original
   theory above if 1–2 both come back clean (real baud changes reaching the
   wire, and genuinely nothing but keep-alives regardless) — that's now the
   last resort, not the first move.

## Reference material

- **Real USB capture**: `DebugFiles/opcom.pcap` (git-ignored, local-only,
  ~75k packets / 3.3MB). Captured via `USBPcapCMD.exe` on a Windows 7 32-bit
  laptop running the genuine `OP-COM.exe` against the same physical
  interface. Format: legacy pcap (not pcapng), linktype 249 (USBPcap). Parsed
  by hand this session (no tshark/scapy available) — global header is
  standard pcap; each record's USBPcap per-packet header is 27 bytes
  (`struct.unpack("<HQIHBHHBBI", data[:27])` → headerLen, irpId, status,
  function, info, bus, device, endpoint, transfer, dataLength), or 28 bytes
  for control transfers (extra 1-byte "stage" field: 0=SETUP, then the raw
  8-byte USB Setup packet follows at that offset).
- The one-off analysis scripts used to derive all of the above lived in this
  session's scratchpad dir (`/private/tmp/claude-502/...`), which will not
  persist to a future session — but they're short and easy to reconstruct
  from `opcom.pcap` using the parsing notes above if needed again (look for:
  control-transfer SETUP packets with `bmRequestType=0x40` for FTDI vendor
  requests; BULK transfers with `dataLength > 0` for real traffic; the
  `(bus, device)` fields to confirm you're looking at the right device —
  this device was `(1, 1)`).
- Also relevant: `docs/formats/opcom-debug-capture.md` (PC-software-level
  capture format, different from the raw USB capture above — that one's from
  OP-COM's own "debug capturing" feature and only shows already-decoded
  application-level command/response records, not raw wire bytes).
- Auto-memory: `opcom-handshake-no-response` memory file is now stale (it
  predates the baud-rate confirmation and blamed the wrong thing) — update or
  supersede it with a pointer to this file when picked back up.
