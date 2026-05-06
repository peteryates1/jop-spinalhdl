# Troubleshooting Checklist

When JOP or Atari 800 FPGA builds fail or misbehave, work through this list before deep-diving.

## 1. I/O Address Mismatch (Occurrences: 1)

**Symptom**: JOP executes (heartbeat LED blinks) but no UART output, watchdog not toggling.
Simulation also shows no UART output despite JOP reaching Java bytecode execution.

**Root cause**: The compiled `.jop` file uses I/O addresses from `Const.java` (runtime).
If `UART_BASE` or other I/O base addresses in `JopMemoryConfig.scala` don't match what
`Const.java` was generated with, reads/writes go to wrong devices.

**Check**: Run `sbt "runMain jop.generate.ConstGeneratorMain <config>"` and compare
output with `java/runtime/src/jop/com/jopdesign/sys/Const.java`. All `IO_*` addresses
must match.

**Fix**: Either update `JopIoSpace` constants to match the compiled `.jop`, or regenerate
`Const.java` and recompile the `.jop`.

## 2. BRAM Size Too Small for .jop (Occurrences: 1)

**Symptom**: JOP heartbeat blinks but watchdog LED off, no UART output.
Same as #1 but caused by truncated program data.

**Root cause**: `mainMemSize` in JopConfig smaller than the `.jop` file's word count.
SpinalHDL truncates BRAM content silently.

**Check**: First line of `.jop` file = word count. Multiply by 4 for bytes.
Compare with `mainMemSize` in the config. Need headroom for heap too.

**Fix**: Increase `mainMemSize`. EP4CGX150 has 6.5 Mbits of block RAM (~800 KB usable).

## 3. Missing VHDL Wrappers in QSF (Occurrences: 1)

**Symptom**: Quartus synthesis fails with "entity 'ram' / 'rom' undefined".

**Root cause**: SpinalHDL generates `ram #(...)` and `rom #(...)` for AlteraLpm memory
style (CycloneIV). These entities are defined in `fpga/ip/altera_lpm/aram.vhd` and
`arom.vhd`. New Quartus project QSF files often miss these.

**Check**: Look for `ram #(` and `rom #(` in generated Verilog. If present, QSF
must include `aram.vhd` and `arom.vhd`.

**Fix**: Add to QSF:
```
set_global_assignment -name VHDL_FILE ../../fpga/ip/altera_lpm/arom.vhd
set_global_assignment -name VHDL_FILE ../../fpga/ip/altera_lpm/aram.vhd
```

## 4. Clock Frequency Mismatch (Occurrences: 1)

**Symptom**: UART output is garbled or absent. Baud rate wrong.

**Root cause**: `clkFreq` in JopConfig doesn't match actual PLL output frequency.
UART divider is computed from `clkFreq`, so wrong frequency = wrong baud rate.

**Check**: Verify PLL output frequencies in `dram_pll.vhd`. JOP uses `pll_c1`.
EP4CGX150 dram_pll: c0=50 MHz, c1=80 MHz, c2=80 MHz (-3ns phase), c3=25 MHz.

**Fix**: Set `clkFreq` to match actual PLL output (e.g., `80 MHz` for EP4CGX150).

## 5. .bin Files Not Copied to Quartus Project Dir (Occurrences: 1)

**Symptom**: FPGA build succeeds but JOP doesn't execute (BRAM all zeros).

**Root cause**: SpinalHDL generates `$readmemb("filename.bin", ...)` with paths
relative to Quartus project directory. If `.bin` files aren't copied there, Quartus
may silently use zero-initialized BRAM.

**Check**: `ls *.bin` in the Quartus project directory. Compare with
`grep readmemb` in the generated Verilog. All referenced files must be present.

**Fix**: `cp ../../spinalhdl/generated/JopBramTop.v*.bin .`

## 6. Auto Fit Skipping Optimization (Occurrences: 1)

**Symptom**: Timing violations (negative slack) on designs that should easily meet timing.

**Root cause**: Quartus Auto Fit mode skips optimization for smaller designs,
causing suboptimal BRAM placement. Critical path goes through BRAM.

**Check**: Look for "Auto Fit" in fit report. Check if `FITTER_EFFORT` is set.

**Fix**: Add `set_global_assignment -name FITTER_EFFORT "STANDARD FIT"` to QSF.

## 7. Wrong Submodule / Directory (Occurrences: 1)

**Symptom**: Changes don't take effect, builds use old files.

**Root cause**: Two copies of jop-spinalhdl exist:
- `/home/peter/jop-spinalhdl/` (standalone)
- `/home/peter/atari800-spinalhdl/jop-spinalhdl/` (submodule — USE THIS ONE)

**Check**: `pwd` — must be under `atari800-spinalhdl/jop-spinalhdl/`.

## 8. quartus_pgm Can't Find USB-Blaster (Occurrences: 1)

**Symptom**: `quartus_pgm` errors with "no programming hardware found".

**Fix**: Run `jtagconfig` first to initialize the JTAG daemon, then retry.

## 9. Missing UART RXD Pin — Break Kills TX (Occurrences: 1)

**Symptom**: JOP executes (both LEDs blink — heartbeat + watchdog), simulation shows
correct UART output, but no data on physical serial port at any baud rate.

**Root cause**: SpinalHDL's UartCtrl has break detection: when RXD is held low, the RX
module asserts `rx_io_break`, which **disables TX** by throwing away all FIFO writes:
```verilog
if(rx_io_break) begin
  io_write_throwWhen_valid = 1'b0;  // TX data silently discarded
end
```
If `ser_rxd` has no pin assignment in the QSF, it floats low → permanent break → TX dead.

**Check**: `grep ser_rxd *.qsf` — must have a `set_location_assignment` entry.
Also check generated Verilog: if the top module has `input wire ser_rxd`, it needs a pin.

**Fix**: Add to QSF (EP4CGX150 DB_FPGA: CP2102N RXD = PIN_AE21):
```
set_location_assignment PIN_AE21 -to ser_rxd
```

## 10. Stale Microcode After jvm.asm Changes (Occurrences: 1)

**Symptom**: JOP heartbeat LED blinks but no UART output — no serial ready signal (0xAA),
no output at any baud rate. Both simulation and hardware affected.

**Root cause**: `asm/src/jvm.asm` was modified but `asm/generated/serial/mem_rom.dat`
was not regenerated. SpinalHDL embeds stale microcode into the Verilog at elaboration time.
The old microcode may have incompatible serial boot code or missing handlers.

**Check**: Compare timestamps:
```bash
ls -la asm/src/jvm.asm asm/generated/serial/mem_rom.dat
```
If `jvm.asm` is newer than `mem_rom.dat`, the microcode is stale.

**Fix**: Rebuild microcode before generating Verilog:
```bash
cd jop-spinalhdl/asm && make serial
```
Then regenerate Verilog and rebuild Quartus.

## 11. Test in Simulation First (General Rule)

Before debugging hardware, always verify in simulation:
```bash
sbt "Test / runMain jop.system.JopCoreBramSim"
```
If simulation also fails, it's a software/config issue, not hardware.
