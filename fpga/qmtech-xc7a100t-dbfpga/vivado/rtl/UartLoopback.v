// UART pin loopback: FPGA B5 (rxd from RP2040 GPIO0) → A5 (txd to RP2040 GPIO1).
// Pure combinational wire — no clock, no buffering logic.
// Use to sanity-check RP2040 GPIO0 → FPGA B5 → FPGA A5 → RP2040 GPIO1 path.
module UartLoopback (
  input  wire rxd,
  output wire txd
);
  assign txd = rxd;
endmodule
