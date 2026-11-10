// Behavioural model of the Xilinx 7-series IDDR primitive, for Verilator
// tests only (synthesis uses the real primitive). SAME_EDGE_PIPELINED mode as
// jop.io.XilinxIddr instantiates it (UG471): D is sampled on the rising edge
// of C and on the falling edge after it, and the pair comes out together on
// the NEXT rising edge -- Q1 the rising-edge sample, Q2 the falling-edge one.
// R and S are not modelled (the design ties them low).
module IDDR #(
  parameter DDR_CLK_EDGE = "SAME_EDGE_PIPELINED",
  parameter INIT_Q1 = 1'b0,
  parameter INIT_Q2 = 1'b0,
  parameter SRTYPE = "SYNC"
) (
  output reg Q1,
  output reg Q2,
  input C,
  input CE,
  input D,
  input R,
  input S
);
  reg rise = INIT_Q1;
  reg fall = INIT_Q2;
  initial begin
    Q1 = INIT_Q1;
    Q2 = INIT_Q2;
  end
  always @(posedge C) if (CE) begin
    rise <= D;
    Q1 <= rise;
    Q2 <= fall;
  end
  always @(negedge C) if (CE) fall <= D;
endmodule
