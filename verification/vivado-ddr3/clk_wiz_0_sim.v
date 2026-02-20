// Behavioral simulation model for Vivado ClkWiz IP (clk_wiz_0).
// Replaces the Xilinx MMCM/PLL-based clock wizard with simple toggle regs.
// Port names match ClkWizBlackBox.scala setBlackBoxName("clk_wiz_0").
//
// NOTE: The "resetn" port is ACTIVE-HIGH in JopDdr3Top:
//   JopDdr3Top drives: clkWiz.io.resetn := !ClockDomain.current.readResetWire
//   So resetn=1 means "in reset", resetn=0 means "normal operation".

`timescale 1ps / 1ps

module clk_wiz_0 (
  input  resetn,     // Active-HIGH reset (despite the name; see JopDdr3Top.scala)
  input  clk_in,     // 100 MHz board clock input (used as reference only)
  output clk_100,    // 100 MHz output
  output clk_200,    // 200 MHz output
  output locked      // PLL locked indicator
);

  // Generate 100 MHz clock: period = 10,000 ps = 10 ns
  reg clk_100_reg = 0;
  always #5000 clk_100_reg = ~clk_100_reg;
  assign clk_100 = clk_100_reg;

  // Generate 200 MHz clock: period = 5,000 ps = 5 ns
  reg clk_200_reg = 0;
  always #2500 clk_200_reg = ~clk_200_reg;
  assign clk_200 = clk_200_reg;

  // Assert locked after reset deasserts (resetn goes 0) + settling time (~1 us)
  reg locked_reg = 0;

  always @(posedge clk_100_reg or posedge resetn) begin
    if (resetn) begin
      // Reset asserted (active-HIGH) — clear locked
      locked_reg <= 0;
    end
  end

  initial begin
    locked_reg = 0;
    // Wait for reset to deassert (resetn goes LOW = normal operation)
    wait (resetn === 1'b0);
    // Wait ~1us (100 cycles of 100MHz) after reset release
    repeat (100) @(posedge clk_100_reg);
    locked_reg = 1;
    $display("[%0t] clk_wiz_0: locked", $time);
  end

  assign locked = locked_reg;

endmodule
