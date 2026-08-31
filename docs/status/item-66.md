# Item 66 — the EP4CGX150's Ethernet/VGA/SD was lost in a migration, not removed

Journal split out of `docs/current-status.md` on 2026-08-31 (item 116).
Summary and current state: [item 66](../current-status.md#item-66).

---

**Found 2026-08-25** while deciding whether to convert the flow.

`jop_dbfpga.qsf` assigns **95 pins**, including Ethernet (`e_mdc`, `e_mdio`,
`e_rxd`, `e_txd`, `e_gtxc`, …), VGA (`vga_r/g/b`, `vga_hs`, `vga_vs`) and SD
(`sd_clk`, `sd_cmd`, `sd_dat_*`, `sd_cd`).

Its `generate-dbfpga` target runs **`ep4cgx150Serial`**, whose top has 45 ports:
`clk_in`, `led`, `sdram_*`, `ser_rxd`, `ser_txd`. Nothing else. So fifty
assignments name ports that do not exist — which Quartus reports as warnings and
otherwise ignores, so the flow "builds" while none of that hardware is driven.

**CORRECTED — it is not a stale project. This board HAD working Ethernet, and
the capability was lost in one line.** Commit **`8641942`** (2026-03-14, "Fix
connector labels and device assignments for QMTECH boards and DB_FPGA"):

```diff
 generate-dbfpga:
-	sbt "runMain jop.system.JopDbFpgaTopVerilog"
+	sbt "runMain jop.system.JopTopVerilog ep4cgx150Serial"

 generate-dbfpga-vgadma:
-	sbt "runMain jop.system.JopDbFpgaVgaDmaTopVerilog"
+	sbt "runMain jop.system.JopTopVerilog ep4cgx150Serial"
```

Two DIFFERENT tops -- one carrying Ethernet/VGA/SD, one carrying VGA DMA -- were
both repointed at a preset that declares none of them, as part of the migration
away from hand-written tops (`7258661`, "Remove IoConfig and legacy tops"). The
`.qsf` still describes what the design used to have. That is also why the two
dbfpga flows now emit byte-identical RTL.

`docs/peripherals/networking.md` documents the working system in detail -- "a
poll-based TCP/IP stack running on the QMTECH EP4CGX150 + DB_FPGA daughter board
with RTL8211EG Gigabit Ethernet PHY", 1 Gbps GMII with MDIO, ARP, DHCP, TCP --
and its build instructions still name `JopDbFpgaTopVerilog`, a main that no
longer exists.

**Everything except the preset survived:**

| piece | state |
|---|---|
| `RTL8211EG`, `VGA`, `SD_CARD` on the DB v4 board | present in `Board.scala` |
| `ethernet`, `vgadma`, `vgatext`, `sdnative`, `sdspi` device types | present |
| Java TCP/IP stack | 16 files in `java/net/src/com/jopdesign/net/` |
| `NetTest`, `DhcpTest`, `HttpServer` | present in `java/apps/Small` |
| a preset wiring them together | **missing** |

**The fix is a preset, and its template already exists.** `xc7a100tDbFull`
declares exactly this device set -- `RTL8211EG`, `VGA`, `SD_CARD` -- on the DB
**v5** assembly. The EP4CGX150 equivalent is the same map on
`SystemAssembly.qmtechWithDb` with the UART on `CP2102N` rather than `RP2040`.
Once it exists, `generate-dbfpga` names it, the 95 pins have ports again, and
the constraints generate from the config like every other converted flow.

Worth doing on its own merits: it restores a documented, hardware-proven
capability, and it is the only EP4CGX150 configuration that would exercise the
Ethernet path at all.

**WRITTEN BACK 2026-08-25.** `JopConfig.ep4cgx150DbFull` -- the device set mined
from `IoConfig.qmtechDbFpga` in history, in the modern declarative form taken
from `xc7a100tDbFull`. Its generated project is **PIN-IDENTICAL to the
hand-written `jop_dbfpga.qsf`, all 95**, which is the evidence that the
reconstruction is faithful.

| | |
|---|---|
| fit | **15,282 LE, 95 pins, Fitter Successful** |
| clocks | `clk_in` 50 MHz, `dramPll` 80 MHz system, `ethPll` **125 MHz** |

**TIMING: MET, once a one-word bug was fixed.** The first build reported −1.812
ns setup and it was not a timing problem at all. Every failing path was
`StreamCCByToggle` inside `MacTxManagedStreamFifoCc` — the clock-domain crossing
between the 80 MHz system and the 125 MHz Ethernet TX domain, which is
asynchronous BY CONSTRUCTION and must be excluded.

`TimingConstraints.forConfig` tested `deviceType.key == "eth"`. The DeviceType
key is **`ethernet`**; `eth` is only the conventional MAP key a preset happens to
use. So the predicate was never true on any design, no Ethernet clock group was
ever emitted, and with fewer than two groups the whole `set_clock_groups` is
dropped — leaving the CDC paths timed as if synchronous.

| clock | before | after |
|---|---|---|
| `dramPll` clk[1] — 80 MHz system | −1.812 (TNS −20.755) | **+0.458** (TNS 0) |
| `ethPll` clk[0] — 125 MHz Ethernet | −1.503 (TNS −17.161) | **+0.802** (TNS 0) |
| `dramPll` clk[3] | +0.667 | +0.704 |

15,270 LE, 95 pins, all three clocks MET.

**The VGA DMA sibling is also back (`ep4cgx150DbVgaDma`) and does NOT close.**
History had two DB_FPGA configurations -- `IoConfig.qmtechDbFpga` (VGA text) and
`qmtechDbFpgaVgaDma` -- and `8641942` repointed both at `ep4cgx150Serial`, so
they have produced byte-identical RTL ever since. Restored as a preset variant
(same 95 pins; VGA is VGA either way), it builds at 14,429 LE but the SYSTEM
clock misses by **−1.011 ns** while Ethernet passes at +0.619 — so this is not
the clock-group bug. The failing paths run into `BmbSdramCtrl32` from
`BmbMemoryController` and `VgaBmbDma`'s CC FIFO: a third BMB master pushing the
arbiter path over, which is [item 5](#item-5) / [item 31](#item-31) again rather
than anything specific to VGA.

**The same bug, twice, from opposite sides.** The comment beside that predicate
records an earlier fix: the hand-written `jop_sdram.sdc` named `e_rxc` on a
UART-only build, Quartus could not match it, and it discarded the whole
`set_clock_groups`. The replacement stopped naming what does not exist — and
never matched what does. Both versions produce the same symptom, silently: a
constraint file that looks right and constrains nothing.

**This was reachable only because a design used Ethernet.** No converted flow had
one until now, so the dead predicate cost nothing and showed nothing. Restoring
a capability found a bug in the machinery built to replace it.

One generator gap closed on the way: a board's Ethernet PLL (`pll_125.v`) had no
route into a generated project, so synthesis stopped with "instantiates
undefined entity". `Board.extraIpFiles` now carries it, emitted only when the
DESIGN declares an Ethernet device -- the board has the PLL either way. It is
hand-written IP and a PllSpec candidate.
