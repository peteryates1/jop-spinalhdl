// Behavioural model of the Xilinx 7-series BUFG primitive, for Verilator
// tests only (synthesis uses the real primitive): a global clock buffer is a
// wire as far as logic is concerned. jop.io.XilinxBufg instantiates it.
module BUFG (
  output O,
  input I
);
  assign O = I;
endmodule
