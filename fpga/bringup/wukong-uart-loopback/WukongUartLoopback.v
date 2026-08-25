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
// Four candidate pairs, because the header the Pico is jumpered to is in doubt.
// The pins first given as "J11.1-4 = D5,G5,G7,G8" are J10's, per the board doc;
// J11 is H4,F4,A4,A5. Loop both headers at once and let the hardware say which.
//   ser_txd_1/ ser_rxd_1  G8 / G7   J10.4/.3
//   ser_txd_2/ ser_rxd_2  G5 / D5   J10.2/.1
//   ser_txd_3/ ser_rxd_3  A5 / A4   J11.4/.3
//   ser_txd_4/ ser_rxd_4  F4 / H4   J11.2/.1
//
// Odd header pin = Pico TX = FPGA input, even = Pico RX = FPGA output, which
// is the convention the working GP12/GP13 jumper test confirmed. Keeping to it
// avoids driving a pin the Pico is also driving.
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
    input  wire       ser_rxd_3,
    output wire       ser_txd_3,
    input  wire       ser_rxd_4,
    output wire       ser_txd_4,
    output wire [1:0] led
);
    assign ser_txd   = ser_rxd;
    assign ser_txd_1 = ser_rxd_1;
    assign ser_txd_2 = ser_rxd_2;
    assign ser_txd_3 = ser_rxd_3;
    assign ser_txd_4 = ser_rxd_4;
    assign led       = 2'b01;   // steady, so a configured board is obvious
endmodule
