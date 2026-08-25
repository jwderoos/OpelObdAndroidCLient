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

## Update 2026-08-25 — first clean on-device trace; plan step 1 answered

Ran the debug build on the Samsung (SM-S908B) with "Verbose OP-COM USB
logging" on, logcat over Wi-Fi adb (the OTG port is occupied by the dongle
during the test — pair via *Pair device with pairing code* first; the phone
picks a new mDNS port every time wireless debugging toggles, stale entries in
Android Studio are just Bonjour cache). `adb install` needs `--user 0` on
this phone (Secure Folder user 150 rejects shell installs with an empty error).

### Bug found on the way: the verbose toggle didn't apply

The first run logged nothing from our tags even with the toggle on. The
transport is built once, lazily, at app-container init and lives for the
whole process; the flag was captured as a `Boolean` at construction, so a
toggle only took effect after an app restart (the process was 13 min old).
Fixed: `UsbSerialOpComLink.verboseLogging` and the `OpComTransport.log`
sink now read the flag on every call, so toggling applies immediately, even
mid-connection. The Debug-screen text was corrected accordingly.

### The trace (500000 baud, full vendor-matched init)

```
10:32:20.274 UsbSerialOpComLink  open: requesting baud=500000 8N1, latency=1ms
10:32:20.274 FtdiSerialPort      baud rate=500000, effective=500000, value=0x0006, divisor=6
10:32:21.402 UsbSerialOpComLink  open: settle delay elapsed
10:32:21.407 UsbSerialOpComLink  write [01 00 ab ac]
10:32:21.461 UsbSerialOpComLink  read  [03 00 7f 7f 00 01]
10:32:21.463 OpComTransport      read 6B -> 1 record(s), 0B unconsumed
10:32:21.463 OpComTransport      record KeepAlive raw=[7f 7f 00]
10:32:23.415 UsbDeviceConnectionJNI close                  (2 s silence, timeout)
```

Answers to the plan above:

1. **Nothing but one `7F 7F 00` record is ever decoded after `AB`.** It
   arrives **54 ms after** `AB` and is never repeated — it behaves like a
   *reply* to `AB`, not a periodic unsolicited keep-alive. Codec resync is
   clean (0 unconsumed bytes), so no framing issue.
2. **`setParameters()` is NOT a no-op**: `FtdiSerialDriver` logs and issues
   `SET_BAUD_RATE wValue=0x0006` — byte-identical to the vendor capture. The
   "baud never reaches the wire" theory is dead. Baud sweep no longer needed.

### Vendor capture re-parsed (`DebugFiles/opcom.pcap`) — exact init diff

Distinct control requests in the whole vendor session (bmReq/bReq/wValue):
`5× RESET(0)` each followed by `GET_MODEM_STATUS`, `1× SET_BAUD 0x0006`,
`1× SET_LATENCY 1`, `2× MODEM_CTRL RTS-on (0x0202)`, `2× MODEM_CTRL DTR-on
(0x0101)`, `6× RESET(1)`, `1× RESET(2)`. **The vendor never sends
`SET_DATA` (bReq 4), `SET_FLOW_CTRL` (bReq 2), or any DTR/RTS *de-assert*.**
The vendor's `7F 7F` record appears **zero times** in the whole capture; `AB`
is answered by `EB` immediately.

Our sequence, from decompiling `usb-serial-for-android` 3.9.0
`FtdiSerialPort` (`openInt`, `setParameters`, `purgeHwBuffers`):

| step | vendor (OP-COM.exe via D2XX) | ours (library) |
|---|---|---|
| reset | `RESET(0)` + `GET_MODEM_STATUS`, **×5** | `RESET(0)` ×1 (in `openInt`) |
| modem lines at open | — | **`MODEM_CTRL 0x0300` = DTR off + RTS off** (baked into `openInt`) |
| baud | `SET_BAUD 6` | `SET_BAUD 6` ✓ |
| line config | **none** | `SET_DATA 8N1` (from `setParameters`) |
| latency | `SET_LATENCY 1` | `SET_LATENCY 1` ✓ |
| lines | RTS on, DTR on | RTS on, DTR on ✓ |
| purge | 6× `RESET(1)`, 1× `RESET(2)` | 6× `RESET(1)`, 1× `RESET(2)` ✓ (`purgeHwBuffers(write→1, read→2)`) |
| after purge | **~125 ms, then RTS on + DTR on again**, then ~0.9 s | 1.1 s |
| first write | `01`,`00`,`ab`,`ac` as **4 separate bulk-OUT transfers** | one 4-byte write |

### Leading hypothesis now

The clone MCU may treat a DTR/RTS *edge* (or the reset/modem-status dance)
as a hardware reset, Arduino-style. Our `openInt` explicitly de-asserts both
lines and we re-assert them 1 ms later — that edge would reboot the MCU right
before our 1.1 s wait; a `7F` 54 ms after `AB` is then plausibly the
bootloader/not-ready state answering. The vendor never produces that edge:
its MCU had been running since USB enumeration (~58 s earlier in the capture).
Alternative reading: `7F` = firmware negative response because a pre-`AB`
mode-select command is missing (HANDOVER-C §4) — but the vendor capture shows
`AB` as the very first bulk-OUT byte, so nothing precedes it on the wire.

### Next experiment (single connect press, decisive)

Keep sending `AB` every ~500 ms for ~10 s after the settle delay, logging every
reply. If `EB` eventually appears → boot-time / reset-edge theory confirmed,
fix = longer settle or avoid the de-assert edge (raw `controlTransfer` for
the init instead of `openInt`'s defaults). If it stays `7F` forever →
replicate the vendor sequence exactly with raw control transfers (5× reset +
modem status, no `SET_DATA`, no de-assert, second RTS/DTR after purge,
byte-per-byte write) and bisect from there.

### Retry-probe result (2026-08-25, later the same day)

Implemented `OpComTransport(handshakeAttempts, handshakeAttemptTimeout)`
(app wires 20 × 500 ms; defaults keep the single-attempt behaviour) and ran
it on the phone. **All 20 `AB` attempts over 10 s were answered by
`7F 7F 00`, each exactly 54 ms (±1 ms) after the write. `EB` never came.**

- Boot-time / DTR-reset-edge theory: **refuted** (nothing changes over 11 s
  after open). Remove it from the candidate list.
- The reply is fully deterministic → `7F` is a *negative response to `AB`*
  from a firmware that is running fine, not garbage and not a not-ready
  state.
- The **54 ms latency is itself a clue**: in the vendor capture `EB`
  arrives within the same 15.6 ms USBPcap tick as `AB` (effectively
  instant). At 500 kbaud nothing on the serial link takes 54 ms; a fixed
  ~50 ms delay before a NAK smells like the firmware attempting something
  (vehicle-side probe / voltage check / bus wake) with a ~50 ms timeout and
  then refusing. This raises the question of **whether the interface was
  plugged into a powered vehicle** during the Android tests vs. during the
  Windows capture — if only the Windows run had 12 V on the OBD side, that
  alone could explain `7F` (clone firmware refusing to identify without
  vehicle power). Cheapest discriminator; ask/verify before more code.
- Codec resilience verified incidentally: the 6-byte record often arrives
  split across two reads (`[03 00] + [7f 7f 00 01]`, `[03 00 7f] + [7f 00
  01]`, …) and is always reassembled with 0 bytes left over.

Remaining code-side differences vs. the vendor (see table above), ranked by
how likely a clone firmware notices them: (1) `openInt`'s explicit DTR/RTS
**de-assert** at open — the vendor never de-asserts; (2) `SET_DATA` (bReq 4)
which the vendor never sends; (3) 5× `RESET`+`GET_MODEM_STATUS` vs. our 1×;
(4) second RTS/DTR assert after purge; (5) byte-per-byte writes. If the
vehicle-power question comes back "same in both runs", the next experiment
is to bypass the library's `open()`/`setParameters()` and issue the vendor's
exact control-transfer sequence with raw `UsbDeviceConnection.controlTransfer`,
then bisect.

## RESOLVED 2026-08-25 — raw vendor-identical USB init gets `EB`

Vehicle power ruled out first (interface was **not** connected to a car in
either the Windows capture or any Android test), so the only remaining
variables were host-side USB differences. `RawFtdiOpComLink` (new,
`app/.../diagnostics/`) bypasses `usb-serial-for-android` and replays the
vendor's control-transfer sequence verbatim with `UsbDeviceConnection`:
5× `RESET`+`GET_MODEM_STATUS`, `SET_BAUD` 0x0006, `SET_LATENCY` 1, RTS then
DTR, 6× `PURGE(1)` + 1× `PURGE(2)`, 125 ms, RTS+DTR again, 900 ms; **no
`SET_DATA`, no DTR/RTS de-assert**; writes as one bulk transfer per byte.
Selected via `OPCOM_USE_RAW_FTDI_LINK` in `OocApplication`.

Result on the Samsung, two connects in a row:

```
write [01 00 ab ac]  → read [0e 00 eb 4f 49 31 32 33 34 35 36 2d 31 32 33 34 bd]   (~3 ms)
                        Response(code=0xeb, payload="OI123456-1234")
write [01 00 aa ab]  → Response(code=0xea, payload=[01 99])      firmware 1.99
write [02 00 ac 01 af] → Response(code=0xec, payload=[01 00])
ConnectionState.Ready
```

Byte-identical to the vendor capture. The `7F`-after-54-ms behaviour is gone.
Every control transfer returned 0 (accepted); `GET_MODEM_STATUS` reads
`01 60` each time.

### What is NOT yet known: which difference was the culprit

The raw link changes five things at once (see table above). Not bisected
yet. Ranked guess: (1) the library's `MODEM_CTRL 0x0300` DTR/RTS **de-assert**
in `FtdiSerialPort.openInt()`; (2) `SET_DATA` from `setParameters()`; (3)
single vs. 5× reset; (4) second RTS/DTR after purge; (5) byte-per-byte
writes. Bisect by re-adding one library behaviour at a time to
`RawFtdiOpComLink` (each = one connect press). Knowing the culprit decides
whether the raw link stays (library can't avoid `openInt`'s de-assert
without a fork) or the library link can be repaired.

### Follow-ups

- Decide which link becomes the one implementation; delete the other and the
  `OPCOM_USE_RAW_FTDI_LINK` switch.
- `RawFtdiOpComLink.read()` busy-polls `bulkTransfer` at the 1 ms latency
  timer while idle (each idle poll returns a 2-byte status packet). Fine for
  diagnostics; consider raising the latency timer or using `UsbRequest`
  before it becomes the permanent link.
- The 20 × 500 ms `AB` retry probe in `OocApplication` is no longer needed
  for diagnosis; drop back to a single attempt (or keep 2–3 as robustness)
  once the culprit is known.
- Update `opcom-handshake-no-response` auto-memory (done 2026-08-25).

## ROOT CAUSE (bisected 2026-08-25): one byte per USB bulk-OUT packet

Bisected by re-adding library behaviours one at a time to the raw link, then
confirmed the other way round (library link with a single change):

| step | change vs. working raw link | result |
|---|---|---|
| 1 | + library's `MODEM_CTRL 0x0300` DTR/RTS de-assert at open | `EB` — **not** the culprit |
| 2 | + library's `SET_DATA 8N1` | `EB` (3 connects) — **not** the culprit |
| 3 | records written as **one** bulk transfer instead of one per byte | **`7F` after 54 ms, 20/20** — culprit |
| confirm | original `UsbSerialOpComLink` (library `open()`/`setParameters()` untouched), `write()` changed to one byte per transfer | **`EB`/`EA`/`EC`, Ready** |

The clone's firmware only consumes the first byte of each USB OUT packet. A
whole record in one packet is seen as a lone length byte `01`, the firmware
waits ~50 ms for the rest and answers with the `7F 7F 00` NAK — that is the
54 ms signature. The vendor software behaves accordingly: **all 1542
bulk-OUT transfers in the reference capture are exactly 1 byte**, including
every `90` send-CAN-frame command, so this is a hard constraint for all
traffic, not just the handshake. Throughput cost: one full-speed USB
transaction per byte (~1 ms), fine for diagnostics.

Every control-transfer difference (reset count, de-assert, `SET_DATA`,
second RTS/DTR) was a red herring; 5× reset and the second RTS/DTR were
never individually tested but are proven irrelevant by the confirmation run.

Final state: `UsbSerialOpComLink.write()` loops one `port.write()` per byte;
`RawFtdiOpComLink` and the `OPCOM_USE_RAW_FTDI_LINK` switch were deleted;
the `AB` retry is dialed back to 3 × 1 s as cheap robustness. The verbose
USB logging toggle (Settings → Debug) stays, now applied live.
