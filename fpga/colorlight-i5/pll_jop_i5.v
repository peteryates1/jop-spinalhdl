// JOP system PLL for the Colorlight i5 v7.0 (ECP5 LFE5U-25F).
//
//   clkin   25 MHz board oscillator (P3)
//   clkout0 40 MHz JOP system clock
//   clkout1 40 MHz SDRAM clock, shifted 315 deg (= -45 deg = -3.1 ns)
//
// GENERATED, do not hand-edit. Regenerate with:
//   ecppll --clkin 25 --clkout0 40 --clkout1 40 --phase1 315 \
//          --module pll_jop_i5 --file pll_jop_i5.v
//
// Kept verbatim on purpose: ecppll picks a divider set it guarantees will lock
// (Fpfd = 25/5 = 5 MHz, Fvco = 5*8*15 = 600 MHz, inside the legal 400-800 band,
// CLKOP = 600/15 = 40). Hand-tuning the dividers -- or the CPHASE/FPHASE pair
// that implements the phase shift -- is the standard way to end up with an
// out-of-band Fvco and a PLL that never asserts LOCK, which presents as the core
// sitting silently in reset with no other symptom.
//
// The -3.1 ns SDRAM clock shift matches the -3 ns every other SDR board in this
// project uses. The figure is an absolute delay (board trace plus IO delay), not
// a fraction of the period, so it is expressed here as the number of degrees
// that comes to at 40 MHz -- change the frequency and the degrees must change
// with it to keep the same nanoseconds.
//
// clkout1 is unused by the BRAM preset, which simply leaves it unconnected.
//
// Why 40 MHz and not 50: the BRAM design routes at 47.6-50.6 MHz depending only
// on the nextpnr placer seed, so 50 MHz passes on luck rather than headroom.
// See docs/boards/colorlight-i5-bringup.md.
//
// Instantiated from SpinalHDL as the I5Pll blackbox (jop.system.pll.Pll).

// diamond 3.7 accepts this PLL
// diamond 3.8-3.9 is untested
// diamond 3.10 or higher is likely to abort with error about unable to use feedback signal
// cause of this could be from wrong CPHASE/FPHASE parameters
module pll_jop_i5
(
    input clkin, // 25 MHz, 0 deg
    output clkout0, // 40 MHz, 0 deg
    output clkout1, // 40 MHz, 315 deg
    output locked
);
(* FREQUENCY_PIN_CLKI="25" *)
(* FREQUENCY_PIN_CLKOP="40" *)
(* FREQUENCY_PIN_CLKOS="40" *)
(* ICP_CURRENT="12" *) (* LPF_RESISTOR="8" *) (* MFG_ENABLE_FILTEROPAMP="1" *) (* MFG_GMCREF_SEL="2" *)
EHXPLLL #(
        .PLLRST_ENA("DISABLED"),
        .INTFB_WAKE("DISABLED"),
        .STDBY_ENABLE("DISABLED"),
        .DPHASE_SOURCE("DISABLED"),
        .OUTDIVIDER_MUXA("DIVA"),
        .OUTDIVIDER_MUXB("DIVB"),
        .OUTDIVIDER_MUXC("DIVC"),
        .OUTDIVIDER_MUXD("DIVD"),
        .CLKI_DIV(5),
        .CLKOP_ENABLE("ENABLED"),
        .CLKOP_DIV(15),
        .CLKOP_CPHASE(7),
        .CLKOP_FPHASE(0),
        .CLKOS_ENABLE("ENABLED"),
        .CLKOS_DIV(15),
        .CLKOS_CPHASE(20),
        .CLKOS_FPHASE(1),
        .FEEDBK_PATH("CLKOP"),
        .CLKFB_DIV(8)
    ) pll_i (
        .RST(1'b0),
        .STDBY(1'b0),
        .CLKI(clkin),
        .CLKOP(clkout0),
        .CLKOS(clkout1),
        .CLKFB(clkout0),
        .CLKINTFB(),
        .PHASESEL0(1'b0),
        .PHASESEL1(1'b0),
        .PHASEDIR(1'b1),
        .PHASESTEP(1'b1),
        .PHASELOADREG(1'b1),
        .PLLWAKESYNC(1'b0),
        .ENCLKOP(1'b0),
        .LOCK(locked)
	);
endmodule
