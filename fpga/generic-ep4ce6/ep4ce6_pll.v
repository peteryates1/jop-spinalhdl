// EP4CE6 PLL stub for fit-check (replace with Quartus altpll IP for real build)
// 50 MHz input -> 80 MHz system + 80 MHz SDRAM (phase-shifted)
module ep4ce6_pll (
    input  inclk0,
    input  areset,
    output c0,      // 80 MHz system clock
    output c1,      // 80 MHz SDRAM clock (phase-shifted)
    output locked
);

// Quartus will replace this with altpll megafunction.
// For synthesis fit-check, just pass through.
assign c0 = inclk0;
assign c1 = inclk0;
assign locked = ~areset;

endmodule
