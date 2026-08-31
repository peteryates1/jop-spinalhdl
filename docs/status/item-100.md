# Item 100 — The newcomer hardware path, and a JTAG cable that was INTERMITTENT

Journal split out of `docs/current-status.md` on 2026-08-31 (item 116).
Summary and current state: [item 100](../current-status.md#item-100).

---

> **2026-08-31 — measured, and it is not the cable.** The two Altera cables were
> swapped: the Terasic (`91d28408`) onto the EP4CGX150, the level-shifted Pico
> (`e6616408`) onto the A-E115FB. Ten single-try detections each, retries
> disabled (`JTAG_SCAN_TRIES=1`, or the built-in retry hides exactly what is
> being measured):
>
> | | Pico `e6616408` | Terasic `91d28408` |
> |---|---|---|
> | EP4CGX150 | 3/10 (before swap) | **10/10** |
> | A-E115FB | **10/10** | worked (before swap) |
>
> The Pico is fine. It was 3/10 on the EP4CGX150 and is perfect on the
> A-E115FB, so the fault travelled with the BOARD END, not the cable — which is
> the opposite of what the old heading ("a cable that died") asserted.
>
> **The experiment alone carries a confound**: the swap changed which cable AND
> re-seated both connectors, so "the pairing was bad" and "a connector was
> loose" both fit the numbers. What separates them is not the measurement but a
> direct observation — the operator reports the **board-side** plug was loose,
> on the connector that is hard to reach and had been assumed good because it is
> an ordinary header rather than the breadboard wiring that looks precarious.
>
> That account fits every cell: intermittent contact at the EP4CGX150 header
> gives 3/10, and the swap necessarily re-seated it. It is not proof — the
> looseness was noticed while removing the plug, so it may have been caused
> then. The falsifiable prediction is that the Pico would now also read 10/10 on
> the EP4CGX150; nobody has run that yet.
>
> The lesson worth keeping is which end got suspected. Two days of investigation
> went to the cable, the firmware, the clock rate, the level shifter and the
> monitor's power supply, because the breadboard end LOOKS unreliable — while
> the reliable-looking end went unexamined.
>
> The EP4CGX150 now has a dependable cable either way, which is what
> [item 101](#item-101) was blocked on.

Continuing the cold-newcomer exercise (items 95-99) onto real hardware. The
agent derived the command sequence from the documentation alone; the commands
were then run in a session with the permissions to write to a board, because a
subagent inherits none of `.claude/settings.local.json` (gitignored, so absent
from a fresh clone).

**Two boards verified end to end, from their documented sequences:**

| board | toolchain | result |
|---|---|---|
| **Colorlight i5** | yosys / nextpnr / ecppack — open source | `49.40 MHz PASS`, bitstream loaded, **`Hello World!`** |
| **A-E115FB** | Quartus 18.1 | IDCODE asserted, configured, **`Hello World!`** |
| EP4CGX150 | Quartus | **blocked — cable hardware, not documentation** |

**The i5 is now what the README points a newcomer at.** One USB cable carries
programming and console, the toolchain is entirely open source, and there is no
blaster to select — which is the step that cost this exercise an afternoon on
the EP4CGX150. The EP4CGX150 remains the primary DEVELOPMENT board; those are
different questions and the README now separates them.

**The EP4CGX150's Pico cable is INTERMITTENT, not failed** -- corrected
2026-08-30. It detects **3 times in 10**, and the failures are bursty rather
than independent: with retries at six attempts, two checks of four still failed
outright, where independent 30 % samples would fail about one time in eight.

**The original diagnosis below was wrong, and the way it was wrong is the
lesson.** Every check was a SINGLE scan, so a run of unlucky samples read as a
hard fault. The contradiction was visible hours earlier and explained away:
`jtagconfig --debug` read a chain that plain `jtagconfig` had just called
broken, and that was put down to a re-scan effect. The elimination below is
sound in method and worthless in conclusion -- "openFPGALoader fails too"
carries almost no weight when one probe is a coin flip.

`jtag_probe_map --assert-device` now scans up to 10 times
(`JTAG_SCAN_TRIES`) and says so when it needed more than one, because a plain
pass on a marginal cable hides the thing that wastes the afternoon. **Retry
buys an honest answer, not a usable cable**: a probe needing four attempts to
read one 32-bit IDCODE will not carry a 4.9 MB bitstream.

**LOWERING THE CLOCK DOES NOT HELP** -- confirmed against a real 4.88 MB
programming run, not just IDCODE reads. The first version of this conclusion
was drawn from DETECTION rates alone, which was the wrong measurement:
detection is a microsecond burst and mostly works, while programming is ~70 s
of continuous shifting and always fails. Slowing a clock cannot help a short
read that already succeeds, but it is exactly the fix for errors accumulating
over a long stream -- so the experiment had to be redone. At 750 kHz a full
programme still failed, after 410 s.

**AND IT NAMED THE FAULT.** That run reported:

```
Error (209015): ... Expected JTAG ID code 0x028040DD ... but found 0xFFFFFFFF
```

All ones is a FLOATING input -- undriven, not corrupted. Interference garbles
bits randomly; it does not produce a clean 0xFFFFFFFF. That argues against EMI
and for an open TDO return path, or for VTREF on header pin 4 sagging so the
level shifter stops driving. It also explains the asymmetry with no extra
assumptions: short reads land in good windows, long transfers never do.

Check with a meter, in order: VTREF on pin 4, then the TDO conductor.

The clock data as originally gathered (detection only):

| firmware | detection rate |
|---|---|
| 6 MHz (`JTAG_CLKDIV=5`) | 3/10, later 1/10 |
| 750 kHz (`JTAG_CLKDIV=40`) | 1/10 |
| 6 MHz again, after restoring | 1/10 |

Signal integrity cannot survive that: marginal edges, reflections and coupling
all improve dramatically at an eighth of the speed. The rate is
CLOCK-INDEPENDENT and drifts over time, which is the fingerprint of an
intermittent CONNECTION -- a lead not fully seated, a contact making and
breaking, or Vref on header pin 4 dropping in and out.

**The board was physically moved between the working state and this one.** It
configured a 4.88 MB .sof reliably on 2026-08-27 at 6 MHz (recorded in
pico-usb-blaster's `blaster_jtag.pio`, which concludes "the clock was never the
problem"), was relocated, and now reads one IDCODE in ten. Re-seat the leads
and check VTREF before touching firmware again.

The clock is a build option (`JTAG_CLKDIV` in pico-usb-blaster/src) and
rebuild-and-reflash is remote via SWD, so this experiment costs about five
minutes and is worth repeating only if something changes electrically.

The elimination as originally recorded, which ruled out everything except the
cable and was right about that much:

| ruled out | how |
|---|---|
| board power | LEDs on, confirmed by eye |
| USB layer | enumerates, re-enumerates, survives `USBDEVFS_RESET` and a VM reassignment |
| Pico firmware | restarted over SWD via the Debug Probe — device re-enumerated, no change |
| Quartus / `jtagd` | **openFPGALoader fails identically** — a different driver stack entirely |
| host, toolchain, passthrough | the Terasic works through all of it, concurrently |

What remains is the cable hardware: the level-shifter carrier, its Vref pickup
from header pin 4, or a Pico GPIO. Needs bench time.

**Two remote techniques worth keeping**, both new here:

1. **SWD reset/reflash of a Pico probe** through the Raspberry Pi Debug Probe.
   `USBDEVFS_RESET` and a VM re-assignment rebuild the USB *link* only; neither
   restarts RP2040 firmware. SWD does.
2. **Isolating one of two identical probes without touching cables** — write 0
   to `/sys/bus/usb/devices/<port>/authorized`. Note that `unbind` does NOT
   work for this: openFPGALoader reaches the device through libusb, so kernel
   driver binding is irrelevant and the device stays visible. Deauthorization
   is what removes it. Both attempts are recorded because the first looked like
   it had worked, and only checking the precondition showed it had not.

**A diagnostic distinction worth knowing:** `jtagconfig --debug` prints
`Captured DR after reset = ()`. An EMPTY capture means the cable never
completed a shift; a capture of ZEROS means it shifted and the target answered
with nothing. A dead cable on a good board and a good cable on a dead board are
indistinguishable in `quartus_pgm`'s output, which exits 0 for both.
