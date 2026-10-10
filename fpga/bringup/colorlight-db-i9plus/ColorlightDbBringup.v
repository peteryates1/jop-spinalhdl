// Bring-up jig for the fpga-db-colorlight-i5 base board with an i9+ module
// (XC7A50T-FGG484). Hand-written and self-contained on purpose: see
// ../README.md. Board facts are from Netlist_fpga-db-colorlight-i5_2026-10-10.
//
// What it exercises:
//   LED0-7      chaser (active high, 300R to GND). Any button held -> the LEDs
//               show the buttons instead; an SRAM failure -> all LEDs blink.
//   SW_*        RESET/OPTION/SELECT/START, to GND, NO board pull-ups (XDC PULLUP).
//   module D2   heartbeat (A18, polarity unknown -- it blinks either way).
//   RJ1 LEDs    T3/T4, alternate with the heartbeat (if the jack is fitted).
//   console     stamp CDC0 = RP2040 uart1: GPIO4 (stamp TX) -> Y21 = our RX,
//               AB21 = our TX -> GPIO5 (stamp RX). 115200 8N1.
//               Prints a banner, runs the SRAM test at power-up, echoes keys,
//               't' re-runs the SRAM test, '?' reprints the banner.
//   CDC1        stamp uart0: GPIO16 (stamp TX) -> P14, R14 -> GPIO17. Wired
//               straight back: whatever is written to CDC1 must echo.
//   SRAM        IS63LV1024L-10 (128K x 8, 10 ns), 4 passes over every byte:
//               hash(a), ~hash(a), checkerboard, ~checkerboard. hash(a) =
//               a[7:0]^a[15:8]^a[16], so every single address bit flips the
//               data -- an open or shorted address line aliases two
//               addresses that hold different values and shows up as a miss.
//               First 8 misses are printed, then a summary.
//
// Pin directions are from the FPGA's side. Every RP2040 GPIO not listed here
// is left unconstrained (high-Z), so nothing contends with the stamp.
module ColorlightDbBringup (
    input  wire        clk25,          // K4, 25 MHz on the module
    output wire [7:0]  led,
    input  wire [3:0]  sw_n,           // {START, SELECT, OPTION, RESET}
    output wire        led_mod,        // module D2
    output wire [1:0]  eth_led,
    input  wire        uart_rx,        // Y21  <- stamp GPIO4  (CDC0)
    output wire        uart_tx,        // AB21 -> stamp GPIO5
    input  wire        uart1_rx,       // P14  <- stamp GPIO16 (CDC1)
    output wire        uart1_tx,       // R14  -> stamp GPIO17
    output reg  [16:0] sram_a,
    inout  wire [7:0]  sram_d,
    output reg         sram_ce_n,
    output reg         sram_oe_n,
    output reg         sram_we_n
);
    localparam CLK_HZ = 25_000_000;
    localparam BAUD   = 115_200;
    localparam DIV    = (CLK_HZ + BAUD / 2) / BAUD;   // 217

    assign uart1_tx = uart1_rx;

    // ---------------------------------------------------------------- reset
    reg [7:0] por = 8'd0;
    wire rst = ~por[7];
    always @(posedge clk25) if (rst) por <= por + 8'd1;

    // ---------------------------------------------------------------- UART RX
    reg [2:0] rx_sync = 3'b111;
    always @(posedge clk25) rx_sync <= {rx_sync[1:0], uart_rx};
    wire rx_in = rx_sync[2];

    reg [7:0]  rx_cnt;
    reg [3:0]  rx_bit;
    reg [7:0]  rx_sh;
    reg        rx_busy;
    reg        rx_valid;
    reg [7:0]  rx_data;
    always @(posedge clk25) begin
        rx_valid <= 1'b0;
        if (rst) begin
            rx_busy <= 1'b0;
        end else if (!rx_busy) begin
            if (!rx_in) begin                       // start bit edge
                rx_busy <= 1'b1;
                rx_cnt  <= DIV / 2;
                rx_bit  <= 4'd0;
            end
        end else if (rx_cnt != 0) begin
            rx_cnt <= rx_cnt - 8'd1;
        end else begin
            rx_cnt <= DIV - 1;
            rx_bit <= rx_bit + 4'd1;
            if (rx_bit == 4'd0) begin
                if (rx_in) rx_busy <= 1'b0;         // false start
            end else if (rx_bit <= 4'd8) begin
                rx_sh <= {rx_in, rx_sh[7:1]};
            end else begin                          // stop bit
                rx_busy <= 1'b0;
                if (rx_in) begin
                    rx_valid <= 1'b1;
                    rx_data  <= rx_sh;
                end
            end
        end
    end

    // ---------------------------------------------------------------- UART TX
    reg [8:0]  tx_sh = 9'h1ff;
    reg [3:0]  tx_bit;
    reg [7:0]  tx_cnt;
    reg        tx_busy;
    reg        tx_go;
    reg [7:0]  tx_byte;
    assign uart_tx = tx_sh[0];
    always @(posedge clk25) begin
        if (rst) begin
            tx_busy <= 1'b0;
            tx_sh   <= 9'h1ff;
        end else if (!tx_busy) begin
            if (tx_go) begin
                tx_sh   <= {tx_byte, 1'b0};
                tx_busy <= 1'b1;
                tx_bit  <= 4'd0;
                tx_cnt  <= DIV - 1;
            end
        end else if (tx_cnt != 0) begin
            tx_cnt <= tx_cnt - 8'd1;
        end else begin
            tx_cnt <= DIV - 1;
            tx_sh  <= {1'b1, tx_sh[8:1]};
            tx_bit <= tx_bit + 4'd1;
            if (tx_bit == 4'd9) tx_busy <= 1'b0;    // start + 8 data + stop
        end
    end

    // ---------------------------------------------------------------- messages
    // A byte >= 0x80 is "print hex nibble (byte & 0x0f) of cap"; 0x00 ends.
    localparam M_BANNER = 2'd0, M_PASS = 2'd1, M_ERR = 2'd2, M_FAIL = 2'd3;
    // Widths are exact: a narrower localparam silently drops leading chars.
    // \015 = CR (Verilog-2001 strings have no \r).
    localparam [8*68-1:0] S_BANNER =
        "\015\nColorlight DB + i9+ bring-up. t = SRAM test, ? = this, else echo\015\n";
    localparam [8*30-1:0] S_PASS = "SRAM PASS (4 x 128K, 0 errs)\015\n";
    localparam [8*31-1:0] S_ERR  = "SRAM ERR p\205 A=\204\203\202\201\200 W=\207\206 R=\211\210\015\n";
    localparam [8*24-1:0] S_FAIL = "SRAM FAIL errors=\216\215\214\213\212\015\n";

    reg  [63:0] cap;              // [19:0] addr, [23:20] pass, [31:24] wrote,
                                  // [39:32] read, [59:40] error count
    reg  [1:0]  msg;
    reg  [6:0]  idx;
    reg         printing;
    reg         print_go;
    reg  [1:0]  print_msg;

    function [7:0] msg_char(input [1:0] m, input [6:0] i);
        begin
            case (m)
            M_BANNER: msg_char = (i < 68) ? S_BANNER[8*(67 - i) +: 8] : 8'h00;
            M_PASS:   msg_char = (i < 30) ? S_PASS  [8*(29 - i) +: 8] : 8'h00;
            M_ERR:    msg_char = (i < 31) ? S_ERR   [8*(30 - i) +: 8] : 8'h00;
            default:  msg_char = (i < 24) ? S_FAIL  [8*(23 - i) +: 8] : 8'h00;
            endcase
        end
    endfunction

    function [7:0] hex(input [3:0] n);
        hex = (n < 10) ? (8'h30 + n) : (8'h41 + n - 10);
    endfunction

    wire [7:0] cur  = msg_char(msg, idx);
    wire [3:0] nib  = cap[{cur[3:0], 2'b00} +: 4];
    wire [7:0] outc = cur[7] ? hex(nib) : cur;

    reg echo_pend;
    reg [7:0] echo_byte;

    always @(posedge clk25) begin
        tx_go <= 1'b0;
        if (rst) begin
            printing <= 1'b0;
        end else if (printing) begin
            if (!tx_busy && !tx_go) begin
                if (cur == 8'h00) begin
                    printing <= 1'b0;
                end else begin
                    tx_byte <= outc;
                    tx_go   <= 1'b1;
                    idx     <= idx + 7'd1;
                end
            end
        end else if (print_go) begin
            printing <= 1'b1;
            msg      <= print_msg;
            idx      <= 7'd0;
        end else if (echo_pend && !tx_busy && !tx_go) begin
            tx_byte <= echo_byte;
            tx_go   <= 1'b1;
        end
    end

    // ---------------------------------------------------------------- SRAM test
    localparam T_IDLE = 4'd0, T_BANNER = 4'd1, T_W_SET = 4'd2, T_W_PULSE = 4'd3,
               T_W_HOLD = 4'd4, T_TURN = 4'd5, T_R_ADDR = 4'd6, T_R_WAIT = 4'd7,
               T_R_CMP = 4'd8, T_PRINT = 4'd9, T_NEXT = 4'd10, T_DONE = 4'd11,
               T_TURN2 = 4'd12;

    reg [3:0]  ts;
    reg [1:0]  pass;
    reg [16:0] a;
    reg [19:0] errs;
    reg [7:0]  dout;
    reg        d_oe;
    reg [7:0]  din;
    reg        test_req;
    reg        tested_ok, tested_bad;

    assign sram_d = d_oe ? dout : 8'hzz;
    always @(posedge clk25) din <= sram_d;

    function [7:0] pattern(input [1:0] p, input [16:0] ad);
        reg [7:0] base;
        begin
            base = p[1] ? (((ad[0] ^ ad[9]) ? 8'hAA : 8'h55))
                        : (ad[7:0] ^ ad[15:8] ^ {7'd0, ad[16]});
            pattern = base ^ {8{p[0]}};
        end
    endfunction
    wire [7:0] exp_d = pattern(pass, a);

    // key handling: 't' starts a test, '?' the banner, anything else echoes
    reg banner_req;
    always @(posedge clk25) begin
        if (rst) begin
            echo_pend  <= 1'b0;
            test_req   <= 1'b1;          // test once at power-up
            banner_req <= 1'b1;
        end else begin
            if (rx_valid) begin
                if (rx_data == "t" || rx_data == "T") test_req <= 1'b1;
                else if (rx_data == "?")             banner_req <= 1'b1;
                else begin echo_byte <= rx_data; echo_pend <= 1'b1; end
            end
            if (echo_pend && !printing && !print_go && !tx_busy && !tx_go) echo_pend <= 1'b0;
            if (ts == T_IDLE && banner_req && !printing && !print_go) banner_req <= 1'b0;
            if (ts == T_IDLE && !banner_req && test_req && !printing && !print_go) test_req <= 1'b0;
        end
    end

    always @(posedge clk25) begin
        print_go <= 1'b0;
        if (rst) begin
            ts <= T_IDLE; sram_ce_n <= 1'b1; sram_oe_n <= 1'b1; sram_we_n <= 1'b1;
            d_oe <= 1'b0; tested_ok <= 1'b0; tested_bad <= 1'b0;
        end else case (ts)
        T_IDLE: begin
            sram_ce_n <= 1'b1; sram_oe_n <= 1'b1; sram_we_n <= 1'b1; d_oe <= 1'b0;
            if (!printing && !print_go) begin
                if (banner_req) begin
                    print_msg <= M_BANNER; print_go <= 1'b1;
                end else if (test_req) begin
                    pass <= 2'd0; a <= 17'd0; errs <= 20'd0;
                    sram_ce_n <= 1'b0;
                    ts <= T_W_SET;
                end
            end
        end
        // write: address + data set up with WE high, WE low a full cycle,
        // WE high again with data still driven, then the address moves
        T_W_SET:   begin sram_a <= a; dout <= exp_d; d_oe <= 1'b1; ts <= T_W_PULSE; end
        T_W_PULSE: begin sram_we_n <= 1'b0; ts <= T_W_HOLD; end
        T_W_HOLD:  begin
            sram_we_n <= 1'b1;
            if (a == 17'h1FFFF) begin a <= 17'd0; ts <= T_TURN; end
            else begin a <= a + 17'd1; ts <= T_W_SET; end
        end
        // bus turnaround: release the data bus a full cycle before OE
        T_TURN:    begin d_oe <= 1'b0; ts <= T_TURN2; end
        T_TURN2:   begin sram_oe_n <= 1'b0; ts <= T_R_ADDR; end
        T_R_ADDR:  begin sram_a <= a; ts <= T_R_WAIT; end
        T_R_WAIT:  ts <= T_R_CMP;            // din samples 2 cycles after A
        T_R_CMP:   begin
            if (din != exp_d) begin
                errs <= errs + 20'd1;
                if (errs < 20'd8) begin
                    cap[19:0]  <= {3'd0, a};
                    cap[23:20] <= {2'd0, pass};
                    cap[31:24] <= exp_d;
                    cap[39:32] <= din;
                    print_msg  <= M_ERR; print_go <= 1'b1;
                    ts <= T_PRINT;
                end else ts <= T_NEXT;
            end else ts <= T_NEXT;
        end
        T_PRINT:   if (!printing && !print_go) ts <= T_NEXT;
        T_NEXT: begin
            if (a == 17'h1FFFF) begin
                a <= 17'd0;
                sram_oe_n <= 1'b1;               // back to writing
                if (pass == 2'd3) ts <= T_DONE;
                else begin pass <= pass + 2'd1; ts <= T_W_SET; end
            end else begin
                a <= a + 17'd1; ts <= T_R_ADDR;
            end
        end
        T_DONE: begin
            sram_ce_n <= 1'b1;
            cap[59:40] <= errs;
            tested_ok  <= (errs == 0);
            tested_bad <= (errs != 0);
            print_msg  <= (errs == 0) ? M_PASS : M_FAIL;
            print_go   <= 1'b1;
            ts <= T_IDLE;
        end
        default: ts <= T_IDLE;
        endcase
    end

    // ---------------------------------------------------------------- LEDs
    reg [24:0] tick;
    always @(posedge clk25) tick <= tick + 25'd1;
    reg [2:0] chase;
    always @(posedge clk25) if (tick[21:0] == 0) chase <= chase + 3'd1;  // 0.17 s/step

    wire [3:0] btn = ~sw_n;
    wire       blink = tick[23];
    assign led = (btn != 0)  ? {btn, btn} :
                 tested_bad  ? {8{blink}} :
                               (8'd1 << chase);
    assign led_mod = tick[24];
    assign eth_led = {tick[24], ~tick[24]};
endmodule
