// Behavioural model of the Xilinx 7-series ODDR primitive, for Verilator
// tests only (synthesis uses the real primitive). SAME_EDGE mode as
// jop.io.XilinxOddr instantiates it: D1 and D2 are both taken on the rising
// edge of C; Q shows D1 for the high half of that cycle and D2 for the low
// half. R and S are not modelled (the design ties them low).
//
// Q is selected by C rather than driven from a posedge and a negedge block,
// which the simulator rejects as MULTIDRIVEN. (No comment line here may start
// with the simulator's name: it would be read as a directive.)
module ODDR #(
  parameter DDR_CLK_EDGE = "SAME_EDGE",
  parameter INIT = 1'b0,
  parameter SRTYPE = "SYNC"
) (
  output Q,
  input C,
  input CE,
  input D1,
  input D2,
  input R,
  input S
);
  reg q1 = INIT;
  reg q2 = INIT;
  always @(posedge C) if (CE) begin
    q1 <= D1;
    q2 <= D2;
  end
  assign Q = C ? q1 : q2;
endmodule
