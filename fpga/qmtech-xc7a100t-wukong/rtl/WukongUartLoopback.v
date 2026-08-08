// UART loopback jig for Wukong bring-up — RX wired straight back to TX.
//
// Hand-written Verilog rather than generated from SpinalHDL: this is a test
// fixture, not part of any design, and three assigns do not warrant a
// generator entry.
//
// Purpose: the dual-subsystem SDR cluster's console is silent, and that could
// be the Pico's CDC-UART bridge, the J11 wiring, or the cluster never reaching
// boot. This removes the FPGA design from the question entirely — whatever the
// host writes to a port must come straight back. If it echoes, the bridge and
// wiring are good and the fault is in the cluster; if it does not, the fault is
// below the design.
//
//   ser_txd  / ser_rxd    E3 / F3   on-board CH340  — known-good control
//   ser_txd_1/ ser_rxd_1  G8 / G7   J11.4/.3 -> Pico GP13/GP12 (uart0)
//   ser_txd_2/ ser_rxd_2  G5 / D5   J11.2/.1 -> Pico GP5/GP4   (uart1)
//
// Pin directions are from the FPGA's side: the Pico's TX drives an FPGA input.
module WukongUartLoopback (
    input  wire       clk_in,
    input  wire       ser_rxd,
    output wire       ser_txd,
    input  wire       ser_rxd_1,
    output wire       ser_txd_1,
    input  wire       ser_rxd_2,
    output wire       ser_txd_2,
    output wire [1:0] led
);
    assign ser_txd   = ser_rxd;
    assign ser_txd_1 = ser_rxd_1;
    assign ser_txd_2 = ser_rxd_2;
    assign led       = 2'b01;   // steady, so a configured board is obvious
endmodule
