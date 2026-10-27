// Colorlight i5 idle image: drives one ball, the module LED D2.
// In: the module's 25 MHz clock (P3). Out: D2 (U16), blinking at
// 25e6 / 2^25 = 0.75 Hz. U16 also reaches SODIMM pin 41 (the repo's SodimmI5
// table): no net on the fpga-db-colorlight-i5 base board, and the ext-board
// JOP build drives it as led[0]. Every other ball is unused, so it is an input.
module I5Idle (
    input  wire clk25,
    output wire led_n
);
    reg [24:0] count = 25'd0;
    always @(posedge clk25) count <= count + 25'd1;
    assign led_n = count[24];
endmodule
