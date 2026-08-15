// Behavioural stand-in for Altera's altera_sdram_tri_controller, for simulation
// ONLY. Exists so AlteraSdramAdapter can be tested at all: the real controller
// is a BlackBox with no Verilog body, so Verilator cannot build any design
// containing it, and every JOP simulation therefore substitutes SdramCtrlNoCke
// instead. That left the adapter -- which runs on every Altera board -- with no
// test coverage whatsoever, and it hid two response-path bugs for weeks
// (current-status item 35, fixed in ef36d99).
//
// This models the AVALON-MM CONTRACT, not the SDRAM. Three properties matter,
// and they are the ones the adapter got wrong:
//
//   1. avs_readdatavalid is a PULSE, one cycle wide. avs_readdata is valid on
//      that cycle and on no other. There is NO back-pressure on read data --
//      avs_waitrequest gates COMMANDS only. A master that is not ready when the
//      pulse arrives has lost the data. This is deliberately unforgiving here:
//      avs_readdata is driven to X between pulses, so any consumer that samples
//      it a cycle late fails loudly instead of reading a stale value that
//      happens to be right.
//   2. avs_waitrequest stalls commands, and does so unpredictably.
//   3. Read data comes back IN ORDER, after a variable latency.
//
// Writes get no readdatavalid; the adapter manufactures its own write responses.

`timescale 1ns/1ps

module altera_sdram_tri_controller #(
  parameter TRISTATE_EN      = 0,
  parameter NUM_CHIPSELECTS  = 1,
  parameter CNTRL_ADDR_WIDTH = 24,
  parameter SDRAM_BANK_WIDTH = 2,
  parameter SDRAM_ROW_WIDTH  = 13,
  parameter SDRAM_COL_WIDTH  = 9,
  parameter SDRAM_DATA_WIDTH = 16,
  parameter CAS_LATENCY      = 3,
  parameter INIT_REFRESH     = 2,
  parameter REFRESH_PERIOD   = 1563,
  parameter POWERUP_DELAY    = 20000,
  parameter T_RFC            = 7,
  parameter T_RP             = 2,
  parameter T_RCD            = 2,
  parameter T_WR             = 5,
  parameter MAX_REC_TIME     = 1
) (
  input                               clk,
  input                               rst_n,

  input                               avs_read,
  input                               avs_write,
  input  [SDRAM_DATA_WIDTH/8-1:0]     avs_byteenable,
  input  [CNTRL_ADDR_WIDTH-1:0]       avs_address,
  input  [SDRAM_DATA_WIDTH-1:0]       avs_writedata,
  output reg [SDRAM_DATA_WIDTH-1:0]   avs_readdata,
  output reg                          avs_readdatavalid,
  output                              avs_waitrequest,

  input                               tcm_grant,
  output                              tcm_request,

  output [SDRAM_ROW_WIDTH-1:0]        sdram_addr,
  output [SDRAM_BANK_WIDTH-1:0]       sdram_ba,
  output [SDRAM_DATA_WIDTH-1:0]       sdram_dq_out,
  input  [SDRAM_DATA_WIDTH-1:0]       sdram_dq_in,
  output                              sdram_dq_oe,
  output [SDRAM_DATA_WIDTH/8-1:0]     sdram_dqm,
  output                              sdram_ras_n,
  output                              sdram_cas_n,
  output                              sdram_we_n,
  output [NUM_CHIPSELECTS-1:0]        sdram_cs_n,
  output                              sdram_cke
);

  localparam MEM_WORDS = 1024;          // the tests only touch low addresses
  localparam Q_DEPTH   = 8;             // max reads in flight

  // ---------------------------------------------------------------- memory
  reg [SDRAM_DATA_WIDTH-1:0] mem [0:MEM_WORDS-1];
  integer i;
  initial begin
    // Mirrored by the Scala side as `(addr ^ 0xA5A5) & dataMask`. A formula
    // rather than zeros so a response carrying the WRONG address's data is
    // still detected -- with a zeroed memory every wrong read looks correct.
    for (i = 0; i < MEM_WORDS; i = i + 1)
      mem[i] = i ^ 16'hA5A5;
    avs_readdata      = {SDRAM_DATA_WIDTH{1'bx}};
    avs_readdatavalid = 1'b0;
  end

  // ------------------------------------------------------- command stalling
  // Free-running LFSR, independent of avs_read/avs_write so there is no
  // combinational loop back through the adapter (which gates its own request
  // on !avs_waitrequest).
  reg [15:0] lfsr;
  always @(posedge clk or negedge rst_n)
    if (!rst_n) lfsr <= 16'hACE1;
    else        lfsr <= {lfsr[14:0], lfsr[15] ^ lfsr[13] ^ lfsr[12] ^ lfsr[10]};

  wire qFull;
  // Stall roughly a third of the time, and always when there is nowhere to put
  // another read's data.
  assign avs_waitrequest = (lfsr[0] & lfsr[3]) | (lfsr[7] & lfsr[1]) | qFull;

  wire cmdFire   = (avs_read | avs_write) & ~avs_waitrequest;
  wire readFire  = avs_read  & ~avs_waitrequest;
  wire writeFire = avs_write & ~avs_waitrequest;

  // ------------------------------------------------- pending read data queue
  // In-order, variable latency. Data is captured at ACCEPT time so a later
  // write to the same address cannot retroactively change an in-flight read.
  reg [SDRAM_DATA_WIDTH-1:0] q [0:Q_DEPTH-1];
  reg [3:0] qCount;
  reg [2:0] qHead, qTail;
  reg [2:0] latency;

  assign qFull = (qCount >= Q_DEPTH-1);

  wire [SDRAM_DATA_WIDTH-1:0] readValue = mem[avs_address[9:0]];

  always @(posedge clk or negedge rst_n) begin
    if (!rst_n) begin
      qCount            <= 0;
      qHead             <= 0;
      qTail             <= 0;
      latency           <= 3'd2;
      avs_readdatavalid <= 1'b0;
      avs_readdata      <= {SDRAM_DATA_WIDTH{1'bx}};
    end else begin
      // Default: NOT valid, and data undriven. Holding stale data here would
      // mask exactly the bug this stub exists to expose.
      avs_readdatavalid <= 1'b0;
      avs_readdata      <= {SDRAM_DATA_WIDTH{1'bx}};

      if (writeFire) begin
        // Byte enables, so a partial write does not clobber the whole word.
        for (i = 0; i < SDRAM_DATA_WIDTH/8; i = i + 1)
          if (avs_byteenable[i])
            mem[avs_address[9:0]][i*8 +: 8] <= avs_writedata[i*8 +: 8];
      end

      if (readFire) begin
        q[qTail] <= readValue;
        qTail    <= qTail + 1;
      end

      if (qCount != 0 || readFire) begin
        if (latency != 0) begin
          latency <= latency - 1;
        end else if (qCount != 0) begin
          avs_readdatavalid <= 1'b1;
          avs_readdata      <= q[qHead];
          qHead             <= qHead + 1;
          // 1..4 cycles, so responses do not fall into a fixed rhythm that a
          // consumer could accidentally be in step with.
          latency           <= {1'b0, lfsr[5:4]};
        end
      end

      case ({readFire, (latency == 0) && (qCount != 0)})
        2'b10:   qCount <= qCount + 1;
        2'b01:   qCount <= qCount - 1;
        default: qCount <= qCount;
      endcase
    end
  end

  // ------------------------------------------------------------ unused pins
  assign tcm_request  = 1'b0;
  assign sdram_addr   = {SDRAM_ROW_WIDTH{1'b0}};
  assign sdram_ba     = {SDRAM_BANK_WIDTH{1'b0}};
  assign sdram_dq_out = {SDRAM_DATA_WIDTH{1'b0}};
  assign sdram_dq_oe  = 1'b0;
  assign sdram_dqm    = {SDRAM_DATA_WIDTH/8{1'b0}};
  assign sdram_ras_n  = 1'b1;
  assign sdram_cas_n  = 1'b1;
  assign sdram_we_n   = 1'b1;
  assign sdram_cs_n   = {NUM_CHIPSELECTS{1'b1}};
  assign sdram_cke    = 1'b1;

endmodule
