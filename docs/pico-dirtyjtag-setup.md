# FPGA Programming with pico-dirtyJtag

Program JOP FPGA bitstreams using a Raspberry Pi Pico as a USB JTAG adapter
via [pico-dirtyJtag](https://github.com/phdussud/pico-dirtyJtag) and
[openFPGALoader](https://github.com/trabucayre/openFPGALoader).

This is an alternative to the Altera USB-Blaster or Quartus Programmer.
It works with any FPGA that openFPGALoader supports, including all Altera
Cyclone and Xilinx Artix-7 boards used by this project.

## Requirements

- Raspberry Pi Pico (RP2040)
- [Pico SDK 2.2.0](https://github.com/raspberrypi/pico-sdk) at `~/pico-sdk`
- openFPGALoader: `sudo apt install openfpgaloader`
- Debug probe (CMSIS-DAP) for flashing the Pico, or USB cable for UF2 drag-and-drop

## Wiring

Connect the Pico to the FPGA's JTAG header:

| Signal | Pico GPIO | Pico Pin | FPGA |
|--------|-----------|----------|------|
| TCK    | GP2       | 4        | TCK  |
| TMS    | GP3       | 5        | TMS  |
| TDI    | GP4       | 6        | TDI  |
| TDO    | GP5       | 7        | TDO  |
| GND    | —         | 3 or 8   | GND  |

These are the same pins used by the pico-fpga firmware, so no rewiring
is needed when switching between firmwares.

## Building the Firmware

The pico-dirtyJtag source is at `~/pico-dirtyJtag` with two local
modifications from upstream:

1. **Custom pin assignments** in `dirtyJtagConfig.h` — TCK=GP2, TMS=GP3,
   TDI=GP4, TDO=GP5 (upstream defaults are GP16–19).
2. **Multicore disabled** in `dirtyJtag.c` — `#define MULTICORE` commented
   out. The multicore USB handling causes bulk transfer timeouts with Pico
   SDK 2.2.0.

```bash
cd ~/pico-dirtyJtag/build
PICO_SDK_PATH=~/pico-sdk cmake ..
make -j$(nproc)
```

## Flashing the Pico

Via SWD debug probe:

```bash
sudo openocd -f interface/cmsis-dap.cfg -f target/rp2040.cfg \
  -c "adapter speed 5000; program ~/pico-dirtyJtag/build/dirtyJtag.elf verify reset exit"
```

Or via UF2: hold BOOTSEL, connect USB, copy `dirtyJtag.uf2` to the
RPI-RP2 mass storage drive.

The Pico appears as USB device `1209:c0ca` ("DirtyJTAG"). Verify with:

```bash
lsusb | grep 1209
```

## Programming FPGAs

### Detect

```bash
sudo openFPGALoader -c dirtyJtag --detect
```

Example output for the A-E115FB (EP4CE115):

```
index 0:
    idcode 0x20f70dd
    manufacturer altera
    family cyclone III/IV/10 LP
    model  EP3C120/EP4CE115/10CL120
    irlength 10
```

### Program SRAM (volatile — lost on power cycle)

```bash
# Altera Cyclone IV (.rbf)
sudo openFPGALoader -c dirtyJtag -m output_files/jop_bram.rbf

# Xilinx Artix-7 (.bit)
sudo openFPGALoader -c dirtyJtag design.bit
```

### Makefile targets

Each FPGA project Makefile includes a `program-djtag` target:

```bash
cd fpga/a-e115fb-bram
make program-djtag
```

This generates the RBF from the SOF (if needed) and programs via
openFPGALoader.

## Tested Boards

| Board | FPGA | Status |
|-------|------|--------|
| A-E115FB | Altera Cyclone IV EP4CE115F23I7 | Working — SRAM programming verified |
| QMTECH EP4CGX150 | Altera Cyclone IV GX | Should work (same openFPGALoader Altera flow) |
| Trenz CYC5000 | Altera Cyclone V | Should work |
| Alchitry Au V2 | Xilinx Artix-7 XC7A35T | Should work (openFPGALoader supports Artix-7) |

## Performance

Programming 3.5 MB (EP4CE115) takes about 60 seconds at the default
6 MHz JTAG clock. This is slower than a USB-Blaster (~6 seconds) but
requires only a $4 Pico and four wires.

## Switching Firmwares

The same Pico and wiring supports two firmwares:

- **pico-dirtyJtag** — FPGA programming via openFPGALoader
- **pico-fpga** — logic analyzer, UART bridge, JTAG scripting

Switch by flashing the desired firmware:

```bash
# To dirtyJtag (FPGA programming)
sudo openocd -f interface/cmsis-dap.cfg -f target/rp2040.cfg \
  -c "adapter speed 5000; program ~/pico-dirtyJtag/build/dirtyJtag.elf verify reset exit"

# To pico-fpga (debug tool)
sudo openocd -f interface/cmsis-dap.cfg -f target/rp2040.cfg \
  -c "adapter speed 5000; program ~/pico-fpga/build/pico_fpga.elf verify reset exit"
```

## VM USB Passthrough

When running inside a VM (QEMU/KVM), the DirtyJTAG USB device must be
passed through to the guest. The VID:PID is `1209:c0ca`, which is
different from the pico-fpga device (`2e8a:000a`). Both need separate
passthrough rules if switching between firmwares.

## Troubleshooting

**"fails to open device"** — run with `sudo`, or add a udev rule:
```bash
# /etc/udev/rules.d/99-dirtyjtag.rules
SUBSYSTEM=="usb", ATTR{idVendor}=="1209", ATTR{idProduct}=="c0ca", MODE="0666"
```
Then: `sudo udevadm control --reload-rules && sudo udevadm trigger`

**"usb bulk read failed -7"** — the multicore build has this issue with
SDK 2.2.0. Ensure `#define MULTICORE` is commented out in `dirtyJtag.c`
and rebuild.

**Device not detected after flash** — in a VM, you need to add the
`1209:c0ca` USB device to the guest after flashing. The old `2e8a:000a`
passthrough rule won't match the new firmware.
