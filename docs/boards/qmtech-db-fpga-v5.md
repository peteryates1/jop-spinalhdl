# QMTECH DB_FPGA V5

Daughter board for QMTECH FPGA core boards (V5 variant with RP2040).

V5 replaces the V4's CP2102N USB-UART, 7-segment display, and board LEDs with an
RP2040 that provides DirtyJTAG (FPGA programming) and a CDC UART bridge. There is
**no dedicated UART chip** on V5 — serial communication comes from the RP2040's USB
CDC ACM interface, or from a module plugged into the extension connectors.

Schematic: `/srv/git/qmtech/DB_FPGA_with_RP2040/DB_FPGA_V5-20221108.pdf`

Used with the **QMTECH XC7A100T Core Board** as our secondary JOP test platform.

See [DB_FPGA overview](qmtech-db-fpga.md) for version comparison.

## RP2040 (DirtyJTAG + UART bridge)

The RP2040 runs `pico-dirtyJtag` firmware (`BOARD_PICO`, `CDC_UART_INTF_COUNT=2`).
Source: `/home/peter/workspaces/pico-dirtyJtag/`

**USB ID**: `1209:c0ca` (DirtyJTAG)

USB interfaces:
- `/dev/ttyACM0` — UART0 bridge (GPIO0/1 → J3 pins 7/8 → FPGA B5/A5 on XC7A100T)
- `/dev/ttyACM1` — UART1 / GPIO control (**not working as UART** — hangs on open)

**UART0 requirements**: open with `dsrdtr=True` and `dtr=True` — the CDC bridge
stops forwarding when DTR is deasserted.

**Programming**: `sudo openFPGALoader -c dirtyJtag design.bit`

**Reflash firmware** (if needed): hold SW1 (BOOTSEL) + tap SW2 (RUN) → mount RPI-RP2
→ `cp /home/peter/workspaces/pico-dirtyJtag/build/dirtyJtag.uf2 /media/peter/RPI-RP2/`

### RP2040 GPIO → FPGA Connector Mapping

`J3_IO N` net name = J3 physical pin N (no offset). Verified by hardware loopback test.

| RP2040 | DB_FPGA Net | J3 Pin | XC7A100T FPGA | EP4CGX150 FPGA | Function |
|:------:|:-----------:|:------:|:-------------:|:--------------:|----------|
| GPIO0 | J3_IO7 | 7 | B5 | C21 | UART0 TX (RP2040→FPGA RX) |
| GPIO1 | J3_IO8 | 8 | A5 | B22 | UART0 RX (FPGA TX→RP2040) |
| GPIO2 | J2_IO44 | J2:42 | T24 | AD10 | — |
| GPIO3 | J2_IO43 | J2:41 | T25 | — | — |
| GPIO4 | J2_IO42 | J2:40 | R25 | AF12 | UART1 TX (not working) |
| GPIO5 | J2_IO41 | J2:39 | P25 | — | UART1 RX (not working) |
| GPIO16 | J1_5 | — | JTAG TDI | JTAG TDI | DirtyJTAG |
| GPIO17 | J1_4 | — | JTAG TDO | JTAG TDO | DirtyJTAG |
| GPIO18 | J1_3 | — | JTAG TCK | JTAG TCK | DirtyJTAG |
| GPIO19 | J1_6 | — | JTAG TMS | JTAG TMS | DirtyJTAG |
| GPIO25 | — | — | — | — | RP2040 LED (not FPGA-accessible) |

GPIO6-15 and GPIO20-23/26-27 conflict with V4 7-segment/LEDs (removed on V5).
GPIO28-29 conflict with V4 CP2102N UART position (replaced by RP2040 on V5).

### Buttons

| Button | Function |
|--------|----------|
| SW1 | RP2040 BOOTSEL (hold during reset to enter USB mass-storage mode) |
| SW2 | RP2040 RUN (reset) |

Neither button is FPGA-accessible. The FPGA reset comes from the core board (e.g., P4 on XC7A100T).

## Peripherals (shared with V4)

Ethernet, VGA, SD card, and PMODs use the same J3/J2 connector pins as V4.
See [V4 doc pin tables](qmtech-db-fpga-v4.md#pin-assignments) — J3 pinout is identical.

| Peripheral | Connector | JOP component | Notes |
|------------|:---------:|---------------|-------|
| Ethernet (RTL8211EG, GMII) | J3 | `BmbEth` + `BmbMdio` | Same J3 pins as V4 |
| VGA (RGB 5-6-5) | J3 | `BmbVgaText` (80×30) | Same J3 pins as V4 |
| SD card (native / SPI) | J3 | `BmbSdNative` / `BmbSdSpi` | Same J3 pins as V4 |
| PMOD J10, J11, JP1 | J2 | — | Some J2 pins now conflict with RP2040 GPIO6-15 |

Note on PMOD conflicts with RP2040: GPIO6-15 are on J2 pins 38-29, overlapping
with PMOD J10/J11 upper ranges and the V4 LED/7-seg area. Check the GPIO table above
before using those J2 pins for PMOD on V5.
