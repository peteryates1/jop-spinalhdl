// Behavioural model of the Lattice ECP5 ODDRX1F primitive, for Verilator
// tests only (synthesis uses the real cell; yosys ships no model of it).
// jop.io.LatticeOddrx1f instantiates it: D0 and D1 are both taken on the
// rising edge of SCLK; Q shows D0 for the high half of that cycle and D1 for
// the low half -- the Xilinx ODDR's SAME_EDGE timing with D1/D2 renamed D0/D1.
// The real cell's latency is not modelled, and the design does not depend on
// it: on the ECP5 every RGMII TX pin leaves one of these on the same clock,
// so whatever the latency is, it is the same on all of them. RST is not
// modelled (the design ties it low).
//
// Q is selected by SCLK rather than driven from a posedge and a negedge block,
// which the simulator rejects as MULTIDRIVEN. (No comment line here may start
// with the simulator's name: it would be read as a directive.)
module ODDRX1F (
  input D0,
  input D1,
  input SCLK,
  input RST,
  output Q
);
  reg q0 = 1'b0;
  reg q1 = 1'b0;
  always @(posedge SCLK) begin
    q0 <= D0;
    q1 <= D1;
  end
  assign Q = SCLK ? q0 : q1;
endmodule
