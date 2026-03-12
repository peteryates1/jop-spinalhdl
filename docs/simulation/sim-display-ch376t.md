# Simulation Display and CH376T Emulator

## Overview

Simulation-time VGA display and CH376T USB host emulator for JOP SpinalHDL.
These run inside SpinalHDL's Verilator simulation, allowing visual verification
of VGA output and keyboard/SD card interaction without FPGA hardware.

## Architecture

```
┌──────────────────────────────────────────┐
│  Verilator (JOP + VGA controller)        │
│                                          │
│  VGA pins: hsync/vsync/RGB ──────────────┼──→ SimDisplay (Java AWT)
│                                          │       640x480 window
│  SPI master: CS/SCLK/MOSI/MISO/INT# ────┼──→ SimCH376T (Scala)
│                                          │       ├─ Keyboard (AWT KeyListener)
│                                          │       └─ SD card (host file image)
└──────────────────────────────────────────┘
```

All simulation components are pure JVM (Scala + Java AWT). No native
dependencies. SpinalHDL's Verilator backend communicates with the JVM
via shared memory IPC — we cannot inject C++ into the Verilator process,
so everything runs on the JVM side in the `doSim` block.

## SimDisplay

Captures VGA timing signals every pixel clock tick and reconstructs a
640x480 framebuffer rendered in a Java AWT window.

### VGA Signal Capture

Called every 25 MHz pixel clock tick:

```scala
display.tick(hsync, vsync, r5, g6, b5)
```

Internally tracks horizontal/vertical counters from sync edges:
- hsync falling edge → reset hCounter
- vsync falling edge → reset vCounter, trigger window repaint
- When inside active area (hCounter < 640, vCounter < 480), write pixel

RGB565 → RGB888 expansion:
- `r8 = (r5 << 3) | (r5 >> 2)`
- `g8 = (g6 << 2) | (g6 >> 4)`
- `b8 = (b5 << 3) | (b5 >> 2)`

### Keyboard Input

The AWT window captures `KeyListener` events when focused. Keypresses
are queued and available to SimCH376T via `pollKey()`.

### VGA Timing Constants (640x480@60Hz)

| Parameter    | Value |
|-------------|-------|
| H active    | 640   |
| H front porch | 16 |
| H sync      | 96    |
| H back porch | 48   |
| H total     | 800   |
| V active    | 480   |
| V front porch | 10  |
| V sync      | 2     |
| V back porch | 33   |
| V total     | 525   |
| Pixel clock | 25.175 MHz |

## SimCH376T

Emulates the CH376T USB host controller SPI protocol. In a real system,
the CH376T connects via SPI to JOP and provides:
- USB-A host port (keyboard)
- SD card slot (storage)

### Hardware Connection

```
JOP SPI master ──→ CH376T ──┬──→ USB-A: keyboard
              CS, INT#      └──→ SD card slot: storage
```

One SPI bus, one CS, one INT# pin. The CH376T firmware handles all USB
and SD card protocols internally. JOP sends high-level commands.

### SPI Protocol

- SPI Mode 0 (CPOL=0, CPHA=0), MSB-first, 8-bit frames
- Host asserts CS low, clocks command byte, then data bytes
- CH376T responds on MISO
- INT# pin asserted low when event ready (key press, disk data)

### Simulated Command Subset

| Command | Code | Description |
|---------|------|-------------|
| GET_IC_VER | 0x01 | Returns 0x43 (CH376 identifier) |
| CHECK_EXIST | 0x06 | Echo test: responds with bitwise NOT of input |
| SET_USB_MODE | 0x15 | Set operating mode (host/device/SD) |
| GET_STATUS | 0x22 | Read interrupt status code |
| RD_USB_DATA0 | 0x27 | Read data from USB endpoint (HID report) |
| WR_HOST_DATA | 0x2C | Write data to USB endpoint |
| DISK_CONNECT | 0x30 | Check SD card present |
| DISK_MOUNT | 0x31 | Initialize SD card |
| DISK_READ | 0x54 | Read sector(s) from SD card |
| DISK_WRITE | 0x56 | Write sector(s) to SD card |
| DISK_RD_GO | 0x55 | Continue multi-sector read |
| DISK_WR_GO | 0x57 | Continue multi-sector write |

### USB Keyboard (HID)

When `SET_USB_MODE(0x06)` activates USB host mode:
- SimCH376T polls `SimDisplay.pollKey()` for AWT keyboard events
- When a key is available, INT# asserts with status `USB_INT_EP1_IN` (0x1D)
- `RD_USB_DATA0` returns an 8-byte HID keyboard report:
  - Byte 0: modifier keys (shift, ctrl, alt)
  - Byte 1: reserved (0x00)
  - Bytes 2-7: up to 6 simultaneous keycodes (USB HID usage table)
- SDL/AWT keycode → USB HID scancode translation table

### SD Card

When `SET_USB_MODE(0x03)` activates SD host mode:
- `DISK_CONNECT` → success if backing image file exists
- `DISK_MOUNT` → initialize (always succeeds)
- `DISK_READ(lba)` → read 512-byte sector from backing file
- `DISK_WRITE(lba)` → write 512-byte sector to backing file
- Sectors addressed by 32-bit LBA
- INT# asserts with `USB_INT_DISK_READ` (0x1D) when sector data ready

Backing file: raw disk image, passed as constructor parameter:
```scala
val ch376 = new SimCH376T(sdImagePath = "test.img")
```

## Atari 800 Integration

In the atari800-spinalhdl project, SimDisplay also serves for validating
the Atari video output. The Atari 800 core and JOP share VGA via
`VgaOverlayMux` (full-screen switch controlled by JOP's OSD enable flag).

The simulation captures whatever comes out of the final VGA mux — either
Atari video or JOP text overlay, depending on OSD state.

Keyboard events from SimDisplay flow through SimCH376T to JOP, which
routes them to the Atari core via BmbAtariCtrl's keyboard scan/response
handshake.

```
SimDisplay (AWT window)
  ├─ VGA input ← Verilator VGA pins (Atari or JOP, muxed)
  └─ Keyboard output → SimCH376T → JOP SPI → BmbAtariCtrl → Atari POKEY
```

## File Structure

```
spinalhdl/src/test/scala/jop/sim/
  SimDisplay.scala        -- VGA framebuffer display (AWT/Swing)
  SimCH376T.scala         -- CH376T SPI protocol emulator

spinalhdl/src/test/scala/jop/system/
  JopVgaBramSim.scala     -- VGA-enabled BRAM simulation test
```

## JopTop.scala Changes

VGA clock domain is currently disabled in simulation mode:

```scala
val vgaCd = (!simulation && sys.ioConfig.hasVga) generate { ... }
```

Must change to create an external clock domain in simulation:

```scala
val vgaCd = sys.ioConfig.hasVga generate {
  if (simulation)
    ClockDomain.external("vgaCd", withReset = false,
      config = ClockDomainConfig(resetKind = BOOT))
  else
    // existing PLL-derived clock domain
}
```

The test harness then drives it:

```scala
dut.clockDomain.forkStimulus(10)   // 100 MHz system clock
dut.vgaCd.forkStimulus(40)         // 25 MHz pixel clock
```

## Test Harness Usage

```scala
class JopVgaBramSim extends AnyFunSuite {
  test("VGA text display") {
    val display = new SimDisplay(640, 480, scale = 2)
    val ch376 = new SimCH376T(sdImagePath = "test.img")
    ch376.setKeySource(display)

    SimConfig.compile(JopTop(config, simulation = true)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.vgaCd.forkStimulus(40)

      // VGA capture fork (runs on pixel clock)
      fork {
        while (true) {
          dut.vgaCd.waitSampling()
          display.tick(
            dut.io.vga_hs.toBoolean,
            dut.io.vga_vs.toBoolean,
            dut.io.vga_r.toInt,
            dut.io.vga_g.toInt,
            dut.io.vga_b.toInt)
        }
      }

      // SPI CH376T fork (runs on system clock)
      fork {
        while (true) {
          dut.clockDomain.waitSampling()
          val miso = ch376.tick(
            dut.io.spiSclk.toBoolean,
            dut.io.spiMosi.toBoolean,
            dut.io.spiCs.toBoolean)
          dut.io.spiMiso #= miso
        }
      }

      // Run simulation
      dut.clockDomain.waitSampling(10000000)
      display.close()
    }
  }
}
```

## Implementation Order

1. SimDisplay — VGA signal capture + AWT rendering
2. JopTop.scala — enable VGA clock domain in simulation mode
3. JopVgaBramSim — test harness to validate display works
4. SimCH376T — SPI protocol emulator (keyboard + SD card)

## Dependencies

None beyond the JDK. Java AWT/Swing is included in Java 21. No SDL2,
JNI, or JNA required.
