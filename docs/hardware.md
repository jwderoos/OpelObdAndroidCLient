# Hardware notes

Target car: **Opel Astra H TwinTop**. Primary goal: diagnostics/actuation of the
**electrohydraulic roof module and trunk release** — engine diagnostics are
explicitly *not* the interesting part.

## Buses on the OBD2 connector (Astra H)

| Bus | OBD2 pins | Speed | Modules |
|---|---|---|---|
| HS-CAN | 6 & 14 | 500 kbps | ECU, ABS, transmission |
| MS-CAN | 3 & 11 | 95.2 kbps | Display, radio, climate |
| **SW-CAN** (single-wire GMLAN, "LS") | **1** | **33.3 kbps** | **BCM, cluster, CIM, roof module, trunk** |

**Hard constraint:** the roof module and trunk release live on SW-CAN (pin 1).
Any hardware that only does HS-CAN is useless for this project.

Two SW-CAN specifics:

- **High-voltage wakeup (HVWU):** actuating roof/trunk with ignition off
  requires waking sleeping GMLAN nodes by briefly driving the bus at ~12 V.
  The transceiver must support this mode.
- Frame rates at 33.3 kbps are low enough that ELM-style monitor mode can
  sniff without dropping frames.

## Options evaluated

### Rejected

- **ELM327 clones, WiCAN (Pro), CANable, generic USB-CAN** — HS-CAN only, no
  pin 1 access.
- **OBD2→DB9 cable** — passive pin adapter in the CAN-analyzer pinout
  (CAN-H → DB9 7, CAN-L → DB9 2), *not* RS232. Standard ones don't carry
  pin 1 anyway.
- **OBDeleven gen1 (white)** — VAG-only: its "extra wiring" is K-line, there
  is no SW-CAN transceiver inside, and the firmware is locked to the
  OBDeleven app with a proprietary protocol.
- **OBDLink SX / EX (USB)** — no GM SW-CAN; that feature only exists in the
  Bluetooth MX+.

### Current path: OP-COM clone (owned)

USB device with all three Opel buses incl. SW-CAN + HVWU. Fits the dev goal
of Android → USB OTG → OBD2.

- Protocol is undocumented clone-firmware binary; approach: run the official
  OP-COM software in a Windows VM, actuate roof/trunk, capture USB traffic
  (Wireshark + USBPcap / usbmon). This yields both the USB framing *and* the
  GMLAN diagnostic payloads for the roof module.
- Search GitHub for existing OP-COM/VAUX-COM clone protocol REs before
  starting from scratch; verify against own captures (clone firmware
  revisions differ).
- On Android the clone enumerates as an FTDI-style serial device —
  `usb-serial-for-android`, no root.
- Status: capture sessions done; converted to re-runnable logs in `logs/`
  (raw captures in `DebugFiles/`, git-ignored). These feed `ReplayTransport`.

### Alternative: OBDLink MX+ (~€100, Bluetooth)

Only off-the-shelf dongle with GM SW-CAN incl. HVWU. STN2255; ELM-compatible
plus documented ST command set (public PDF): `STP 33`/`STP 34` select SW-CAN,
monitor mode for sniffing. Trade-off vs OP-COM path: documented
implementation task instead of reverse engineering, but Bluetooth instead of
USB and €100. Buy if the OP-COM RE stalls or app momentum matters more.

### Later: DIY ESP32 dongle (module-based prototype first, PCB optional)

Deferred until real roof-module traffic is understood. Prototype v1 uses
off-the-shelf modules only (see sourcing below) — no custom PCB required,
keeping the build reproducible for other hobbyists. Design notes:

- **ESP32-C3** has exactly **one** CAN controller (TWAI), but pins are
  **freely assignable** via the GPIO matrix (`twai_general_config_t`).
  Consequence: wire **two transceivers** on two GPIO pairs and retarget the
  single controller at runtime by reinstalling the driver with the other
  pins + bitrate (500 k ↔ 33.3 k, switch takes ms). Not simultaneous
  dual-bus, but fine for one-module-at-a-time diagnostics; saves an external
  MCP2518FD.
- Transceivers: **SN65HVD230** (HS-CAN, pins 6/14) + **NCV7356** or TH8056
  (SW-CAN, pin 1). NCV7356 mode pins select normal / HVWU — needed for
  roof/trunk with ignition off. Barely exists as hobby breakouts → custom
  PCB. Hold the idle transceiver in standby/listen-only via its standby pin
  so it can't disturb the other bus.
- Sourcing / part numbers:
  - SN65HVD230 is available as a tiny cheap module (3.3 V, ESP32-direct).
    **Remove its onboard 120 Ω termination resistor** before in-car use —
    the vehicle bus is already terminated at 60 Ω; a diag tool must not add
    termination. Re-add (jumper/socket) for bench rigs with a bare ECU.
  - NCV7356 suffixes are packaging only, same silicon: `D1` = SOIC-8,
    `D2` = SOIC-14 (adds INH pin), `R2` = tape & reel, `G` = Pb-free.
    **NCV7356D1G / D1R2G (SOIC-8) is the one to use** — has both MODE0/MODE1
    pins for HVWU. Prefer LCSC/Mouser (~€1–2) over AliExpress chips (fake
    risk). Runs from 12 V VBAT directly (no regulator needed for it), logic
    pins are 3.3 V-compatible, and it needs the external load resistor on
    the LOAD pin per datasheet/GMLAN app note (also does waveshaping —
    mandatory).
  - **Ready-made NCV7356 breakout: MikroE "Single Wire CAN Click"
    (MIKROE-4225)**, ~€20 at Mouser/RS/TME/SparkFun. NCV7356D1R2G with
    on-board load-resistor circuitry; all modes (Normal / High-Speed /
    HVWU / Sleep) selectable via MODE0/MODE1 on the mikroBUS header.
    mikroBUS format but usable standalone: wire TX/RX straight to the
    ESP32 TWAI GPIOs (the "UART 9600 bps" in MikroE docs is just their
    demo; 33.3 kbps CAN is within the chip's 40 kbps rating), and MODE0/1
    to two GPIOs — do not hardwire modes (HVWU needed for ignition-off
    roof/trunk, Sleep for battery drain). Check schematic for the VBAT
    (5–27 V) supply input → feed from OBD pin 16.

**Wiring diagram:** maintained as WireViz source in
[`hardware/wiring.yml`](hardware/wiring.yml) (rendered:
[`hardware/wiring.svg`](hardware/wiring.svg) + BOM `wiring.bom.tsv`).
Re-render after edits with `uvx wireviz docs/hardware/wiring.yml`
(needs `brew install graphviz`). GPIO plan and firmware notes are in the
YAML header comments.

**USB support:** the C3's native USB (GPIO18/19, the SuperMini USB-C port)
enumerates as a fixed-function CDC serial device — works directly with
`usb-serial-for-android` over OTG, so the firmware can offer both BLE and
USB transports. ⚠ Dual-supply gotcha: with the buck on the 5 V pin *and* a
phone supplying OTG VBUS, add a Schottky diode between buck output and the
5 V pin (many SuperMini clones tie VBUS straight to 5 V — verify) to avoid
back-feeding the phone.

**Charging the phone from the dongle: rejected for v1.** Phone-as-OTG-host
sources VBUS by default; reversing that needs USB-C PD power-role swap (PD
source controller + firmware + cooperative phone) — a sub-project that
kills the cheap/reproducible goal. Instead: (a) BLE for data + normal car
charger, or (b) wired data + charging via a PD-powered USB-C hub between
phone and dongle (car PD adapter feeds the hub; dongle stays a dumb CDC
device). Revisit PD sourcing only at the custom-PCB stage, if ever.

**Prototype v1 is therefore fully module-based, no custom PCB:** ESP32-C3
SuperMini + SN65HVD230 module (termination resistor removed) + Single Wire
CAN Click + small automotive-tolerant buck. Also the recommended
reproducible setup for other hobbyists (no PCB ordering barrier); the
custom PCB remains a later optimization, not a prerequisite.
- C3 pin restrictions (not TWAI-specific): GPIO 11–17 = SPI flash (off
  limits); GPIO 18/19 = USB D−/D+ (keep free for native USB); GPIO 2/8/9 =
  strapping pins (avoid having a transceiver drive them at reset). On a
  SuperMini that leaves e.g. 0/1/3/4/5/6/7/10/20/21 — enough for two TX/RX
  pairs.
- **Power:** OBD pin 16 is battery-hot. Use an automotive-tolerant buck (not
  a bare AMS1117) and plan a sleep strategy or the dongle drains the battery.
- Enclosure: generic OBD2 male shell cavity is roughly 40×25×12 mm —
  SuperMini + buck + one transceiver breakout fits tightly; two transceivers
  on breakouts won't. Another reason for a custom PCB.

## Plan of record

1. ✅ Capture OP-COM roof/trunk sessions → `ooc-canlog` files in `logs/` →
   develop protocol layer against `ReplayTransport`.
2. Reverse the OP-COM clone USB protocol → `OpComTransport` over USB OTG.
   Fallback: OBDLink MX+ over Bluetooth (documented ST command set).
3. Once the required GMLAN traffic is understood, build the module-based
   ESP32-C3 prototype (SuperMini + SN65HVD230 module + Single Wire CAN
   Click + buck). A custom PCB is a later optimization, not a prerequisite.
