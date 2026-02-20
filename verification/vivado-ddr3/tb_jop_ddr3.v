// Testbench for JopDdr3Top with real MIG RTL and Xilinx DDR3 behavioral model.
//
// Architecture:
//   tb_jop_ddr3 (this file)
//     +-- JopDdr3Top (SpinalHDL-generated, exact FPGA design)
//     |     +-- clk_wiz_0 -> clk_wiz_0_sim.v (behavioral clock wizard)
//     |     +-- mig_7series_0 -> real MIG RTL (PHY, controller, calibration)
//     +-- ddr3_model (Xilinx DDR3 behavioral model)
//
// The DDR3 model is initialized AFTER MIG calibration completes, by calling
// memory_write() directly on the model with the JOP program data.
//
// UART output from usb_tx is decoded by a bit-level UART receiver.

// DDR3 model requires ps resolution
`timescale 1ps / 1ps

module tb_jop_ddr3;

  // ========================================================================
  // Parameters
  // ========================================================================

  parameter CLK_PERIOD    = 10000;     // 100 MHz board clock (ps)
  parameter RESET_HOLD    = 200000;    // Hold reset for 200 ns (ps)
  parameter MAX_CYCLES    = 2000000;   // Default timeout (cycles of ui_clk)
  parameter BAUD_RATE     = 1000000;  // BmbUart default: 1 Mbaud
  parameter CLK_FREQ      = 100000000; // 100 MHz

  // DDR3 geometry (2Gb x16)
  parameter COL_WIDTH     = 10;
  parameter ROW_WIDTH     = 14;
  parameter BA_WIDTH      = 3;
  parameter DQ_WIDTH      = 16;
  parameter DQS_WIDTH     = 2;
  parameter DM_WIDTH      = 2;
  parameter ODT_WIDTH     = 1;
  parameter CS_WIDTH      = 1;
  parameter nCK_PER_CLK   = 4;

  // ========================================================================
  // Clock and Reset
  // ========================================================================

  reg board_clk = 0;
  reg board_resetn = 0;  // Active-low

  always #(CLK_PERIOD/2) board_clk = ~board_clk;

  initial begin
    board_resetn = 0;
    #RESET_HOLD;
    board_resetn = 1;
    $display("[%0t] Board reset released", $time);
  end

  // ========================================================================
  // DUT: JopDdr3Top
  // ========================================================================

  wire [7:0]  led;
  wire        usb_tx;
  wire [15:0] ddr3_dq;
  wire [1:0]  ddr3_dqs_n;
  wire [1:0]  ddr3_dqs_p;
  wire [13:0] ddr3_addr;
  wire [2:0]  ddr3_ba;
  wire        ddr3_ras_n;
  wire        ddr3_cas_n;
  wire        ddr3_we_n;
  wire        ddr3_reset_n;
  wire [0:0]  ddr3_ck_p;
  wire [0:0]  ddr3_ck_n;
  wire [0:0]  ddr3_cke;
  wire [0:0]  ddr3_cs_n;
  wire [1:0]  ddr3_dm;
  wire [0:0]  ddr3_odt;

  JopDdr3Top dut (
    .clk          (board_clk),
    .resetn       (board_resetn),
    .led          (led),
    .usb_rx       (1'b1),        // Idle UART (no serial download)
    .usb_tx       (usb_tx),
    .ddr3_dq      (ddr3_dq),
    .ddr3_dqs_n   (ddr3_dqs_n),
    .ddr3_dqs_p   (ddr3_dqs_p),
    .ddr3_addr    (ddr3_addr),
    .ddr3_ba      (ddr3_ba),
    .ddr3_ras_n   (ddr3_ras_n),
    .ddr3_cas_n   (ddr3_cas_n),
    .ddr3_we_n    (ddr3_we_n),
    .ddr3_reset_n (ddr3_reset_n),
    .ddr3_ck_p    (ddr3_ck_p),
    .ddr3_ck_n    (ddr3_ck_n),
    .ddr3_cke     (ddr3_cke),
    .ddr3_cs_n    (ddr3_cs_n),
    .ddr3_dm      (ddr3_dm),
    .ddr3_odt     (ddr3_odt)
  );

  // ========================================================================
  // DDR3 Memory Model (Xilinx/Micron behavioral model)
  // ========================================================================

  // Direct connection (no WireDelay — all propagation delays are 0 in sim)
  ddr3_model #(
    .MEM_BITS (15),  // 2^15 = 32768 burst slots = 512KB (default 10 = 16KB too small)
    .DEBUG    (0)    // Disable verbose per-command $display messages
  ) comp_ddr3 (
    .rst_n    (ddr3_reset_n),
    .ck       (ddr3_ck_p),
    .ck_n     (ddr3_ck_n),
    .cke      (ddr3_cke),
    .cs_n     (ddr3_cs_n),
    .ras_n    (ddr3_ras_n),
    .cas_n    (ddr3_cas_n),
    .we_n     (ddr3_we_n),
    .dm_tdqs  (ddr3_dm),
    .ba       (ddr3_ba),
    .addr     (ddr3_addr),
    .dq       (ddr3_dq),
    .dqs      (ddr3_dqs_p),
    .dqs_n    (ddr3_dqs_n),
    .tdqs_n   (),
    .odt      (ddr3_odt)
  );

  // ========================================================================
  // DDR3 Memory Initialization
  // ========================================================================
  //
  // Load JOP program into DDR3 model AFTER MIG calibration completes.
  // The MIG uses BANK_ROW_COLUMN address ordering:
  //   col  = app_addr[9:0]
  //   row  = app_addr[23:10]
  //   bank = app_addr[26:24]
  // Where app_addr is the byte address with lower 4 bits zeroed.
  //
  // The DDR3 model's memory_write(bank, row, col, data) stores 128-bit
  // burst data (BL=8 * 16-bit DQ = 128 bits).

  reg [31:0] init_words [0:32767];  // 128KB / 4 = 32K words
  integer init_line, init_word_idx;
  reg [127:0] init_data_128;
  reg [27:0]  init_byte_addr;
  reg [2:0]   init_bank;
  reg [13:0]  init_row;
  reg [9:0]   init_col;
  integer init_done = 0;

  initial begin
    $readmemh("ddr3_init.hex", init_words);

    // Wait for MIG calibration to complete
    wait (dut.mig_init_calib_complete === 1'b1);
    $display("[%0t] MIG calibration complete — initializing DDR3 memory", $time);

    // Load 128KB: 32K 32-bit words = 8K 128-bit lines
    for (init_line = 0; init_line < 8192; init_line = init_line + 1) begin
      init_word_idx = init_line * 4;
      init_byte_addr = init_line * 16;  // 16 bytes per 128-bit line

      // Pack 4 x 32-bit words into 128-bit data (little-endian word order)
      init_data_128 = {init_words[init_word_idx + 3],
                       init_words[init_word_idx + 2],
                       init_words[init_word_idx + 1],
                       init_words[init_word_idx + 0]};

      // MIG BANK_ROW_COLUMN address decomposition
      init_col  = init_byte_addr[9:0];
      init_row  = init_byte_addr[23:10];
      init_bank = init_byte_addr[26:24];

      comp_ddr3.memory_write(init_bank, init_row, init_col, init_data_128);
    end

    // Verify first few words
    $display("DDR3 init: %0d lines loaded (128KB)", 8192);
    $display("  word[0]    = %h", init_words[0]);
    $display("  word[1]    = %h", init_words[1]);
    $display("  word[2]    = %h", init_words[2]);
    $display("  word[3]    = %h", init_words[3]);
    init_done = 1;
  end

  // ========================================================================
  // UART TX Decoder (decode serial bitstream from usb_tx)
  // ========================================================================
  //
  // BmbUart default: 1,000,000 baud (1 Mbaud), 8N1
  // Bit period = 1e12 ps / 1,000,000 = 1,000,000 ps = 1 us

  localparam BIT_PERIOD = 1000000;  // ps (for 1 Mbaud)
  localparam HALF_BIT   = BIT_PERIOD / 2;

  reg [7:0] uart_rx_byte;
  reg       uart_rx_valid;
  integer   uart_bit_idx;

  // String buffer for line assembly
  reg [8*256-1:0] uart_line;
  integer uart_line_len = 0;

  initial begin
    uart_rx_valid = 0;
    forever begin
      // Wait for start bit (falling edge on usb_tx)
      @(negedge usb_tx);

      // Verify start bit: sample at mid-bit
      #HALF_BIT;
      if (usb_tx !== 1'b0) begin
        // False start — continue waiting
      end else begin
        // Sample 8 data bits
        for (uart_bit_idx = 0; uart_bit_idx < 8; uart_bit_idx = uart_bit_idx + 1) begin
          #BIT_PERIOD;
          uart_rx_byte[uart_bit_idx] = usb_tx;
        end

        // Skip stop bit
        #BIT_PERIOD;

        // Print character
        uart_rx_valid = 1;
        if (uart_rx_byte >= 8'h20 && uart_rx_byte < 8'h7F)
          $write("%c", uart_rx_byte);
        else if (uart_rx_byte == 8'h0A)
          $write("\n");
        else if (uart_rx_byte == 8'h0D)
          ; // skip CR
        else
          $write("[%02h]", uart_rx_byte);

        $fflush();
        uart_rx_valid = 0;
      end
    end
  end

  // ========================================================================
  // Monitoring: cycle counter, progress, hang detection
  // ========================================================================

  // Use MIG ui_clk for cycle counting (matches JOP execution)
  wire ui_clk = dut.mig.ui_clk;
  wire ui_rst = dut.mig.ui_clk_sync_rst;

  integer cycle_count = 0;
  integer calib_done_cycle = 0;
  reg     calib_reported = 0;

  always @(posedge ui_clk) begin
    if (!ui_rst) begin
      cycle_count <= cycle_count + 1;

      // Report calibration completion (once)
      if (dut.mig_init_calib_complete && !calib_reported) begin
        calib_done_cycle <= cycle_count;
        calib_reported <= 1;
        $display("[%0t] (cycle %0d) Calibration Done", $time, cycle_count);
      end

      // Progress report every 100k cycles
      if (cycle_count > 0 && cycle_count % 100000 == 0) begin
        $display("[%0t] (cycle %0d) LED=%b",
                 $time, cycle_count, led);
      end

      // Timeout
      if (cycle_count >= MAX_CYCLES) begin
        $display("\n=== TIMEOUT after %0d cycles ===", cycle_count);
        $display("LED = %b", led);
        $finish;
      end
    end
  end

  // ========================================================================
  // MIG Transaction + JOP Execution Trace Logger
  // ========================================================================
  //
  // Logs MIG UI transactions with JOP pipeline context to a CSV file.
  // This enables correlation of DDR3 access patterns with JOP execution
  // state for debugging GC-related hangs on real FPGA hardware.
  //
  // Two record types in one file:
  //   MIG commands:  cycle,type,addr,data,mask,memState,pc,ir,jpc,aout,bout
  //   JOP state:     cycle,type,0,0,0,memState,pc,ir,jpc,aout,bout
  //
  // Types: R=read cmd, W=write cmd, WD=write data, D=read data,
  //        S=state change (memCtrl FSM transition)

  // --- MIG UI signals (adapter -> MIG interface) ---
  wire        mig_app_en   = dut.mainArea_adapter_io_app_en;
  wire [2:0]  mig_app_cmd  = dut.mainArea_adapter_io_app_cmd;
  wire [27:0] mig_app_addr = dut.mainArea_adapter_io_app_addr;
  wire        mig_app_rdy  = dut.mig_app_rdy;

  wire        mig_wdf_wren = dut.mainArea_adapter_io_app_wdf_wren;
  wire        mig_wdf_end  = dut.mainArea_adapter_io_app_wdf_end;
  wire        mig_wdf_rdy  = dut.mig_app_wdf_rdy;
  wire [127:0] mig_wdf_data = dut.mainArea_adapter_io_app_wdf_data;
  wire [15:0]  mig_wdf_mask = dut.mainArea_adapter_io_app_wdf_mask;

  wire        mig_rd_valid = dut.mig_app_rd_data_valid;
  wire        mig_rd_end   = dut.mig_app_rd_data_end;
  wire [127:0] mig_rd_data = dut.mig_app_rd_data;

  // --- JOP pipeline signals ---
  wire [4:0]  jop_mem_state = dut.mainArea_jopCore.memCtrl.state_2;
  wire [10:0] jop_pc        = dut.mainArea_jopCore.pipeline.fetch_io_pc_out;
  wire [9:0]  jop_ir        = dut.mainArea_jopCore.pipeline.fetch_io_ir_out;
  wire [11:0] jop_jpc       = dut.mainArea_jopCore.pipeline.bcfetch_io_jpc_out;
  wire [31:0] jop_aout      = dut.mainArea_jopCore.pipeline.stack_io_aout;
  wire [31:0] jop_bout      = dut.mainArea_jopCore.pipeline.stack_io_bout;

  // Memory controller internal registers
  wire [25:0] jop_addr_reg  = dut.mainArea_jopCore.memCtrl.addrReg;
  wire [11:0] jop_bc_start  = dut.mainArea_jopCore.memCtrl.bcStartReg;  // method start addr
  wire        jop_busy      = dut.mainArea_jopCore.memCtrl.io_memOut_busy;

  integer txn_log;
  integer txn_count = 0;
  reg [4:0] prev_mem_state = 0;

  // State name lookup for human-readable output
  function [12*8-1:0] state_name;
    input [4:0] st;
    case (st)
      5'd0:  state_name = "IDLE        ";
      5'd1:  state_name = "READ_WAIT   ";
      5'd2:  state_name = "WRITE_WAIT  ";
      5'd3:  state_name = "IAST_WAIT   ";
      5'd4:  state_name = "HANDLE_READ ";
      5'd5:  state_name = "HANDLE_WAIT ";
      5'd6:  state_name = "HANDLE_CALC ";
      5'd7:  state_name = "HANDLE_ACCS ";
      5'd8:  state_name = "HANDLE_DWAIT";
      5'd9:  state_name = "HANDLE_BREAD";
      5'd10: state_name = "HANDLE_BWAIT";
      5'd11: state_name = "NP_EXC      ";
      5'd12: state_name = "AB_EXC      ";
      5'd13: state_name = "BC_CACHECHECK";
      5'd14: state_name = "BC_FILL_R1  ";
      5'd15: state_name = "BC_FILL_LOOP";
      5'd16: state_name = "BC_FILL_CMD ";
      5'd17: state_name = "CP_SETUP    ";
      5'd18: state_name = "CP_READ     ";
      5'd19: state_name = "CP_READ_WAIT";
      5'd20: state_name = "CP_WRITE    ";
      5'd21: state_name = "CP_STOP     ";
      5'd22: state_name = "GS_READ     ";
      5'd23: state_name = "PS_WRITE    ";
      5'd24: state_name = "LAST        ";
      default: state_name = "UNKNOWN     ";
    endcase
  endfunction

  initial begin
    txn_log = $fopen("mig_trace.csv", "w");
    $fwrite(txn_log, "cycle,type,mig_addr,mig_data,mig_mask,memState,pc,ir,jpc,aout,bout,addrReg,bcStart\n");
  end

  // Log MIG transactions + JOP state
  always @(posedge ui_clk) begin
    if (!ui_rst && init_done) begin

      // --- MIG command accepted ---
      if (mig_app_en && mig_app_rdy) begin
        if (mig_app_cmd == 3'b001) begin
          $fwrite(txn_log, "%0d,R,%07h,0,0,%0d,%03h,%03h,%03h,%08h,%08h,%07h,%03h\n",
                  cycle_count, mig_app_addr,
                  jop_mem_state, jop_pc, jop_ir, jop_jpc,
                  jop_aout, jop_bout, jop_addr_reg, jop_bc_start);
          txn_count <= txn_count + 1;
        end else if (mig_app_cmd == 3'b000) begin
          $fwrite(txn_log, "%0d,W,%07h,0,0,%0d,%03h,%03h,%03h,%08h,%08h,%07h,%03h\n",
                  cycle_count, mig_app_addr,
                  jop_mem_state, jop_pc, jop_ir, jop_jpc,
                  jop_aout, jop_bout, jop_addr_reg, jop_bc_start);
          txn_count <= txn_count + 1;
        end
      end

      // --- Write data accepted ---
      if (mig_wdf_wren && mig_wdf_rdy) begin
        $fwrite(txn_log, "%0d,WD,0,%032h,%04h,%0d,0,0,0,0,0,0,0\n",
                cycle_count, mig_wdf_data, mig_wdf_mask, jop_mem_state);
      end

      // --- Read data returned ---
      if (mig_rd_valid) begin
        $fwrite(txn_log, "%0d,D,0,%032h,0,%0d,%03h,%03h,%03h,%08h,%08h,%07h,%03h\n",
                cycle_count, mig_rd_data,
                jop_mem_state, jop_pc, jop_ir, jop_jpc,
                jop_aout, jop_bout, jop_addr_reg, jop_bc_start);
      end

      // --- Memory controller state change ---
      if (jop_mem_state !== prev_mem_state) begin
        $fwrite(txn_log, "%0d,S,0,0,0,%0d,%03h,%03h,%03h,%08h,%08h,%07h,%03h\n",
                cycle_count,
                jop_mem_state, jop_pc, jop_ir, jop_jpc,
                jop_aout, jop_bout, jop_addr_reg, jop_bc_start);
        prev_mem_state <= jop_mem_state;
      end
    end
  end

  // Close log and report stats at end of sim
  always @(posedge ui_clk) begin
    if (cycle_count >= MAX_CYCLES) begin
      $display("MIG trace: %0d commands logged to mig_trace.csv", txn_count);
      $fflush(txn_log);
      $fclose(txn_log);
    end
  end

  // ========================================================================
  // Waveform dump
  // ========================================================================

  // Waveform dump disabled by default (huge VCD). Use sim_gui for waveforms.
  // initial begin
  //   $dumpfile("tb_jop_ddr3.vcd");
  //   $dumpvars(0, tb_jop_ddr3);
  // end

endmodule
