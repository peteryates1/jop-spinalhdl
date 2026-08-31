# Item 91 — Two Altera boards, two cables, and a check that finally has a reason

Journal split out of `docs/current-status.md` on 2026-08-31 (item 116).
Summary and current state: [item 91](../current-status.md#item-91).

---

The level shifters arrived, so the EP4CGX150 now has its own pico-usb-blaster
(serial `e6616408` -- the Pico then believed to be blocked driving 3.3 V into
the A-E115FB's JTAG; that bank measured **3.25 V** on 2026-08-31, so it was
never an over-voltage problem) and the Terasic (`91d28408`) stays on the
A-E115FB. Both are attached at once and both were proven on hardware today:

| board | cable | Quartus cable name | result |
|---|---|---|---|
| EP4CGX150 | pico-usb-blaster | `USB Blaster [1-11]` | IDCODE `028040DD`, configured, `Hello World!` |
| A-E115FB | Terasic | `USB-Blaster [1-7]` | IDCODE `020F70DD`, configured, `Hello World!` |

Note the product strings differ by one hyphen, which is the only thing that
made two blasters survivable before the port path was added. `--cable` reads
the string from sysfs and appends the port path, so it separates two probes of
the same kind too.

#### Why the Pico cable is slower, measured

Not the clock: both cables report **6 MHz**, and both are full-speed USB with
64-byte bulk endpoints -- the Pico deliberately emulates the FT245 geometry.
Splitting a run at Quartus's own `Started/Ended Programmer operation`:

| | Terasic | Pico |
|---|---|---|
| chain detect (before programming) | 1 s | **31 s** |
| configuration | 6 s | 43 s |
| bitstream | 3.57 MB | 4.93 MB |
| **throughput** | **595 KB/s** | **115 KB/s** |
| % of the 6 MHz ceiling (750 KB/s) | 79 % | 15 % |

So the ~9.6x wall-clock gap is three effects stacked, and the biggest single
one is **not throughput** -- it is ~30 s of fixed cost before a byte of
bitstream moves. That cost is identical under Quartus 18.1 (31.64 s) and 25.1
(31.77 s), so it belongs to the cable, not the tool.

**The limit is USB latency, not bandwidth and not TCK.** Full-speed carries
~1.2 MB/s and 6 MHz byte-shift needs only 750 KB/s, so neither is binding. Per
1 ms USB frame the Terasic moves ~595 bytes (9-10 bulk packets in flight); the
Pico moves ~115 bytes, about two. A genuine USB-Blaster streams; the Pico's
emulation round-trips roughly once per frame.

That also explains the 30 s. Chain detection is *many tiny transactions*, the
worst possible shape for a latency-bound cable -- and with both boards powered
off, the same scan on both cables returned in ~0 s. No device, no transactions,
no penalty. It scales with transaction count, not with opening the cable.

**Two corrections to the first numbers recorded here.** The steady-state
configure is 43 s, not 72 s -- 72 s was the first configuration after power-up
and has not recurred in three runs since. And "14x" compared different
bitstreams built by different Quartus versions: the real figures are 9.6x wall
clock and 5.2x throughput. A cable comparison carries a confound as easily as a
preset one does.

The **negative** test is the one that proves the mapping rather than the wiring.
Forcing each alias onto the other's cable is refused:

```
$ jtag_probe_map --assert-device ae115fb "USB Blaster [1-11]"
WRONG BOARD on 'USB Blaster [1-11]': IDCODE 028040DD, expected 020F70DD ...
```

**`--assert-device` now has a better justification than the one it was written
for.** It existed because the two boards shared a cable and programming the
wrong one was silent. They no longer share a cable -- but the check stayed,
because of this:

```
Info (213045): Using programming cable "USB Blaster [1-11]"
  Unable to read device chain - JTAG chain broken
Info: Quartus Prime Programmer was successful. 0 errors, 0 warnings
```

**`quartus_pgm` exits 0 on a chain it never read.** Both boards were powered off
at the start of the session and every tool in the flow reported success. A
powered-off board, an unplugged header and a level shifter with no Vref are all
indistinguishable from a good program unless something scans the chain first.
So `assert-device` moved out of the two board Makefiles that had hand-copied it
and into `fpga/quartus.mk`, where `program-sof` depends on it -- which picked up
the three EP4CGX150 variants and the SDRAM test board, none of which had it.

**A bug in the checker, found by the boards being off.** `assert_device` was
supposed to print "found no device -- board powered off, or cable on nothing".
It printed nothing at all, exit 1. Under `set -e` + `pipefail` a no-match `grep`
makes the `found=$(...)` assignment fail, and the function returns *there* --
before any of the diagnosis. The entire reason the check exists was unreachable
in exactly the case it was written for. `|| true` on the assignment.

### Gotcha — a serial adapter can stall in a way that looks like a dead board

The A-E115FB programmed perfectly over JTAG and its console would not open:
`[Errno 5] Input/output error`, on a `/dev/ttyUSB4` that existed, resolved by
alias, and had the right permissions. `dmesg`:

```
usb 1-12: failed to send control message: -32
ch341-uart ttyUSB4: failed to read modem status: -32
```

`-32` is `EPIPE`: the CH340's control endpoint had stalled. No amount of
retrying clears it and nothing in the flow reports anything more useful than
EIO. A `USBDEVFS_RESET` re-enumerates the device in place and fixes it, which
is now `usb_serial_map --reset <alias>` (unprivileged if possible, `sudo -n`
otherwise, then re-resolves because the tty name can change across the reset).
After that the same download ran first time.

Worth separating the two halves when a board looks dead: **JTAG and UART are
different cables with different failure modes**, and this one had a working
FPGA behind a broken adapter.
