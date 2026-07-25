// Continuously transmit 0xAA at 2 Mbaud on two FPGA TX pins.
// 50 MHz clock → 25 cycles per bit.
// Frame: START D0..D7(0xAA=0b10101010, LSB-first) STOP IDLE
// Bit pattern: 0 0 1 0 1 0 1 0 1 1 1  (11 bits, bit_cnt 0..10)
module UartTxGen (
    input  wire clk,       // 50 MHz (U22)
    output reg  txd_a5,    // A5  → RP2040 GPIO1 (UART0 RX, ttyACM0 reference)
    output reg  txd_p25    // P25 → RP2040 GPIO5 (UART1 RX, ttyACM0 with UART1 fw)
);
    localparam CLKS_PER_BIT = 25;   // 50 MHz / 2 Mbaud
    localparam BITS_PER_FRAME = 11; // start + 8 data + stop + 1 idle

    reg [4:0] clk_cnt  = 0;
    reg [3:0] bit_cnt  = 4'd10; // start in IDLE state so A5 stays HIGH until first frame

    // txd outputs must initialise HIGH (idle) — if they start LOW the RP2040 UART
    // sees a BREAK condition and re-syncs to the wrong frame position.
    initial txd_a5  = 1'b1;
    initial txd_p25 = 1'b1;

    // 0xAA LSB-first: bits = 0,1,0,1,0,1,0,1
    // bit_cnt: 0=start(0), 1=D0(0), 2=D1(1), 3=D2(0), 4=D3(1),
    //          5=D4(0), 6=D5(1), 7=D6(0), 8=D7(1), 9=stop(1), 10=idle(1)
    function [0:0] frame_bit;
        input [3:0] b;
        case (b)
            4'd0:    frame_bit = 1'b0;
            4'd1:    frame_bit = 1'b0;
            4'd2:    frame_bit = 1'b1;
            4'd3:    frame_bit = 1'b0;
            4'd4:    frame_bit = 1'b1;
            4'd5:    frame_bit = 1'b0;
            4'd6:    frame_bit = 1'b1;
            4'd7:    frame_bit = 1'b0;
            4'd8:    frame_bit = 1'b1;
            default: frame_bit = 1'b1; // stop + idle
        endcase
    endfunction

    always @(posedge clk) begin
        if (clk_cnt == CLKS_PER_BIT - 1) begin
            clk_cnt <= 0;
            bit_cnt <= (bit_cnt == BITS_PER_FRAME - 1) ? 0 : bit_cnt + 1;
        end else begin
            clk_cnt <= clk_cnt + 1;
        end
        txd_a5  <= frame_bit(bit_cnt);
        txd_p25 <= frame_bit(bit_cnt);
    end
endmodule
