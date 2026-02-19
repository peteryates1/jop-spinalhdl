// SDR SDRAM Behavioral Model with Timing Assertions
//
// Generic 16-bit SDR SDRAM model for Questa/ModelSim simulation.
// Tracks bank/row state and checks timing constraints that
// SpinalHDL's SdramModel does NOT enforce.
//
// Configurable for W9825G6JH6 (13-bit row, 9-bit col, CAS=3)
// and W9864G6JT (12-bit row, 8-bit col, CAS=2).

`timescale 1ns/1ps

module sdram_model #(
  parameter ROW_BITS   = 13,
  parameter COL_BITS   = 9,
  parameter BANK_BITS  = 2,
  parameter DQ_BITS    = 16,
  parameter CAS        = 3,
  // Timing constraints in clock cycles (at 100 MHz = 10ns)
  parameter tRP_CYC    = 2,   // Precharge to Activate
  parameter tRCD_CYC   = 2,   // Activate to Read/Write
  parameter tRAS_CYC   = 5,   // Activate to Precharge min
  parameter tRC_CYC    = 6,   // Activate to Activate same bank
  parameter tWR_CYC    = 1,   // Write recovery (last write data to precharge)
  parameter tRFC_CYC   = 6,   // Refresh cycle time
  parameter MEM_BYTES  = 8*1024*1024  // Total memory size in bytes
)(
  input                      clk,
  input                      cke,
  input                      cs_n,
  input                      ras_n,
  input                      cas_n,
  input                      we_n,
  input  [BANK_BITS-1:0]     ba,
  input  [ROW_BITS-1:0]      addr,
  input  [DQ_BITS/8-1:0]     dqm,
  inout  [DQ_BITS-1:0]       dq
);

  localparam NUM_BANKS = 1 << BANK_BITS;
  localparam COL_SIZE  = 1 << COL_BITS;
  localparam ROW_SIZE  = 1 << ROW_BITS;
  localparam BANK_SIZE = COL_SIZE * ROW_SIZE * (DQ_BITS/8);

  // Command decode
  localparam CMD_NOP       = 3'b111;
  localparam CMD_ACTIVE    = 3'b011;
  localparam CMD_READ      = 3'b101;
  localparam CMD_WRITE     = 3'b100;
  localparam CMD_PRECHARGE = 3'b010;
  localparam CMD_REFRESH   = 3'b001;
  localparam CMD_MODE      = 3'b000;

  // Memory storage
  reg [7:0] mem [0:MEM_BYTES-1];

  // Bank state
  reg                   bank_active [0:NUM_BANKS-1];
  reg [ROW_BITS-1:0]    bank_row    [0:NUM_BANKS-1];

  // Timing counters (cycles since last event, per bank)
  reg [15:0] bank_activate_cycle [0:NUM_BANKS-1];  // cycle of last ACTIVE
  reg [15:0] bank_precharge_cycle [0:NUM_BANKS-1]; // cycle of last PRECHARGE
  reg [15:0] bank_write_cycle [0:NUM_BANKS-1];     // cycle of last WRITE
  reg [15:0] last_refresh_cycle;

  // Global cycle counter
  reg [31:0] cycle_count;

  // Read pipeline (CAS latency, compensated for Verilog NBA timing)
  //
  // In SpinalHDL simulation, the Scala SDRAM model callback runs AFTER register
  // updates at the same clock edge, so it sees commands in the SAME cycle they're
  // driven. In Verilog, this model's always @(posedge clk) sees register values
  // from the PREVIOUS cycle (NBA scheduling), adding +1 cycle of effective latency.
  //
  // SdramCtrl's readHistory pipeline is CAS+2 stages (0 to CAS+2). To align:
  //   SpinalHDL: 0 (same-cycle) + (CAS-1) shifts + 1 (register) = CAS cycles
  //   Verilog:   1 (NBA delay) + (CAS-1) shifts + 0.5 (negedge) + 0.5+1 (sample+reg) = CAS+1
  // We need CAS+1 cycles from chip_cmd to data in chip_sdram_DQ_read (available
  // in active region at CAS+2). Pipeline output at stage CAS-1 achieves this.
  //
  reg [DQ_BITS-1:0]  read_pipe_data [0:CAS-1];
  reg                read_pipe_valid [0:CAS-1];
  reg [DQ_BITS/8-1:0] read_pipe_dqm [0:CAS-1];

  // DQ output drive — driven at negedge clk so pipeline NBA from posedge has settled.
  // This models the real SDRAM's tAC (data appears after the clock edge).
  reg [DQ_BITS-1:0] dq_drive;
  reg               dq_oe_neg;

  always @(negedge clk) begin
    dq_oe_neg <= read_pipe_valid[CAS-1];
    if (read_pipe_valid[CAS-1]) begin
      // Drive full data — DQM masking for reads is handled by the controller,
      // not at command time (DQM at READ cmd is for output disable 2 clocks later,
      // which the controller manages via readHistory/DQM timing)
      dq_drive <= read_pipe_data[CAS-1];
    end
  end

  assign dq = dq_oe_neg ? dq_drive : {DQ_BITS{1'bz}};

  // Timing violation counters
  integer timing_violations;
  integer total_reads;
  integer total_writes;
  integer total_activates;
  integer total_precharges;
  integer total_refreshes;

  wire [2:0] cmd = {ras_n, cas_n, we_n};

  // Temp variable for byte address computation (used in always block)
  reg [31:0] baddr;

  integer i;

  // Initialize
  initial begin
    cycle_count = 0;
    timing_violations = 0;
    total_reads = 0;
    total_writes = 0;
    total_activates = 0;
    total_precharges = 0;
    total_refreshes = 0;
    last_refresh_cycle = 0;
    for (i = 0; i < NUM_BANKS; i = i + 1) begin
      bank_active[i] = 0;
      bank_row[i] = 0;
      bank_activate_cycle[i] = 0;
      bank_precharge_cycle[i] = 0;
      bank_write_cycle[i] = 0;
    end
    for (i = 0; i < CAS; i = i + 1) begin
      read_pipe_data[i] = 0;
      read_pipe_valid[i] = 0;
      read_pipe_dqm[i] = 0;
    end
    // Memory initialization is handled by the testbench (byte-expanded from
    // 32-bit hex words).  Do NOT $readmemh here — mem is byte-addressed.
  end

  // Helper: compute byte address from bank, row, column
  function [31:0] byte_addr;
    input [BANK_BITS-1:0] b;
    input [ROW_BITS-1:0]  r;
    input [COL_BITS-1:0]  c;
    begin
      // Address mapping: {row, bank, column} * bytes_per_word
      byte_addr = ((r * NUM_BANKS + b) * COL_SIZE + c) * (DQ_BITS/8);
    end
  endfunction

  // Task: load 32-bit words into byte-addressed memory
  task load_word;
    input [31:0] word_addr;
    input [31:0] data;
    begin
      // Little-endian byte order (matching SpinalHDL SdramModel)
      mem[word_addr*4 + 0] = data[7:0];
      mem[word_addr*4 + 1] = data[15:8];
      mem[word_addr*4 + 2] = data[23:16];
      mem[word_addr*4 + 3] = data[31:24];
    end
  endtask

  always @(posedge clk) begin
    if (cke) begin
      cycle_count <= cycle_count + 1;

      // Shift read pipeline (output at CAS-1, compensating for Verilog NBA delay)
      for (i = CAS-1; i > 0; i = i - 1) begin
        read_pipe_data[i] <= read_pipe_data[i-1];
        read_pipe_valid[i] <= read_pipe_valid[i-1];
        read_pipe_dqm[i] <= read_pipe_dqm[i-1];
      end
      read_pipe_data[0] <= 0;
      read_pipe_valid[0] <= 0;
      read_pipe_dqm[0] <= 0;

      // Process command
      if (!cs_n) begin
        case (cmd)

          CMD_ACTIVE: begin
            total_activates <= total_activates + 1;
            // Check tRP: precharge to activate
            if (cycle_count - bank_precharge_cycle[ba] < tRP_CYC && bank_precharge_cycle[ba] != 0) begin
              $display("[%0t] TIMING VIOLATION: tRP (bank %0d) — %0d cycles since precharge, need %0d",
                       $time, ba, cycle_count - bank_precharge_cycle[ba], tRP_CYC);
              timing_violations <= timing_violations + 1;
            end
            // Check tRC: activate to activate same bank
            if (cycle_count - bank_activate_cycle[ba] < tRC_CYC && bank_activate_cycle[ba] != 0 && bank_active[ba]) begin
              $display("[%0t] TIMING VIOLATION: tRC (bank %0d) — %0d cycles since last activate, need %0d",
                       $time, ba, cycle_count - bank_activate_cycle[ba], tRC_CYC);
              timing_violations <= timing_violations + 1;
            end
            // Check bank not already active
            if (bank_active[ba]) begin
              $display("[%0t] PROTOCOL ERROR: ACTIVE to already-active bank %0d (row %0d -> %0d)",
                       $time, ba, bank_row[ba], addr);
              timing_violations <= timing_violations + 1;
            end
            bank_active[ba] <= 1;
            bank_row[ba] <= addr;
            bank_activate_cycle[ba] <= cycle_count;
          end

          CMD_READ: begin
            total_reads <= total_reads + 1;
            // Check bank is active
            if (!bank_active[ba]) begin
              $display("[%0t] PROTOCOL ERROR: READ from inactive bank %0d", $time, ba);
              timing_violations <= timing_violations + 1;
            end
            // Check tRCD: activate to read
            if (cycle_count - bank_activate_cycle[ba] < tRCD_CYC) begin
              $display("[%0t] TIMING VIOLATION: tRCD (bank %0d) — %0d cycles since activate, need %0d",
                       $time, ba, cycle_count - bank_activate_cycle[ba], tRCD_CYC);
              timing_violations <= timing_violations + 1;
            end
            // Read data into pipeline
            baddr = byte_addr(ba, bank_row[ba], addr[COL_BITS-1:0]);
            if (total_reads < 20)
              $display("[%0t] SDRAM READ: bank=%0d row=%0d col=%0d baddr=%h data=%h%h",
                       $time, ba, bank_row[ba], addr[COL_BITS-1:0], baddr,
                       mem[baddr+1], mem[baddr]);
            read_pipe_data[0] <= {mem[baddr+1], mem[baddr]};
            read_pipe_valid[0] <= 1;
            read_pipe_dqm[0] <= dqm;
          end

          CMD_WRITE: begin
            total_writes <= total_writes + 1;
            // Check bank is active
            if (!bank_active[ba]) begin
              $display("[%0t] PROTOCOL ERROR: WRITE to inactive bank %0d", $time, ba);
              timing_violations <= timing_violations + 1;
            end
            // Check tRCD: activate to write
            if (cycle_count - bank_activate_cycle[ba] < tRCD_CYC) begin
              $display("[%0t] TIMING VIOLATION: tRCD (bank %0d) — %0d cycles since activate, need %0d",
                       $time, ba, cycle_count - bank_activate_cycle[ba], tRCD_CYC);
              timing_violations <= timing_violations + 1;
            end
            // Write data (respecting DQM)
            baddr = byte_addr(ba, bank_row[ba], addr[COL_BITS-1:0]);
            if (!dqm[0]) mem[baddr]   <= dq[7:0];
            if (!dqm[1]) mem[baddr+1] <= dq[15:8];
            bank_write_cycle[ba] <= cycle_count;
          end

          CMD_PRECHARGE: begin
            total_precharges <= total_precharges + 1;
            if (addr[10]) begin
              // Precharge all banks
              for (i = 0; i < NUM_BANKS; i = i + 1) begin
                // Check tRAS: activate to precharge
                if (bank_active[i] && cycle_count - bank_activate_cycle[i] < tRAS_CYC) begin
                  $display("[%0t] TIMING VIOLATION: tRAS (bank %0d) — %0d cycles since activate, need %0d",
                           $time, i, cycle_count - bank_activate_cycle[i], tRAS_CYC);
                  timing_violations <= timing_violations + 1;
                end
                // Check tWR: write to precharge
                if (bank_active[i] && cycle_count - bank_write_cycle[i] < tWR_CYC && bank_write_cycle[i] != 0) begin
                  $display("[%0t] TIMING VIOLATION: tWR (bank %0d) — %0d cycles since write, need %0d",
                           $time, i, cycle_count - bank_write_cycle[i], tWR_CYC);
                  timing_violations <= timing_violations + 1;
                end
                bank_active[i] <= 0;
                bank_precharge_cycle[i] <= cycle_count;
              end
            end else begin
              // Precharge single bank
              if (bank_active[ba] && cycle_count - bank_activate_cycle[ba] < tRAS_CYC) begin
                $display("[%0t] TIMING VIOLATION: tRAS (bank %0d) — %0d cycles since activate, need %0d",
                         $time, ba, cycle_count - bank_activate_cycle[ba], tRAS_CYC);
                timing_violations <= timing_violations + 1;
              end
              if (bank_active[ba] && cycle_count - bank_write_cycle[ba] < tWR_CYC && bank_write_cycle[ba] != 0) begin
                $display("[%0t] TIMING VIOLATION: tWR (bank %0d) — %0d cycles since write, need %0d",
                         $time, ba, cycle_count - bank_write_cycle[ba], tWR_CYC);
                timing_violations <= timing_violations + 1;
              end
              bank_active[ba] <= 0;
              bank_precharge_cycle[ba] <= cycle_count;
            end
          end

          CMD_REFRESH: begin
            total_refreshes <= total_refreshes + 1;
            // All banks must be precharged for refresh
            for (i = 0; i < NUM_BANKS; i = i + 1) begin
              if (bank_active[i]) begin
                $display("[%0t] PROTOCOL ERROR: REFRESH with bank %0d still active", $time, i);
                timing_violations <= timing_violations + 1;
              end
            end
            last_refresh_cycle <= cycle_count;
          end

          CMD_MODE: begin
            // Mode register set — just log it
            $display("[%0t] MODE REGISTER SET: addr=%h", $time, addr);
          end

          CMD_NOP: begin
            // Nothing
          end

          default: begin
            // Unknown command
          end
        endcase
      end
    end // cke
  end

  // Periodic statistics and final report
  always @(posedge clk) begin
    if (cycle_count > 0 && cycle_count % 1000000 == 0) begin
      $display("[%0t] SDRAM stats: %0d reads, %0d writes, %0d activates, %0d precharges, %0d refreshes, %0d violations",
               $time, total_reads, total_writes, total_activates, total_precharges, total_refreshes, timing_violations);
    end
  end

endmodule
