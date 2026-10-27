// Ethernet bring-up, stage 1: the Colorlight i5 v7.0 module's B50612D PHYs
// over MDIO, on the fpga-db-colorlight-i5 base board (RJ1 = the module's ETH1
// pairs, SODIMM 15-35). Exercises the jack, magnetics, SODIMM pairs and PHY
// without any RGMII: autonegotiation runs inside the PHY. The logic is
// ../colorlight-db-i9plus-eth/EthMdioProbe.v unchanged; only the pins differ.
//
// The PHYs sit on the module and are wired straight to the i5's own balls, so
// none of the i9+'s pins apply. Two sources agree ball for ball: the wuxx
// Colorlight-FPGA-Projects README (the source of the repo's SODIMM tables;
// "ETH-PHY0 (U28)", "ETH-PHY1 (U29)") and LiteX's colorlight_i5.py
// (_io_v7_0), which numbers them the other way round (eth 0 = U29, eth 1 =
// U28). Shared: MDC N5, MDIO P5, RESET P4. U29: GTXCLK G1, TX_EN K1. U28:
// GTXCLK U19, TX_EN P19. None of these balls is on the SODIMM edge in the
// repo's i5 table (ColorlightSodimm.SodimmI5), which fits their being
// module-internal. phy_*[0] is U29, phy_*[1] is U28.
//
// P4 is NOT DRIVEN, as H2 is not on the i9+: there, driving the shared PHY
// reset broke the stamp's links on hardware (2026-10-10), and a variant that
// only left it alone worked. Whether the i5 module holds P4 high on its own is
// not known; if no PHY answers here, that is the first suspect.
//
// Once a second, for every MDIO address 0..31 that answers (ID1 not
// 0000/FFFF), prints on the console (stamp CDC0, 115200 8N1):
//   "PHY aa ID iiii:jjjj BMCR cccc BMSR ssss LPA llll 1KS kkkk AUX xxxx\r\n"
// then "--\r\n". BMSR is read twice (link status latches low) and the second
// read is printed. Broadcom AUX (0x19) bits 10:8 = resolved speed/duplex
// (7 = 1000FD, 6 = 1000HD, 5 = 100FD, 3 = 100HD, 2 = 10FD, 1 = 10HD), bit 2
// link, bit 15 autoneg complete. 1KS = 1000BASE-T status (0x0A).
// LEDs: LED0..LED7 = BMSR link of addresses 0..7 (last scan); RJ1 left LED =
// any link, right LED = heartbeat; module LED D2 (active low) = heartbeat.
// Base-board pins go through the SODIMM (the netlist names U5's pins by i9+
// ball; SodimmI5 turns each SODIMM pin into an i5 ball): see EthMdioProbe.lpf.
module EthMdioProbe (
    input  wire       clk25,
    output wire       uart_tx,          // E1 = SODIMM 153 -> stamp GPIO5 (CDC0)
    output wire       mdc,
    inout  wire       mdio,
    output wire [7:0] led,
    output wire [1:0] eth_led,
    output wire       led_n,            // U16, module LED D2
    // RGMII TX of both PHYs, held idle: TX_EN low (a floating TX_EN could put
    // garbage on the wire), GTXCLK low. RX pins are left high-Z (PHY outputs,
    // and its strap inputs during reset).
    output wire [1:0] phy_tx_en,
    output wire [1:0] phy_gtxclk
);
    assign phy_tx_en  = 2'b00;
    assign phy_gtxclk = 2'b00;
    // ---- start-up delay (the PHYs' own reset is left to the module) ----
    reg [19:0] rcnt = 0;
    wire rst_done = rcnt[19];                    // 2^19 / 25 MHz = 21 ms
    always @(posedge clk25) if (!rst_done) rcnt <= rcnt + 1;

    // ---- MDIO master, MDC = 25 MHz / 16 = 1.56 MHz ----
    reg  [3:0]  div = 0;
    always @(posedge clk25) div <= div + 4'd1;
    wire mdc_rise = (div == 4'd7);               // MDC goes high
    wire mdc_fall = (div == 4'd15);              // MDC goes low: drive / sample here
    assign mdc = div[3];

    reg        m_go = 0, m_busy = 0;
    reg [4:0]  m_phy, m_reg;
    reg [15:0] m_data;
    reg [6:0]  m_bit;                            // 0..63
    reg        mdio_oe = 0, mdio_o = 1;
    reg [15:0] m_sh;
    assign mdio = mdio_oe ? mdio_o : 1'bz;
    // read frame: 32 x 1 preamble, 01 start, 10 read, 5 phy, 5 reg, Z0, 16 data
    wire [13:0] hdr = {2'b01, 2'b10, m_phy, m_reg};
    always @(posedge clk25) begin
        if (m_go && !m_busy) begin m_busy <= 1; m_bit <= 0; end
        else if (m_busy && mdc_fall) begin
            if (m_bit < 32)       begin mdio_oe <= 1; mdio_o <= 1; end
            else if (m_bit < 46)  begin mdio_oe <= 1; mdio_o <= hdr[45 - m_bit]; end
            else                       mdio_oe <= 0;               // turnaround + data
            m_bit <= m_bit + 1;
            if (m_bit == 64) m_busy <= 0;
        end
        // sample on MDC rise: data bits are frame bits 48..63
        if (m_busy && mdc_rise && m_bit >= 49 && m_bit <= 64) m_sh <= {m_sh[14:0], mdio};
        if (m_busy && mdc_fall && m_bit == 64) m_data <= m_sh;
    end

    // ---- UART TX, 115200 ----
    localparam DIV = 217;
    reg [7:0] u_ch; reg u_go = 0; reg u_busy = 0;
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

    // ---- sequencer ----
    // line: "PHY aa ID iiii:jjjj BMCR cccc BMSR ssss LPA llll 1KS kkkk AUX xxxx\r\n"
    reg [15:0] r_id1, r_id2, r_bmcr, r_bmsr, r_lpa, r_1ks, r_aux;
    reg [4:0]  addr = 0;
    reg [3:0]  st = 0;
    reg [2:0]  ri = 0;                            // which register in the per-PHY list
    reg [6:0]  ci = 0;
    reg [24:0] wait_cnt = 0;
    reg [7:0]  link = 0;
    reg        any_link = 0, any_now = 0;
    localparam S_WAIT = 0, S_ID = 1, S_IDW = 2, S_REG = 3, S_REGW = 4, S_PRINT = 5,
               S_PRW = 6, S_NEXT = 7, S_DASH = 8, S_DASHW = 9;
    function [7:0] hex(input [3:0] n); hex = (n < 10) ? 8'h30 + n : 8'h61 + n - 10; endfunction
    // register list per PHY: 3 (ID2), 0 (BMCR), 1, 1 (BMSR twice), 5 (LPA), 0x0A (1KS), 0x19 (AUX)
    reg [4:0] rl;
    always @* case (ri)
        0: rl = 5'h03; 1: rl = 5'h00; 2: rl = 5'h01; 3: rl = 5'h01;
        4: rl = 5'h05; 5: rl = 5'h0A; default: rl = 5'h19;
    endcase
    // line template, 68 chars + CRLF
    reg [7:0] lc;
    always @* begin
        case (ci)
            0: lc = "P"; 1: lc = "H"; 2: lc = "Y"; 3: lc = " ";
            4: lc = hex({3'b0, addr[4]}); 5: lc = hex(addr[3:0]);
            6: lc = " "; 7: lc = "I"; 8: lc = "D"; 9: lc = " ";
            10: lc = hex(r_id1[15:12]); 11: lc = hex(r_id1[11:8]); 12: lc = hex(r_id1[7:4]); 13: lc = hex(r_id1[3:0]);
            14: lc = ":";
            15: lc = hex(r_id2[15:12]); 16: lc = hex(r_id2[11:8]); 17: lc = hex(r_id2[7:4]); 18: lc = hex(r_id2[3:0]);
            19: lc = " "; 20: lc = "B"; 21: lc = "M"; 22: lc = "C"; 23: lc = "R"; 24: lc = " ";
            25: lc = hex(r_bmcr[15:12]); 26: lc = hex(r_bmcr[11:8]); 27: lc = hex(r_bmcr[7:4]); 28: lc = hex(r_bmcr[3:0]);
            29: lc = " "; 30: lc = "B"; 31: lc = "M"; 32: lc = "S"; 33: lc = "R"; 34: lc = " ";
            35: lc = hex(r_bmsr[15:12]); 36: lc = hex(r_bmsr[11:8]); 37: lc = hex(r_bmsr[7:4]); 38: lc = hex(r_bmsr[3:0]);
            39: lc = " "; 40: lc = "L"; 41: lc = "P"; 42: lc = "A"; 43: lc = " ";
            44: lc = hex(r_lpa[15:12]); 45: lc = hex(r_lpa[11:8]); 46: lc = hex(r_lpa[7:4]); 47: lc = hex(r_lpa[3:0]);
            48: lc = " "; 49: lc = "1"; 50: lc = "K"; 51: lc = "S"; 52: lc = " ";
            53: lc = hex(r_1ks[15:12]); 54: lc = hex(r_1ks[11:8]); 55: lc = hex(r_1ks[7:4]); 56: lc = hex(r_1ks[3:0]);
            57: lc = " "; 58: lc = "A"; 59: lc = "U"; 60: lc = "X"; 61: lc = " ";
            62: lc = hex(r_aux[15:12]); 63: lc = hex(r_aux[11:8]); 64: lc = hex(r_aux[7:4]); 65: lc = hex(r_aux[3:0]);
            66: lc = 8'h0D; default: lc = 8'h0A;
        endcase
    end
    always @(posedge clk25) begin
        m_go <= 0; u_go <= 0;
        case (st)
            S_WAIT: begin
                wait_cnt <= wait_cnt + 1;
                if (rst_done && wait_cnt == 25'd24_999_999) begin
                    wait_cnt <= 0; addr <= 0; any_now <= 0; st <= S_ID;
                end
            end
            S_ID:  if (!m_busy) begin m_phy <= addr; m_reg <= 5'h02; m_go <= 1; st <= S_IDW; end
            S_IDW: if (!m_go && !m_busy) begin
                       r_id1 <= m_data;
                       if (m_data == 16'hFFFF || m_data == 16'h0000) begin
                           if (addr < 8) link[addr[2:0]] <= 0;
                           st <= S_NEXT;
                       end else begin ri <= 0; st <= S_REG; end
                   end
            S_REG: if (!m_busy) begin m_phy <= addr; m_reg <= rl; m_go <= 1; st <= S_REGW; end
            S_REGW: if (!m_go && !m_busy) begin
                       case (ri)
                           0: r_id2 <= m_data; 1: r_bmcr <= m_data;
                           2: ; 3: r_bmsr <= m_data;
                           4: r_lpa <= m_data; 5: r_1ks <= m_data; default: r_aux <= m_data;
                       endcase
                       if (ri == 6) begin ci <= 0; st <= S_PRINT; end
                       else begin ri <= ri + 1; st <= S_REG; end
                   end
            S_PRINT: if (!u_busy && !u_go) begin u_ch <= lc; u_go <= 1; st <= S_PRW; end
            S_PRW: if (!u_go && !u_busy) begin
                       if (ci == 67) begin
                           if (addr < 8) link[addr[2:0]] <= r_bmsr[2];
                           if (r_bmsr[2]) any_now <= 1;
                           st <= S_NEXT;
                       end else begin ci <= ci + 1; st <= S_PRINT; end
                   end
            S_NEXT: if (addr == 31) begin ci <= 0; st <= S_DASH; any_link <= any_now; end
                    else begin addr <= addr + 1; st <= S_ID; end
            S_DASH: if (!u_busy && !u_go) begin
                        u_ch <= (ci == 0 || ci == 1) ? "-" : (ci == 2) ? 8'h0D : 8'h0A;
                        u_go <= 1; st <= S_DASHW;
                    end
            S_DASHW: if (!u_go && !u_busy) begin
                        if (ci == 3) st <= S_WAIT; else begin ci <= ci + 1; st <= S_DASH; end
                    end
        endcase
    end

    reg [24:0] beat = 0;
    always @(posedge clk25) beat <= beat + 1;
    assign led = link;
    assign eth_led = {beat[24], any_link};
    assign led_n = beat[24];
endmodule
