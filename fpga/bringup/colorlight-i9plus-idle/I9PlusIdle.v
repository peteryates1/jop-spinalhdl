// Power-up image for a Colorlight i9+ module's SPI flash (XC7A50T-FGG484).
//
// The flash image travels with the MODULE, not the carrier it sits on, so this
// drives nothing outside the module: the module's own 25 MHz clock in (K4) and
// the module's own LED D2 (A18) blinking at ~0.75 Hz to show the image loaded.
// Every other ball is unused, so high-Z with no pull (UNUSEDPIN PULLNONE). It
// is safe on any carrier: the fpga-db-colorlight-i5 base board (whose RP2040
// GPIOs and DC-coupled HDMI land on FPGA balls), the stock ext board, or none.
module I9PlusIdle (
    input  wire clk25,     // K4, on the module
    output wire led_mod    // A18, module LED D2
);
    reg [24:0] tick = 0;
    always @(posedge clk25) tick <= tick + 25'd1;
    assign led_mod = tick[24];
endmodule
