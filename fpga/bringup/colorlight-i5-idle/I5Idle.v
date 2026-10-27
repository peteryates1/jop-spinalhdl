// Colorlight i5 idle image: drives nothing outside the module.
// In: the module's 25 MHz clock (P3). Out: the module LED D2 (U16), which is
// not on the SODIMM, blinking at 25e6 / 2^25 = 0.75 Hz. Every other ball is
// unused, so it is an input and drives nothing on any carrier.
module I5Idle (
    input  wire clk25,
    output wire led_n
);
    reg [24:0] count = 25'd0;
    always @(posedge clk25) count <= count + 25'd1;
    assign led_n = count[24];
endmodule
