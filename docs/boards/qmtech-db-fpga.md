# QMTECH DB_FPGA Daughter Board

Universal daughter board for QMTECH FPGA core boards. Connects via dual 32x2 pin
headers (J2, J3) at 0.1" pitch. Two major revisions with significantly different
peripheral sets.

GitHub: <https://github.com/ChinaQMTECH/DB_FPGA>

## Versions

| Feature | [V4](qmtech-db-fpga-v4.md) | [V5](qmtech-db-fpga-v5.md) |
|---------|:--:|:--:|
| UART | CP2102N chip, Mini USB → `/dev/ttyUSB0` | No dedicated UART chip — RP2040 provides CDC bridge → `/dev/ttyACM0` |
| JTAG | External programmer | RP2040 DirtyJTAG on-board |
| 7-segment display | 3-digit (2352B) on J2 | Removed |
| LEDs | 5× on J2 | Removed (RP2040 LED only) |
| Buttons | SW1 (J3) + SW2-SW5 (J2), FPGA-accessible | SW1=BOOTSEL, SW2=RUN — RP2040 only, not FPGA-accessible |
| Ethernet | RTL8211EG GMII on J3 | Same |
| VGA | RGB 5-6-5 on J3 | Same |
| SD card | microSD on J3 | Same |
| PMOD | J10, J11, JP1 on J2 | Same (some pins now conflict with RP2040) |

## Connector Pinout (both versions)

Both versions use the same 64-pin J2/J3 headers. QMTECH 64-pin header invariant:
pins 1-2, 5-6, 61-62 = GND; pins 3-4 = 3V3/VCCO; pins 63-64 = VIN; pins 7-60 = I/O.

`Jx_IO N` net names equal the physical Jx pin number (J3_IO7 is on pin 7 — no offset).

| DB_FPGA | EP4CGX150 core board | XC7A100T core board |
|:-------:|:--------------------:|:-------------------:|
| J2 | U5 (Banks 3, 4) | U2 (Banks 13, 14, 15) |
| J3 | U4 (Banks 5, 6, 7) | U4 (Banks 34, 35) |

## Peripherals on J3 (same on both versions)

| Peripheral | Connector | JOP component |
|------------|:---------:|---------------|
| Ethernet (RTL8211EG, GMII) | J3 | `Eth` + `Mdio` |
| VGA (RGB 5-6-5) | J3 | `VgaText` (80×30) |
| SD card (native 4-bit / SPI) | J3 | `SdNative` / `SdSpi` |

See [V4 doc](qmtech-db-fpga-v4.md#pin-assignments) for full J3 pin tables (J3 pinout is identical on V5).

## JOP Usage

Our primary test hardware uses the **V5** with the QMTECH XC7A100T Core Board.
See [V5 doc](qmtech-db-fpga-v5.md) for programming, UART, and RP2040 details.

The EP4CGX150 (primary JOP platform) uses the **V4** (CP2102N UART, ttyUSB0).
See [V4 doc](qmtech-db-fpga-v4.md).

## Appendix: EP4CE15 Example Projects

Complete working examples in `/srv/git/qmtech/CYCLONE_IV_EP4CE15/Software/`:

| Project | Peripheral | Description |
|---------|-----------|-------------|
| Project05_CP2102_UART_V2 | UART | Echo test, 9600 baud |
| Project06_7SEG_LED | 7-segment | 3-digit multiplexed display |
| Project07_MicroSD | SD card | SPI mode read/write |
| Project08_VGA | VGA | 1024x768@60Hz, 16-bit color, test patterns |
| Project09_GMII_Ethernet | Ethernet | GMII + UDP/IP stack |
