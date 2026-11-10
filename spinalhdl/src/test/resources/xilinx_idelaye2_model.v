// Behavioural model of the Xilinx 7-series IDELAYE2 primitive, for Verilator
// tests only (synthesis uses the real primitive). FIXED mode as
// jop.io.XilinxIdelaye2 instantiates it: DATAOUT is IDATAIN. The delay itself
// (IDELAY_VALUE taps of 78 ps, plus the cell's own) is a timing matter for
// Vivado, not a logic one, so it is not modelled. CNTVALUEOUT is unused.
module IDELAYE2 #(
  parameter CINVCTRL_SEL = "FALSE",
  parameter DELAY_SRC = "IDATAIN",
  parameter HIGH_PERFORMANCE_MODE = "TRUE",
  parameter IDELAY_TYPE = "FIXED",
  parameter integer IDELAY_VALUE = 0,
  parameter PIPE_SEL = "FALSE",
  parameter real REFCLK_FREQUENCY = 200.0,
  parameter SIGNAL_PATTERN = "DATA"
) (
  output [4:0] CNTVALUEOUT,
  output DATAOUT,
  input C,
  input CE,
  input CINVCTRL,
  input [4:0] CNTVALUEIN,
  input DATAIN,
  input IDATAIN,
  input INC,
  input LD,
  input LDPIPEEN,
  input REGRST
);
  assign DATAOUT = IDATAIN;
  assign CNTVALUEOUT = 5'd0;
endmodule
