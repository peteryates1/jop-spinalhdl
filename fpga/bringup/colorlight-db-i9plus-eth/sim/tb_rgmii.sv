`timescale 1ns/1ps
// behavioural models (Verilator has no UNISIM)
module ODDR #(parameter DDR_CLK_EDGE = "SAME_EDGE", parameter INIT = 1'b0, parameter SRTYPE = "SYNC")
  (output reg Q, input C, input CE, input D1, input D2, input R, input S);
  reg d2q;
  initial Q = INIT;
  always @(posedge C) begin Q <= D1; d2q <= D2; end
  always @(negedge C) Q <= d2q;
endmodule
module BUFG(input I, output O); assign O = I; endmodule
module MMCME2_BASE #(parameter real CLKIN1_PERIOD = 10.0, parameter integer DIVCLK_DIVIDE = 1,
    parameter real CLKFBOUT_MULT_F = 5.0, parameter real CLKOUT0_DIVIDE_F = 1.0,
    parameter real CLKOUT0_PHASE = 0.0)
  (input CLKIN1, input RST, input PWRDWN, input CLKFBIN, output CLKFBOUT, output CLKOUT0,
   output CLKOUT0B, output CLKOUT1, output CLKOUT1B, output CLKOUT2, output CLKOUT2B,
   output CLKOUT3, output CLKOUT3B, output CLKOUT4, output CLKOUT5, output CLKOUT6,
   output CLKFBOUTB, output LOCKED);
  // only what the design uses: CLKOUT0 = CLKIN1 (same frequency) shifted by CLKOUT0_PHASE
  assign #(CLKIN1_PERIOD * CLKOUT0_PHASE / 360.0) CLKOUT0 = CLKIN1;
  assign CLKFBOUT = CLKIN1;
  assign {CLKOUT0B, CLKOUT1, CLKOUT1B, CLKOUT2, CLKOUT2B, CLKOUT3, CLKOUT3B, CLKOUT4, CLKOUT5,
          CLKOUT6, CLKFBOUTB} = 0;
  assign LOCKED = 1;
endmodule

module tb;
  reg clk = 0; always #20 clk = ~clk;
  reg [3:0] sw_n = 4'h0;                      // all low at power-up: slow pull-ups on the board
  wire uart_tx, mdc, mdio; wire [7:0] led; wire [1:0] eth_led, txc, tx_ctl;
  wire [3:0] txd0, txd1;
  wire rxc0, rxctl0; wire [3:0] rxd0;
  assign #1 rxc0 = clk;
  assign #1 rxd0 = txd0;
  assign #1 rxctl0 = tx_ctl[0];
  reg rxc1 = 0; always #4 rxc1 = ~rxc1;
  EthRgmiiTest #(.SEC_CYCLES(1_250_000), .BTN_BITS(8)) dut(
    .clk25(clk), .sw_n(sw_n), .uart_tx(uart_tx), .mdc(mdc), .mdio(mdio),
    .txc(txc), .txd0(txd0), .txd1(txd1), .tx_ctl(tx_ctl),
    .rxc({rxc1, rxc0}), .rxd0(rxd0), .rxd1(4'h0), .rx_ctl({1'b0, rxctl0}),
    .led(led), .eth_led(eth_led));
  reg phy_oe = 0, phy_o = 0;
  assign mdio = phy_oe ? phy_o : 1'bz;
  pullup(mdio);
  reg [63:0] sh = 0; reg [15:0] val; integer dbit = -1, wcnt = -1; reg [4:0] wreg;
  function [15:0] regval(input [4:0] r);
    case (r) 0: regval = 16'h1000; 1: regval = 16'h796d; 9: regval = 16'h0000;
             5'h19: regval = 16'h851c; default: regval = 16'hdead; endcase
  endfunction
  always @(posedge mdc) begin
    #100;
    if (dbit >= 0) begin
      if (dbit == 16) begin phy_oe <= 0; dbit = -1; end
      else begin phy_o <= val[15 - dbit]; dbit = dbit + 1; end
    end else if (wcnt >= 0) begin
      sh = {sh[62:0], mdio}; wcnt = wcnt + 1;
      if (wcnt == 18) begin $display("%0t MDIO WRITE phy1 reg %0d = %04x (TA %b)", $time, wreg, sh[15:0], sh[17:16]); wcnt = -1; sh = 0; end
    end else begin
      sh = {sh[62:0], mdio};
      if (sh[13:12] == 2'b01 && &sh[45:14] && sh[9:5] == 5'd1) begin
        if (sh[11:10] == 2'b10) begin
          val = regval(sh[4:0]);
          @(posedge mdc); #100; phy_oe <= 1; phy_o <= 0; dbit = 0;
        end else if (sh[11:10] == 2'b01) begin wreg = sh[4:0]; wcnt = 0; end
        sh = 0;
      end
    end
  end
  // TXC against the frame: from TX_CTL rising (start of nibble 0) to the next TXC edges
  realtime t_ctl; integer want_rise = 0, want_fall = 0;
  always @(posedge tx_ctl[0]) begin t_ctl = $realtime; want_rise = 1; want_fall = 1; end
  always @(posedge txc[0]) if (want_rise) begin $display("%0t TXC rise %0.1f ns into the nibble", $time, $realtime - t_ctl); want_rise = 0; end
  always @(negedge txc[0]) if (want_fall) begin $display("%0t TXC fall %0.1f ns into the nibble", $time, $realtime - t_ctl); want_fall = 0; end
  integer txc_edges = 0; always @(posedge txc[0]) txc_edges++;
  initial forever begin #10_000_000; $display("%0t TXC0 edges %0d", $time, txc_edges); txc_edges = 0; end
  integer i; reg [7:0] b; string line = "";
  initial forever begin
    @(negedge uart_tx); #(4340);
    for (i = 0; i < 8; i++) begin #(8680); b[i] = uart_tx; end
    #(8680);
    if (b == 8'h0A) begin $display("%0t UART: %s", $time, line); line = ""; end
    else if (b != 8'h0D) line = {line, string'(b)};
  end
  task press(input integer k); begin
    sw_n[k] = 0; #5_000_000; sw_n[k] = 1; $display("%0t pressed %0d", $time, k);
  end endtask
  initial begin
    #30_000_000 sw_n = 4'hF; $display("%0t buttons released", $time);
    #85_000_000  press(1);                     // 120 ms OPTION: TXC 90 -> 270
    #45_000_000  press(1);                     // 170 ms OPTION: TXC 270 -> off
    #45_000_000  press(1);                     // 220 ms OPTION: TXC off -> 90
    #5_000_000   press(3);                     // 230 ms START: loopback on
    #45_000_000  press(2);                     // 280 ms SELECT: TX off
    #45_000_000;                               // 330 ms
    @(posedge clk); #1 force dut.st = 5'd30; @(posedge clk); #1 release dut.st;
    $display("%0t forced st = 30", $time);
    #45_000_000  press(0);                     // 380 ms RESET: clear the record
    #15_000_000  press(3);                     // 400 ms START: loopback off
    #65_000_000  $finish;                      // 470 ms
  end
endmodule
