// Ethernet bring-up, stage 2: RGMII frames at 100 Mb/s on the i9+ module's
// B50612D PHYs, fpga-db-colorlight-i5 base board (RJ1 = the PHY at MDIO 1).
//
// 100 Mb/s keeps RGMII trivial: 4-bit data, one nibble per 25 MHz clock (low
// nibble first), TX_CTL = TX_EN. The module's own 25 MHz oscillator clocks TX.
//
// At power-up everything runs: TXC at 90 degrees, TX on, and the MDIO writes
// for 100 Mb/s. The buttons turn things off to bisect a fault (a button
// already low at power-up does nothing until it has been released):
//   OPTION  steps TXC: 90 degrees -> 270 -> off (held low) -> 90.
//           RGMII samples TX_CTL on BOTH TXC edges (TX_EN rising, TX_EN ^ TX_ER
//           falling), so neither edge may sit on a data transition. TXD and
//           TX_CTL leave IOB flip-flops on clk25; TXC is an MMCM copy of clk25
//           shifted 90 degrees, so its edges land 10 ns and 30 ns into the nibble.
//   SELECT  toggles TX: once a second, frame.hex (a UDP broadcast,
//           192.168.0.250 -> 192.168.0.255:5005, from make_frame.py) on BOTH
//           PHYs' RGMII TX.
//   START   toggles PHY1's MII loopback: BMCR <- 0x6100 (loopback, 100 FD),
//           so its RX returns what it took from our TX, cable and switch
//           out of the path; again: BMCR <- 0x1200 (autoneg on + restart).
//           Before either, 1000BASE-T control (reg 9) <- 0 so gigabit is not
//           advertised and the link comes up at 100.
//   RESET   clears the fault record.
// LED0 link, LED1/LED2 TXC mode, LED3 TX on, LED4 MDIO writes done, LED5 a
// fault was seen, LED6 loopback, LED7 clk25 heartbeat.
//
// Faults: an illegal sequencer state is counted, recorded with the state
// before it, and left for S_IDLE. rxc0/rxc1 = RXC cycles per clk25 second
// (125 MHz = 07735940, 25 MHz = 017d7840): a disturbed clk25 reads off.
//
// Once a second, on the console (stamp CDC0, 115200 8N1), hex bytes:
//   "S bmsr aux bmcr 1kctl txcount rx0count rx1count uptime"   (16-bit each)
//   "F faults badst prevst mode rxc0(32) rxc1(32)"
//        mode = {loopback, wr_done, tx, txc mode (0 off, 1 = 90, 2 = 270)}
//   "0 <32 bytes>"  latest frame on PHY0's RX (U5: RXC H4), preamble included
//   "1 <32 bytes>"  latest frame on PHY1's RX (U9: RXC L3)
//
// H2 (shared PHY reset) is NOT driven: see README.md.
// RX: sampled on the FALLING edge of RXC, safe with or without the PHY's
// internal RGMII delay.
// A JTAG load by openFPGALoader v1.1.1 lands on an FPGA that has already
// configured itself from flash, and the design then does not start from its
// INIT values (README). The first sequencer had no default branch, started in
// state 31 and never printed; this one records the illegal state and carries on.
module EthRgmiiTest #(
    parameter integer SEC_CYCLES = 25_000_000,  // clk25 cycles per report
    parameter integer BTN_BITS   = 18           // button sample every 2^n cycles (10.5 ms)
) (
    input  wire       clk25,
    input  wire [3:0] sw_n,                      // {START, SELECT, OPTION, RESET}, to GND
    output wire       uart_tx,
    output wire       mdc,
    inout  wire       mdio,
    // RGMII, index 0 = PHY0 (U5), 1 = PHY1 (U9)
    output wire [1:0] txc,
    (* IOB = "TRUE" *) output reg [3:0] txd0 = 0,
    (* IOB = "TRUE" *) output reg [3:0] txd1 = 0,
    (* IOB = "TRUE" *) output reg [1:0] tx_ctl = 0,
    input  wire [1:0] rxc,
    input  wire [3:0] rxd0, rxd1,
    input  wire [1:0] rx_ctl,
    output wire [7:0] led,
    output wire [1:0] eth_led
);
    localparam integer FRAME_LEN = 96;

    // ------------------------------------------------------------------
    // buttons: synchronised, sampled every 2^BTN_BITS cycles (debounce),
    // one press event per press
    // ------------------------------------------------------------------
    reg [BTN_BITS-1:0] bdiv = 0;
    // all start as 'pressed' (F): the first sample is at cycle 0, before the pins
    // reach b_s1, and a button low at power-up must not count as a press
    reg [3:0] b_s0 = 4'hF, b_s1 = 4'hF, b_q = 4'hF;
    always @(posedge clk25) begin
        b_s0 <= ~sw_n; b_s1 <= b_s0;
        bdiv <= bdiv + 1;
        if (bdiv == 0) b_q <= b_s1;
    end
    wire [3:0] b_press = (bdiv == 0) ? (b_s1 & ~b_q) : 4'b0;
    reg [1:0] txc_mode = 1;                      // 0 off, 1 = 90 degrees, 2 = 270
    reg       tx_en = 1;
    always @(posedge clk25) begin
        if (b_press[1]) txc_mode <= (txc_mode == 2) ? 2'd0 : txc_mode + 2'd1;
        if (b_press[2]) tx_en    <= ~tx_en;
    end

    // ------------------------------------------------------------------
    // MDIO master (read and write), MDC = 25 MHz / 16
    // ------------------------------------------------------------------
    reg  [3:0]  div = 0;
    always @(posedge clk25) div <= div + 4'd1;
    wire mdc_rise = (div == 4'd7);
    wire mdc_fall = (div == 4'd15);
    assign mdc = div[3];

    reg        m_go = 0, m_busy = 0, m_wr = 0;
    reg [4:0]  m_phy = 0, m_reg = 0;
    reg [15:0] m_wdata = 0, m_data = 0, m_sh = 0;
    reg [6:0]  m_bit = 0;
    reg        mdio_oe = 0, mdio_o = 1;
    assign mdio = mdio_oe ? mdio_o : 1'bz;
    // 32 x 1 preamble, then 01, op (10 read / 01 write), phy, reg, TA, data
    wire [31:0] wframe = {2'b01, 2'b01, m_phy, m_reg, 2'b10, m_wdata};
    wire [13:0] rhdr   = {2'b01, 2'b10, m_phy, m_reg};
    always @(posedge clk25) begin
        if (m_go && !m_busy) begin m_busy <= 1; m_bit <= 0; end
        else if (m_busy && mdc_fall) begin
            if (m_bit < 32)                begin mdio_oe <= 1; mdio_o <= 1; end
            else if (m_wr)                 begin mdio_oe <= 1; mdio_o <= wframe[63 - m_bit]; end
            else if (m_bit < 46)           begin mdio_oe <= 1; mdio_o <= rhdr[45 - m_bit]; end
            else                                 mdio_oe <= 0;
            m_bit <= m_bit + 1;
            if (m_bit == 64) begin m_busy <= 0; mdio_oe <= 0; end
        end
        if (m_busy && mdc_rise && !m_wr && m_bit >= 49 && m_bit <= 64) m_sh <= {m_sh[14:0], mdio};
        if (m_busy && mdc_fall && m_bit == 64) m_data <= m_sh;
    end

    // ------------------------------------------------------------------
    // TX: frame ROM, both PHYs, nibble per clk25
    // ------------------------------------------------------------------
    reg [7:0] frame [0:FRAME_LEN-1];
    initial $readmemh("frame.hex", frame);
    reg        tx_go = 0, tx_busy = 0;
    reg [7:0]  tx_ni = 0;                        // nibble index 0..191
    reg [15:0] tx_count = 0;
    wire [7:0] tx_byte = frame[tx_ni[7:1]];
    wire [3:0] tx_nib  = tx_ni[0] ? tx_byte[7:4] : tx_byte[3:0];
    always @(posedge clk25) begin
        if (tx_go && !tx_busy) begin tx_busy <= 1; tx_ni <= 0; end
        else if (tx_busy) begin
            txd0 <= tx_nib; txd1 <= tx_nib; tx_ctl <= 2'b11;
            if (tx_ni == 2*FRAME_LEN - 1) begin tx_busy <= 0; tx_count <= tx_count + 1; end
            else tx_ni <= tx_ni + 1;
        end else begin
            txd0 <= 0; txd1 <= 0; tx_ctl <= 2'b00;
        end
    end
    // TXC: clk25 shifted 90 degrees (VCO 1000 MHz, both dividers integer)
    wire fb, fb_g, c90, clk90;
    MMCME2_BASE #(
        .CLKIN1_PERIOD(40.000), .DIVCLK_DIVIDE(1), .CLKFBOUT_MULT_F(40.000),
        .CLKOUT0_DIVIDE_F(40.000), .CLKOUT0_PHASE(90.000)
    ) txmmcm (
        .CLKIN1(clk25), .RST(1'b0), .PWRDWN(1'b0), .CLKFBIN(fb_g), .CLKFBOUT(fb),
        .CLKOUT0(c90), .CLKOUT0B(), .CLKOUT1(), .CLKOUT1B(), .CLKOUT2(), .CLKOUT2B(),
        .CLKOUT3(), .CLKOUT3B(), .CLKOUT4(), .CLKOUT5(), .CLKOUT6(), .CLKFBOUTB(),
        .LOCKED());
    BUFG bfb (.I(fb), .O(fb_g));
    BUFG b90 (.I(c90), .O(clk90));
    // (D1, D2) = (1, 0): TXC = clk90; (0, 1): TXC = ~clk90 = 270; (0, 0): low
    reg txc_d1 = 0, txc_d2 = 0;
    always @(posedge clk25) begin txc_d1 <= (txc_mode == 1); txc_d2 <= (txc_mode == 2); end
    genvar g;
    generate for (g = 0; g < 2; g = g + 1) begin : txclk
        ODDR #(.DDR_CLK_EDGE("SAME_EDGE"), .INIT(1'b0), .SRTYPE("SYNC")) o (
            .Q(txc[g]), .C(clk90), .CE(1'b1), .D1(txc_d1), .D2(txc_d2), .R(1'b0), .S(1'b0));
    end endgenerate

    // ------------------------------------------------------------------
    // RX, per PHY, in its own RXC domain (falling edge), plus a free-running
    // RXC cycle count for the clock check
    // ------------------------------------------------------------------
    wire [15:0] rx_cnt_g [0:1];                  // gray-coded frame counts
    wire [255:0] rx_cap [0:1];                   // first 32 bytes, byte k at [8k +: 8]
    wire [31:0] rc_g [0:1];                      // gray-coded RXC cycle counts
    generate for (g = 0; g < 2; g = g + 1) begin : rx
        wire [3:0] d = (g == 0) ? rxd0 : rxd1;
        reg        dv_q = 0;
        reg [6:0]  ni = 0;                       // nibble index within frame
        reg [3:0]  lo = 0;
        reg [255:0] cap = 0, buf_ = 0;
        reg [15:0] cnt = 0, cnt_g = 0;
        always @(negedge rxc[g]) begin
            dv_q <= rx_ctl[g];
            if (rx_ctl[g]) begin
                if (!ni[0]) lo <= d;
                else if (ni[6] == 0) buf_[ni[5:1]*8 +: 8] <= {d, lo};
                if (ni != 7'h7F) ni <= ni + 1;
            end else begin
                if (dv_q) begin                  // end of frame
                    cap <= buf_; cnt <= cnt + 1;
                    cnt_g <= (cnt + 1) ^ ((cnt + 1) >> 1);
                end
                ni <= 0;
            end
        end
        reg [31:0] rc = 0, rcg = 0;
        always @(posedge rxc[g]) begin rc <= rc + 1; rcg <= rc ^ (rc >> 1); end
        assign rx_cnt_g[g] = cnt_g;
        assign rx_cap[g]   = cap;
        assign rc_g[g]     = rcg;
    end endgenerate
    // into clk25: counts through two flops (gray), captures read as-is
    // (bring-up: a capture can tear if a frame ends mid-print)
    reg [15:0] c0a = 0, c0b = 0, c1a = 0, c1b = 0;
    reg [31:0] r0a = 0, r0b = 0, r1a = 0, r1b = 0;
    always @(posedge clk25) begin
        c0a <= rx_cnt_g[0]; c0b <= c0a; c1a <= rx_cnt_g[1]; c1b <= c1a;
        r0a <= rc_g[0];     r0b <= r0a; r1a <= rc_g[1];     r1b <= r1a;
    end
    function [31:0] g2b(input [31:0] gr);
        integer i; begin g2b[31] = gr[31]; for (i = 30; i >= 0; i = i - 1) g2b[i] = g2b[i+1] ^ gr[i]; end
    endfunction
    wire [15:0] rx0_count = g2b({16'd0, c0b}), rx1_count = g2b({16'd0, c1b});
    wire [31:0] rc0_now = g2b(r0b), rc1_now = g2b(r1b);

    // ------------------------------------------------------------------
    // UART TX, 115200
    // ------------------------------------------------------------------
    localparam DIV = 217;
    reg [7:0] u_ch = 0; reg u_go = 0; reg u_busy = 0;
    reg [7:0] u_baud = 0; reg [3:0] u_n = 0; reg [9:0] u_sh = 10'h3FF;
    always @(posedge clk25) begin
        if (u_go && !u_busy) begin u_busy <= 1; u_sh <= {1'b1, u_ch, 1'b0}; u_n <= 10; u_baud <= DIV - 1; end
        else if (u_busy) begin
            if (u_baud != 0) u_baud <= u_baud - 1;
            else begin
                u_sh <= {1'b1, u_sh[9:1]}; u_baud <= DIV - 1;
                if (u_n == 1) u_busy <= 0; else u_n <= u_n - 1;
            end
        end
    end
    assign uart_tx = u_busy ? u_sh[0] : 1'b1;

    // ------------------------------------------------------------------
    // sequencer
    // ------------------------------------------------------------------
    reg [15:0] r_bmsr = 0, r_aux = 0, r_bmcr = 0, r_1kc = 0, uptime = 0;
    reg [15:0] faults = 0;
    reg [4:0]  bad_st = 0, bad_prev = 0, st_q = 0;
    reg [31:0] rc0_last = 0, rc1_last = 0, rc0_ps = 0, rc1_ps = 0;
    reg        wr_req = 1, wr_done = 0;          // power-up: the writes for 100 Mb/s
    reg        lb = 0, lb_want = 0;              // PHY1 loopback: now, after the next writes
    reg [2:0]  rd_i = 0;                         // 0 BMSR (latched, dropped), 1 BMSR, 2 AUX, 3 BMCR, 4 reg 9
    reg [24:0] tick = 0;
    reg [1:0]  line = 0;                         // 0 = S, 1 = F, 2 = RX0, 3 = RX1
    reg [5:0]  bi = 0;                           // byte index in line
    reg [1:0]  pos = 0;                          // 0 = space, 1,2 = hex digits
    reg        tail = 0;                         // CR, LF
    reg [255:0] snap = 0;                        // the line being printed
    function [7:0] hex(input [3:0] n); hex = (n < 10) ? 8'h30 + n : 8'h61 + n - 10; endfunction
    wire [5:0] nbytes = (line == 0) ? 6'd16 : (line == 1) ? 6'd13 : 6'd32;
    wire [7:0] cur = snap[bi*8 +: 8];
    wire [4:0] rd_reg = (rd_i <= 1) ? 5'd1 : (rd_i == 2) ? 5'h19 : (rd_i == 3) ? 5'd0 : 5'd9;

    localparam S_IDLE = 0, S_W9 = 1, S_W9W = 2, S_W0 = 3, S_W0W = 4, S_RD = 5, S_RDW = 6,
               S_LINE = 7, S_LBL = 8, S_LBLW = 9, S_CH = 10, S_CHW = 11, S_EOL = 12, S_EOLW = 13;
    // Keep this encoding: the fault record reports the raw state numbers.
    (* fsm_encoding = "none" *) reg [4:0] st = S_IDLE;
    always @(posedge clk25) begin
        m_go <= 0; u_go <= 0; tx_go <= 0;
        tick <= tick + 1;
        st_q <= st;
        case (st)
            S_IDLE: if (tick == SEC_CYCLES - 1) begin
                        tick <= 0; tx_go <= tx_en; uptime <= uptime + 1;
                        rc0_ps <= rc0_now - rc0_last; rc0_last <= rc0_now;
                        rc1_ps <= rc1_now - rc1_last; rc1_last <= rc1_now;
                        m_phy <= 1; rd_i <= 0;
                        st <= wr_req ? S_W9 : S_RD;
                    end
            S_W9:   begin m_reg <= 9; m_wdata <= 16'h0000; m_wr <= 1; m_go <= 1; st <= S_W9W; end
            S_W9W:  if (!m_go && !m_busy) st <= S_W0;
            S_W0:   begin m_reg <= 0; m_wdata <= lb_want ? 16'h6100 : 16'h1200; m_go <= 1; st <= S_W0W; end
            S_W0W:  if (!m_go && !m_busy) begin
                        m_wr <= 0; wr_req <= 0; wr_done <= 1; lb <= lb_want; st <= S_RD;
                    end
            S_RD:   begin m_reg <= rd_reg; m_go <= 1; st <= S_RDW; end
            S_RDW:  if (!m_go && !m_busy) begin
                        case (rd_i)
                            1: r_bmsr <= m_data;
                            2: r_aux  <= m_data;
                            3: r_bmcr <= m_data;
                            4: r_1kc  <= m_data;
                            default: ;
                        endcase
                        if (rd_i == 4) begin line <= 0; st <= S_LINE; end
                        else begin rd_i <= rd_i + 1; st <= S_RD; end
                    end
            S_LINE: begin
                case (line)
                    // byte k at snap[8k +: 8], each field high byte first
                    0: snap <= {128'd0,
                                uptime[7:0], uptime[15:8],
                                rx1_count[7:0], rx1_count[15:8], rx0_count[7:0], rx0_count[15:8],
                                tx_count[7:0], tx_count[15:8], r_1kc[7:0], r_1kc[15:8],
                                r_bmcr[7:0], r_bmcr[15:8], r_aux[7:0], r_aux[15:8],
                                r_bmsr[7:0], r_bmsr[15:8]};
                    1: snap <= {152'd0,
                                rc1_ps[7:0], rc1_ps[15:8], rc1_ps[23:16], rc1_ps[31:24],
                                rc0_ps[7:0], rc0_ps[15:8], rc0_ps[23:16], rc0_ps[31:24],
                                {3'd0, lb, wr_done, tx_en, txc_mode}, {3'd0, bad_prev}, {3'd0, bad_st},
                                faults[7:0], faults[15:8]};
                    2: snap <= rx_cap[0];
                    default: snap <= rx_cap[1];
                endcase
                bi <= 0; pos <= 0; st <= S_LBL;
            end
            S_LBL:  if (!u_busy && !u_go) begin
                        u_ch <= (line == 0) ? "S" : (line == 1) ? "F" : (line == 2) ? "0" : "1";
                        u_go <= 1; st <= S_LBLW;
                    end
            S_LBLW: if (!u_go && !u_busy) st <= S_CH;
            S_CH:   if (!u_busy && !u_go) begin
                        u_ch <= (pos == 0) ? " " : (pos == 1) ? hex(cur[7:4]) : hex(cur[3:0]);
                        u_go <= 1; st <= S_CHW;
                    end
            S_CHW:  if (!u_go && !u_busy) begin
                        if (pos == 2) begin
                            pos <= 0;
                            if (bi == nbytes - 1) begin tail <= 0; st <= S_EOL; end
                            else begin bi <= bi + 1; st <= S_CH; end
                        end else begin pos <= pos + 1; st <= S_CH; end
                    end
            S_EOL:  if (!u_busy && !u_go) begin u_ch <= tail ? 8'h0A : 8'h0D; u_go <= 1; st <= S_EOLW; end
            S_EOLW: if (!u_go && !u_busy) begin
                        if (tail) begin
                            if (line == 3) st <= S_IDLE; else begin line <= line + 1; st <= S_LINE; end
                        end else begin tail <= 1; st <= S_EOL; end
                    end
`ifndef NO_RECOVER
            default: begin                       // illegal state: record it, carry on
                faults <= faults + 1; bad_st <= st; bad_prev <= st_q; st <= S_IDLE;
            end
`endif
        endcase
        if (b_press[0]) begin faults <= 0; bad_st <= 0; bad_prev <= 0; end
        if (b_press[3]) begin lb_want <= ~lb; wr_req <= 1; end
    end

    reg [24:0] beat = 0;
    always @(posedge clk25) beat <= beat + 1;
    assign led = {beat[24], lb, faults != 0, wr_done, tx_en, txc_mode, r_bmsr[2]};
    assign eth_led = {beat[24], r_bmsr[2]};
endmodule
