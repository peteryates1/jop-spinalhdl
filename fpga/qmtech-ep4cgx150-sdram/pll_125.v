// PLL: 50MHz input -> 125MHz output for Ethernet GMII TX
// c0: 125 MHz, 0° phase — TX logic clock and e_gtxc to PHY
//
// RX uses source-synchronous e_rxc from PHY with FAST_INPUT_REGISTER
// constraints for I/O block register placement.

module pll_125 (
	input  inclk0,    // 50MHz board oscillator
	output c0,        // 125MHz, 0° phase
	output locked
);

	wire [4:0] sub_wire0;
	assign c0 = sub_wire0[0];

	altpll #(
		.bandwidth_type("AUTO"),
		.clk0_divide_by(2),
		.clk0_duty_cycle(50),
		.clk0_multiply_by(5),
		.clk0_phase_shift("0"),
		.compensate_clock("CLK0"),
		.inclk0_input_frequency(20000),  // 50MHz = 20000ps period
		.intended_device_family("Cyclone IV GX"),
		.lpm_hint("CBX_MODULE_PREFIX=pll_125"),
		.lpm_type("altpll"),
		.operation_mode("NORMAL"),
		.pll_type("AUTO"),
		.port_activeclock("PORT_UNUSED"),
		.port_areset("PORT_UNUSED"),
		.port_clkbad0("PORT_UNUSED"),
		.port_clkbad1("PORT_UNUSED"),
		.port_clkloss("PORT_UNUSED"),
		.port_clkswitch("PORT_UNUSED"),
		.port_configupdate("PORT_UNUSED"),
		.port_fbin("PORT_UNUSED"),
		.port_inclk0("PORT_USED"),
		.port_inclk1("PORT_UNUSED"),
		.port_locked("PORT_USED"),
		.port_pfdena("PORT_UNUSED"),
		.port_phasecounterselect("PORT_UNUSED"),
		.port_phasedone("PORT_UNUSED"),
		.port_phasestep("PORT_UNUSED"),
		.port_phaseupdown("PORT_UNUSED"),
		.port_pllena("PORT_UNUSED"),
		.port_scanaclr("PORT_UNUSED"),
		.port_scanclk("PORT_UNUSED"),
		.port_scanclkena("PORT_UNUSED"),
		.port_scandata("PORT_UNUSED"),
		.port_scandataout("PORT_UNUSED"),
		.port_scandone("PORT_UNUSED"),
		.port_scanread("PORT_UNUSED"),
		.port_scanwrite("PORT_UNUSED"),
		.port_clk0("PORT_USED"),
		.port_clk1("PORT_UNUSED"),
		.port_clk2("PORT_UNUSED"),
		.port_clk3("PORT_UNUSED"),
		.port_clk4("PORT_UNUSED"),
		.port_clk5("PORT_UNUSED"),
		.port_clkena0("PORT_UNUSED"),
		.port_clkena1("PORT_UNUSED"),
		.port_clkena2("PORT_UNUSED"),
		.port_clkena3("PORT_UNUSED"),
		.port_clkena4("PORT_UNUSED"),
		.port_clkena5("PORT_UNUSED"),
		.port_extclk0("PORT_UNUSED"),
		.port_extclk1("PORT_UNUSED"),
		.port_extclk2("PORT_UNUSED"),
		.port_extclk3("PORT_UNUSED"),
		.self_reset_on_loss_lock("OFF"),
		.width_clock(5)
	) altpll_component (
		.inclk({1'b0, inclk0}),
		.clk(sub_wire0),
		.locked(locked),
		.activeclock(),
		.areset(1'b0),
		.clkbad(),
		.clkena({6{1'b1}}),
		.clkloss(),
		.clkswitch(1'b0),
		.configupdate(1'b0),
		.enable0(),
		.enable1(),
		.extclk(),
		.extclkena({4{1'b1}}),
		.fbin(1'b1),
		.fbmimicbidir(),
		.fbout(),
		.fref(),
		.icdrclk(),
		.pfdena(1'b1),
		.phasecounterselect({4{1'b1}}),
		.phasedone(),
		.phasestep(1'b1),
		.phaseupdown(1'b1),
		.pllena(1'b1),
		.scanaclr(1'b0),
		.scanclk(1'b0),
		.scanclkena(1'b1),
		.scandata(1'b0),
		.scandataout(),
		.scandone(),
		.scanread(1'b0),
		.scanwrite(1'b0),
		.sclkout0(),
		.sclkout1(),
		.vcooverrange(),
		.vcounderrange()
	);

endmodule
