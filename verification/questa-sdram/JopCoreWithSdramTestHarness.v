// Generator : SpinalHDL v1.12.2    git head : f25edbcee624ef41548345cfb91c42060e33313f
// Component : JopCoreWithSdramTestHarness
// Git hash  : 9d85ba5592006d792e5637d055f68061e48dad4e

`timescale 1ns/1ps

module JopCoreWithSdramTestHarness (
  output wire [12:0]   io_sdram_ADDR,
  output wire [1:0]    io_sdram_BA,
  output wire [1:0]    io_sdram_DQM,
  output wire          io_sdram_CASn,
  output wire          io_sdram_CKE,
  output wire          io_sdram_CSn,
  output wire          io_sdram_RASn,
  output wire          io_sdram_WEn,
  output wire [10:0]   io_pc,
  output wire [11:0]   io_jpc,
  output wire [9:0]    io_instr,
  output wire          io_jfetch,
  output wire          io_jopdfetch,
  output wire [31:0]   io_aout,
  output wire [31:0]   io_bout,
  output wire          io_memBusy,
  output wire [7:0]    io_uartTxData,
  output wire          io_uartTxValid,
  output wire          io_ioWr,
  output wire [7:0]    io_ioAddr,
  output wire [31:0]   io_ioWrData,
  output wire          io_bmbCmdValid,
  output wire          io_bmbCmdReady,
  output wire [25:0]   io_bmbCmdAddr,
  output wire [0:0]    io_bmbCmdOpcode,
  output wire          io_bmbRspValid,
  output wire [31:0]   io_bmbRspData,
  input  wire          reset,
  inout  wire [15:0]   io_sdram_DQ,
  input  wire          clk
);

  wire                jopSystem_io_exc;
  wire       [12:0]   jopSystem_io_sdram_ADDR;
  wire       [1:0]    jopSystem_io_sdram_BA;
  wire                jopSystem_io_sdram_CASn;
  wire                jopSystem_io_sdram_CKE;
  wire                jopSystem_io_sdram_CSn;
  wire       [1:0]    jopSystem_io_sdram_DQM;
  wire                jopSystem_io_sdram_RASn;
  wire                jopSystem_io_sdram_WEn;
  wire       [15:0]   jopSystem_io_sdram_DQ_write;
  wire       [15:0]   jopSystem_io_sdram_DQ_writeEnable;
  wire       [7:0]    jopSystem_io_ioAddr;
  wire                jopSystem_io_ioRd;
  wire                jopSystem_io_ioWr;
  wire       [31:0]   jopSystem_io_ioWrData;
  wire       [10:0]   jopSystem_io_pc;
  wire       [11:0]   jopSystem_io_jpc;
  wire       [9:0]    jopSystem_io_instr;
  wire                jopSystem_io_jfetch;
  wire                jopSystem_io_jopdfetch;
  wire       [31:0]   jopSystem_io_aout;
  wire       [31:0]   jopSystem_io_bout;
  wire                jopSystem_io_memBusy;
  wire       [4:0]    jopSystem_io_debugMemState;
  wire                jopSystem_io_debugMemHandleActive;
  wire                jopSystem_io_bmbCmdValid;
  wire                jopSystem_io_bmbCmdReady;
  wire       [25:0]   jopSystem_io_bmbCmdAddr;
  wire       [0:0]    jopSystem_io_bmbCmdOpcode;
  wire                jopSystem_io_bmbRspValid;
  wire       [31:0]   jopSystem_io_bmbRspData;
  reg                 _zz_io_sdram_DQ;
  reg                 _zz_io_sdram_DQ_1;
  reg                 _zz_io_sdram_DQ_2;
  reg                 _zz_io_sdram_DQ_3;
  reg                 _zz_io_sdram_DQ_4;
  reg                 _zz_io_sdram_DQ_5;
  reg                 _zz_io_sdram_DQ_6;
  reg                 _zz_io_sdram_DQ_7;
  reg                 _zz_io_sdram_DQ_8;
  reg                 _zz_io_sdram_DQ_9;
  reg                 _zz_io_sdram_DQ_10;
  reg                 _zz_io_sdram_DQ_11;
  reg                 _zz_io_sdram_DQ_12;
  reg                 _zz_io_sdram_DQ_13;
  reg                 _zz_io_sdram_DQ_14;
  reg                 _zz_io_sdram_DQ_15;
  wire       [15:0]   _zz_io_sdram_DQ_read;
  wire       [15:0]   _zz_io_sdram_DQ_16;
  wire       [15:0]   _zz_when_InOutWrapper_l15;
  reg        [31:0]   sysCntReg;
  reg        [7:0]    uartTxDataReg;
  reg                 uartTxValidReg;
  reg        [31:0]   ioRdData;
  wire       [3:0]    ioSubAddr;
  wire       [1:0]    ioSlaveId;
  reg        [7:0]    excTypeReg;
  reg                 excPend;
  reg                 excDly;
  wire       [15:0]   _zz_when_InOutWrapper_l15_1;
  wire       [15:0]   _zz_io_sdram_DQ_17;
  reg        [15:0]   _zz_io_sdram_DQ_read_1;
  wire                when_InOutWrapper_l15;
  wire                when_InOutWrapper_l15_1;
  wire                when_InOutWrapper_l15_2;
  wire                when_InOutWrapper_l15_3;
  wire                when_InOutWrapper_l15_4;
  wire                when_InOutWrapper_l15_5;
  wire                when_InOutWrapper_l15_6;
  wire                when_InOutWrapper_l15_7;
  wire                when_InOutWrapper_l15_8;
  wire                when_InOutWrapper_l15_9;
  wire                when_InOutWrapper_l15_10;
  wire                when_InOutWrapper_l15_11;
  wire                when_InOutWrapper_l15_12;
  wire                when_InOutWrapper_l15_13;
  wire                when_InOutWrapper_l15_14;
  wire                when_InOutWrapper_l15_15;

  JopCoreWithSdram jopSystem (
    .io_sdram_ADDR           (jopSystem_io_sdram_ADDR[12:0]          ), //o
    .io_sdram_BA             (jopSystem_io_sdram_BA[1:0]             ), //o
    .io_sdram_DQ_read        (_zz_io_sdram_DQ_read[15:0]             ), //i
    .io_sdram_DQ_write       (jopSystem_io_sdram_DQ_write[15:0]      ), //o
    .io_sdram_DQ_writeEnable (jopSystem_io_sdram_DQ_writeEnable[15:0]), //o
    .io_sdram_DQM            (jopSystem_io_sdram_DQM[1:0]            ), //o
    .io_sdram_CASn           (jopSystem_io_sdram_CASn                ), //o
    .io_sdram_CKE            (jopSystem_io_sdram_CKE                 ), //o
    .io_sdram_CSn            (jopSystem_io_sdram_CSn                 ), //o
    .io_sdram_RASn           (jopSystem_io_sdram_RASn                ), //o
    .io_sdram_WEn            (jopSystem_io_sdram_WEn                 ), //o
    .io_ioAddr               (jopSystem_io_ioAddr[7:0]               ), //o
    .io_ioRd                 (jopSystem_io_ioRd                      ), //o
    .io_ioWr                 (jopSystem_io_ioWr                      ), //o
    .io_ioWrData             (jopSystem_io_ioWrData[31:0]            ), //o
    .io_ioRdData             (ioRdData[31:0]                         ), //i
    .io_pc                   (jopSystem_io_pc[10:0]                  ), //o
    .io_jpc                  (jopSystem_io_jpc[11:0]                 ), //o
    .io_instr                (jopSystem_io_instr[9:0]                ), //o
    .io_jfetch               (jopSystem_io_jfetch                    ), //o
    .io_jopdfetch            (jopSystem_io_jopdfetch                 ), //o
    .io_aout                 (jopSystem_io_aout[31:0]                ), //o
    .io_bout                 (jopSystem_io_bout[31:0]                ), //o
    .io_memBusy              (jopSystem_io_memBusy                   ), //o
    .io_irq                  (1'b0                                   ), //i
    .io_irqEna               (1'b0                                   ), //i
    .io_exc                  (jopSystem_io_exc                       ), //i
    .io_debugMemState        (jopSystem_io_debugMemState[4:0]        ), //o
    .io_debugMemHandleActive (jopSystem_io_debugMemHandleActive      ), //o
    .io_bmbCmdValid          (jopSystem_io_bmbCmdValid               ), //o
    .io_bmbCmdReady          (jopSystem_io_bmbCmdReady               ), //o
    .io_bmbCmdAddr           (jopSystem_io_bmbCmdAddr[25:0]          ), //o
    .io_bmbCmdOpcode         (jopSystem_io_bmbCmdOpcode              ), //o
    .io_bmbRspValid          (jopSystem_io_bmbRspValid               ), //o
    .io_bmbRspData           (jopSystem_io_bmbRspData[31:0]          ), //o
    .reset                   (reset                                  ), //i
    .clk                     (clk                                    )  //i
  );
  assign io_sdram_DQ[0] = _zz_io_sdram_DQ_15 ? _zz_io_sdram_DQ_17[0] : 1'bz;
  assign io_sdram_DQ[1] = _zz_io_sdram_DQ_14 ? _zz_io_sdram_DQ_17[1] : 1'bz;
  assign io_sdram_DQ[2] = _zz_io_sdram_DQ_13 ? _zz_io_sdram_DQ_17[2] : 1'bz;
  assign io_sdram_DQ[3] = _zz_io_sdram_DQ_12 ? _zz_io_sdram_DQ_17[3] : 1'bz;
  assign io_sdram_DQ[4] = _zz_io_sdram_DQ_11 ? _zz_io_sdram_DQ_17[4] : 1'bz;
  assign io_sdram_DQ[5] = _zz_io_sdram_DQ_10 ? _zz_io_sdram_DQ_17[5] : 1'bz;
  assign io_sdram_DQ[6] = _zz_io_sdram_DQ_9 ? _zz_io_sdram_DQ_17[6] : 1'bz;
  assign io_sdram_DQ[7] = _zz_io_sdram_DQ_8 ? _zz_io_sdram_DQ_17[7] : 1'bz;
  assign io_sdram_DQ[8] = _zz_io_sdram_DQ_7 ? _zz_io_sdram_DQ_17[8] : 1'bz;
  assign io_sdram_DQ[9] = _zz_io_sdram_DQ_6 ? _zz_io_sdram_DQ_17[9] : 1'bz;
  assign io_sdram_DQ[10] = _zz_io_sdram_DQ_5 ? _zz_io_sdram_DQ_17[10] : 1'bz;
  assign io_sdram_DQ[11] = _zz_io_sdram_DQ_4 ? _zz_io_sdram_DQ_17[11] : 1'bz;
  assign io_sdram_DQ[12] = _zz_io_sdram_DQ_3 ? _zz_io_sdram_DQ_17[12] : 1'bz;
  assign io_sdram_DQ[13] = _zz_io_sdram_DQ_2 ? _zz_io_sdram_DQ_17[13] : 1'bz;
  assign io_sdram_DQ[14] = _zz_io_sdram_DQ_1 ? _zz_io_sdram_DQ_17[14] : 1'bz;
  assign io_sdram_DQ[15] = _zz_io_sdram_DQ ? _zz_io_sdram_DQ_17[15] : 1'bz;
  always @(*) begin
    _zz_io_sdram_DQ = 1'b0;
    if(when_InOutWrapper_l15_15) begin
      _zz_io_sdram_DQ = 1'b1;
    end
  end

  always @(*) begin
    _zz_io_sdram_DQ_1 = 1'b0;
    if(when_InOutWrapper_l15_14) begin
      _zz_io_sdram_DQ_1 = 1'b1;
    end
  end

  always @(*) begin
    _zz_io_sdram_DQ_2 = 1'b0;
    if(when_InOutWrapper_l15_13) begin
      _zz_io_sdram_DQ_2 = 1'b1;
    end
  end

  always @(*) begin
    _zz_io_sdram_DQ_3 = 1'b0;
    if(when_InOutWrapper_l15_12) begin
      _zz_io_sdram_DQ_3 = 1'b1;
    end
  end

  always @(*) begin
    _zz_io_sdram_DQ_4 = 1'b0;
    if(when_InOutWrapper_l15_11) begin
      _zz_io_sdram_DQ_4 = 1'b1;
    end
  end

  always @(*) begin
    _zz_io_sdram_DQ_5 = 1'b0;
    if(when_InOutWrapper_l15_10) begin
      _zz_io_sdram_DQ_5 = 1'b1;
    end
  end

  always @(*) begin
    _zz_io_sdram_DQ_6 = 1'b0;
    if(when_InOutWrapper_l15_9) begin
      _zz_io_sdram_DQ_6 = 1'b1;
    end
  end

  always @(*) begin
    _zz_io_sdram_DQ_7 = 1'b0;
    if(when_InOutWrapper_l15_8) begin
      _zz_io_sdram_DQ_7 = 1'b1;
    end
  end

  always @(*) begin
    _zz_io_sdram_DQ_8 = 1'b0;
    if(when_InOutWrapper_l15_7) begin
      _zz_io_sdram_DQ_8 = 1'b1;
    end
  end

  always @(*) begin
    _zz_io_sdram_DQ_9 = 1'b0;
    if(when_InOutWrapper_l15_6) begin
      _zz_io_sdram_DQ_9 = 1'b1;
    end
  end

  always @(*) begin
    _zz_io_sdram_DQ_10 = 1'b0;
    if(when_InOutWrapper_l15_5) begin
      _zz_io_sdram_DQ_10 = 1'b1;
    end
  end

  always @(*) begin
    _zz_io_sdram_DQ_11 = 1'b0;
    if(when_InOutWrapper_l15_4) begin
      _zz_io_sdram_DQ_11 = 1'b1;
    end
  end

  always @(*) begin
    _zz_io_sdram_DQ_12 = 1'b0;
    if(when_InOutWrapper_l15_3) begin
      _zz_io_sdram_DQ_12 = 1'b1;
    end
  end

  always @(*) begin
    _zz_io_sdram_DQ_13 = 1'b0;
    if(when_InOutWrapper_l15_2) begin
      _zz_io_sdram_DQ_13 = 1'b1;
    end
  end

  always @(*) begin
    _zz_io_sdram_DQ_14 = 1'b0;
    if(when_InOutWrapper_l15_1) begin
      _zz_io_sdram_DQ_14 = 1'b1;
    end
  end

  always @(*) begin
    _zz_io_sdram_DQ_15 = 1'b0;
    if(when_InOutWrapper_l15) begin
      _zz_io_sdram_DQ_15 = 1'b1;
    end
  end

  assign io_sdram_ADDR = jopSystem_io_sdram_ADDR;
  assign io_sdram_BA = jopSystem_io_sdram_BA;
  assign _zz_io_sdram_DQ_16 = jopSystem_io_sdram_DQ_write;
  assign _zz_when_InOutWrapper_l15 = jopSystem_io_sdram_DQ_writeEnable;
  assign io_sdram_DQM = jopSystem_io_sdram_DQM;
  assign io_sdram_CASn = jopSystem_io_sdram_CASn;
  assign io_sdram_CKE = jopSystem_io_sdram_CKE;
  assign io_sdram_CSn = jopSystem_io_sdram_CSn;
  assign io_sdram_RASn = jopSystem_io_sdram_RASn;
  assign io_sdram_WEn = jopSystem_io_sdram_WEn;
  always @(*) begin
    ioRdData = 32'h0;
    case(ioSlaveId)
      2'b00 : begin
        case(ioSubAddr)
          4'b0000 : begin
            ioRdData = sysCntReg;
          end
          4'b0001 : begin
            ioRdData = sysCntReg;
          end
          4'b0100 : begin
            ioRdData = {24'd0, excTypeReg};
          end
          4'b0110 : begin
            ioRdData = 32'h0;
          end
          4'b0111 : begin
            ioRdData = 32'h0;
          end
          default : begin
          end
        endcase
      end
      2'b01 : begin
        case(ioSubAddr)
          4'b0000 : begin
            ioRdData = 32'h00000001;
          end
          default : begin
          end
        endcase
      end
      default : begin
      end
    endcase
  end

  assign ioSubAddr = jopSystem_io_ioAddr[3 : 0];
  assign ioSlaveId = jopSystem_io_ioAddr[5 : 4];
  assign jopSystem_io_exc = (excPend && (! excDly));
  assign io_pc = jopSystem_io_pc;
  assign io_jpc = jopSystem_io_jpc;
  assign io_instr = jopSystem_io_instr;
  assign io_jfetch = jopSystem_io_jfetch;
  assign io_jopdfetch = jopSystem_io_jopdfetch;
  assign io_aout = jopSystem_io_aout;
  assign io_bout = jopSystem_io_bout;
  assign io_memBusy = jopSystem_io_memBusy;
  assign io_uartTxData = uartTxDataReg;
  assign io_uartTxValid = uartTxValidReg;
  assign io_ioWr = jopSystem_io_ioWr;
  assign io_ioAddr = jopSystem_io_ioAddr;
  assign io_ioWrData = jopSystem_io_ioWrData;
  assign io_bmbCmdValid = jopSystem_io_bmbCmdValid;
  assign io_bmbCmdReady = jopSystem_io_bmbCmdReady;
  assign io_bmbCmdAddr = jopSystem_io_bmbCmdAddr;
  assign io_bmbCmdOpcode = jopSystem_io_bmbCmdOpcode;
  assign io_bmbRspValid = jopSystem_io_bmbRspValid;
  assign io_bmbRspData = jopSystem_io_bmbRspData;
  assign when_InOutWrapper_l15 = _zz_when_InOutWrapper_l15_1[0];
  always @(*) begin
    _zz_io_sdram_DQ_read_1[0] = io_sdram_DQ[0];
    _zz_io_sdram_DQ_read_1[1] = io_sdram_DQ[1];
    _zz_io_sdram_DQ_read_1[2] = io_sdram_DQ[2];
    _zz_io_sdram_DQ_read_1[3] = io_sdram_DQ[3];
    _zz_io_sdram_DQ_read_1[4] = io_sdram_DQ[4];
    _zz_io_sdram_DQ_read_1[5] = io_sdram_DQ[5];
    _zz_io_sdram_DQ_read_1[6] = io_sdram_DQ[6];
    _zz_io_sdram_DQ_read_1[7] = io_sdram_DQ[7];
    _zz_io_sdram_DQ_read_1[8] = io_sdram_DQ[8];
    _zz_io_sdram_DQ_read_1[9] = io_sdram_DQ[9];
    _zz_io_sdram_DQ_read_1[10] = io_sdram_DQ[10];
    _zz_io_sdram_DQ_read_1[11] = io_sdram_DQ[11];
    _zz_io_sdram_DQ_read_1[12] = io_sdram_DQ[12];
    _zz_io_sdram_DQ_read_1[13] = io_sdram_DQ[13];
    _zz_io_sdram_DQ_read_1[14] = io_sdram_DQ[14];
    _zz_io_sdram_DQ_read_1[15] = io_sdram_DQ[15];
  end

  assign when_InOutWrapper_l15_1 = _zz_when_InOutWrapper_l15_1[1];
  assign when_InOutWrapper_l15_2 = _zz_when_InOutWrapper_l15_1[2];
  assign when_InOutWrapper_l15_3 = _zz_when_InOutWrapper_l15_1[3];
  assign when_InOutWrapper_l15_4 = _zz_when_InOutWrapper_l15_1[4];
  assign when_InOutWrapper_l15_5 = _zz_when_InOutWrapper_l15_1[5];
  assign when_InOutWrapper_l15_6 = _zz_when_InOutWrapper_l15_1[6];
  assign when_InOutWrapper_l15_7 = _zz_when_InOutWrapper_l15_1[7];
  assign when_InOutWrapper_l15_8 = _zz_when_InOutWrapper_l15_1[8];
  assign when_InOutWrapper_l15_9 = _zz_when_InOutWrapper_l15_1[9];
  assign when_InOutWrapper_l15_10 = _zz_when_InOutWrapper_l15_1[10];
  assign when_InOutWrapper_l15_11 = _zz_when_InOutWrapper_l15_1[11];
  assign when_InOutWrapper_l15_12 = _zz_when_InOutWrapper_l15_1[12];
  assign when_InOutWrapper_l15_13 = _zz_when_InOutWrapper_l15_1[13];
  assign when_InOutWrapper_l15_14 = _zz_when_InOutWrapper_l15_1[14];
  assign when_InOutWrapper_l15_15 = _zz_when_InOutWrapper_l15_1[15];
  assign _zz_when_InOutWrapper_l15_1 = _zz_when_InOutWrapper_l15;
  assign _zz_io_sdram_DQ_17 = _zz_io_sdram_DQ_16;
  assign _zz_io_sdram_DQ_read = _zz_io_sdram_DQ_read_1;
  always @(posedge clk or posedge reset) begin
    if(reset) begin
      sysCntReg <= 32'h000f4240;
      uartTxDataReg <= 8'h0;
      uartTxValidReg <= 1'b0;
      excTypeReg <= 8'h0;
      excPend <= 1'b0;
      excDly <= 1'b0;
    end else begin
      sysCntReg <= (sysCntReg + 32'h0000000a);
      excPend <= 1'b0;
      uartTxValidReg <= 1'b0;
      if(jopSystem_io_ioWr) begin
        case(ioSlaveId)
          2'b00 : begin
            case(ioSubAddr)
              4'b0100 : begin
                excTypeReg <= jopSystem_io_ioWrData[7 : 0];
                excPend <= 1'b1;
              end
              default : begin
              end
            endcase
          end
          2'b01 : begin
            case(ioSubAddr)
              4'b0001 : begin
                uartTxDataReg <= jopSystem_io_ioWrData[7 : 0];
                uartTxValidReg <= 1'b1;
              end
              default : begin
              end
            endcase
          end
          default : begin
          end
        endcase
      end
      excDly <= excPend;
    end
  end


endmodule

module JopCoreWithSdram (
  output wire [12:0]   io_sdram_ADDR,
  output wire [1:0]    io_sdram_BA,
  input  wire [15:0]   io_sdram_DQ_read,
  output wire [15:0]   io_sdram_DQ_write,
  output wire [15:0]   io_sdram_DQ_writeEnable,
  output wire [1:0]    io_sdram_DQM,
  output wire          io_sdram_CASn,
  output wire          io_sdram_CKE,
  output wire          io_sdram_CSn,
  output wire          io_sdram_RASn,
  output wire          io_sdram_WEn,
  output wire [7:0]    io_ioAddr,
  output wire          io_ioRd,
  output wire          io_ioWr,
  output wire [31:0]   io_ioWrData,
  input  wire [31:0]   io_ioRdData,
  output wire [10:0]   io_pc,
  output wire [11:0]   io_jpc,
  output wire [9:0]    io_instr,
  output wire          io_jfetch,
  output wire          io_jopdfetch,
  output wire [31:0]   io_aout,
  output wire [31:0]   io_bout,
  output wire          io_memBusy,
  input  wire          io_irq,
  input  wire          io_irqEna,
  input  wire          io_exc,
  output wire [4:0]    io_debugMemState,
  output wire          io_debugMemHandleActive,
  output wire          io_bmbCmdValid,
  output wire          io_bmbCmdReady,
  output wire [25:0]   io_bmbCmdAddr,
  output wire [0:0]    io_bmbCmdOpcode,
  output wire          io_bmbRspValid,
  output wire [31:0]   io_bmbRspData,
  input  wire          reset,
  input  wire          clk
);

  wire                jopCore_1_io_bmb_cmd_valid;
  wire                jopCore_1_io_bmb_cmd_payload_last;
  wire       [0:0]    jopCore_1_io_bmb_cmd_payload_fragment_opcode;
  wire       [25:0]   jopCore_1_io_bmb_cmd_payload_fragment_address;
  wire       [3:0]    jopCore_1_io_bmb_cmd_payload_fragment_length;
  wire       [31:0]   jopCore_1_io_bmb_cmd_payload_fragment_data;
  wire       [3:0]    jopCore_1_io_bmb_cmd_payload_fragment_mask;
  wire       [3:0]    jopCore_1_io_bmb_cmd_payload_fragment_context;
  wire                jopCore_1_io_bmb_rsp_ready;
  wire       [7:0]    jopCore_1_io_ioAddr;
  wire                jopCore_1_io_ioRd;
  wire                jopCore_1_io_ioWr;
  wire       [31:0]   jopCore_1_io_ioWrData;
  wire       [10:0]   jopCore_1_io_pc;
  wire       [11:0]   jopCore_1_io_jpc;
  wire       [9:0]    jopCore_1_io_instr;
  wire                jopCore_1_io_jfetch;
  wire                jopCore_1_io_jopdfetch;
  wire       [31:0]   jopCore_1_io_aout;
  wire       [31:0]   jopCore_1_io_bout;
  wire                jopCore_1_io_memBusy;
  wire                jopCore_1_io_debugBcRd;
  wire       [4:0]    jopCore_1_io_debugMemState;
  wire                jopCore_1_io_debugMemHandleActive;
  wire                jopCore_1_io_debugAddrWr;
  wire                jopCore_1_io_debugRdc;
  wire                jopCore_1_io_debugRd;
  wire       [31:0]   jopCore_1_io_debugRamData;
  wire                sdramCtrl_1_io_bmb_cmd_ready;
  wire                sdramCtrl_1_io_bmb_rsp_valid;
  wire                sdramCtrl_1_io_bmb_rsp_payload_last;
  wire       [0:0]    sdramCtrl_1_io_bmb_rsp_payload_fragment_opcode;
  wire       [31:0]   sdramCtrl_1_io_bmb_rsp_payload_fragment_data;
  wire       [3:0]    sdramCtrl_1_io_bmb_rsp_payload_fragment_context;
  wire       [12:0]   sdramCtrl_1_io_sdram_ADDR;
  wire       [1:0]    sdramCtrl_1_io_sdram_BA;
  wire                sdramCtrl_1_io_sdram_CASn;
  wire                sdramCtrl_1_io_sdram_CKE;
  wire                sdramCtrl_1_io_sdram_CSn;
  wire       [1:0]    sdramCtrl_1_io_sdram_DQM;
  wire                sdramCtrl_1_io_sdram_RASn;
  wire                sdramCtrl_1_io_sdram_WEn;
  wire       [15:0]   sdramCtrl_1_io_sdram_DQ_write;
  wire       [15:0]   sdramCtrl_1_io_sdram_DQ_writeEnable;

  JopCore jopCore_1 (
    .io_bmb_cmd_valid                    (jopCore_1_io_bmb_cmd_valid                          ), //o
    .io_bmb_cmd_ready                    (sdramCtrl_1_io_bmb_cmd_ready                        ), //i
    .io_bmb_cmd_payload_last             (jopCore_1_io_bmb_cmd_payload_last                   ), //o
    .io_bmb_cmd_payload_fragment_opcode  (jopCore_1_io_bmb_cmd_payload_fragment_opcode        ), //o
    .io_bmb_cmd_payload_fragment_address (jopCore_1_io_bmb_cmd_payload_fragment_address[25:0] ), //o
    .io_bmb_cmd_payload_fragment_length  (jopCore_1_io_bmb_cmd_payload_fragment_length[3:0]   ), //o
    .io_bmb_cmd_payload_fragment_data    (jopCore_1_io_bmb_cmd_payload_fragment_data[31:0]    ), //o
    .io_bmb_cmd_payload_fragment_mask    (jopCore_1_io_bmb_cmd_payload_fragment_mask[3:0]     ), //o
    .io_bmb_cmd_payload_fragment_context (jopCore_1_io_bmb_cmd_payload_fragment_context[3:0]  ), //o
    .io_bmb_rsp_valid                    (sdramCtrl_1_io_bmb_rsp_valid                        ), //i
    .io_bmb_rsp_ready                    (jopCore_1_io_bmb_rsp_ready                          ), //o
    .io_bmb_rsp_payload_last             (sdramCtrl_1_io_bmb_rsp_payload_last                 ), //i
    .io_bmb_rsp_payload_fragment_opcode  (sdramCtrl_1_io_bmb_rsp_payload_fragment_opcode      ), //i
    .io_bmb_rsp_payload_fragment_data    (sdramCtrl_1_io_bmb_rsp_payload_fragment_data[31:0]  ), //i
    .io_bmb_rsp_payload_fragment_context (sdramCtrl_1_io_bmb_rsp_payload_fragment_context[3:0]), //i
    .io_ioAddr                           (jopCore_1_io_ioAddr[7:0]                            ), //o
    .io_ioRd                             (jopCore_1_io_ioRd                                   ), //o
    .io_ioWr                             (jopCore_1_io_ioWr                                   ), //o
    .io_ioWrData                         (jopCore_1_io_ioWrData[31:0]                         ), //o
    .io_ioRdData                         (io_ioRdData[31:0]                                   ), //i
    .io_pc                               (jopCore_1_io_pc[10:0]                               ), //o
    .io_jpc                              (jopCore_1_io_jpc[11:0]                              ), //o
    .io_instr                            (jopCore_1_io_instr[9:0]                             ), //o
    .io_jfetch                           (jopCore_1_io_jfetch                                 ), //o
    .io_jopdfetch                        (jopCore_1_io_jopdfetch                              ), //o
    .io_aout                             (jopCore_1_io_aout[31:0]                             ), //o
    .io_bout                             (jopCore_1_io_bout[31:0]                             ), //o
    .io_memBusy                          (jopCore_1_io_memBusy                                ), //o
    .io_irq                              (io_irq                                              ), //i
    .io_irqEna                           (io_irqEna                                           ), //i
    .io_exc                              (io_exc                                              ), //i
    .io_debugBcRd                        (jopCore_1_io_debugBcRd                              ), //o
    .io_debugMemState                    (jopCore_1_io_debugMemState[4:0]                     ), //o
    .io_debugMemHandleActive             (jopCore_1_io_debugMemHandleActive                   ), //o
    .io_debugAddrWr                      (jopCore_1_io_debugAddrWr                            ), //o
    .io_debugRdc                         (jopCore_1_io_debugRdc                               ), //o
    .io_debugRd                          (jopCore_1_io_debugRd                                ), //o
    .io_debugRamAddr                     (                                                    ), //i
    .io_debugRamData                     (jopCore_1_io_debugRamData[31:0]                     ), //o
    .reset                               (reset                                               ), //i
    .clk                                 (clk                                                 )  //i
  );
  BmbSdramCtrl32 sdramCtrl_1 (
    .io_bmb_cmd_valid                    (jopCore_1_io_bmb_cmd_valid                          ), //i
    .io_bmb_cmd_ready                    (sdramCtrl_1_io_bmb_cmd_ready                        ), //o
    .io_bmb_cmd_payload_last             (jopCore_1_io_bmb_cmd_payload_last                   ), //i
    .io_bmb_cmd_payload_fragment_opcode  (jopCore_1_io_bmb_cmd_payload_fragment_opcode        ), //i
    .io_bmb_cmd_payload_fragment_address (jopCore_1_io_bmb_cmd_payload_fragment_address[25:0] ), //i
    .io_bmb_cmd_payload_fragment_length  (jopCore_1_io_bmb_cmd_payload_fragment_length[3:0]   ), //i
    .io_bmb_cmd_payload_fragment_data    (jopCore_1_io_bmb_cmd_payload_fragment_data[31:0]    ), //i
    .io_bmb_cmd_payload_fragment_mask    (jopCore_1_io_bmb_cmd_payload_fragment_mask[3:0]     ), //i
    .io_bmb_cmd_payload_fragment_context (jopCore_1_io_bmb_cmd_payload_fragment_context[3:0]  ), //i
    .io_bmb_rsp_valid                    (sdramCtrl_1_io_bmb_rsp_valid                        ), //o
    .io_bmb_rsp_ready                    (jopCore_1_io_bmb_rsp_ready                          ), //i
    .io_bmb_rsp_payload_last             (sdramCtrl_1_io_bmb_rsp_payload_last                 ), //o
    .io_bmb_rsp_payload_fragment_opcode  (sdramCtrl_1_io_bmb_rsp_payload_fragment_opcode      ), //o
    .io_bmb_rsp_payload_fragment_data    (sdramCtrl_1_io_bmb_rsp_payload_fragment_data[31:0]  ), //o
    .io_bmb_rsp_payload_fragment_context (sdramCtrl_1_io_bmb_rsp_payload_fragment_context[3:0]), //o
    .io_sdram_ADDR                       (sdramCtrl_1_io_sdram_ADDR[12:0]                     ), //o
    .io_sdram_BA                         (sdramCtrl_1_io_sdram_BA[1:0]                        ), //o
    .io_sdram_DQ_read                    (io_sdram_DQ_read[15:0]                              ), //i
    .io_sdram_DQ_write                   (sdramCtrl_1_io_sdram_DQ_write[15:0]                 ), //o
    .io_sdram_DQ_writeEnable             (sdramCtrl_1_io_sdram_DQ_writeEnable[15:0]           ), //o
    .io_sdram_DQM                        (sdramCtrl_1_io_sdram_DQM[1:0]                       ), //o
    .io_sdram_CASn                       (sdramCtrl_1_io_sdram_CASn                           ), //o
    .io_sdram_CKE                        (sdramCtrl_1_io_sdram_CKE                            ), //o
    .io_sdram_CSn                        (sdramCtrl_1_io_sdram_CSn                            ), //o
    .io_sdram_RASn                       (sdramCtrl_1_io_sdram_RASn                           ), //o
    .io_sdram_WEn                        (sdramCtrl_1_io_sdram_WEn                            ), //o
    .clk                                 (clk                                                 ), //i
    .reset                               (reset                                               )  //i
  );
  assign io_sdram_ADDR = sdramCtrl_1_io_sdram_ADDR;
  assign io_sdram_BA = sdramCtrl_1_io_sdram_BA;
  assign io_sdram_DQ_write = sdramCtrl_1_io_sdram_DQ_write;
  assign io_sdram_DQ_writeEnable = sdramCtrl_1_io_sdram_DQ_writeEnable;
  assign io_sdram_DQM = sdramCtrl_1_io_sdram_DQM;
  assign io_sdram_CASn = sdramCtrl_1_io_sdram_CASn;
  assign io_sdram_CKE = sdramCtrl_1_io_sdram_CKE;
  assign io_sdram_CSn = sdramCtrl_1_io_sdram_CSn;
  assign io_sdram_RASn = sdramCtrl_1_io_sdram_RASn;
  assign io_sdram_WEn = sdramCtrl_1_io_sdram_WEn;
  assign io_ioAddr = jopCore_1_io_ioAddr;
  assign io_ioRd = jopCore_1_io_ioRd;
  assign io_ioWr = jopCore_1_io_ioWr;
  assign io_ioWrData = jopCore_1_io_ioWrData;
  assign io_pc = jopCore_1_io_pc;
  assign io_jpc = jopCore_1_io_jpc;
  assign io_instr = jopCore_1_io_instr;
  assign io_jfetch = jopCore_1_io_jfetch;
  assign io_jopdfetch = jopCore_1_io_jopdfetch;
  assign io_aout = jopCore_1_io_aout;
  assign io_bout = jopCore_1_io_bout;
  assign io_memBusy = jopCore_1_io_memBusy;
  assign io_debugMemState = jopCore_1_io_debugMemState;
  assign io_debugMemHandleActive = jopCore_1_io_debugMemHandleActive;
  assign io_bmbCmdValid = jopCore_1_io_bmb_cmd_valid;
  assign io_bmbCmdReady = sdramCtrl_1_io_bmb_cmd_ready;
  assign io_bmbCmdAddr = jopCore_1_io_bmb_cmd_payload_fragment_address;
  assign io_bmbCmdOpcode = jopCore_1_io_bmb_cmd_payload_fragment_opcode;
  assign io_bmbRspValid = sdramCtrl_1_io_bmb_rsp_valid;
  assign io_bmbRspData = sdramCtrl_1_io_bmb_rsp_payload_fragment_data;

endmodule

module BmbSdramCtrl32 (
  input  wire          io_bmb_cmd_valid,
  output reg           io_bmb_cmd_ready,
  input  wire          io_bmb_cmd_payload_last,
  input  wire [0:0]    io_bmb_cmd_payload_fragment_opcode,
  input  wire [25:0]   io_bmb_cmd_payload_fragment_address,
  input  wire [3:0]    io_bmb_cmd_payload_fragment_length,
  input  wire [31:0]   io_bmb_cmd_payload_fragment_data,
  input  wire [3:0]    io_bmb_cmd_payload_fragment_mask,
  input  wire [3:0]    io_bmb_cmd_payload_fragment_context,
  output wire          io_bmb_rsp_valid,
  input  wire          io_bmb_rsp_ready,
  output reg           io_bmb_rsp_payload_last,
  output wire [0:0]    io_bmb_rsp_payload_fragment_opcode,
  output wire [31:0]   io_bmb_rsp_payload_fragment_data,
  output wire [3:0]    io_bmb_rsp_payload_fragment_context,
  output wire [12:0]   io_sdram_ADDR,
  output wire [1:0]    io_sdram_BA,
  input  wire [15:0]   io_sdram_DQ_read,
  output wire [15:0]   io_sdram_DQ_write,
  output wire [15:0]   io_sdram_DQ_writeEnable,
  output wire [1:0]    io_sdram_DQM,
  output wire          io_sdram_CASn,
  output wire          io_sdram_CKE,
  output wire          io_sdram_CSn,
  output wire          io_sdram_RASn,
  output wire          io_sdram_WEn,
  input  wire          clk,
  input  wire          reset
);

  reg                 ctrl_io_bus_cmd_valid;
  reg        [23:0]   ctrl_io_bus_cmd_payload_address;
  reg                 ctrl_io_bus_cmd_payload_write;
  reg        [15:0]   ctrl_io_bus_cmd_payload_data;
  reg        [1:0]    ctrl_io_bus_cmd_payload_mask;
  reg        [3:0]    ctrl_io_bus_cmd_payload_context_context;
  reg                 ctrl_io_bus_cmd_payload_context_isHigh;
  wire                ctrl_io_bus_rsp_ready;
  wire                ctrl_io_bus_cmd_ready;
  wire                ctrl_io_bus_rsp_valid;
  wire       [15:0]   ctrl_io_bus_rsp_payload_data;
  wire       [3:0]    ctrl_io_bus_rsp_payload_context_context;
  wire                ctrl_io_bus_rsp_payload_context_isHigh;
  wire       [12:0]   ctrl_io_sdram_ADDR;
  wire       [1:0]    ctrl_io_sdram_BA;
  wire                ctrl_io_sdram_CASn;
  wire                ctrl_io_sdram_CKE;
  wire                ctrl_io_sdram_CSn;
  wire       [1:0]    ctrl_io_sdram_DQM;
  wire                ctrl_io_sdram_RASn;
  wire                ctrl_io_sdram_WEn;
  wire       [15:0]   ctrl_io_sdram_DQ_write;
  wire       [15:0]   ctrl_io_sdram_DQ_writeEnable;
  wire       [24:0]   _zz_sdramWordAddr;
  wire       [23:0]   _zz_io_bus_cmd_payload_address;
  wire       [4:0]    _zz__zz_burstCmdTotal;
  wire       [1:0]    _zz__zz_burstCmdTotal_1;
  wire       [2:0]    _zz_burstWordTotal;
  wire       [3:0]    _zz_io_bmb_rsp_payload_last;
  wire       [3:0]    _zz_when_BmbSdramCtrl32_l173;
  reg                 sendingHigh;
  wire       [23:0]   sdramWordAddr;
  reg                 burstActive;
  reg        [23:0]   burstBaseAddr;
  reg        [3:0]    burstCmdIdx;
  reg        [3:0]    burstCmdTotal;
  reg        [3:0]    burstWordTotal;
  reg        [3:0]    burstWordsSent;
  reg        [3:0]    burstContext;
  wire                isBurstRead;
  wire                when_BmbSdramCtrl32_l86;
  wire                ctrl_io_bus_cmd_fire;
  wire       [4:0]    _zz_burstCmdTotal;
  wire                when_BmbSdramCtrl32_l129;
  reg        [15:0]   lowHalfData;
  wire                ctrl_io_bus_rsp_fire;
  wire                when_BmbSdramCtrl32_l157;
  wire                io_bmb_rsp_fire;
  wire                when_BmbSdramCtrl32_l173;

  assign _zz_sdramWordAddr = (io_bmb_cmd_payload_fragment_address >>> 1'd1);
  assign _zz_io_bus_cmd_payload_address = {20'd0, burstCmdIdx};
  assign _zz__zz_burstCmdTotal_1 = {1'b0,1'b1};
  assign _zz__zz_burstCmdTotal = {3'd0, _zz__zz_burstCmdTotal_1};
  assign _zz_burstWordTotal = (_zz_burstCmdTotal >>> 2'd2);
  assign _zz_io_bmb_rsp_payload_last = (burstWordsSent + 4'b0001);
  assign _zz_when_BmbSdramCtrl32_l173 = (burstWordsSent + 4'b0001);
  SdramCtrl ctrl (
    .io_bus_cmd_valid                   (ctrl_io_bus_cmd_valid                       ), //i
    .io_bus_cmd_ready                   (ctrl_io_bus_cmd_ready                       ), //o
    .io_bus_cmd_payload_address         (ctrl_io_bus_cmd_payload_address[23:0]       ), //i
    .io_bus_cmd_payload_write           (ctrl_io_bus_cmd_payload_write               ), //i
    .io_bus_cmd_payload_data            (ctrl_io_bus_cmd_payload_data[15:0]          ), //i
    .io_bus_cmd_payload_mask            (ctrl_io_bus_cmd_payload_mask[1:0]           ), //i
    .io_bus_cmd_payload_context_context (ctrl_io_bus_cmd_payload_context_context[3:0]), //i
    .io_bus_cmd_payload_context_isHigh  (ctrl_io_bus_cmd_payload_context_isHigh      ), //i
    .io_bus_rsp_valid                   (ctrl_io_bus_rsp_valid                       ), //o
    .io_bus_rsp_ready                   (ctrl_io_bus_rsp_ready                       ), //i
    .io_bus_rsp_payload_data            (ctrl_io_bus_rsp_payload_data[15:0]          ), //o
    .io_bus_rsp_payload_context_context (ctrl_io_bus_rsp_payload_context_context[3:0]), //o
    .io_bus_rsp_payload_context_isHigh  (ctrl_io_bus_rsp_payload_context_isHigh      ), //o
    .io_sdram_ADDR                      (ctrl_io_sdram_ADDR[12:0]                    ), //o
    .io_sdram_BA                        (ctrl_io_sdram_BA[1:0]                       ), //o
    .io_sdram_DQ_read                   (io_sdram_DQ_read[15:0]                      ), //i
    .io_sdram_DQ_write                  (ctrl_io_sdram_DQ_write[15:0]                ), //o
    .io_sdram_DQ_writeEnable            (ctrl_io_sdram_DQ_writeEnable[15:0]          ), //o
    .io_sdram_DQM                       (ctrl_io_sdram_DQM[1:0]                      ), //o
    .io_sdram_CASn                      (ctrl_io_sdram_CASn                          ), //o
    .io_sdram_CKE                       (ctrl_io_sdram_CKE                           ), //o
    .io_sdram_CSn                       (ctrl_io_sdram_CSn                           ), //o
    .io_sdram_RASn                      (ctrl_io_sdram_RASn                          ), //o
    .io_sdram_WEn                       (ctrl_io_sdram_WEn                           ), //o
    .clk                                (clk                                         ), //i
    .reset                              (reset                                       )  //i
  );
  assign sdramWordAddr = _zz_sdramWordAddr[23:0];
  assign isBurstRead = ((io_bmb_cmd_valid && (! (io_bmb_cmd_payload_fragment_opcode == 1'b1))) && (4'b0011 < io_bmb_cmd_payload_fragment_length));
  always @(*) begin
    if(burstActive) begin
      io_bmb_cmd_ready = 1'b0;
    end else begin
      if(isBurstRead) begin
        io_bmb_cmd_ready = 1'b1;
      end else begin
        io_bmb_cmd_ready = (ctrl_io_bus_cmd_ready && sendingHigh);
      end
    end
  end

  always @(*) begin
    if(burstActive) begin
      ctrl_io_bus_cmd_payload_write = 1'b0;
    end else begin
      if(isBurstRead) begin
        ctrl_io_bus_cmd_payload_write = 1'b0;
      end else begin
        ctrl_io_bus_cmd_payload_write = (io_bmb_cmd_payload_fragment_opcode == 1'b1);
      end
    end
  end

  always @(*) begin
    if(burstActive) begin
      ctrl_io_bus_cmd_payload_data = 16'h0;
    end else begin
      if(isBurstRead) begin
        ctrl_io_bus_cmd_payload_data = 16'h0;
      end else begin
        if(when_BmbSdramCtrl32_l129) begin
          ctrl_io_bus_cmd_payload_data = io_bmb_cmd_payload_fragment_data[15 : 0];
        end else begin
          ctrl_io_bus_cmd_payload_data = io_bmb_cmd_payload_fragment_data[31 : 16];
        end
      end
    end
  end

  always @(*) begin
    if(burstActive) begin
      ctrl_io_bus_cmd_payload_mask = 2'b00;
    end else begin
      if(isBurstRead) begin
        ctrl_io_bus_cmd_payload_mask = 2'b00;
      end else begin
        if(when_BmbSdramCtrl32_l129) begin
          ctrl_io_bus_cmd_payload_mask = io_bmb_cmd_payload_fragment_mask[1 : 0];
        end else begin
          ctrl_io_bus_cmd_payload_mask = io_bmb_cmd_payload_fragment_mask[3 : 2];
        end
      end
    end
  end

  always @(*) begin
    if(burstActive) begin
      ctrl_io_bus_cmd_payload_context_context = burstContext;
    end else begin
      if(isBurstRead) begin
        ctrl_io_bus_cmd_payload_context_context = io_bmb_cmd_payload_fragment_context;
      end else begin
        ctrl_io_bus_cmd_payload_context_context = io_bmb_cmd_payload_fragment_context;
      end
    end
  end

  assign when_BmbSdramCtrl32_l86 = (burstCmdIdx < burstCmdTotal);
  always @(*) begin
    if(burstActive) begin
      if(when_BmbSdramCtrl32_l86) begin
        ctrl_io_bus_cmd_valid = 1'b1;
      end else begin
        ctrl_io_bus_cmd_valid = 1'b0;
      end
    end else begin
      if(isBurstRead) begin
        ctrl_io_bus_cmd_valid = 1'b0;
      end else begin
        ctrl_io_bus_cmd_valid = io_bmb_cmd_valid;
      end
    end
  end

  always @(*) begin
    if(burstActive) begin
      if(when_BmbSdramCtrl32_l86) begin
        ctrl_io_bus_cmd_payload_address = (burstBaseAddr + _zz_io_bus_cmd_payload_address);
      end else begin
        ctrl_io_bus_cmd_payload_address = 24'h0;
      end
    end else begin
      if(isBurstRead) begin
        ctrl_io_bus_cmd_payload_address = 24'h0;
      end else begin
        if(when_BmbSdramCtrl32_l129) begin
          ctrl_io_bus_cmd_payload_address = sdramWordAddr;
        end else begin
          ctrl_io_bus_cmd_payload_address = (sdramWordAddr + 24'h000001);
        end
      end
    end
  end

  always @(*) begin
    if(burstActive) begin
      if(when_BmbSdramCtrl32_l86) begin
        ctrl_io_bus_cmd_payload_context_isHigh = burstCmdIdx[0];
      end else begin
        ctrl_io_bus_cmd_payload_context_isHigh = 1'b0;
      end
    end else begin
      if(isBurstRead) begin
        ctrl_io_bus_cmd_payload_context_isHigh = 1'b0;
      end else begin
        if(when_BmbSdramCtrl32_l129) begin
          ctrl_io_bus_cmd_payload_context_isHigh = 1'b0;
        end else begin
          ctrl_io_bus_cmd_payload_context_isHigh = 1'b1;
        end
      end
    end
  end

  assign ctrl_io_bus_cmd_fire = (ctrl_io_bus_cmd_valid && ctrl_io_bus_cmd_ready);
  assign _zz_burstCmdTotal = ({1'b0,io_bmb_cmd_payload_fragment_length} + _zz__zz_burstCmdTotal);
  assign when_BmbSdramCtrl32_l129 = (! sendingHigh);
  assign ctrl_io_bus_rsp_fire = (ctrl_io_bus_rsp_valid && ctrl_io_bus_rsp_ready);
  assign when_BmbSdramCtrl32_l157 = (ctrl_io_bus_rsp_fire && (! ctrl_io_bus_rsp_payload_context_isHigh));
  assign io_bmb_rsp_valid = (ctrl_io_bus_rsp_valid && ctrl_io_bus_rsp_payload_context_isHigh);
  assign io_bmb_rsp_payload_fragment_opcode = 1'b0;
  assign io_bmb_rsp_payload_fragment_context = ctrl_io_bus_rsp_payload_context_context;
  assign io_bmb_rsp_payload_fragment_data = {ctrl_io_bus_rsp_payload_data,lowHalfData};
  always @(*) begin
    if(burstActive) begin
      io_bmb_rsp_payload_last = (burstWordTotal <= _zz_io_bmb_rsp_payload_last);
    end else begin
      io_bmb_rsp_payload_last = 1'b1;
    end
  end

  assign io_bmb_rsp_fire = (io_bmb_rsp_valid && io_bmb_rsp_ready);
  assign when_BmbSdramCtrl32_l173 = (burstWordTotal <= _zz_when_BmbSdramCtrl32_l173);
  assign ctrl_io_bus_rsp_ready = (ctrl_io_bus_rsp_payload_context_isHigh ? io_bmb_rsp_ready : 1'b1);
  assign io_sdram_ADDR = ctrl_io_sdram_ADDR;
  assign io_sdram_BA = ctrl_io_sdram_BA;
  assign io_sdram_DQ_write = ctrl_io_sdram_DQ_write;
  assign io_sdram_DQ_writeEnable = ctrl_io_sdram_DQ_writeEnable;
  assign io_sdram_DQM = ctrl_io_sdram_DQM;
  assign io_sdram_CASn = ctrl_io_sdram_CASn;
  assign io_sdram_CKE = ctrl_io_sdram_CKE;
  assign io_sdram_CSn = ctrl_io_sdram_CSn;
  assign io_sdram_RASn = ctrl_io_sdram_RASn;
  assign io_sdram_WEn = ctrl_io_sdram_WEn;
  always @(posedge clk or posedge reset) begin
    if(reset) begin
      sendingHigh <= 1'b0;
      burstActive <= 1'b0;
    end else begin
      if(!burstActive) begin
        if(isBurstRead) begin
          burstActive <= 1'b1;
        end else begin
          if(ctrl_io_bus_cmd_fire) begin
            sendingHigh <= (! sendingHigh);
          end
        end
      end
      if(burstActive) begin
        if(io_bmb_rsp_fire) begin
          if(when_BmbSdramCtrl32_l173) begin
            burstActive <= 1'b0;
          end
        end
      end
    end
  end

  always @(posedge clk) begin
    if(burstActive) begin
      if(when_BmbSdramCtrl32_l86) begin
        if(ctrl_io_bus_cmd_fire) begin
          burstCmdIdx <= (burstCmdIdx + 4'b0001);
        end
      end
    end else begin
      if(isBurstRead) begin
        burstBaseAddr <= sdramWordAddr;
        burstCmdIdx <= 4'b0000;
        burstCmdTotal <= (_zz_burstCmdTotal >>> 1'd1);
        burstWordTotal <= {1'd0, _zz_burstWordTotal};
        burstWordsSent <= 4'b0000;
        burstContext <= io_bmb_cmd_payload_fragment_context;
      end
    end
    if(when_BmbSdramCtrl32_l157) begin
      lowHalfData <= ctrl_io_bus_rsp_payload_data;
    end
    if(burstActive) begin
      if(io_bmb_rsp_fire) begin
        burstWordsSent <= (burstWordsSent + 4'b0001);
      end
    end
  end


endmodule

module JopCore (
  output wire          io_bmb_cmd_valid,
  input  wire          io_bmb_cmd_ready,
  output wire          io_bmb_cmd_payload_last,
  output wire [0:0]    io_bmb_cmd_payload_fragment_opcode,
  output wire [25:0]   io_bmb_cmd_payload_fragment_address,
  output wire [3:0]    io_bmb_cmd_payload_fragment_length,
  output wire [31:0]   io_bmb_cmd_payload_fragment_data,
  output wire [3:0]    io_bmb_cmd_payload_fragment_mask,
  output wire [3:0]    io_bmb_cmd_payload_fragment_context,
  input  wire          io_bmb_rsp_valid,
  output wire          io_bmb_rsp_ready,
  input  wire          io_bmb_rsp_payload_last,
  input  wire [0:0]    io_bmb_rsp_payload_fragment_opcode,
  input  wire [31:0]   io_bmb_rsp_payload_fragment_data,
  input  wire [3:0]    io_bmb_rsp_payload_fragment_context,
  output wire [7:0]    io_ioAddr,
  output wire          io_ioRd,
  output wire          io_ioWr,
  output wire [31:0]   io_ioWrData,
  input  wire [31:0]   io_ioRdData,
  output wire [10:0]   io_pc,
  output wire [11:0]   io_jpc,
  output wire [9:0]    io_instr,
  output wire          io_jfetch,
  output wire          io_jopdfetch,
  output wire [31:0]   io_aout,
  output wire [31:0]   io_bout,
  output wire          io_memBusy,
  input  wire          io_irq,
  input  wire          io_irqEna,
  input  wire          io_exc,
  output wire          io_debugBcRd,
  output wire [4:0]    io_debugMemState,
  output wire          io_debugMemHandleActive,
  output wire          io_debugAddrWr,
  output wire          io_debugRdc,
  output wire          io_debugRd,
  input  wire [7:0]    io_debugRamAddr,
  output wire [31:0]   io_debugRamData,
  input  wire          reset,
  input  wire          clk
);

  wire                pipeline_io_memCtrl_rd;
  wire                pipeline_io_memCtrl_rdc;
  wire                pipeline_io_memCtrl_rdf;
  wire                pipeline_io_memCtrl_wr;
  wire                pipeline_io_memCtrl_wrf;
  wire                pipeline_io_memCtrl_addrWr;
  wire                pipeline_io_memCtrl_bcRd;
  wire                pipeline_io_memCtrl_stidx;
  wire                pipeline_io_memCtrl_iaload;
  wire                pipeline_io_memCtrl_iastore;
  wire                pipeline_io_memCtrl_getfield;
  wire                pipeline_io_memCtrl_putfield;
  wire                pipeline_io_memCtrl_putref;
  wire                pipeline_io_memCtrl_getstatic;
  wire                pipeline_io_memCtrl_putstatic;
  wire                pipeline_io_memCtrl_copy;
  wire                pipeline_io_memCtrl_cinval;
  wire                pipeline_io_memCtrl_atmstart;
  wire                pipeline_io_memCtrl_atmend;
  wire       [15:0]   pipeline_io_memCtrl_bcopd;
  wire       [31:0]   pipeline_io_aout;
  wire       [31:0]   pipeline_io_bout;
  wire       [15:0]   pipeline_io_bcopd;
  wire       [10:0]   pipeline_io_pc;
  wire       [11:0]   pipeline_io_jpc;
  wire       [9:0]    pipeline_io_instr;
  wire                pipeline_io_jfetch;
  wire                pipeline_io_jopdfetch;
  wire                pipeline_io_memBusyOut;
  wire                pipeline_io_debugBcRd;
  wire                pipeline_io_debugAddrWr;
  wire                pipeline_io_debugRdc;
  wire                pipeline_io_debugRd;
  wire       [31:0]   pipeline_io_debugRamData;
  wire       [31:0]   memCtrl_io_memOut_rdData;
  wire                memCtrl_io_memOut_busy;
  wire       [11:0]   memCtrl_io_memOut_bcStart;
  wire       [8:0]    memCtrl_io_jbcWrite_addr;
  wire       [31:0]   memCtrl_io_jbcWrite_data;
  wire                memCtrl_io_jbcWrite_enable;
  wire                memCtrl_io_bmb_cmd_valid;
  wire                memCtrl_io_bmb_cmd_payload_last;
  wire       [0:0]    memCtrl_io_bmb_cmd_payload_fragment_opcode;
  wire       [25:0]   memCtrl_io_bmb_cmd_payload_fragment_address;
  wire       [3:0]    memCtrl_io_bmb_cmd_payload_fragment_length;
  wire       [31:0]   memCtrl_io_bmb_cmd_payload_fragment_data;
  wire       [3:0]    memCtrl_io_bmb_cmd_payload_fragment_mask;
  wire       [3:0]    memCtrl_io_bmb_cmd_payload_fragment_context;
  wire                memCtrl_io_bmb_rsp_ready;
  wire       [7:0]    memCtrl_io_ioAddr;
  wire                memCtrl_io_ioRd;
  wire                memCtrl_io_ioWr;
  wire       [31:0]   memCtrl_io_ioWrData;
  wire       [4:0]    memCtrl_io_debug_state;
  wire                memCtrl_io_debug_busy;
  wire                memCtrl_io_debug_handleActive;

  JopPipeline pipeline (
    .io_memRdData         (memCtrl_io_memOut_rdData[31:0] ), //i
    .io_memBcStart        (memCtrl_io_memOut_bcStart[11:0]), //i
    .io_memBusy           (memCtrl_io_memOut_busy         ), //i
    .io_jbcWrAddr         (memCtrl_io_jbcWrite_addr[8:0]  ), //i
    .io_jbcWrData         (memCtrl_io_jbcWrite_data[31:0] ), //i
    .io_jbcWrEn           (memCtrl_io_jbcWrite_enable     ), //i
    .io_memCtrl_rd        (pipeline_io_memCtrl_rd         ), //o
    .io_memCtrl_rdc       (pipeline_io_memCtrl_rdc        ), //o
    .io_memCtrl_rdf       (pipeline_io_memCtrl_rdf        ), //o
    .io_memCtrl_wr        (pipeline_io_memCtrl_wr         ), //o
    .io_memCtrl_wrf       (pipeline_io_memCtrl_wrf        ), //o
    .io_memCtrl_addrWr    (pipeline_io_memCtrl_addrWr     ), //o
    .io_memCtrl_bcRd      (pipeline_io_memCtrl_bcRd       ), //o
    .io_memCtrl_stidx     (pipeline_io_memCtrl_stidx      ), //o
    .io_memCtrl_iaload    (pipeline_io_memCtrl_iaload     ), //o
    .io_memCtrl_iastore   (pipeline_io_memCtrl_iastore    ), //o
    .io_memCtrl_getfield  (pipeline_io_memCtrl_getfield   ), //o
    .io_memCtrl_putfield  (pipeline_io_memCtrl_putfield   ), //o
    .io_memCtrl_putref    (pipeline_io_memCtrl_putref     ), //o
    .io_memCtrl_getstatic (pipeline_io_memCtrl_getstatic  ), //o
    .io_memCtrl_putstatic (pipeline_io_memCtrl_putstatic  ), //o
    .io_memCtrl_copy      (pipeline_io_memCtrl_copy       ), //o
    .io_memCtrl_cinval    (pipeline_io_memCtrl_cinval     ), //o
    .io_memCtrl_atmstart  (pipeline_io_memCtrl_atmstart   ), //o
    .io_memCtrl_atmend    (pipeline_io_memCtrl_atmend     ), //o
    .io_memCtrl_bcopd     (pipeline_io_memCtrl_bcopd[15:0]), //o
    .io_aout              (pipeline_io_aout[31:0]         ), //o
    .io_bout              (pipeline_io_bout[31:0]         ), //o
    .io_bcopd             (pipeline_io_bcopd[15:0]        ), //o
    .io_irq               (io_irq                         ), //i
    .io_irqEna            (io_irqEna                      ), //i
    .io_exc               (io_exc                         ), //i
    .io_pc                (pipeline_io_pc[10:0]           ), //o
    .io_jpc               (pipeline_io_jpc[11:0]          ), //o
    .io_instr             (pipeline_io_instr[9:0]         ), //o
    .io_jfetch            (pipeline_io_jfetch             ), //o
    .io_jopdfetch         (pipeline_io_jopdfetch          ), //o
    .io_memBusyOut        (pipeline_io_memBusyOut         ), //o
    .io_debugBcRd         (pipeline_io_debugBcRd          ), //o
    .io_debugAddrWr       (pipeline_io_debugAddrWr        ), //o
    .io_debugRdc          (pipeline_io_debugRdc           ), //o
    .io_debugRd           (pipeline_io_debugRd            ), //o
    .io_debugRamAddr      (io_debugRamAddr[7:0]           ), //i
    .io_debugRamData      (pipeline_io_debugRamData[31:0] ), //o
    .reset                (reset                          ), //i
    .clk                  (clk                            )  //i
  );
  BmbMemoryController memCtrl (
    .io_memIn_rd                         (pipeline_io_memCtrl_rd                           ), //i
    .io_memIn_rdc                        (pipeline_io_memCtrl_rdc                          ), //i
    .io_memIn_rdf                        (pipeline_io_memCtrl_rdf                          ), //i
    .io_memIn_wr                         (pipeline_io_memCtrl_wr                           ), //i
    .io_memIn_wrf                        (pipeline_io_memCtrl_wrf                          ), //i
    .io_memIn_addrWr                     (pipeline_io_memCtrl_addrWr                       ), //i
    .io_memIn_bcRd                       (pipeline_io_memCtrl_bcRd                         ), //i
    .io_memIn_stidx                      (pipeline_io_memCtrl_stidx                        ), //i
    .io_memIn_iaload                     (pipeline_io_memCtrl_iaload                       ), //i
    .io_memIn_iastore                    (pipeline_io_memCtrl_iastore                      ), //i
    .io_memIn_getfield                   (pipeline_io_memCtrl_getfield                     ), //i
    .io_memIn_putfield                   (pipeline_io_memCtrl_putfield                     ), //i
    .io_memIn_putref                     (pipeline_io_memCtrl_putref                       ), //i
    .io_memIn_getstatic                  (pipeline_io_memCtrl_getstatic                    ), //i
    .io_memIn_putstatic                  (pipeline_io_memCtrl_putstatic                    ), //i
    .io_memIn_copy                       (pipeline_io_memCtrl_copy                         ), //i
    .io_memIn_cinval                     (pipeline_io_memCtrl_cinval                       ), //i
    .io_memIn_atmstart                   (pipeline_io_memCtrl_atmstart                     ), //i
    .io_memIn_atmend                     (pipeline_io_memCtrl_atmend                       ), //i
    .io_memIn_bcopd                      (pipeline_io_memCtrl_bcopd[15:0]                  ), //i
    .io_memOut_rdData                    (memCtrl_io_memOut_rdData[31:0]                   ), //o
    .io_memOut_busy                      (memCtrl_io_memOut_busy                           ), //o
    .io_memOut_bcStart                   (memCtrl_io_memOut_bcStart[11:0]                  ), //o
    .io_aout                             (pipeline_io_aout[31:0]                           ), //i
    .io_bout                             (pipeline_io_bout[31:0]                           ), //i
    .io_bcopd                            (pipeline_io_bcopd[15:0]                          ), //i
    .io_jbcWrite_addr                    (memCtrl_io_jbcWrite_addr[8:0]                    ), //o
    .io_jbcWrite_data                    (memCtrl_io_jbcWrite_data[31:0]                   ), //o
    .io_jbcWrite_enable                  (memCtrl_io_jbcWrite_enable                       ), //o
    .io_bmb_cmd_valid                    (memCtrl_io_bmb_cmd_valid                         ), //o
    .io_bmb_cmd_ready                    (io_bmb_cmd_ready                                 ), //i
    .io_bmb_cmd_payload_last             (memCtrl_io_bmb_cmd_payload_last                  ), //o
    .io_bmb_cmd_payload_fragment_opcode  (memCtrl_io_bmb_cmd_payload_fragment_opcode       ), //o
    .io_bmb_cmd_payload_fragment_address (memCtrl_io_bmb_cmd_payload_fragment_address[25:0]), //o
    .io_bmb_cmd_payload_fragment_length  (memCtrl_io_bmb_cmd_payload_fragment_length[3:0]  ), //o
    .io_bmb_cmd_payload_fragment_data    (memCtrl_io_bmb_cmd_payload_fragment_data[31:0]   ), //o
    .io_bmb_cmd_payload_fragment_mask    (memCtrl_io_bmb_cmd_payload_fragment_mask[3:0]    ), //o
    .io_bmb_cmd_payload_fragment_context (memCtrl_io_bmb_cmd_payload_fragment_context[3:0] ), //o
    .io_bmb_rsp_valid                    (io_bmb_rsp_valid                                 ), //i
    .io_bmb_rsp_ready                    (memCtrl_io_bmb_rsp_ready                         ), //o
    .io_bmb_rsp_payload_last             (io_bmb_rsp_payload_last                          ), //i
    .io_bmb_rsp_payload_fragment_opcode  (io_bmb_rsp_payload_fragment_opcode               ), //i
    .io_bmb_rsp_payload_fragment_data    (io_bmb_rsp_payload_fragment_data[31:0]           ), //i
    .io_bmb_rsp_payload_fragment_context (io_bmb_rsp_payload_fragment_context[3:0]         ), //i
    .io_ioAddr                           (memCtrl_io_ioAddr[7:0]                           ), //o
    .io_ioRd                             (memCtrl_io_ioRd                                  ), //o
    .io_ioWr                             (memCtrl_io_ioWr                                  ), //o
    .io_ioWrData                         (memCtrl_io_ioWrData[31:0]                        ), //o
    .io_ioRdData                         (io_ioRdData[31:0]                                ), //i
    .io_debug_state                      (memCtrl_io_debug_state[4:0]                      ), //o
    .io_debug_busy                       (memCtrl_io_debug_busy                            ), //o
    .io_debug_handleActive               (memCtrl_io_debug_handleActive                    ), //o
    .clk                                 (clk                                              ), //i
    .reset                               (reset                                            )  //i
  );
  assign io_bmb_cmd_valid = memCtrl_io_bmb_cmd_valid;
  assign io_bmb_cmd_payload_last = memCtrl_io_bmb_cmd_payload_last;
  assign io_bmb_cmd_payload_fragment_opcode = memCtrl_io_bmb_cmd_payload_fragment_opcode;
  assign io_bmb_cmd_payload_fragment_address = memCtrl_io_bmb_cmd_payload_fragment_address;
  assign io_bmb_cmd_payload_fragment_length = memCtrl_io_bmb_cmd_payload_fragment_length;
  assign io_bmb_cmd_payload_fragment_data = memCtrl_io_bmb_cmd_payload_fragment_data;
  assign io_bmb_cmd_payload_fragment_mask = memCtrl_io_bmb_cmd_payload_fragment_mask;
  assign io_bmb_cmd_payload_fragment_context = memCtrl_io_bmb_cmd_payload_fragment_context;
  assign io_bmb_rsp_ready = memCtrl_io_bmb_rsp_ready;
  assign io_debugRamData = pipeline_io_debugRamData;
  assign io_ioAddr = memCtrl_io_ioAddr;
  assign io_ioRd = memCtrl_io_ioRd;
  assign io_ioWr = memCtrl_io_ioWr;
  assign io_ioWrData = memCtrl_io_ioWrData;
  assign io_pc = pipeline_io_pc;
  assign io_jpc = pipeline_io_jpc;
  assign io_instr = pipeline_io_instr;
  assign io_jfetch = pipeline_io_jfetch;
  assign io_jopdfetch = pipeline_io_jopdfetch;
  assign io_aout = pipeline_io_aout;
  assign io_bout = pipeline_io_bout;
  assign io_memBusy = memCtrl_io_memOut_busy;
  assign io_debugBcRd = pipeline_io_debugBcRd;
  assign io_debugMemState = memCtrl_io_debug_state;
  assign io_debugMemHandleActive = memCtrl_io_debug_handleActive;
  assign io_debugAddrWr = pipeline_io_debugAddrWr;
  assign io_debugRdc = pipeline_io_debugRdc;
  assign io_debugRd = pipeline_io_debugRd;

endmodule

module SdramCtrl (
  input  wire          io_bus_cmd_valid,
  output reg           io_bus_cmd_ready,
  input  wire [23:0]   io_bus_cmd_payload_address,
  input  wire          io_bus_cmd_payload_write,
  input  wire [15:0]   io_bus_cmd_payload_data,
  input  wire [1:0]    io_bus_cmd_payload_mask,
  input  wire [3:0]    io_bus_cmd_payload_context_context,
  input  wire          io_bus_cmd_payload_context_isHigh,
  output wire          io_bus_rsp_valid,
  input  wire          io_bus_rsp_ready,
  output wire [15:0]   io_bus_rsp_payload_data,
  output wire [3:0]    io_bus_rsp_payload_context_context,
  output wire          io_bus_rsp_payload_context_isHigh,
  output wire [12:0]   io_sdram_ADDR,
  output wire [1:0]    io_sdram_BA,
  input  wire [15:0]   io_sdram_DQ_read,
  output wire [15:0]   io_sdram_DQ_write,
  output wire [15:0]   io_sdram_DQ_writeEnable,
  output wire [1:0]    io_sdram_DQM,
  output wire          io_sdram_CASn,
  output wire          io_sdram_CKE,
  output wire          io_sdram_CSn,
  output wire          io_sdram_RASn,
  output wire          io_sdram_WEn,
  input  wire          clk,
  input  wire          reset
);
  localparam SdramCtrlBackendTask_MODE = 3'd0;
  localparam SdramCtrlBackendTask_PRECHARGE_ALL = 3'd1;
  localparam SdramCtrlBackendTask_PRECHARGE_SINGLE = 3'd2;
  localparam SdramCtrlBackendTask_REFRESH = 3'd3;
  localparam SdramCtrlBackendTask_ACTIVE = 3'd4;
  localparam SdramCtrlBackendTask_READ = 3'd5;
  localparam SdramCtrlBackendTask_WRITE = 3'd6;
  localparam SdramCtrlFrontendState_BOOT_PRECHARGE = 2'd0;
  localparam SdramCtrlFrontendState_BOOT_REFRESH = 2'd1;
  localparam SdramCtrlFrontendState_BOOT_MODE = 2'd2;
  localparam SdramCtrlFrontendState_RUN = 2'd3;

  wire                chip_backupIn_fifo_io_push_ready;
  wire                chip_backupIn_fifo_io_pop_valid;
  wire       [15:0]   chip_backupIn_fifo_io_pop_payload_data;
  wire       [3:0]    chip_backupIn_fifo_io_pop_payload_context_context;
  wire                chip_backupIn_fifo_io_pop_payload_context_isHigh;
  wire       [1:0]    chip_backupIn_fifo_io_occupancy;
  wire       [1:0]    chip_backupIn_fifo_io_availability;
  wire       [9:0]    _zz_refresh_counter_valueNext;
  wire       [0:0]    _zz_refresh_counter_valueNext_1;
  wire       [2:0]    _zz_frontend_bootRefreshCounter_valueNext;
  wire       [0:0]    _zz_frontend_bootRefreshCounter_valueNext_1;
  reg                 _zz__zz_when_SdramCtrl_l224;
  reg        [12:0]   _zz_when_SdramCtrl_l224_1;
  reg                 _zz_bubbleInserter_insertBubble;
  reg                 _zz_bubbleInserter_insertBubble_1;
  wire                refresh_counter_willIncrement;
  wire                refresh_counter_willClear;
  reg        [9:0]    refresh_counter_valueNext;
  reg        [9:0]    refresh_counter_value;
  wire                refresh_counter_willOverflowIfInc;
  wire                refresh_counter_willOverflow;
  reg                 refresh_pending;
  reg        [14:0]   powerup_counter;
  reg                 powerup_done;
  wire                when_SdramCtrl_l146;
  wire       [14:0]   _zz_when_SdramCtrl_l148;
  wire                when_SdramCtrl_l148;
  reg                 frontend_banks_0_active;
  reg        [12:0]   frontend_banks_0_row;
  reg                 frontend_banks_1_active;
  reg        [12:0]   frontend_banks_1_row;
  reg                 frontend_banks_2_active;
  reg        [12:0]   frontend_banks_2_row;
  reg                 frontend_banks_3_active;
  reg        [12:0]   frontend_banks_3_row;
  wire       [8:0]    frontend_address_column;
  wire       [1:0]    frontend_address_bank;
  wire       [12:0]   frontend_address_row;
  wire       [23:0]   _zz_frontend_address_column;
  reg                 frontend_rsp_valid;
  reg                 frontend_rsp_ready;
  reg        [2:0]    frontend_rsp_payload_task;
  wire       [1:0]    frontend_rsp_payload_bank;
  reg        [12:0]   frontend_rsp_payload_rowColumn;
  wire       [15:0]   frontend_rsp_payload_data;
  wire       [1:0]    frontend_rsp_payload_mask;
  wire       [3:0]    frontend_rsp_payload_context_context;
  wire                frontend_rsp_payload_context_isHigh;
  reg        [1:0]    frontend_state;
  reg                 frontend_bootRefreshCounter_willIncrement;
  wire                frontend_bootRefreshCounter_willClear;
  reg        [2:0]    frontend_bootRefreshCounter_valueNext;
  reg        [2:0]    frontend_bootRefreshCounter_value;
  wire                frontend_bootRefreshCounter_willOverflowIfInc;
  wire                frontend_bootRefreshCounter_willOverflow;
  wire                when_SdramCtrl_l210;
  wire                _zz_when_SdramCtrl_l224;
  wire       [3:0]    _zz_1;
  wire                _zz_2;
  wire                _zz_3;
  wire                _zz_4;
  wire                _zz_5;
  wire                when_SdramCtrl_l224;
  wire       [2:0]    _zz_frontend_rsp_payload_task;
  wire                when_SdramCtrl_l229;
  wire                bubbleInserter_cmd_valid;
  wire                bubbleInserter_cmd_ready;
  wire       [2:0]    bubbleInserter_cmd_payload_task;
  wire       [1:0]    bubbleInserter_cmd_payload_bank;
  wire       [12:0]   bubbleInserter_cmd_payload_rowColumn;
  wire       [15:0]   bubbleInserter_cmd_payload_data;
  wire       [1:0]    bubbleInserter_cmd_payload_mask;
  wire       [3:0]    bubbleInserter_cmd_payload_context_context;
  wire                bubbleInserter_cmd_payload_context_isHigh;
  reg                 frontend_rsp_rValid;
  reg        [2:0]    frontend_rsp_rData_task;
  reg        [1:0]    frontend_rsp_rData_bank;
  reg        [12:0]   frontend_rsp_rData_rowColumn;
  reg        [15:0]   frontend_rsp_rData_data;
  reg        [1:0]    frontend_rsp_rData_mask;
  reg        [3:0]    frontend_rsp_rData_context_context;
  reg                 frontend_rsp_rData_context_isHigh;
  wire                when_Stream_l448;
  wire                bubbleInserter_rsp_valid;
  wire                bubbleInserter_rsp_ready;
  wire       [2:0]    bubbleInserter_rsp_payload_task;
  wire       [1:0]    bubbleInserter_rsp_payload_bank;
  wire       [12:0]   bubbleInserter_rsp_payload_rowColumn;
  wire       [15:0]   bubbleInserter_rsp_payload_data;
  wire       [1:0]    bubbleInserter_rsp_payload_mask;
  wire       [3:0]    bubbleInserter_rsp_payload_context_context;
  wire                bubbleInserter_rsp_payload_context_isHigh;
  reg                 bubbleInserter_insertBubble;
  wire                _zz_bubbleInserter_cmd_ready;
  wire                bubbleInserter_cmd_haltWhen_valid;
  wire                bubbleInserter_cmd_haltWhen_ready;
  wire       [2:0]    bubbleInserter_cmd_haltWhen_payload_task;
  wire       [1:0]    bubbleInserter_cmd_haltWhen_payload_bank;
  wire       [12:0]   bubbleInserter_cmd_haltWhen_payload_rowColumn;
  wire       [15:0]   bubbleInserter_cmd_haltWhen_payload_data;
  wire       [1:0]    bubbleInserter_cmd_haltWhen_payload_mask;
  wire       [3:0]    bubbleInserter_cmd_haltWhen_payload_context_context;
  wire                bubbleInserter_cmd_haltWhen_payload_context_isHigh;
  reg        [0:0]    bubbleInserter_timings_read_counter;
  wire                bubbleInserter_timings_read_busy;
  wire                when_SdramCtrl_l256;
  reg        [2:0]    bubbleInserter_timings_write_counter;
  wire                bubbleInserter_timings_write_busy;
  wire                when_SdramCtrl_l256_1;
  reg        [2:0]    bubbleInserter_timings_banks_0_precharge_counter;
  wire                bubbleInserter_timings_banks_0_precharge_busy;
  wire                when_SdramCtrl_l256_2;
  reg        [2:0]    bubbleInserter_timings_banks_0_active_counter;
  wire                bubbleInserter_timings_banks_0_active_busy;
  wire                when_SdramCtrl_l256_3;
  reg        [2:0]    bubbleInserter_timings_banks_1_precharge_counter;
  wire                bubbleInserter_timings_banks_1_precharge_busy;
  wire                when_SdramCtrl_l256_4;
  reg        [2:0]    bubbleInserter_timings_banks_1_active_counter;
  wire                bubbleInserter_timings_banks_1_active_busy;
  wire                when_SdramCtrl_l256_5;
  reg        [2:0]    bubbleInserter_timings_banks_2_precharge_counter;
  wire                bubbleInserter_timings_banks_2_precharge_busy;
  wire                when_SdramCtrl_l256_6;
  reg        [2:0]    bubbleInserter_timings_banks_2_active_counter;
  wire                bubbleInserter_timings_banks_2_active_busy;
  wire                when_SdramCtrl_l256_7;
  reg        [2:0]    bubbleInserter_timings_banks_3_precharge_counter;
  wire                bubbleInserter_timings_banks_3_precharge_busy;
  wire                when_SdramCtrl_l256_8;
  reg        [2:0]    bubbleInserter_timings_banks_3_active_counter;
  wire                bubbleInserter_timings_banks_3_active_busy;
  wire                when_SdramCtrl_l256_9;
  wire                when_SdramCtrl_l265;
  wire                when_SdramCtrl_l265_1;
  wire                when_SdramCtrl_l265_2;
  wire                when_SdramCtrl_l265_3;
  wire                when_SdramCtrl_l265_4;
  wire                when_Utils_l1120;
  wire                when_SdramCtrl_l265_5;
  wire                when_Utils_l1120_1;
  wire                when_SdramCtrl_l265_6;
  wire                when_Utils_l1120_2;
  wire                when_SdramCtrl_l265_7;
  wire                when_Utils_l1120_3;
  wire                when_SdramCtrl_l265_8;
  wire                when_SdramCtrl_l265_9;
  wire                when_SdramCtrl_l265_10;
  wire                when_SdramCtrl_l265_11;
  wire                when_SdramCtrl_l265_12;
  wire                when_SdramCtrl_l265_13;
  wire                when_Utils_l1120_4;
  wire                when_SdramCtrl_l265_14;
  wire                when_Utils_l1120_5;
  wire                when_SdramCtrl_l265_15;
  wire                when_Utils_l1120_6;
  wire                when_SdramCtrl_l265_16;
  wire                when_Utils_l1120_7;
  wire                when_SdramCtrl_l265_17;
  wire                when_Utils_l1120_8;
  wire                when_SdramCtrl_l265_18;
  wire                when_Utils_l1120_9;
  wire                when_SdramCtrl_l265_19;
  wire                when_Utils_l1120_10;
  wire                when_SdramCtrl_l265_20;
  wire                when_Utils_l1120_11;
  wire                when_SdramCtrl_l265_21;
  wire                when_SdramCtrl_l265_22;
  wire                when_Utils_l1120_12;
  wire                when_SdramCtrl_l265_23;
  wire                when_Utils_l1120_13;
  wire                when_SdramCtrl_l265_24;
  wire                when_Utils_l1120_14;
  wire                when_SdramCtrl_l265_25;
  wire                when_Utils_l1120_15;
  wire                when_SdramCtrl_l265_26;
  wire                chip_cmd_valid;
  wire                chip_cmd_ready;
  wire       [2:0]    chip_cmd_payload_task;
  wire       [1:0]    chip_cmd_payload_bank;
  wire       [12:0]   chip_cmd_payload_rowColumn;
  wire       [15:0]   chip_cmd_payload_data;
  wire       [1:0]    chip_cmd_payload_mask;
  wire       [3:0]    chip_cmd_payload_context_context;
  wire                chip_cmd_payload_context_isHigh;
  reg        [12:0]   chip_sdram_ADDR;
  reg        [1:0]    chip_sdram_BA;
  reg        [15:0]   chip_sdram_DQ_read;
  reg        [15:0]   chip_sdram_DQ_write;
  reg        [15:0]   chip_sdram_DQ_writeEnable;
  reg        [1:0]    chip_sdram_DQM;
  reg                 chip_sdram_CASn;
  reg                 chip_sdram_CKE;
  reg                 chip_sdram_CSn;
  reg                 chip_sdram_RASn;
  reg                 chip_sdram_WEn;
  wire                chip_remoteCke;
  wire                chip_readHistory_0;
  wire                chip_readHistory_1;
  wire                chip_readHistory_2;
  wire                chip_readHistory_3;
  wire                chip_readHistory_4;
  wire                chip_readHistory_5;
  wire                _zz_chip_readHistory_0;
  reg                 _zz_chip_readHistory_1;
  reg                 _zz_chip_readHistory_2;
  reg                 _zz_chip_readHistory_3;
  reg                 _zz_chip_readHistory_4;
  reg                 _zz_chip_readHistory_5;
  reg        [3:0]    chip_cmd_payload_context_delay_1_context;
  reg                 chip_cmd_payload_context_delay_1_isHigh;
  reg        [3:0]    chip_cmd_payload_context_delay_2_context;
  reg                 chip_cmd_payload_context_delay_2_isHigh;
  reg        [3:0]    chip_cmd_payload_context_delay_3_context;
  reg                 chip_cmd_payload_context_delay_3_isHigh;
  reg        [3:0]    chip_cmd_payload_context_delay_4_context;
  reg                 chip_cmd_payload_context_delay_4_isHigh;
  reg        [3:0]    chip_contextDelayed_context;
  reg                 chip_contextDelayed_isHigh;
  wire                chip_sdramCkeNext;
  reg                 chip_sdramCkeInternal;
  reg                 chip_sdramCkeInternal_regNext;
  wire                _zz_chip_sdram_DQM;
  wire                chip_backupIn_valid;
  wire                chip_backupIn_ready;
  wire       [15:0]   chip_backupIn_payload_data;
  wire       [3:0]    chip_backupIn_payload_context_context;
  wire                chip_backupIn_payload_context_isHigh;
  `ifndef SYNTHESIS
  reg [127:0] frontend_rsp_payload_task_string;
  reg [111:0] frontend_state_string;
  reg [127:0] _zz_frontend_rsp_payload_task_string;
  reg [127:0] bubbleInserter_cmd_payload_task_string;
  reg [127:0] frontend_rsp_rData_task_string;
  reg [127:0] bubbleInserter_rsp_payload_task_string;
  reg [127:0] bubbleInserter_cmd_haltWhen_payload_task_string;
  reg [127:0] chip_cmd_payload_task_string;
  `endif


  assign _zz_refresh_counter_valueNext_1 = refresh_counter_willIncrement;
  assign _zz_refresh_counter_valueNext = {9'd0, _zz_refresh_counter_valueNext_1};
  assign _zz_frontend_bootRefreshCounter_valueNext_1 = frontend_bootRefreshCounter_willIncrement;
  assign _zz_frontend_bootRefreshCounter_valueNext = {2'd0, _zz_frontend_bootRefreshCounter_valueNext_1};
  StreamFifoLowLatency chip_backupIn_fifo (
    .io_push_valid                   (chip_backupIn_valid                                   ), //i
    .io_push_ready                   (chip_backupIn_fifo_io_push_ready                      ), //o
    .io_push_payload_data            (chip_backupIn_payload_data[15:0]                      ), //i
    .io_push_payload_context_context (chip_backupIn_payload_context_context[3:0]            ), //i
    .io_push_payload_context_isHigh  (chip_backupIn_payload_context_isHigh                  ), //i
    .io_pop_valid                    (chip_backupIn_fifo_io_pop_valid                       ), //o
    .io_pop_ready                    (io_bus_rsp_ready                                      ), //i
    .io_pop_payload_data             (chip_backupIn_fifo_io_pop_payload_data[15:0]          ), //o
    .io_pop_payload_context_context  (chip_backupIn_fifo_io_pop_payload_context_context[3:0]), //o
    .io_pop_payload_context_isHigh   (chip_backupIn_fifo_io_pop_payload_context_isHigh      ), //o
    .io_flush                        (1'b0                                                  ), //i
    .io_occupancy                    (chip_backupIn_fifo_io_occupancy[1:0]                  ), //o
    .io_availability                 (chip_backupIn_fifo_io_availability[1:0]               ), //o
    .clk                             (clk                                                   ), //i
    .reset                           (reset                                                 )  //i
  );
  always @(*) begin
    case(frontend_address_bank)
      2'b00 : begin
        _zz__zz_when_SdramCtrl_l224 = frontend_banks_0_active;
        _zz_when_SdramCtrl_l224_1 = frontend_banks_0_row;
      end
      2'b01 : begin
        _zz__zz_when_SdramCtrl_l224 = frontend_banks_1_active;
        _zz_when_SdramCtrl_l224_1 = frontend_banks_1_row;
      end
      2'b10 : begin
        _zz__zz_when_SdramCtrl_l224 = frontend_banks_2_active;
        _zz_when_SdramCtrl_l224_1 = frontend_banks_2_row;
      end
      default : begin
        _zz__zz_when_SdramCtrl_l224 = frontend_banks_3_active;
        _zz_when_SdramCtrl_l224_1 = frontend_banks_3_row;
      end
    endcase
  end

  always @(*) begin
    case(bubbleInserter_cmd_payload_bank)
      2'b00 : begin
        _zz_bubbleInserter_insertBubble = bubbleInserter_timings_banks_0_precharge_busy;
        _zz_bubbleInserter_insertBubble_1 = bubbleInserter_timings_banks_0_active_busy;
      end
      2'b01 : begin
        _zz_bubbleInserter_insertBubble = bubbleInserter_timings_banks_1_precharge_busy;
        _zz_bubbleInserter_insertBubble_1 = bubbleInserter_timings_banks_1_active_busy;
      end
      2'b10 : begin
        _zz_bubbleInserter_insertBubble = bubbleInserter_timings_banks_2_precharge_busy;
        _zz_bubbleInserter_insertBubble_1 = bubbleInserter_timings_banks_2_active_busy;
      end
      default : begin
        _zz_bubbleInserter_insertBubble = bubbleInserter_timings_banks_3_precharge_busy;
        _zz_bubbleInserter_insertBubble_1 = bubbleInserter_timings_banks_3_active_busy;
      end
    endcase
  end

  `ifndef SYNTHESIS
  always @(*) begin
    case(frontend_rsp_payload_task)
      SdramCtrlBackendTask_MODE : frontend_rsp_payload_task_string = "MODE            ";
      SdramCtrlBackendTask_PRECHARGE_ALL : frontend_rsp_payload_task_string = "PRECHARGE_ALL   ";
      SdramCtrlBackendTask_PRECHARGE_SINGLE : frontend_rsp_payload_task_string = "PRECHARGE_SINGLE";
      SdramCtrlBackendTask_REFRESH : frontend_rsp_payload_task_string = "REFRESH         ";
      SdramCtrlBackendTask_ACTIVE : frontend_rsp_payload_task_string = "ACTIVE          ";
      SdramCtrlBackendTask_READ : frontend_rsp_payload_task_string = "READ            ";
      SdramCtrlBackendTask_WRITE : frontend_rsp_payload_task_string = "WRITE           ";
      default : frontend_rsp_payload_task_string = "????????????????";
    endcase
  end
  always @(*) begin
    case(frontend_state)
      SdramCtrlFrontendState_BOOT_PRECHARGE : frontend_state_string = "BOOT_PRECHARGE";
      SdramCtrlFrontendState_BOOT_REFRESH : frontend_state_string = "BOOT_REFRESH  ";
      SdramCtrlFrontendState_BOOT_MODE : frontend_state_string = "BOOT_MODE     ";
      SdramCtrlFrontendState_RUN : frontend_state_string = "RUN           ";
      default : frontend_state_string = "??????????????";
    endcase
  end
  always @(*) begin
    case(_zz_frontend_rsp_payload_task)
      SdramCtrlBackendTask_MODE : _zz_frontend_rsp_payload_task_string = "MODE            ";
      SdramCtrlBackendTask_PRECHARGE_ALL : _zz_frontend_rsp_payload_task_string = "PRECHARGE_ALL   ";
      SdramCtrlBackendTask_PRECHARGE_SINGLE : _zz_frontend_rsp_payload_task_string = "PRECHARGE_SINGLE";
      SdramCtrlBackendTask_REFRESH : _zz_frontend_rsp_payload_task_string = "REFRESH         ";
      SdramCtrlBackendTask_ACTIVE : _zz_frontend_rsp_payload_task_string = "ACTIVE          ";
      SdramCtrlBackendTask_READ : _zz_frontend_rsp_payload_task_string = "READ            ";
      SdramCtrlBackendTask_WRITE : _zz_frontend_rsp_payload_task_string = "WRITE           ";
      default : _zz_frontend_rsp_payload_task_string = "????????????????";
    endcase
  end
  always @(*) begin
    case(bubbleInserter_cmd_payload_task)
      SdramCtrlBackendTask_MODE : bubbleInserter_cmd_payload_task_string = "MODE            ";
      SdramCtrlBackendTask_PRECHARGE_ALL : bubbleInserter_cmd_payload_task_string = "PRECHARGE_ALL   ";
      SdramCtrlBackendTask_PRECHARGE_SINGLE : bubbleInserter_cmd_payload_task_string = "PRECHARGE_SINGLE";
      SdramCtrlBackendTask_REFRESH : bubbleInserter_cmd_payload_task_string = "REFRESH         ";
      SdramCtrlBackendTask_ACTIVE : bubbleInserter_cmd_payload_task_string = "ACTIVE          ";
      SdramCtrlBackendTask_READ : bubbleInserter_cmd_payload_task_string = "READ            ";
      SdramCtrlBackendTask_WRITE : bubbleInserter_cmd_payload_task_string = "WRITE           ";
      default : bubbleInserter_cmd_payload_task_string = "????????????????";
    endcase
  end
  always @(*) begin
    case(frontend_rsp_rData_task)
      SdramCtrlBackendTask_MODE : frontend_rsp_rData_task_string = "MODE            ";
      SdramCtrlBackendTask_PRECHARGE_ALL : frontend_rsp_rData_task_string = "PRECHARGE_ALL   ";
      SdramCtrlBackendTask_PRECHARGE_SINGLE : frontend_rsp_rData_task_string = "PRECHARGE_SINGLE";
      SdramCtrlBackendTask_REFRESH : frontend_rsp_rData_task_string = "REFRESH         ";
      SdramCtrlBackendTask_ACTIVE : frontend_rsp_rData_task_string = "ACTIVE          ";
      SdramCtrlBackendTask_READ : frontend_rsp_rData_task_string = "READ            ";
      SdramCtrlBackendTask_WRITE : frontend_rsp_rData_task_string = "WRITE           ";
      default : frontend_rsp_rData_task_string = "????????????????";
    endcase
  end
  always @(*) begin
    case(bubbleInserter_rsp_payload_task)
      SdramCtrlBackendTask_MODE : bubbleInserter_rsp_payload_task_string = "MODE            ";
      SdramCtrlBackendTask_PRECHARGE_ALL : bubbleInserter_rsp_payload_task_string = "PRECHARGE_ALL   ";
      SdramCtrlBackendTask_PRECHARGE_SINGLE : bubbleInserter_rsp_payload_task_string = "PRECHARGE_SINGLE";
      SdramCtrlBackendTask_REFRESH : bubbleInserter_rsp_payload_task_string = "REFRESH         ";
      SdramCtrlBackendTask_ACTIVE : bubbleInserter_rsp_payload_task_string = "ACTIVE          ";
      SdramCtrlBackendTask_READ : bubbleInserter_rsp_payload_task_string = "READ            ";
      SdramCtrlBackendTask_WRITE : bubbleInserter_rsp_payload_task_string = "WRITE           ";
      default : bubbleInserter_rsp_payload_task_string = "????????????????";
    endcase
  end
  always @(*) begin
    case(bubbleInserter_cmd_haltWhen_payload_task)
      SdramCtrlBackendTask_MODE : bubbleInserter_cmd_haltWhen_payload_task_string = "MODE            ";
      SdramCtrlBackendTask_PRECHARGE_ALL : bubbleInserter_cmd_haltWhen_payload_task_string = "PRECHARGE_ALL   ";
      SdramCtrlBackendTask_PRECHARGE_SINGLE : bubbleInserter_cmd_haltWhen_payload_task_string = "PRECHARGE_SINGLE";
      SdramCtrlBackendTask_REFRESH : bubbleInserter_cmd_haltWhen_payload_task_string = "REFRESH         ";
      SdramCtrlBackendTask_ACTIVE : bubbleInserter_cmd_haltWhen_payload_task_string = "ACTIVE          ";
      SdramCtrlBackendTask_READ : bubbleInserter_cmd_haltWhen_payload_task_string = "READ            ";
      SdramCtrlBackendTask_WRITE : bubbleInserter_cmd_haltWhen_payload_task_string = "WRITE           ";
      default : bubbleInserter_cmd_haltWhen_payload_task_string = "????????????????";
    endcase
  end
  always @(*) begin
    case(chip_cmd_payload_task)
      SdramCtrlBackendTask_MODE : chip_cmd_payload_task_string = "MODE            ";
      SdramCtrlBackendTask_PRECHARGE_ALL : chip_cmd_payload_task_string = "PRECHARGE_ALL   ";
      SdramCtrlBackendTask_PRECHARGE_SINGLE : chip_cmd_payload_task_string = "PRECHARGE_SINGLE";
      SdramCtrlBackendTask_REFRESH : chip_cmd_payload_task_string = "REFRESH         ";
      SdramCtrlBackendTask_ACTIVE : chip_cmd_payload_task_string = "ACTIVE          ";
      SdramCtrlBackendTask_READ : chip_cmd_payload_task_string = "READ            ";
      SdramCtrlBackendTask_WRITE : chip_cmd_payload_task_string = "WRITE           ";
      default : chip_cmd_payload_task_string = "????????????????";
    endcase
  end
  `endif

  assign refresh_counter_willClear = 1'b0;
  assign refresh_counter_willOverflowIfInc = (refresh_counter_value == 10'h30d);
  assign refresh_counter_willOverflow = (refresh_counter_willOverflowIfInc && refresh_counter_willIncrement);
  always @(*) begin
    if(refresh_counter_willOverflow) begin
      refresh_counter_valueNext = 10'h0;
    end else begin
      refresh_counter_valueNext = (refresh_counter_value + _zz_refresh_counter_valueNext);
    end
    if(refresh_counter_willClear) begin
      refresh_counter_valueNext = 10'h0;
    end
  end

  assign refresh_counter_willIncrement = 1'b1;
  assign when_SdramCtrl_l146 = (! powerup_done);
  assign _zz_when_SdramCtrl_l148[14 : 0] = 15'h7fff;
  assign when_SdramCtrl_l148 = (powerup_counter == _zz_when_SdramCtrl_l148);
  assign _zz_frontend_address_column = io_bus_cmd_payload_address;
  assign frontend_address_column = _zz_frontend_address_column[8 : 0];
  assign frontend_address_bank = _zz_frontend_address_column[10 : 9];
  assign frontend_address_row = _zz_frontend_address_column[23 : 11];
  always @(*) begin
    frontend_rsp_valid = 1'b0;
    case(frontend_state)
      SdramCtrlFrontendState_BOOT_PRECHARGE : begin
        if(powerup_done) begin
          frontend_rsp_valid = 1'b1;
        end
      end
      SdramCtrlFrontendState_BOOT_REFRESH : begin
        frontend_rsp_valid = 1'b1;
      end
      SdramCtrlFrontendState_BOOT_MODE : begin
        frontend_rsp_valid = 1'b1;
      end
      default : begin
        if(refresh_pending) begin
          frontend_rsp_valid = 1'b1;
        end else begin
          if(io_bus_cmd_valid) begin
            frontend_rsp_valid = 1'b1;
          end
        end
      end
    endcase
  end

  always @(*) begin
    frontend_rsp_payload_task = SdramCtrlBackendTask_REFRESH;
    case(frontend_state)
      SdramCtrlFrontendState_BOOT_PRECHARGE : begin
        frontend_rsp_payload_task = SdramCtrlBackendTask_PRECHARGE_ALL;
      end
      SdramCtrlFrontendState_BOOT_REFRESH : begin
        frontend_rsp_payload_task = SdramCtrlBackendTask_REFRESH;
      end
      SdramCtrlFrontendState_BOOT_MODE : begin
        frontend_rsp_payload_task = SdramCtrlBackendTask_MODE;
      end
      default : begin
        if(refresh_pending) begin
          if(when_SdramCtrl_l210) begin
            frontend_rsp_payload_task = SdramCtrlBackendTask_PRECHARGE_ALL;
          end else begin
            frontend_rsp_payload_task = SdramCtrlBackendTask_REFRESH;
          end
        end else begin
          if(io_bus_cmd_valid) begin
            if(when_SdramCtrl_l224) begin
              frontend_rsp_payload_task = SdramCtrlBackendTask_PRECHARGE_SINGLE;
            end else begin
              if(when_SdramCtrl_l229) begin
                frontend_rsp_payload_task = SdramCtrlBackendTask_ACTIVE;
              end else begin
                frontend_rsp_payload_task = _zz_frontend_rsp_payload_task;
              end
            end
          end
        end
      end
    endcase
  end

  assign frontend_rsp_payload_bank = frontend_address_bank;
  always @(*) begin
    frontend_rsp_payload_rowColumn = frontend_address_row;
    case(frontend_state)
      SdramCtrlFrontendState_BOOT_PRECHARGE : begin
      end
      SdramCtrlFrontendState_BOOT_REFRESH : begin
      end
      SdramCtrlFrontendState_BOOT_MODE : begin
      end
      default : begin
        if(!refresh_pending) begin
          if(io_bus_cmd_valid) begin
            if(!when_SdramCtrl_l224) begin
              if(!when_SdramCtrl_l229) begin
                frontend_rsp_payload_rowColumn = {4'd0, frontend_address_column};
              end
            end
          end
        end
      end
    endcase
  end

  assign frontend_rsp_payload_data = io_bus_cmd_payload_data;
  assign frontend_rsp_payload_mask = io_bus_cmd_payload_mask;
  assign frontend_rsp_payload_context_context = io_bus_cmd_payload_context_context;
  assign frontend_rsp_payload_context_isHigh = io_bus_cmd_payload_context_isHigh;
  always @(*) begin
    io_bus_cmd_ready = 1'b0;
    case(frontend_state)
      SdramCtrlFrontendState_BOOT_PRECHARGE : begin
      end
      SdramCtrlFrontendState_BOOT_REFRESH : begin
      end
      SdramCtrlFrontendState_BOOT_MODE : begin
      end
      default : begin
        if(!refresh_pending) begin
          if(io_bus_cmd_valid) begin
            if(!when_SdramCtrl_l224) begin
              if(!when_SdramCtrl_l229) begin
                io_bus_cmd_ready = frontend_rsp_ready;
              end
            end
          end
        end
      end
    endcase
  end

  always @(*) begin
    frontend_bootRefreshCounter_willIncrement = 1'b0;
    case(frontend_state)
      SdramCtrlFrontendState_BOOT_PRECHARGE : begin
      end
      SdramCtrlFrontendState_BOOT_REFRESH : begin
        if(frontend_rsp_ready) begin
          frontend_bootRefreshCounter_willIncrement = 1'b1;
        end
      end
      SdramCtrlFrontendState_BOOT_MODE : begin
      end
      default : begin
      end
    endcase
  end

  assign frontend_bootRefreshCounter_willClear = 1'b0;
  assign frontend_bootRefreshCounter_willOverflowIfInc = (frontend_bootRefreshCounter_value == 3'b111);
  assign frontend_bootRefreshCounter_willOverflow = (frontend_bootRefreshCounter_willOverflowIfInc && frontend_bootRefreshCounter_willIncrement);
  always @(*) begin
    frontend_bootRefreshCounter_valueNext = (frontend_bootRefreshCounter_value + _zz_frontend_bootRefreshCounter_valueNext);
    if(frontend_bootRefreshCounter_willClear) begin
      frontend_bootRefreshCounter_valueNext = 3'b000;
    end
  end

  assign when_SdramCtrl_l210 = (((frontend_banks_0_active || frontend_banks_1_active) || frontend_banks_2_active) || frontend_banks_3_active);
  assign _zz_when_SdramCtrl_l224 = _zz__zz_when_SdramCtrl_l224;
  assign _zz_1 = ({3'd0,1'b1} <<< frontend_address_bank);
  assign _zz_2 = _zz_1[0];
  assign _zz_3 = _zz_1[1];
  assign _zz_4 = _zz_1[2];
  assign _zz_5 = _zz_1[3];
  assign when_SdramCtrl_l224 = (_zz_when_SdramCtrl_l224 && (_zz_when_SdramCtrl_l224_1 != frontend_address_row));
  assign _zz_frontend_rsp_payload_task = (io_bus_cmd_payload_write ? SdramCtrlBackendTask_WRITE : SdramCtrlBackendTask_READ);
  assign when_SdramCtrl_l229 = (! _zz_when_SdramCtrl_l224);
  always @(*) begin
    frontend_rsp_ready = bubbleInserter_cmd_ready;
    if(when_Stream_l448) begin
      frontend_rsp_ready = 1'b1;
    end
  end

  assign when_Stream_l448 = (! bubbleInserter_cmd_valid);
  assign bubbleInserter_cmd_valid = frontend_rsp_rValid;
  assign bubbleInserter_cmd_payload_task = frontend_rsp_rData_task;
  assign bubbleInserter_cmd_payload_bank = frontend_rsp_rData_bank;
  assign bubbleInserter_cmd_payload_rowColumn = frontend_rsp_rData_rowColumn;
  assign bubbleInserter_cmd_payload_data = frontend_rsp_rData_data;
  assign bubbleInserter_cmd_payload_mask = frontend_rsp_rData_mask;
  assign bubbleInserter_cmd_payload_context_context = frontend_rsp_rData_context_context;
  assign bubbleInserter_cmd_payload_context_isHigh = frontend_rsp_rData_context_isHigh;
  always @(*) begin
    bubbleInserter_insertBubble = 1'b0;
    if(bubbleInserter_cmd_valid) begin
      case(bubbleInserter_cmd_payload_task)
        SdramCtrlBackendTask_MODE : begin
          bubbleInserter_insertBubble = bubbleInserter_timings_banks_0_active_busy;
        end
        SdramCtrlBackendTask_PRECHARGE_ALL : begin
          bubbleInserter_insertBubble = (|{bubbleInserter_timings_banks_3_precharge_busy,{bubbleInserter_timings_banks_2_precharge_busy,{bubbleInserter_timings_banks_1_precharge_busy,bubbleInserter_timings_banks_0_precharge_busy}}});
        end
        SdramCtrlBackendTask_PRECHARGE_SINGLE : begin
          bubbleInserter_insertBubble = _zz_bubbleInserter_insertBubble;
        end
        SdramCtrlBackendTask_REFRESH : begin
          bubbleInserter_insertBubble = (|{bubbleInserter_timings_banks_3_active_busy,{bubbleInserter_timings_banks_2_active_busy,{bubbleInserter_timings_banks_1_active_busy,bubbleInserter_timings_banks_0_active_busy}}});
        end
        SdramCtrlBackendTask_ACTIVE : begin
          bubbleInserter_insertBubble = _zz_bubbleInserter_insertBubble_1;
        end
        SdramCtrlBackendTask_READ : begin
          bubbleInserter_insertBubble = bubbleInserter_timings_read_busy;
        end
        default : begin
          bubbleInserter_insertBubble = bubbleInserter_timings_write_busy;
        end
      endcase
    end
  end

  assign _zz_bubbleInserter_cmd_ready = (! bubbleInserter_insertBubble);
  assign bubbleInserter_cmd_haltWhen_valid = (bubbleInserter_cmd_valid && _zz_bubbleInserter_cmd_ready);
  assign bubbleInserter_cmd_ready = (bubbleInserter_cmd_haltWhen_ready && _zz_bubbleInserter_cmd_ready);
  assign bubbleInserter_cmd_haltWhen_payload_task = bubbleInserter_cmd_payload_task;
  assign bubbleInserter_cmd_haltWhen_payload_bank = bubbleInserter_cmd_payload_bank;
  assign bubbleInserter_cmd_haltWhen_payload_rowColumn = bubbleInserter_cmd_payload_rowColumn;
  assign bubbleInserter_cmd_haltWhen_payload_data = bubbleInserter_cmd_payload_data;
  assign bubbleInserter_cmd_haltWhen_payload_mask = bubbleInserter_cmd_payload_mask;
  assign bubbleInserter_cmd_haltWhen_payload_context_context = bubbleInserter_cmd_payload_context_context;
  assign bubbleInserter_cmd_haltWhen_payload_context_isHigh = bubbleInserter_cmd_payload_context_isHigh;
  assign bubbleInserter_rsp_valid = bubbleInserter_cmd_haltWhen_valid;
  assign bubbleInserter_cmd_haltWhen_ready = bubbleInserter_rsp_ready;
  assign bubbleInserter_rsp_payload_task = bubbleInserter_cmd_haltWhen_payload_task;
  assign bubbleInserter_rsp_payload_bank = bubbleInserter_cmd_haltWhen_payload_bank;
  assign bubbleInserter_rsp_payload_rowColumn = bubbleInserter_cmd_haltWhen_payload_rowColumn;
  assign bubbleInserter_rsp_payload_data = bubbleInserter_cmd_haltWhen_payload_data;
  assign bubbleInserter_rsp_payload_mask = bubbleInserter_cmd_haltWhen_payload_mask;
  assign bubbleInserter_rsp_payload_context_context = bubbleInserter_cmd_haltWhen_payload_context_context;
  assign bubbleInserter_rsp_payload_context_isHigh = bubbleInserter_cmd_haltWhen_payload_context_isHigh;
  assign bubbleInserter_timings_read_busy = (bubbleInserter_timings_read_counter != 1'b0);
  assign when_SdramCtrl_l256 = (bubbleInserter_timings_read_busy && bubbleInserter_rsp_ready);
  assign bubbleInserter_timings_write_busy = (bubbleInserter_timings_write_counter != 3'b000);
  assign when_SdramCtrl_l256_1 = (bubbleInserter_timings_write_busy && bubbleInserter_rsp_ready);
  assign bubbleInserter_timings_banks_0_precharge_busy = (bubbleInserter_timings_banks_0_precharge_counter != 3'b000);
  assign when_SdramCtrl_l256_2 = (bubbleInserter_timings_banks_0_precharge_busy && bubbleInserter_rsp_ready);
  assign bubbleInserter_timings_banks_0_active_busy = (bubbleInserter_timings_banks_0_active_counter != 3'b000);
  assign when_SdramCtrl_l256_3 = (bubbleInserter_timings_banks_0_active_busy && bubbleInserter_rsp_ready);
  assign bubbleInserter_timings_banks_1_precharge_busy = (bubbleInserter_timings_banks_1_precharge_counter != 3'b000);
  assign when_SdramCtrl_l256_4 = (bubbleInserter_timings_banks_1_precharge_busy && bubbleInserter_rsp_ready);
  assign bubbleInserter_timings_banks_1_active_busy = (bubbleInserter_timings_banks_1_active_counter != 3'b000);
  assign when_SdramCtrl_l256_5 = (bubbleInserter_timings_banks_1_active_busy && bubbleInserter_rsp_ready);
  assign bubbleInserter_timings_banks_2_precharge_busy = (bubbleInserter_timings_banks_2_precharge_counter != 3'b000);
  assign when_SdramCtrl_l256_6 = (bubbleInserter_timings_banks_2_precharge_busy && bubbleInserter_rsp_ready);
  assign bubbleInserter_timings_banks_2_active_busy = (bubbleInserter_timings_banks_2_active_counter != 3'b000);
  assign when_SdramCtrl_l256_7 = (bubbleInserter_timings_banks_2_active_busy && bubbleInserter_rsp_ready);
  assign bubbleInserter_timings_banks_3_precharge_busy = (bubbleInserter_timings_banks_3_precharge_counter != 3'b000);
  assign when_SdramCtrl_l256_8 = (bubbleInserter_timings_banks_3_precharge_busy && bubbleInserter_rsp_ready);
  assign bubbleInserter_timings_banks_3_active_busy = (bubbleInserter_timings_banks_3_active_counter != 3'b000);
  assign when_SdramCtrl_l256_9 = (bubbleInserter_timings_banks_3_active_busy && bubbleInserter_rsp_ready);
  assign when_SdramCtrl_l265 = (bubbleInserter_timings_banks_0_active_counter <= 3'b001);
  assign when_SdramCtrl_l265_1 = (bubbleInserter_timings_banks_1_active_counter <= 3'b001);
  assign when_SdramCtrl_l265_2 = (bubbleInserter_timings_banks_2_active_counter <= 3'b001);
  assign when_SdramCtrl_l265_3 = (bubbleInserter_timings_banks_3_active_counter <= 3'b001);
  assign when_SdramCtrl_l265_4 = (bubbleInserter_timings_banks_0_active_counter <= 3'b001);
  assign when_Utils_l1120 = (bubbleInserter_cmd_payload_bank == 2'b00);
  assign when_SdramCtrl_l265_5 = (bubbleInserter_timings_banks_0_active_counter <= 3'b001);
  assign when_Utils_l1120_1 = (bubbleInserter_cmd_payload_bank == 2'b01);
  assign when_SdramCtrl_l265_6 = (bubbleInserter_timings_banks_1_active_counter <= 3'b001);
  assign when_Utils_l1120_2 = (bubbleInserter_cmd_payload_bank == 2'b10);
  assign when_SdramCtrl_l265_7 = (bubbleInserter_timings_banks_2_active_counter <= 3'b001);
  assign when_Utils_l1120_3 = (bubbleInserter_cmd_payload_bank == 2'b11);
  assign when_SdramCtrl_l265_8 = (bubbleInserter_timings_banks_3_active_counter <= 3'b001);
  assign when_SdramCtrl_l265_9 = (bubbleInserter_timings_banks_0_active_counter <= 3'b101);
  assign when_SdramCtrl_l265_10 = (bubbleInserter_timings_banks_1_active_counter <= 3'b101);
  assign when_SdramCtrl_l265_11 = (bubbleInserter_timings_banks_2_active_counter <= 3'b101);
  assign when_SdramCtrl_l265_12 = (bubbleInserter_timings_banks_3_active_counter <= 3'b101);
  assign when_SdramCtrl_l265_13 = (bubbleInserter_timings_write_counter <= 3'b001);
  assign when_Utils_l1120_4 = (bubbleInserter_cmd_payload_bank == 2'b00);
  assign when_SdramCtrl_l265_14 = (bubbleInserter_timings_banks_0_precharge_counter <= 3'b100);
  assign when_Utils_l1120_5 = (bubbleInserter_cmd_payload_bank == 2'b01);
  assign when_SdramCtrl_l265_15 = (bubbleInserter_timings_banks_1_precharge_counter <= 3'b100);
  assign when_Utils_l1120_6 = (bubbleInserter_cmd_payload_bank == 2'b10);
  assign when_SdramCtrl_l265_16 = (bubbleInserter_timings_banks_2_precharge_counter <= 3'b100);
  assign when_Utils_l1120_7 = (bubbleInserter_cmd_payload_bank == 2'b11);
  assign when_SdramCtrl_l265_17 = (bubbleInserter_timings_banks_3_precharge_counter <= 3'b100);
  assign when_Utils_l1120_8 = (bubbleInserter_cmd_payload_bank == 2'b00);
  assign when_SdramCtrl_l265_18 = (bubbleInserter_timings_banks_0_active_counter <= 3'b101);
  assign when_Utils_l1120_9 = (bubbleInserter_cmd_payload_bank == 2'b01);
  assign when_SdramCtrl_l265_19 = (bubbleInserter_timings_banks_1_active_counter <= 3'b101);
  assign when_Utils_l1120_10 = (bubbleInserter_cmd_payload_bank == 2'b10);
  assign when_SdramCtrl_l265_20 = (bubbleInserter_timings_banks_2_active_counter <= 3'b101);
  assign when_Utils_l1120_11 = (bubbleInserter_cmd_payload_bank == 2'b11);
  assign when_SdramCtrl_l265_21 = (bubbleInserter_timings_banks_3_active_counter <= 3'b101);
  assign when_SdramCtrl_l265_22 = (bubbleInserter_timings_write_counter <= 3'b100);
  assign when_Utils_l1120_12 = (bubbleInserter_cmd_payload_bank == 2'b00);
  assign when_SdramCtrl_l265_23 = (bubbleInserter_timings_banks_0_precharge_counter <= 3'b001);
  assign when_Utils_l1120_13 = (bubbleInserter_cmd_payload_bank == 2'b01);
  assign when_SdramCtrl_l265_24 = (bubbleInserter_timings_banks_1_precharge_counter <= 3'b001);
  assign when_Utils_l1120_14 = (bubbleInserter_cmd_payload_bank == 2'b10);
  assign when_SdramCtrl_l265_25 = (bubbleInserter_timings_banks_2_precharge_counter <= 3'b001);
  assign when_Utils_l1120_15 = (bubbleInserter_cmd_payload_bank == 2'b11);
  assign when_SdramCtrl_l265_26 = (bubbleInserter_timings_banks_3_precharge_counter <= 3'b001);
  assign chip_cmd_valid = bubbleInserter_rsp_valid;
  assign bubbleInserter_rsp_ready = chip_cmd_ready;
  assign chip_cmd_payload_task = bubbleInserter_rsp_payload_task;
  assign chip_cmd_payload_bank = bubbleInserter_rsp_payload_bank;
  assign chip_cmd_payload_rowColumn = bubbleInserter_rsp_payload_rowColumn;
  assign chip_cmd_payload_data = bubbleInserter_rsp_payload_data;
  assign chip_cmd_payload_mask = bubbleInserter_rsp_payload_mask;
  assign chip_cmd_payload_context_context = bubbleInserter_rsp_payload_context_context;
  assign chip_cmd_payload_context_isHigh = bubbleInserter_rsp_payload_context_isHigh;
  assign io_sdram_ADDR = chip_sdram_ADDR;
  assign io_sdram_BA = chip_sdram_BA;
  assign io_sdram_DQ_write = chip_sdram_DQ_write;
  assign io_sdram_DQ_writeEnable = chip_sdram_DQ_writeEnable;
  assign io_sdram_DQM = chip_sdram_DQM;
  assign io_sdram_CASn = chip_sdram_CASn;
  assign io_sdram_CKE = chip_sdram_CKE;
  assign io_sdram_CSn = chip_sdram_CSn;
  assign io_sdram_RASn = chip_sdram_RASn;
  assign io_sdram_WEn = chip_sdram_WEn;
  assign _zz_chip_readHistory_0 = (chip_cmd_valid && ((chip_cmd_payload_task == SdramCtrlBackendTask_READ) || (chip_cmd_payload_task == SdramCtrlBackendTask_WRITE)));
  assign chip_readHistory_0 = _zz_chip_readHistory_0;
  assign chip_readHistory_1 = _zz_chip_readHistory_1;
  assign chip_readHistory_2 = _zz_chip_readHistory_2;
  assign chip_readHistory_3 = _zz_chip_readHistory_3;
  assign chip_readHistory_4 = _zz_chip_readHistory_4;
  assign chip_readHistory_5 = _zz_chip_readHistory_5;
  assign chip_sdramCkeNext = (! ((|{chip_readHistory_5,{chip_readHistory_4,{chip_readHistory_3,{chip_readHistory_2,{chip_readHistory_1,chip_readHistory_0}}}}}) && (! io_bus_rsp_ready)));
  assign chip_remoteCke = chip_sdramCkeInternal_regNext;
  assign _zz_chip_sdram_DQM = (! chip_readHistory_1);
  assign chip_backupIn_valid = (chip_readHistory_5 && chip_remoteCke);
  assign chip_backupIn_payload_data = chip_sdram_DQ_read;
  assign chip_backupIn_payload_context_context = chip_contextDelayed_context;
  assign chip_backupIn_payload_context_isHigh = chip_contextDelayed_isHigh;
  assign chip_backupIn_ready = chip_backupIn_fifo_io_push_ready;
  assign io_bus_rsp_valid = chip_backupIn_fifo_io_pop_valid;
  assign io_bus_rsp_payload_data = chip_backupIn_fifo_io_pop_payload_data;
  assign io_bus_rsp_payload_context_context = chip_backupIn_fifo_io_pop_payload_context_context;
  assign io_bus_rsp_payload_context_isHigh = chip_backupIn_fifo_io_pop_payload_context_isHigh;
  assign chip_cmd_ready = chip_remoteCke;
  always @(posedge clk or posedge reset) begin
    if(reset) begin
      refresh_counter_value <= 10'h0;
      refresh_pending <= 1'b0;
      powerup_counter <= 15'h0;
      powerup_done <= 1'b0;
      frontend_banks_0_active <= 1'b0;
      frontend_banks_1_active <= 1'b0;
      frontend_banks_2_active <= 1'b0;
      frontend_banks_3_active <= 1'b0;
      frontend_state <= SdramCtrlFrontendState_BOOT_PRECHARGE;
      frontend_bootRefreshCounter_value <= 3'b000;
      frontend_rsp_rValid <= 1'b0;
      bubbleInserter_timings_read_counter <= 1'b0;
      bubbleInserter_timings_write_counter <= 3'b000;
      bubbleInserter_timings_banks_0_precharge_counter <= 3'b000;
      bubbleInserter_timings_banks_0_active_counter <= 3'b000;
      bubbleInserter_timings_banks_1_precharge_counter <= 3'b000;
      bubbleInserter_timings_banks_1_active_counter <= 3'b000;
      bubbleInserter_timings_banks_2_precharge_counter <= 3'b000;
      bubbleInserter_timings_banks_2_active_counter <= 3'b000;
      bubbleInserter_timings_banks_3_precharge_counter <= 3'b000;
      bubbleInserter_timings_banks_3_active_counter <= 3'b000;
      _zz_chip_readHistory_1 <= 1'b0;
      _zz_chip_readHistory_2 <= 1'b0;
      _zz_chip_readHistory_3 <= 1'b0;
      _zz_chip_readHistory_4 <= 1'b0;
      _zz_chip_readHistory_5 <= 1'b0;
      chip_sdramCkeInternal <= 1'b1;
      chip_sdramCkeInternal_regNext <= 1'b1;
    end else begin
      refresh_counter_value <= refresh_counter_valueNext;
      if(refresh_counter_willOverflow) begin
        refresh_pending <= 1'b1;
      end
      if(when_SdramCtrl_l146) begin
        powerup_counter <= (powerup_counter + 15'h0001);
        if(when_SdramCtrl_l148) begin
          powerup_done <= 1'b1;
        end
      end
      frontend_bootRefreshCounter_value <= frontend_bootRefreshCounter_valueNext;
      case(frontend_state)
        SdramCtrlFrontendState_BOOT_PRECHARGE : begin
          if(powerup_done) begin
            if(frontend_rsp_ready) begin
              frontend_state <= SdramCtrlFrontendState_BOOT_REFRESH;
            end
          end
        end
        SdramCtrlFrontendState_BOOT_REFRESH : begin
          if(frontend_rsp_ready) begin
            if(frontend_bootRefreshCounter_willOverflowIfInc) begin
              frontend_state <= SdramCtrlFrontendState_BOOT_MODE;
            end
          end
        end
        SdramCtrlFrontendState_BOOT_MODE : begin
          if(frontend_rsp_ready) begin
            frontend_state <= SdramCtrlFrontendState_RUN;
          end
        end
        default : begin
          if(refresh_pending) begin
            if(when_SdramCtrl_l210) begin
              if(frontend_rsp_ready) begin
                frontend_banks_0_active <= 1'b0;
                frontend_banks_1_active <= 1'b0;
                frontend_banks_2_active <= 1'b0;
                frontend_banks_3_active <= 1'b0;
              end
            end else begin
              if(frontend_rsp_ready) begin
                refresh_pending <= 1'b0;
              end
            end
          end else begin
            if(io_bus_cmd_valid) begin
              if(when_SdramCtrl_l224) begin
                if(frontend_rsp_ready) begin
                  if(_zz_2) begin
                    frontend_banks_0_active <= 1'b0;
                  end
                  if(_zz_3) begin
                    frontend_banks_1_active <= 1'b0;
                  end
                  if(_zz_4) begin
                    frontend_banks_2_active <= 1'b0;
                  end
                  if(_zz_5) begin
                    frontend_banks_3_active <= 1'b0;
                  end
                end
              end else begin
                if(when_SdramCtrl_l229) begin
                  if(frontend_rsp_ready) begin
                    if(_zz_2) begin
                      frontend_banks_0_active <= 1'b1;
                    end
                    if(_zz_3) begin
                      frontend_banks_1_active <= 1'b1;
                    end
                    if(_zz_4) begin
                      frontend_banks_2_active <= 1'b1;
                    end
                    if(_zz_5) begin
                      frontend_banks_3_active <= 1'b1;
                    end
                  end
                end
              end
            end
          end
        end
      endcase
      if(frontend_rsp_ready) begin
        frontend_rsp_rValid <= frontend_rsp_valid;
      end
      if(when_SdramCtrl_l256) begin
        bubbleInserter_timings_read_counter <= (bubbleInserter_timings_read_counter - 1'b1);
      end
      if(when_SdramCtrl_l256_1) begin
        bubbleInserter_timings_write_counter <= (bubbleInserter_timings_write_counter - 3'b001);
      end
      if(when_SdramCtrl_l256_2) begin
        bubbleInserter_timings_banks_0_precharge_counter <= (bubbleInserter_timings_banks_0_precharge_counter - 3'b001);
      end
      if(when_SdramCtrl_l256_3) begin
        bubbleInserter_timings_banks_0_active_counter <= (bubbleInserter_timings_banks_0_active_counter - 3'b001);
      end
      if(when_SdramCtrl_l256_4) begin
        bubbleInserter_timings_banks_1_precharge_counter <= (bubbleInserter_timings_banks_1_precharge_counter - 3'b001);
      end
      if(when_SdramCtrl_l256_5) begin
        bubbleInserter_timings_banks_1_active_counter <= (bubbleInserter_timings_banks_1_active_counter - 3'b001);
      end
      if(when_SdramCtrl_l256_6) begin
        bubbleInserter_timings_banks_2_precharge_counter <= (bubbleInserter_timings_banks_2_precharge_counter - 3'b001);
      end
      if(when_SdramCtrl_l256_7) begin
        bubbleInserter_timings_banks_2_active_counter <= (bubbleInserter_timings_banks_2_active_counter - 3'b001);
      end
      if(when_SdramCtrl_l256_8) begin
        bubbleInserter_timings_banks_3_precharge_counter <= (bubbleInserter_timings_banks_3_precharge_counter - 3'b001);
      end
      if(when_SdramCtrl_l256_9) begin
        bubbleInserter_timings_banks_3_active_counter <= (bubbleInserter_timings_banks_3_active_counter - 3'b001);
      end
      if(bubbleInserter_cmd_valid) begin
        case(bubbleInserter_cmd_payload_task)
          SdramCtrlBackendTask_MODE : begin
            if(bubbleInserter_cmd_ready) begin
              if(when_SdramCtrl_l265) begin
                bubbleInserter_timings_banks_0_active_counter <= 3'b001;
              end
              if(when_SdramCtrl_l265_1) begin
                bubbleInserter_timings_banks_1_active_counter <= 3'b001;
              end
              if(when_SdramCtrl_l265_2) begin
                bubbleInserter_timings_banks_2_active_counter <= 3'b001;
              end
              if(when_SdramCtrl_l265_3) begin
                bubbleInserter_timings_banks_3_active_counter <= 3'b001;
              end
            end
          end
          SdramCtrlBackendTask_PRECHARGE_ALL : begin
            if(bubbleInserter_cmd_ready) begin
              if(when_SdramCtrl_l265_4) begin
                bubbleInserter_timings_banks_0_active_counter <= 3'b001;
              end
            end
          end
          SdramCtrlBackendTask_PRECHARGE_SINGLE : begin
            if(bubbleInserter_cmd_ready) begin
              if(when_Utils_l1120) begin
                if(when_SdramCtrl_l265_5) begin
                  bubbleInserter_timings_banks_0_active_counter <= 3'b001;
                end
              end
              if(when_Utils_l1120_1) begin
                if(when_SdramCtrl_l265_6) begin
                  bubbleInserter_timings_banks_1_active_counter <= 3'b001;
                end
              end
              if(when_Utils_l1120_2) begin
                if(when_SdramCtrl_l265_7) begin
                  bubbleInserter_timings_banks_2_active_counter <= 3'b001;
                end
              end
              if(when_Utils_l1120_3) begin
                if(when_SdramCtrl_l265_8) begin
                  bubbleInserter_timings_banks_3_active_counter <= 3'b001;
                end
              end
            end
          end
          SdramCtrlBackendTask_REFRESH : begin
            if(bubbleInserter_cmd_ready) begin
              if(when_SdramCtrl_l265_9) begin
                bubbleInserter_timings_banks_0_active_counter <= 3'b101;
              end
              if(when_SdramCtrl_l265_10) begin
                bubbleInserter_timings_banks_1_active_counter <= 3'b101;
              end
              if(when_SdramCtrl_l265_11) begin
                bubbleInserter_timings_banks_2_active_counter <= 3'b101;
              end
              if(when_SdramCtrl_l265_12) begin
                bubbleInserter_timings_banks_3_active_counter <= 3'b101;
              end
            end
          end
          SdramCtrlBackendTask_ACTIVE : begin
            if(bubbleInserter_cmd_ready) begin
              if(when_SdramCtrl_l265_13) begin
                bubbleInserter_timings_write_counter <= 3'b001;
              end
              bubbleInserter_timings_read_counter <= 1'b1;
              if(when_Utils_l1120_4) begin
                if(when_SdramCtrl_l265_14) begin
                  bubbleInserter_timings_banks_0_precharge_counter <= 3'b100;
                end
              end
              if(when_Utils_l1120_5) begin
                if(when_SdramCtrl_l265_15) begin
                  bubbleInserter_timings_banks_1_precharge_counter <= 3'b100;
                end
              end
              if(when_Utils_l1120_6) begin
                if(when_SdramCtrl_l265_16) begin
                  bubbleInserter_timings_banks_2_precharge_counter <= 3'b100;
                end
              end
              if(when_Utils_l1120_7) begin
                if(when_SdramCtrl_l265_17) begin
                  bubbleInserter_timings_banks_3_precharge_counter <= 3'b100;
                end
              end
              if(when_Utils_l1120_8) begin
                if(when_SdramCtrl_l265_18) begin
                  bubbleInserter_timings_banks_0_active_counter <= 3'b101;
                end
              end
              if(when_Utils_l1120_9) begin
                if(when_SdramCtrl_l265_19) begin
                  bubbleInserter_timings_banks_1_active_counter <= 3'b101;
                end
              end
              if(when_Utils_l1120_10) begin
                if(when_SdramCtrl_l265_20) begin
                  bubbleInserter_timings_banks_2_active_counter <= 3'b101;
                end
              end
              if(when_Utils_l1120_11) begin
                if(when_SdramCtrl_l265_21) begin
                  bubbleInserter_timings_banks_3_active_counter <= 3'b101;
                end
              end
            end
          end
          SdramCtrlBackendTask_READ : begin
            if(bubbleInserter_cmd_ready) begin
              if(when_SdramCtrl_l265_22) begin
                bubbleInserter_timings_write_counter <= 3'b100;
              end
            end
          end
          default : begin
            if(bubbleInserter_cmd_ready) begin
              if(when_Utils_l1120_12) begin
                if(when_SdramCtrl_l265_23) begin
                  bubbleInserter_timings_banks_0_precharge_counter <= 3'b001;
                end
              end
              if(when_Utils_l1120_13) begin
                if(when_SdramCtrl_l265_24) begin
                  bubbleInserter_timings_banks_1_precharge_counter <= 3'b001;
                end
              end
              if(when_Utils_l1120_14) begin
                if(when_SdramCtrl_l265_25) begin
                  bubbleInserter_timings_banks_2_precharge_counter <= 3'b001;
                end
              end
              if(when_Utils_l1120_15) begin
                if(when_SdramCtrl_l265_26) begin
                  bubbleInserter_timings_banks_3_precharge_counter <= 3'b001;
                end
              end
            end
          end
        endcase
      end
      if(chip_remoteCke) begin
        _zz_chip_readHistory_1 <= _zz_chip_readHistory_0;
      end
      if(chip_remoteCke) begin
        _zz_chip_readHistory_2 <= _zz_chip_readHistory_1;
      end
      if(chip_remoteCke) begin
        _zz_chip_readHistory_3 <= _zz_chip_readHistory_2;
      end
      if(chip_remoteCke) begin
        _zz_chip_readHistory_4 <= _zz_chip_readHistory_3;
      end
      if(chip_remoteCke) begin
        _zz_chip_readHistory_5 <= _zz_chip_readHistory_4;
      end
      chip_sdramCkeInternal <= chip_sdramCkeNext;
      chip_sdramCkeInternal_regNext <= chip_sdramCkeInternal;
    end
  end

  always @(posedge clk) begin
    case(frontend_state)
      SdramCtrlFrontendState_BOOT_PRECHARGE : begin
      end
      SdramCtrlFrontendState_BOOT_REFRESH : begin
      end
      SdramCtrlFrontendState_BOOT_MODE : begin
      end
      default : begin
        if(!refresh_pending) begin
          if(io_bus_cmd_valid) begin
            if(!when_SdramCtrl_l224) begin
              if(when_SdramCtrl_l229) begin
                if(_zz_2) begin
                  frontend_banks_0_row <= frontend_address_row;
                end
                if(_zz_3) begin
                  frontend_banks_1_row <= frontend_address_row;
                end
                if(_zz_4) begin
                  frontend_banks_2_row <= frontend_address_row;
                end
                if(_zz_5) begin
                  frontend_banks_3_row <= frontend_address_row;
                end
              end
            end
          end
        end
      end
    endcase
    if(frontend_rsp_ready) begin
      frontend_rsp_rData_task <= frontend_rsp_payload_task;
      frontend_rsp_rData_bank <= frontend_rsp_payload_bank;
      frontend_rsp_rData_rowColumn <= frontend_rsp_payload_rowColumn;
      frontend_rsp_rData_data <= frontend_rsp_payload_data;
      frontend_rsp_rData_mask <= frontend_rsp_payload_mask;
      frontend_rsp_rData_context_context <= frontend_rsp_payload_context_context;
      frontend_rsp_rData_context_isHigh <= frontend_rsp_payload_context_isHigh;
    end
    if(chip_remoteCke) begin
      chip_cmd_payload_context_delay_1_context <= chip_cmd_payload_context_context;
      chip_cmd_payload_context_delay_1_isHigh <= chip_cmd_payload_context_isHigh;
    end
    if(chip_remoteCke) begin
      chip_cmd_payload_context_delay_2_context <= chip_cmd_payload_context_delay_1_context;
      chip_cmd_payload_context_delay_2_isHigh <= chip_cmd_payload_context_delay_1_isHigh;
    end
    if(chip_remoteCke) begin
      chip_cmd_payload_context_delay_3_context <= chip_cmd_payload_context_delay_2_context;
      chip_cmd_payload_context_delay_3_isHigh <= chip_cmd_payload_context_delay_2_isHigh;
    end
    if(chip_remoteCke) begin
      chip_cmd_payload_context_delay_4_context <= chip_cmd_payload_context_delay_3_context;
      chip_cmd_payload_context_delay_4_isHigh <= chip_cmd_payload_context_delay_3_isHigh;
    end
    if(chip_remoteCke) begin
      chip_contextDelayed_context <= chip_cmd_payload_context_delay_4_context;
      chip_contextDelayed_isHigh <= chip_cmd_payload_context_delay_4_isHigh;
    end
    chip_sdram_CKE <= chip_sdramCkeNext;
    if(chip_remoteCke) begin
      chip_sdram_DQ_read <= io_sdram_DQ_read;
      chip_sdram_CSn <= 1'b0;
      chip_sdram_RASn <= 1'b1;
      chip_sdram_CASn <= 1'b1;
      chip_sdram_WEn <= 1'b1;
      chip_sdram_DQ_write <= chip_cmd_payload_data;
      chip_sdram_DQ_writeEnable <= 16'h0;
      chip_sdram_DQM[0] <= _zz_chip_sdram_DQM;
      chip_sdram_DQM[1] <= _zz_chip_sdram_DQM;
      if(chip_cmd_valid) begin
        case(chip_cmd_payload_task)
          SdramCtrlBackendTask_PRECHARGE_ALL : begin
            chip_sdram_ADDR[10] <= 1'b1;
            chip_sdram_CSn <= 1'b0;
            chip_sdram_RASn <= 1'b0;
            chip_sdram_CASn <= 1'b1;
            chip_sdram_WEn <= 1'b0;
          end
          SdramCtrlBackendTask_REFRESH : begin
            chip_sdram_CSn <= 1'b0;
            chip_sdram_RASn <= 1'b0;
            chip_sdram_CASn <= 1'b0;
            chip_sdram_WEn <= 1'b1;
          end
          SdramCtrlBackendTask_MODE : begin
            chip_sdram_ADDR <= 13'h0;
            chip_sdram_ADDR[2 : 0] <= 3'b000;
            chip_sdram_ADDR[3] <= 1'b0;
            chip_sdram_ADDR[6 : 4] <= 3'b011;
            chip_sdram_ADDR[8 : 7] <= 2'b00;
            chip_sdram_ADDR[9] <= 1'b0;
            chip_sdram_BA <= 2'b00;
            chip_sdram_CSn <= 1'b0;
            chip_sdram_RASn <= 1'b0;
            chip_sdram_CASn <= 1'b0;
            chip_sdram_WEn <= 1'b0;
          end
          SdramCtrlBackendTask_ACTIVE : begin
            chip_sdram_ADDR <= chip_cmd_payload_rowColumn;
            chip_sdram_BA <= chip_cmd_payload_bank;
            chip_sdram_CSn <= 1'b0;
            chip_sdram_RASn <= 1'b0;
            chip_sdram_CASn <= 1'b1;
            chip_sdram_WEn <= 1'b1;
          end
          SdramCtrlBackendTask_WRITE : begin
            chip_sdram_ADDR <= chip_cmd_payload_rowColumn;
            chip_sdram_ADDR[10] <= 1'b0;
            chip_sdram_DQ_writeEnable <= 16'hffff;
            chip_sdram_DQ_write <= chip_cmd_payload_data;
            chip_sdram_DQM <= (~ chip_cmd_payload_mask);
            chip_sdram_BA <= chip_cmd_payload_bank;
            chip_sdram_CSn <= 1'b0;
            chip_sdram_RASn <= 1'b1;
            chip_sdram_CASn <= 1'b0;
            chip_sdram_WEn <= 1'b0;
          end
          SdramCtrlBackendTask_READ : begin
            chip_sdram_ADDR <= chip_cmd_payload_rowColumn;
            chip_sdram_ADDR[10] <= 1'b0;
            chip_sdram_BA <= chip_cmd_payload_bank;
            chip_sdram_CSn <= 1'b0;
            chip_sdram_RASn <= 1'b1;
            chip_sdram_CASn <= 1'b0;
            chip_sdram_WEn <= 1'b1;
          end
          default : begin
            chip_sdram_BA <= chip_cmd_payload_bank;
            chip_sdram_ADDR[10] <= 1'b0;
            chip_sdram_CSn <= 1'b0;
            chip_sdram_RASn <= 1'b0;
            chip_sdram_CASn <= 1'b1;
            chip_sdram_WEn <= 1'b0;
          end
        endcase
      end
    end
  end


endmodule

module BmbMemoryController (
  input  wire          io_memIn_rd,
  input  wire          io_memIn_rdc,
  input  wire          io_memIn_rdf,
  input  wire          io_memIn_wr,
  input  wire          io_memIn_wrf,
  input  wire          io_memIn_addrWr,
  input  wire          io_memIn_bcRd,
  input  wire          io_memIn_stidx,
  input  wire          io_memIn_iaload,
  input  wire          io_memIn_iastore,
  input  wire          io_memIn_getfield,
  input  wire          io_memIn_putfield,
  input  wire          io_memIn_putref,
  input  wire          io_memIn_getstatic,
  input  wire          io_memIn_putstatic,
  input  wire          io_memIn_copy,
  input  wire          io_memIn_cinval,
  input  wire          io_memIn_atmstart,
  input  wire          io_memIn_atmend,
  input  wire [15:0]   io_memIn_bcopd,
  output reg  [31:0]   io_memOut_rdData,
  output wire          io_memOut_busy,
  output wire [11:0]   io_memOut_bcStart,
  input  wire [31:0]   io_aout,
  input  wire [31:0]   io_bout,
  input  wire [15:0]   io_bcopd,
  output wire [8:0]    io_jbcWrite_addr,
  output wire [31:0]   io_jbcWrite_data,
  output wire          io_jbcWrite_enable,
  output reg           io_bmb_cmd_valid,
  input  wire          io_bmb_cmd_ready,
  output wire          io_bmb_cmd_payload_last,
  output reg  [0:0]    io_bmb_cmd_payload_fragment_opcode,
  output reg  [25:0]   io_bmb_cmd_payload_fragment_address,
  output reg  [3:0]    io_bmb_cmd_payload_fragment_length,
  output reg  [31:0]   io_bmb_cmd_payload_fragment_data,
  output wire [3:0]    io_bmb_cmd_payload_fragment_mask,
  output wire [3:0]    io_bmb_cmd_payload_fragment_context,
  input  wire          io_bmb_rsp_valid,
  output wire          io_bmb_rsp_ready,
  input  wire          io_bmb_rsp_payload_last,
  input  wire [0:0]    io_bmb_rsp_payload_fragment_opcode,
  input  wire [31:0]   io_bmb_rsp_payload_fragment_data,
  input  wire [3:0]    io_bmb_rsp_payload_fragment_context,
  output reg  [7:0]    io_ioAddr,
  output reg           io_ioRd,
  output reg           io_ioWr,
  output reg  [31:0]   io_ioWrData,
  input  wire [31:0]   io_ioRdData,
  output wire [4:0]    io_debug_state,
  output wire          io_debug_busy,
  output wire          io_debug_handleActive,
  input  wire          clk,
  input  wire          reset
);
  localparam State_IDLE = 5'd0;
  localparam State_READ_WAIT = 5'd1;
  localparam State_WRITE_WAIT = 5'd2;
  localparam State_IAST_WAIT = 5'd3;
  localparam State_HANDLE_READ = 5'd4;
  localparam State_HANDLE_WAIT = 5'd5;
  localparam State_HANDLE_CALC = 5'd6;
  localparam State_HANDLE_ACCESS = 5'd7;
  localparam State_HANDLE_DATA_WAIT = 5'd8;
  localparam State_HANDLE_BOUND_READ = 5'd9;
  localparam State_HANDLE_BOUND_WAIT = 5'd10;
  localparam State_NP_EXC = 5'd11;
  localparam State_AB_EXC = 5'd12;
  localparam State_BC_CACHE_CHECK = 5'd13;
  localparam State_BC_FILL_R1 = 5'd14;
  localparam State_BC_FILL_LOOP = 5'd15;
  localparam State_BC_FILL_CMD = 5'd16;
  localparam State_CP_SETUP = 5'd17;
  localparam State_CP_READ = 5'd18;
  localparam State_CP_READ_WAIT = 5'd19;
  localparam State_CP_WRITE = 5'd20;
  localparam State_CP_STOP = 5'd21;
  localparam State_GS_READ = 5'd22;
  localparam State_PS_WRITE = 5'd23;
  localparam State_LAST = 5'd24;

  wire       [17:0]   methodCache_1_io_bcAddr;
  reg        [23:0]   ocache_io_handle;
  wire       [7:0]    ocache_io_fieldIdx;
  wire                ocache_io_chkGf;
  reg                 ocache_io_chkPf;
  reg                 ocache_io_wrGf;
  reg                 ocache_io_wrPf;
  wire                ocache_io_inval;
  wire       [8:0]    methodCache_1_io_bcStart;
  wire                methodCache_1_io_rdy;
  wire                methodCache_1_io_inCache;
  wire                ocache_io_hit;
  wire       [31:0]   ocache_io_dout;
  wire       [23:0]   _zz_addrReg;
  wire       [15:0]   _zz_addrReg_1;
  wire       [23:0]   _zz_addrReg_2;
  wire       [15:0]   _zz_addrReg_3;
  wire       [21:0]   _zz_bcFillAddr_1;
  wire       [31:0]   _zz_bcFillLen;
  wire       [23:0]   _zz_handleIndex;
  wire       [15:0]   _zz_handleIndex_1;
  wire       [23:0]   _zz_handleIndex_2;
  wire       [15:0]   _zz_handleIndex_3;
  wire       [10:0]   _zz_bcStartReg;
  wire       [11:0]   _zz_io_bmb_cmd_payload_fragment_length_3;
  wire       [11:0]   _zz_io_bmb_cmd_payload_fragment_length_4;
  wire       [23:0]   _zz_bcFillAddr_2;
  wire       [9:0]    _zz_jbcWrAddrReg;
  wire       [9:0]    _zz_jbcWrAddrReg_1;
  wire       [9:0]    _zz_when_BmbMemoryController_l755;
  wire       [23:0]   _zz_wasHwo;
  wire       [23:0]   _zz_io_bmb_cmd_payload_fragment_address;
  reg        [4:0]    state_2;
  reg        [23:0]   addrReg;
  reg        [31:0]   rdDataReg;
  reg        [23:0]   handleDataPtr;
  reg        [23:0]   handleIndex;
  reg                 handleIsWrite;
  reg                 handleIsArray;
  reg        [31:0]   handleWriteData;
  reg        [23:0]   bcFillAddr;
  reg        [9:0]    bcFillLen;
  reg        [9:0]    bcFillCount;
  reg        [11:0]   bcStartReg;
  reg        [8:0]    jbcWrAddrReg;
  reg        [31:0]   jbcWrDataReg;
  reg                 jbcWrEnReg;
  reg        [31:0]   valueReg;
  reg        [23:0]   indexReg;
  reg                 wasStidx;
  reg        [8:0]    bcCacheStartReg;
  reg        [23:0]   baseReg;
  reg        [23:0]   posReg;
  reg        [23:0]   offsetReg;
  reg                 cpStopBit;
  reg                 cmdAccepted;
  reg        [25:0]   pendingCmdAddr;
  reg        [31:0]   pendingCmdData;
  reg                 pendingCmdIsWrite;
  reg                 readOcache;
  reg                 ocWasGetfield;
  reg                 wasHwo;
  reg        [23:0]   handleAddrReg;
  wire                notBusy;
  wire                io_bmb_rsp_fire;
  wire                when_BmbMemoryController_l237;
  wire       [23:0]   aoutAddr;
  wire                aoutIsIo;
  wire                addrIsIo;
  wire                memReadRequested;
  reg                 mcacheFind;
  wire                when_BmbMemoryController_l364;
  wire                when_BmbMemoryController_l402;
  wire       [31:0]   _zz_bcFillAddr;
  wire                when_BmbMemoryController_l492;
  wire                when_BmbMemoryController_l431;
  wire                when_BmbMemoryController_l556;
  wire                when_BmbMemoryController_l563;
  wire                when_BmbMemoryController_l570;
  wire                io_bmb_cmd_fire;
  wire                when_BmbMemoryController_l593;
  wire                when_BmbMemoryController_l600;
  wire                when_BmbMemoryController_l607;
  wire       [9:0]    _zz_io_bmb_cmd_payload_fragment_length;
  wire       [9:0]    _zz_io_bmb_cmd_payload_fragment_length_1;
  wire       [9:0]    _zz_io_bmb_cmd_payload_fragment_length_2;
  wire                when_BmbMemoryController_l755;
  wire                when_BmbMemoryController_l779;
  wire                when_BmbMemoryController_l885;
  wire                when_BmbMemoryController_l892;
  wire                when_BmbMemoryController_l927;
  reg                 _zz_io_debug_handleActive;
  `ifndef SYNTHESIS
  reg [135:0] state_2_string;
  `endif


  assign _zz_addrReg_1 = io_bcopd;
  assign _zz_addrReg = {8'd0, _zz_addrReg_1};
  assign _zz_addrReg_3 = io_bcopd;
  assign _zz_addrReg_2 = {8'd0, _zz_addrReg_3};
  assign _zz_bcFillAddr_1 = (_zz_bcFillAddr >>> 4'd10);
  assign _zz_bcFillLen = (_zz_bcFillAddr & 32'h000003ff);
  assign _zz_handleIndex_1 = io_bcopd[15 : 0];
  assign _zz_handleIndex = {8'd0, _zz_handleIndex_1};
  assign _zz_handleIndex_3 = io_bcopd[15 : 0];
  assign _zz_handleIndex_2 = {8'd0, _zz_handleIndex_3};
  assign _zz_bcStartReg = {methodCache_1_io_bcStart,2'b00};
  assign _zz_io_bmb_cmd_payload_fragment_length_3 = (_zz_io_bmb_cmd_payload_fragment_length_4 - 12'h001);
  assign _zz_io_bmb_cmd_payload_fragment_length_4 = ({2'd0,_zz_io_bmb_cmd_payload_fragment_length_2} <<< 2'd2);
  assign _zz_bcFillAddr_2 = {14'd0, _zz_io_bmb_cmd_payload_fragment_length_2};
  assign _zz_jbcWrAddrReg = (_zz_jbcWrAddrReg_1 + bcFillCount);
  assign _zz_jbcWrAddrReg_1 = {1'd0, bcCacheStartReg};
  assign _zz_when_BmbMemoryController_l755 = (bcFillCount + 10'h001);
  assign _zz_wasHwo = io_bmb_rsp_payload_fragment_data[23 : 0];
  assign _zz_io_bmb_cmd_payload_fragment_address = (addrReg + 24'h000001);
  MethodCache methodCache_1 (
    .io_bcLen   (bcFillLen[9:0]               ), //i
    .io_bcAddr  (methodCache_1_io_bcAddr[17:0]), //i
    .io_find    (mcacheFind                   ), //i
    .io_bcStart (methodCache_1_io_bcStart[8:0]), //o
    .io_rdy     (methodCache_1_io_rdy         ), //o
    .io_inCache (methodCache_1_io_inCache     ), //o
    .clk        (clk                          ), //i
    .reset      (reset                        )  //i
  );
  ObjectCache ocache (
    .io_handle   (ocache_io_handle[23:0]                ), //i
    .io_fieldIdx (ocache_io_fieldIdx[7:0]               ), //i
    .io_chkGf    (ocache_io_chkGf                       ), //i
    .io_chkPf    (ocache_io_chkPf                       ), //i
    .io_hit      (ocache_io_hit                         ), //o
    .io_dout     (ocache_io_dout[31:0]                  ), //o
    .io_wrGf     (ocache_io_wrGf                        ), //i
    .io_wrPf     (ocache_io_wrPf                        ), //i
    .io_gfVal    (io_bmb_rsp_payload_fragment_data[31:0]), //i
    .io_pfVal    (handleWriteData[31:0]                 ), //i
    .io_inval    (ocache_io_inval                       ), //i
    .clk         (clk                                   ), //i
    .reset       (reset                                 )  //i
  );
  `ifndef SYNTHESIS
  always @(*) begin
    case(state_2)
      State_IDLE : state_2_string = "IDLE             ";
      State_READ_WAIT : state_2_string = "READ_WAIT        ";
      State_WRITE_WAIT : state_2_string = "WRITE_WAIT       ";
      State_IAST_WAIT : state_2_string = "IAST_WAIT        ";
      State_HANDLE_READ : state_2_string = "HANDLE_READ      ";
      State_HANDLE_WAIT : state_2_string = "HANDLE_WAIT      ";
      State_HANDLE_CALC : state_2_string = "HANDLE_CALC      ";
      State_HANDLE_ACCESS : state_2_string = "HANDLE_ACCESS    ";
      State_HANDLE_DATA_WAIT : state_2_string = "HANDLE_DATA_WAIT ";
      State_HANDLE_BOUND_READ : state_2_string = "HANDLE_BOUND_READ";
      State_HANDLE_BOUND_WAIT : state_2_string = "HANDLE_BOUND_WAIT";
      State_NP_EXC : state_2_string = "NP_EXC           ";
      State_AB_EXC : state_2_string = "AB_EXC           ";
      State_BC_CACHE_CHECK : state_2_string = "BC_CACHE_CHECK   ";
      State_BC_FILL_R1 : state_2_string = "BC_FILL_R1       ";
      State_BC_FILL_LOOP : state_2_string = "BC_FILL_LOOP     ";
      State_BC_FILL_CMD : state_2_string = "BC_FILL_CMD      ";
      State_CP_SETUP : state_2_string = "CP_SETUP         ";
      State_CP_READ : state_2_string = "CP_READ          ";
      State_CP_READ_WAIT : state_2_string = "CP_READ_WAIT     ";
      State_CP_WRITE : state_2_string = "CP_WRITE         ";
      State_CP_STOP : state_2_string = "CP_STOP          ";
      State_GS_READ : state_2_string = "GS_READ          ";
      State_PS_WRITE : state_2_string = "PS_WRITE         ";
      State_LAST : state_2_string = "LAST             ";
      default : state_2_string = "?????????????????";
    endcase
  end
  `endif

  assign notBusy = ((state_2 == State_IDLE) || (((state_2 == State_READ_WAIT) || (state_2 == State_WRITE_WAIT)) && io_bmb_rsp_valid));
  assign io_memOut_busy = (! notBusy);
  always @(*) begin
    io_memOut_rdData = rdDataReg;
    if(when_BmbMemoryController_l237) begin
      io_memOut_rdData = io_bmb_rsp_payload_fragment_data;
    end
    if(readOcache) begin
      io_memOut_rdData = ocache_io_dout;
    end
  end

  assign io_bmb_rsp_fire = (io_bmb_rsp_valid && io_bmb_rsp_ready);
  assign when_BmbMemoryController_l237 = (io_bmb_rsp_fire && (state_2 == State_READ_WAIT));
  assign io_memOut_bcStart = bcStartReg;
  always @(*) begin
    io_bmb_cmd_valid = 1'b0;
    case(state_2)
      State_IDLE : begin
        if(memReadRequested) begin
          if(!aoutIsIo) begin
            io_bmb_cmd_valid = 1'b1;
          end
        end else begin
          if(when_BmbMemoryController_l431) begin
            if(!addrIsIo) begin
              io_bmb_cmd_valid = 1'b1;
            end
          end
        end
      end
      State_READ_WAIT : begin
        if(when_BmbMemoryController_l570) begin
          io_bmb_cmd_valid = 1'b1;
        end
      end
      State_WRITE_WAIT : begin
        if(when_BmbMemoryController_l607) begin
          io_bmb_cmd_valid = 1'b1;
        end
      end
      State_IAST_WAIT : begin
      end
      State_BC_CACHE_CHECK : begin
      end
      State_BC_FILL_R1 : begin
        io_bmb_cmd_valid = 1'b1;
      end
      State_BC_FILL_LOOP : begin
      end
      State_BC_FILL_CMD : begin
      end
      State_HANDLE_READ : begin
        io_bmb_cmd_valid = 1'b1;
      end
      State_HANDLE_WAIT : begin
      end
      State_HANDLE_CALC : begin
      end
      State_HANDLE_ACCESS : begin
        if(!addrIsIo) begin
          io_bmb_cmd_valid = 1'b1;
        end
      end
      State_HANDLE_DATA_WAIT : begin
      end
      State_HANDLE_BOUND_READ : begin
        io_bmb_cmd_valid = 1'b1;
      end
      State_HANDLE_BOUND_WAIT : begin
      end
      State_NP_EXC : begin
      end
      State_AB_EXC : begin
      end
      State_GS_READ : begin
        io_bmb_cmd_valid = 1'b1;
      end
      State_PS_WRITE : begin
        io_bmb_cmd_valid = 1'b1;
      end
      State_LAST : begin
      end
      State_CP_SETUP : begin
      end
      State_CP_READ : begin
        io_bmb_cmd_valid = 1'b1;
      end
      State_CP_READ_WAIT : begin
      end
      State_CP_WRITE : begin
        io_bmb_cmd_valid = 1'b1;
      end
      default : begin
      end
    endcase
  end

  assign io_bmb_cmd_payload_last = 1'b1;
  always @(*) begin
    io_bmb_cmd_payload_fragment_opcode = 1'b0;
    case(state_2)
      State_IDLE : begin
        if(memReadRequested) begin
          if(!aoutIsIo) begin
            io_bmb_cmd_payload_fragment_opcode = 1'b0;
          end
        end else begin
          if(when_BmbMemoryController_l431) begin
            if(!addrIsIo) begin
              io_bmb_cmd_payload_fragment_opcode = 1'b1;
            end
          end
        end
      end
      State_READ_WAIT : begin
        if(when_BmbMemoryController_l570) begin
          io_bmb_cmd_payload_fragment_opcode = 1'b0;
        end
      end
      State_WRITE_WAIT : begin
        if(when_BmbMemoryController_l607) begin
          io_bmb_cmd_payload_fragment_opcode = 1'b1;
        end
      end
      State_IAST_WAIT : begin
      end
      State_BC_CACHE_CHECK : begin
      end
      State_BC_FILL_R1 : begin
        io_bmb_cmd_payload_fragment_opcode = 1'b0;
      end
      State_BC_FILL_LOOP : begin
      end
      State_BC_FILL_CMD : begin
      end
      State_HANDLE_READ : begin
        io_bmb_cmd_payload_fragment_opcode = 1'b0;
      end
      State_HANDLE_WAIT : begin
      end
      State_HANDLE_CALC : begin
      end
      State_HANDLE_ACCESS : begin
        if(!addrIsIo) begin
          if(handleIsWrite) begin
            io_bmb_cmd_payload_fragment_opcode = 1'b1;
          end else begin
            io_bmb_cmd_payload_fragment_opcode = 1'b0;
          end
        end
      end
      State_HANDLE_DATA_WAIT : begin
      end
      State_HANDLE_BOUND_READ : begin
        io_bmb_cmd_payload_fragment_opcode = 1'b0;
      end
      State_HANDLE_BOUND_WAIT : begin
      end
      State_NP_EXC : begin
      end
      State_AB_EXC : begin
      end
      State_GS_READ : begin
        io_bmb_cmd_payload_fragment_opcode = 1'b0;
      end
      State_PS_WRITE : begin
        io_bmb_cmd_payload_fragment_opcode = 1'b1;
      end
      State_LAST : begin
      end
      State_CP_SETUP : begin
      end
      State_CP_READ : begin
        io_bmb_cmd_payload_fragment_opcode = 1'b0;
      end
      State_CP_READ_WAIT : begin
      end
      State_CP_WRITE : begin
        io_bmb_cmd_payload_fragment_opcode = 1'b1;
      end
      default : begin
      end
    endcase
  end

  always @(*) begin
    io_bmb_cmd_payload_fragment_address = 26'h0;
    case(state_2)
      State_IDLE : begin
        if(memReadRequested) begin
          if(!aoutIsIo) begin
            io_bmb_cmd_payload_fragment_address = ({2'd0,aoutAddr} <<< 2'd2);
          end
        end else begin
          if(when_BmbMemoryController_l431) begin
            if(!addrIsIo) begin
              io_bmb_cmd_payload_fragment_address = ({2'd0,addrReg} <<< 2'd2);
            end
          end
        end
      end
      State_READ_WAIT : begin
        if(when_BmbMemoryController_l570) begin
          io_bmb_cmd_payload_fragment_address = pendingCmdAddr;
        end
      end
      State_WRITE_WAIT : begin
        if(when_BmbMemoryController_l607) begin
          io_bmb_cmd_payload_fragment_address = pendingCmdAddr;
        end
      end
      State_IAST_WAIT : begin
      end
      State_BC_CACHE_CHECK : begin
      end
      State_BC_FILL_R1 : begin
        io_bmb_cmd_payload_fragment_address = ({2'd0,bcFillAddr} <<< 2'd2);
      end
      State_BC_FILL_LOOP : begin
      end
      State_BC_FILL_CMD : begin
      end
      State_HANDLE_READ : begin
        io_bmb_cmd_payload_fragment_address = ({2'd0,addrReg} <<< 2'd2);
      end
      State_HANDLE_WAIT : begin
      end
      State_HANDLE_CALC : begin
      end
      State_HANDLE_ACCESS : begin
        if(!addrIsIo) begin
          io_bmb_cmd_payload_fragment_address = ({2'd0,addrReg} <<< 2'd2);
        end
      end
      State_HANDLE_DATA_WAIT : begin
      end
      State_HANDLE_BOUND_READ : begin
        io_bmb_cmd_payload_fragment_address = ({2'd0,_zz_io_bmb_cmd_payload_fragment_address} <<< 2'd2);
      end
      State_HANDLE_BOUND_WAIT : begin
      end
      State_NP_EXC : begin
      end
      State_AB_EXC : begin
      end
      State_GS_READ : begin
        io_bmb_cmd_payload_fragment_address = ({2'd0,addrReg} <<< 2'd2);
      end
      State_PS_WRITE : begin
        io_bmb_cmd_payload_fragment_address = ({2'd0,addrReg} <<< 2'd2);
      end
      State_LAST : begin
      end
      State_CP_SETUP : begin
      end
      State_CP_READ : begin
        io_bmb_cmd_payload_fragment_address = ({2'd0,posReg} <<< 2'd2);
      end
      State_CP_READ_WAIT : begin
      end
      State_CP_WRITE : begin
        io_bmb_cmd_payload_fragment_address = ({2'd0,addrReg} <<< 2'd2);
      end
      default : begin
      end
    endcase
  end

  always @(*) begin
    io_bmb_cmd_payload_fragment_length = 4'b0011;
    case(state_2)
      State_IDLE : begin
      end
      State_READ_WAIT : begin
      end
      State_WRITE_WAIT : begin
      end
      State_IAST_WAIT : begin
      end
      State_BC_CACHE_CHECK : begin
      end
      State_BC_FILL_R1 : begin
        io_bmb_cmd_payload_fragment_length = _zz_io_bmb_cmd_payload_fragment_length_3[3:0];
      end
      State_BC_FILL_LOOP : begin
      end
      State_BC_FILL_CMD : begin
      end
      State_HANDLE_READ : begin
      end
      State_HANDLE_WAIT : begin
      end
      State_HANDLE_CALC : begin
      end
      State_HANDLE_ACCESS : begin
      end
      State_HANDLE_DATA_WAIT : begin
      end
      State_HANDLE_BOUND_READ : begin
      end
      State_HANDLE_BOUND_WAIT : begin
      end
      State_NP_EXC : begin
      end
      State_AB_EXC : begin
      end
      State_GS_READ : begin
      end
      State_PS_WRITE : begin
      end
      State_LAST : begin
      end
      State_CP_SETUP : begin
      end
      State_CP_READ : begin
      end
      State_CP_READ_WAIT : begin
      end
      State_CP_WRITE : begin
      end
      default : begin
      end
    endcase
  end

  assign io_bmb_cmd_payload_fragment_context = 4'b0000;
  always @(*) begin
    io_bmb_cmd_payload_fragment_data = 32'h0;
    case(state_2)
      State_IDLE : begin
        if(!memReadRequested) begin
          if(when_BmbMemoryController_l431) begin
            if(!addrIsIo) begin
              io_bmb_cmd_payload_fragment_data = io_aout;
            end
          end
        end
      end
      State_READ_WAIT : begin
      end
      State_WRITE_WAIT : begin
        if(when_BmbMemoryController_l607) begin
          io_bmb_cmd_payload_fragment_data = pendingCmdData;
        end
      end
      State_IAST_WAIT : begin
      end
      State_BC_CACHE_CHECK : begin
      end
      State_BC_FILL_R1 : begin
      end
      State_BC_FILL_LOOP : begin
      end
      State_BC_FILL_CMD : begin
      end
      State_HANDLE_READ : begin
      end
      State_HANDLE_WAIT : begin
      end
      State_HANDLE_CALC : begin
      end
      State_HANDLE_ACCESS : begin
        if(!addrIsIo) begin
          if(handleIsWrite) begin
            io_bmb_cmd_payload_fragment_data = handleWriteData;
          end
        end
      end
      State_HANDLE_DATA_WAIT : begin
      end
      State_HANDLE_BOUND_READ : begin
      end
      State_HANDLE_BOUND_WAIT : begin
      end
      State_NP_EXC : begin
      end
      State_AB_EXC : begin
      end
      State_GS_READ : begin
      end
      State_PS_WRITE : begin
        io_bmb_cmd_payload_fragment_data = valueReg;
      end
      State_LAST : begin
      end
      State_CP_SETUP : begin
      end
      State_CP_READ : begin
      end
      State_CP_READ_WAIT : begin
      end
      State_CP_WRITE : begin
        io_bmb_cmd_payload_fragment_data = valueReg;
      end
      default : begin
      end
    endcase
  end

  assign io_bmb_cmd_payload_fragment_mask = 4'b1111;
  assign io_bmb_rsp_ready = 1'b1;
  always @(*) begin
    io_ioAddr = addrReg[7 : 0];
    case(state_2)
      State_IDLE : begin
        if(memReadRequested) begin
          if(aoutIsIo) begin
            io_ioAddr = io_aout[7 : 0];
          end
        end else begin
          if(when_BmbMemoryController_l431) begin
            if(addrIsIo) begin
              io_ioAddr = addrReg[7 : 0];
            end
          end
        end
      end
      State_READ_WAIT : begin
        if(when_BmbMemoryController_l556) begin
          io_ioAddr = io_aout[7 : 0];
        end
        if(when_BmbMemoryController_l563) begin
          io_ioAddr = addrReg[7 : 0];
        end
      end
      State_WRITE_WAIT : begin
        if(when_BmbMemoryController_l593) begin
          io_ioAddr = io_aout[7 : 0];
        end
        if(when_BmbMemoryController_l600) begin
          io_ioAddr = addrReg[7 : 0];
        end
      end
      State_IAST_WAIT : begin
      end
      State_BC_CACHE_CHECK : begin
      end
      State_BC_FILL_R1 : begin
      end
      State_BC_FILL_LOOP : begin
      end
      State_BC_FILL_CMD : begin
      end
      State_HANDLE_READ : begin
      end
      State_HANDLE_WAIT : begin
      end
      State_HANDLE_CALC : begin
      end
      State_HANDLE_ACCESS : begin
        if(addrIsIo) begin
          io_ioAddr = addrReg[7 : 0];
        end
      end
      State_HANDLE_DATA_WAIT : begin
      end
      State_HANDLE_BOUND_READ : begin
      end
      State_HANDLE_BOUND_WAIT : begin
        if(io_bmb_rsp_fire) begin
          if(when_BmbMemoryController_l927) begin
            io_ioAddr = 8'h04;
          end
        end
      end
      State_NP_EXC : begin
      end
      State_AB_EXC : begin
      end
      State_GS_READ : begin
      end
      State_PS_WRITE : begin
      end
      State_LAST : begin
      end
      State_CP_SETUP : begin
      end
      State_CP_READ : begin
      end
      State_CP_READ_WAIT : begin
      end
      State_CP_WRITE : begin
      end
      default : begin
      end
    endcase
  end

  always @(*) begin
    io_ioRd = 1'b0;
    case(state_2)
      State_IDLE : begin
        if(memReadRequested) begin
          if(aoutIsIo) begin
            io_ioRd = 1'b1;
          end
        end
      end
      State_READ_WAIT : begin
        if(when_BmbMemoryController_l556) begin
          io_ioRd = 1'b1;
        end
      end
      State_WRITE_WAIT : begin
        if(when_BmbMemoryController_l593) begin
          io_ioRd = 1'b1;
        end
      end
      State_IAST_WAIT : begin
      end
      State_BC_CACHE_CHECK : begin
      end
      State_BC_FILL_R1 : begin
      end
      State_BC_FILL_LOOP : begin
      end
      State_BC_FILL_CMD : begin
      end
      State_HANDLE_READ : begin
      end
      State_HANDLE_WAIT : begin
      end
      State_HANDLE_CALC : begin
      end
      State_HANDLE_ACCESS : begin
        if(addrIsIo) begin
          if(!handleIsWrite) begin
            io_ioRd = 1'b1;
          end
        end
      end
      State_HANDLE_DATA_WAIT : begin
      end
      State_HANDLE_BOUND_READ : begin
      end
      State_HANDLE_BOUND_WAIT : begin
      end
      State_NP_EXC : begin
      end
      State_AB_EXC : begin
      end
      State_GS_READ : begin
      end
      State_PS_WRITE : begin
      end
      State_LAST : begin
      end
      State_CP_SETUP : begin
      end
      State_CP_READ : begin
      end
      State_CP_READ_WAIT : begin
      end
      State_CP_WRITE : begin
      end
      default : begin
      end
    endcase
  end

  always @(*) begin
    io_ioWr = 1'b0;
    case(state_2)
      State_IDLE : begin
        if(!memReadRequested) begin
          if(when_BmbMemoryController_l431) begin
            if(addrIsIo) begin
              io_ioWr = 1'b1;
            end
          end
        end
      end
      State_READ_WAIT : begin
        if(when_BmbMemoryController_l563) begin
          io_ioWr = 1'b1;
        end
      end
      State_WRITE_WAIT : begin
        if(when_BmbMemoryController_l600) begin
          io_ioWr = 1'b1;
        end
      end
      State_IAST_WAIT : begin
      end
      State_BC_CACHE_CHECK : begin
      end
      State_BC_FILL_R1 : begin
      end
      State_BC_FILL_LOOP : begin
      end
      State_BC_FILL_CMD : begin
      end
      State_HANDLE_READ : begin
      end
      State_HANDLE_WAIT : begin
      end
      State_HANDLE_CALC : begin
      end
      State_HANDLE_ACCESS : begin
        if(addrIsIo) begin
          if(handleIsWrite) begin
            io_ioWr = 1'b1;
          end
        end
      end
      State_HANDLE_DATA_WAIT : begin
      end
      State_HANDLE_BOUND_READ : begin
      end
      State_HANDLE_BOUND_WAIT : begin
        if(io_bmb_rsp_fire) begin
          if(when_BmbMemoryController_l927) begin
            io_ioWr = 1'b1;
          end
        end
      end
      State_NP_EXC : begin
      end
      State_AB_EXC : begin
      end
      State_GS_READ : begin
      end
      State_PS_WRITE : begin
      end
      State_LAST : begin
      end
      State_CP_SETUP : begin
      end
      State_CP_READ : begin
      end
      State_CP_READ_WAIT : begin
      end
      State_CP_WRITE : begin
      end
      default : begin
      end
    endcase
  end

  always @(*) begin
    io_ioWrData = io_aout;
    case(state_2)
      State_IDLE : begin
        if(!memReadRequested) begin
          if(when_BmbMemoryController_l431) begin
            if(addrIsIo) begin
              io_ioWrData = io_aout;
            end
          end
        end
      end
      State_READ_WAIT : begin
        if(when_BmbMemoryController_l563) begin
          io_ioWrData = io_aout;
        end
      end
      State_WRITE_WAIT : begin
        if(when_BmbMemoryController_l600) begin
          io_ioWrData = io_aout;
        end
      end
      State_IAST_WAIT : begin
      end
      State_BC_CACHE_CHECK : begin
      end
      State_BC_FILL_R1 : begin
      end
      State_BC_FILL_LOOP : begin
      end
      State_BC_FILL_CMD : begin
      end
      State_HANDLE_READ : begin
      end
      State_HANDLE_WAIT : begin
      end
      State_HANDLE_CALC : begin
      end
      State_HANDLE_ACCESS : begin
        if(addrIsIo) begin
          if(handleIsWrite) begin
            io_ioWrData = handleWriteData;
          end
        end
      end
      State_HANDLE_DATA_WAIT : begin
      end
      State_HANDLE_BOUND_READ : begin
      end
      State_HANDLE_BOUND_WAIT : begin
        if(io_bmb_rsp_fire) begin
          if(when_BmbMemoryController_l927) begin
            io_ioWrData = 32'h00000003;
          end
        end
      end
      State_NP_EXC : begin
      end
      State_AB_EXC : begin
      end
      State_GS_READ : begin
      end
      State_PS_WRITE : begin
      end
      State_LAST : begin
      end
      State_CP_SETUP : begin
      end
      State_CP_READ : begin
      end
      State_CP_READ_WAIT : begin
      end
      State_CP_WRITE : begin
      end
      default : begin
      end
    endcase
  end

  assign io_jbcWrite_addr = jbcWrAddrReg;
  assign io_jbcWrite_data = jbcWrDataReg;
  assign io_jbcWrite_enable = jbcWrEnReg;
  assign aoutAddr = io_aout[23 : 0];
  assign aoutIsIo = (aoutAddr[23 : 22] == 2'b11);
  assign addrIsIo = (addrReg[23 : 22] == 2'b11);
  assign memReadRequested = ((io_memIn_rd || io_memIn_rdc) || io_memIn_rdf);
  assign methodCache_1_io_bcAddr = bcFillAddr[17 : 0];
  always @(*) begin
    mcacheFind = 1'b0;
    case(state_2)
      State_IDLE : begin
        if(!memReadRequested) begin
          if(!when_BmbMemoryController_l431) begin
            if(!io_memIn_putstatic) begin
              if(!io_memIn_getstatic) begin
                if(io_memIn_bcRd) begin
                  mcacheFind = 1'b1;
                end
              end
            end
          end
        end
      end
      State_READ_WAIT : begin
      end
      State_WRITE_WAIT : begin
      end
      State_IAST_WAIT : begin
      end
      State_BC_CACHE_CHECK : begin
      end
      State_BC_FILL_R1 : begin
      end
      State_BC_FILL_LOOP : begin
      end
      State_BC_FILL_CMD : begin
      end
      State_HANDLE_READ : begin
      end
      State_HANDLE_WAIT : begin
      end
      State_HANDLE_CALC : begin
      end
      State_HANDLE_ACCESS : begin
      end
      State_HANDLE_DATA_WAIT : begin
      end
      State_HANDLE_BOUND_READ : begin
      end
      State_HANDLE_BOUND_WAIT : begin
      end
      State_NP_EXC : begin
      end
      State_AB_EXC : begin
      end
      State_GS_READ : begin
      end
      State_PS_WRITE : begin
      end
      State_LAST : begin
      end
      State_CP_SETUP : begin
      end
      State_CP_READ : begin
      end
      State_CP_READ_WAIT : begin
      end
      State_CP_WRITE : begin
      end
      default : begin
      end
    endcase
  end

  always @(*) begin
    ocache_io_handle = aoutAddr;
    case(state_2)
      State_IDLE : begin
      end
      State_READ_WAIT : begin
      end
      State_WRITE_WAIT : begin
      end
      State_IAST_WAIT : begin
      end
      State_BC_CACHE_CHECK : begin
      end
      State_BC_FILL_R1 : begin
      end
      State_BC_FILL_LOOP : begin
      end
      State_BC_FILL_CMD : begin
      end
      State_HANDLE_READ : begin
        if(when_BmbMemoryController_l779) begin
          ocache_io_handle = addrReg;
        end
      end
      State_HANDLE_WAIT : begin
      end
      State_HANDLE_CALC : begin
      end
      State_HANDLE_ACCESS : begin
      end
      State_HANDLE_DATA_WAIT : begin
      end
      State_HANDLE_BOUND_READ : begin
      end
      State_HANDLE_BOUND_WAIT : begin
      end
      State_NP_EXC : begin
      end
      State_AB_EXC : begin
      end
      State_GS_READ : begin
      end
      State_PS_WRITE : begin
      end
      State_LAST : begin
      end
      State_CP_SETUP : begin
      end
      State_CP_READ : begin
      end
      State_CP_READ_WAIT : begin
      end
      State_CP_WRITE : begin
      end
      default : begin
      end
    endcase
  end

  assign ocache_io_fieldIdx = io_bcopd[7 : 0];
  assign ocache_io_chkGf = (io_memIn_getfield && (! wasStidx));
  always @(*) begin
    ocache_io_chkPf = 1'b0;
    case(state_2)
      State_IDLE : begin
      end
      State_READ_WAIT : begin
      end
      State_WRITE_WAIT : begin
      end
      State_IAST_WAIT : begin
      end
      State_BC_CACHE_CHECK : begin
      end
      State_BC_FILL_R1 : begin
      end
      State_BC_FILL_LOOP : begin
      end
      State_BC_FILL_CMD : begin
      end
      State_HANDLE_READ : begin
        if(when_BmbMemoryController_l779) begin
          ocache_io_chkPf = 1'b1;
        end
      end
      State_HANDLE_WAIT : begin
      end
      State_HANDLE_CALC : begin
      end
      State_HANDLE_ACCESS : begin
      end
      State_HANDLE_DATA_WAIT : begin
      end
      State_HANDLE_BOUND_READ : begin
      end
      State_HANDLE_BOUND_WAIT : begin
      end
      State_NP_EXC : begin
      end
      State_AB_EXC : begin
      end
      State_GS_READ : begin
      end
      State_PS_WRITE : begin
      end
      State_LAST : begin
      end
      State_CP_SETUP : begin
      end
      State_CP_READ : begin
      end
      State_CP_READ_WAIT : begin
      end
      State_CP_WRITE : begin
      end
      default : begin
      end
    endcase
  end

  assign ocache_io_inval = (io_memIn_stidx || io_memIn_cinval);
  always @(*) begin
    ocache_io_wrGf = 1'b0;
    case(state_2)
      State_IDLE : begin
      end
      State_READ_WAIT : begin
      end
      State_WRITE_WAIT : begin
      end
      State_IAST_WAIT : begin
      end
      State_BC_CACHE_CHECK : begin
      end
      State_BC_FILL_R1 : begin
      end
      State_BC_FILL_LOOP : begin
      end
      State_BC_FILL_CMD : begin
      end
      State_HANDLE_READ : begin
      end
      State_HANDLE_WAIT : begin
      end
      State_HANDLE_CALC : begin
      end
      State_HANDLE_ACCESS : begin
      end
      State_HANDLE_DATA_WAIT : begin
        if(io_bmb_rsp_fire) begin
          if(when_BmbMemoryController_l892) begin
            if(ocWasGetfield) begin
              ocache_io_wrGf = 1'b1;
            end
          end
        end
      end
      State_HANDLE_BOUND_READ : begin
      end
      State_HANDLE_BOUND_WAIT : begin
      end
      State_NP_EXC : begin
      end
      State_AB_EXC : begin
      end
      State_GS_READ : begin
      end
      State_PS_WRITE : begin
      end
      State_LAST : begin
      end
      State_CP_SETUP : begin
      end
      State_CP_READ : begin
      end
      State_CP_READ_WAIT : begin
      end
      State_CP_WRITE : begin
      end
      default : begin
      end
    endcase
  end

  always @(*) begin
    ocache_io_wrPf = 1'b0;
    case(state_2)
      State_IDLE : begin
      end
      State_READ_WAIT : begin
      end
      State_WRITE_WAIT : begin
      end
      State_IAST_WAIT : begin
      end
      State_BC_CACHE_CHECK : begin
      end
      State_BC_FILL_R1 : begin
      end
      State_BC_FILL_LOOP : begin
      end
      State_BC_FILL_CMD : begin
      end
      State_HANDLE_READ : begin
      end
      State_HANDLE_WAIT : begin
      end
      State_HANDLE_CALC : begin
      end
      State_HANDLE_ACCESS : begin
      end
      State_HANDLE_DATA_WAIT : begin
        if(io_bmb_rsp_fire) begin
          if(when_BmbMemoryController_l892) begin
            if(!ocWasGetfield) begin
              ocache_io_wrPf = 1'b1;
            end
          end
        end
      end
      State_HANDLE_BOUND_READ : begin
      end
      State_HANDLE_BOUND_WAIT : begin
      end
      State_NP_EXC : begin
      end
      State_AB_EXC : begin
      end
      State_GS_READ : begin
      end
      State_PS_WRITE : begin
      end
      State_LAST : begin
      end
      State_CP_SETUP : begin
      end
      State_CP_READ : begin
      end
      State_CP_READ_WAIT : begin
      end
      State_CP_WRITE : begin
      end
      default : begin
      end
    endcase
  end

  assign when_BmbMemoryController_l364 = (state_2 != State_IDLE);
  assign when_BmbMemoryController_l402 = ((io_memIn_iastore || io_memIn_putfield) || io_memIn_putstatic);
  assign _zz_bcFillAddr = io_aout;
  assign when_BmbMemoryController_l492 = (ocache_io_hit && (! wasStidx));
  assign when_BmbMemoryController_l431 = (io_memIn_wr || io_memIn_wrf);
  assign when_BmbMemoryController_l556 = (memReadRequested && aoutIsIo);
  assign when_BmbMemoryController_l563 = ((io_memIn_wr || io_memIn_wrf) && addrIsIo);
  assign when_BmbMemoryController_l570 = (! cmdAccepted);
  assign io_bmb_cmd_fire = (io_bmb_cmd_valid && io_bmb_cmd_ready);
  assign when_BmbMemoryController_l593 = (memReadRequested && aoutIsIo);
  assign when_BmbMemoryController_l600 = ((io_memIn_wr || io_memIn_wrf) && addrIsIo);
  assign when_BmbMemoryController_l607 = (! cmdAccepted);
  assign _zz_io_bmb_cmd_payload_fragment_length = (bcFillLen - bcFillCount);
  assign _zz_io_bmb_cmd_payload_fragment_length_1 = 10'h004;
  assign _zz_io_bmb_cmd_payload_fragment_length_2 = ((_zz_io_bmb_cmd_payload_fragment_length < _zz_io_bmb_cmd_payload_fragment_length_1) ? _zz_io_bmb_cmd_payload_fragment_length : _zz_io_bmb_cmd_payload_fragment_length_1);
  assign when_BmbMemoryController_l755 = (bcFillLen <= _zz_when_BmbMemoryController_l755);
  assign when_BmbMemoryController_l779 = (((! handleIsArray) && handleIsWrite) && (! wasStidx));
  assign when_BmbMemoryController_l885 = (! handleIsWrite);
  assign when_BmbMemoryController_l892 = (((! handleIsArray) && (! wasStidx)) && (! wasHwo));
  assign when_BmbMemoryController_l927 = (io_bmb_rsp_payload_fragment_data[23 : 0] <= handleIndex);
  assign io_debug_state = state_2;
  assign io_debug_busy = (! notBusy);
  always @(*) begin
    case(state_2)
      State_HANDLE_READ : begin
        _zz_io_debug_handleActive = 1'b1;
      end
      State_HANDLE_WAIT : begin
        _zz_io_debug_handleActive = 1'b1;
      end
      State_HANDLE_CALC : begin
        _zz_io_debug_handleActive = 1'b1;
      end
      State_HANDLE_ACCESS : begin
        _zz_io_debug_handleActive = 1'b1;
      end
      State_HANDLE_DATA_WAIT : begin
        _zz_io_debug_handleActive = 1'b1;
      end
      State_HANDLE_BOUND_READ : begin
        _zz_io_debug_handleActive = 1'b1;
      end
      State_HANDLE_BOUND_WAIT : begin
        _zz_io_debug_handleActive = 1'b1;
      end
      State_NP_EXC : begin
        _zz_io_debug_handleActive = 1'b1;
      end
      State_AB_EXC : begin
        _zz_io_debug_handleActive = 1'b1;
      end
      default : begin
        _zz_io_debug_handleActive = 1'b0;
      end
    endcase
  end

  assign io_debug_handleActive = _zz_io_debug_handleActive;
  always @(posedge clk or posedge reset) begin
    if(reset) begin
      state_2 <= State_IDLE;
      addrReg <= 24'h0;
      rdDataReg <= 32'h0;
      handleDataPtr <= 24'h0;
      handleIndex <= 24'h0;
      handleIsWrite <= 1'b0;
      handleIsArray <= 1'b0;
      handleWriteData <= 32'h0;
      bcFillAddr <= 24'h0;
      bcFillLen <= 10'h0;
      bcFillCount <= 10'h0;
      bcStartReg <= 12'h0;
      jbcWrAddrReg <= 9'h0;
      jbcWrDataReg <= 32'h0;
      jbcWrEnReg <= 1'b0;
      valueReg <= 32'h0;
      indexReg <= 24'h0;
      wasStidx <= 1'b0;
      bcCacheStartReg <= 9'h0;
      baseReg <= 24'h0;
      posReg <= 24'h0;
      offsetReg <= 24'h0;
      cpStopBit <= 1'b0;
      cmdAccepted <= 1'b1;
      pendingCmdAddr <= 26'h0;
      pendingCmdData <= 32'h0;
      pendingCmdIsWrite <= 1'b0;
      readOcache <= 1'b0;
      ocWasGetfield <= 1'b0;
      wasHwo <= 1'b0;
      handleAddrReg <= 24'h0;
    end else begin
      jbcWrEnReg <= 1'b0;
      if(when_BmbMemoryController_l364) begin
        readOcache <= 1'b0;
      end
      case(state_2)
        State_IDLE : begin
          if(io_memIn_addrWr) begin
            addrReg <= aoutAddr;
          end
          if(io_memIn_stidx) begin
            indexReg <= aoutAddr;
            wasStidx <= 1'b1;
          end
          if(when_BmbMemoryController_l402) begin
            valueReg <= io_aout;
          end
          if(memReadRequested) begin
            if(aoutIsIo) begin
              rdDataReg <= io_ioRdData;
            end else begin
              pendingCmdAddr <= ({2'd0,aoutAddr} <<< 2'd2);
              pendingCmdIsWrite <= 1'b0;
              cmdAccepted <= io_bmb_cmd_ready;
              state_2 <= State_READ_WAIT;
            end
          end else begin
            if(when_BmbMemoryController_l431) begin
              if(!addrIsIo) begin
                pendingCmdAddr <= ({2'd0,addrReg} <<< 2'd2);
                pendingCmdData <= io_aout;
                pendingCmdIsWrite <= 1'b1;
                cmdAccepted <= io_bmb_cmd_ready;
                state_2 <= State_WRITE_WAIT;
              end
            end else begin
              if(io_memIn_putstatic) begin
                addrReg <= (wasStidx ? indexReg : _zz_addrReg);
                state_2 <= State_PS_WRITE;
              end else begin
                if(io_memIn_getstatic) begin
                  addrReg <= (wasStidx ? indexReg : _zz_addrReg_2);
                  state_2 <= State_GS_READ;
                end else begin
                  if(io_memIn_bcRd) begin
                    bcFillAddr <= {2'd0, _zz_bcFillAddr_1};
                    bcFillLen <= _zz_bcFillLen[9:0];
                    bcFillCount <= 10'h0;
                    state_2 <= State_BC_CACHE_CHECK;
                  end else begin
                    if(io_memIn_iaload) begin
                      addrReg <= io_bout[23 : 0];
                      handleIndex <= aoutAddr;
                      indexReg <= aoutAddr;
                      handleIsWrite <= 1'b0;
                      handleIsArray <= 1'b1;
                      state_2 <= State_HANDLE_READ;
                    end else begin
                      if(io_memIn_getfield) begin
                        if(when_BmbMemoryController_l492) begin
                          readOcache <= 1'b1;
                          wasStidx <= 1'b0;
                        end else begin
                          addrReg <= aoutAddr;
                          handleIndex <= (wasStidx ? indexReg : _zz_handleIndex);
                          handleIsWrite <= 1'b0;
                          handleIsArray <= 1'b0;
                          ocWasGetfield <= 1'b1;
                          state_2 <= State_HANDLE_READ;
                        end
                      end else begin
                        if(io_memIn_putfield) begin
                          addrReg <= io_bout[23 : 0];
                          handleIndex <= (wasStidx ? indexReg : _zz_handleIndex_2);
                          handleIsWrite <= 1'b1;
                          handleIsArray <= 1'b0;
                          handleWriteData <= io_aout;
                          ocWasGetfield <= 1'b0;
                          state_2 <= State_HANDLE_READ;
                        end else begin
                          if(io_memIn_copy) begin
                            baseReg <= io_bout[23 : 0];
                            posReg <= (aoutAddr + io_bout[23 : 0]);
                            cpStopBit <= io_aout[31];
                            state_2 <= State_CP_SETUP;
                          end else begin
                            if(io_memIn_iastore) begin
                              handleIsWrite <= 1'b1;
                              handleIsArray <= 1'b1;
                              state_2 <= State_IAST_WAIT;
                            end
                          end
                        end
                      end
                    end
                  end
                end
              end
            end
          end
        end
        State_READ_WAIT : begin
          if(io_memIn_addrWr) begin
            addrReg <= aoutAddr;
          end
          if(when_BmbMemoryController_l556) begin
            rdDataReg <= io_ioRdData;
          end
          if(when_BmbMemoryController_l570) begin
            if(io_bmb_cmd_fire) begin
              cmdAccepted <= 1'b1;
            end
          end
          if(io_bmb_rsp_fire) begin
            rdDataReg <= io_bmb_rsp_payload_fragment_data;
            state_2 <= State_IDLE;
          end
        end
        State_WRITE_WAIT : begin
          if(io_memIn_addrWr) begin
            addrReg <= aoutAddr;
          end
          if(when_BmbMemoryController_l593) begin
            rdDataReg <= io_ioRdData;
          end
          if(when_BmbMemoryController_l607) begin
            if(io_bmb_cmd_fire) begin
              cmdAccepted <= 1'b1;
            end
          end
          if(io_bmb_rsp_fire) begin
            state_2 <= State_IDLE;
          end
        end
        State_IAST_WAIT : begin
          addrReg <= io_bout[23 : 0];
          handleIndex <= aoutAddr;
          handleWriteData <= valueReg;
          state_2 <= State_HANDLE_READ;
        end
        State_BC_CACHE_CHECK : begin
          if(methodCache_1_io_rdy) begin
            bcCacheStartReg <= methodCache_1_io_bcStart;
            bcStartReg <= {1'd0, _zz_bcStartReg};
            if(methodCache_1_io_inCache) begin
              state_2 <= State_IDLE;
            end else begin
              state_2 <= State_BC_FILL_R1;
            end
          end
        end
        State_BC_FILL_R1 : begin
          if(io_bmb_cmd_fire) begin
            bcFillAddr <= (bcFillAddr + _zz_bcFillAddr_2);
            state_2 <= State_BC_FILL_LOOP;
          end
        end
        State_BC_FILL_LOOP : begin
          if(io_bmb_rsp_fire) begin
            jbcWrDataReg <= {{{io_bmb_rsp_payload_fragment_data[7 : 0],io_bmb_rsp_payload_fragment_data[15 : 8]},io_bmb_rsp_payload_fragment_data[23 : 16]},io_bmb_rsp_payload_fragment_data[31 : 24]};
            jbcWrEnReg <= 1'b1;
            jbcWrAddrReg <= _zz_jbcWrAddrReg[8:0];
            bcFillCount <= (bcFillCount + 10'h001);
            if(io_bmb_rsp_payload_last) begin
              if(when_BmbMemoryController_l755) begin
                state_2 <= State_IDLE;
              end else begin
                state_2 <= State_BC_FILL_R1;
              end
            end
          end
        end
        State_BC_FILL_CMD : begin
          state_2 <= State_IDLE;
        end
        State_HANDLE_READ : begin
          handleAddrReg <= addrReg;
          if(io_bmb_cmd_fire) begin
            state_2 <= State_HANDLE_WAIT;
          end
        end
        State_HANDLE_WAIT : begin
          if(io_bmb_rsp_fire) begin
            handleDataPtr <= io_bmb_rsp_payload_fragment_data[23 : 0];
            wasHwo <= (_zz_wasHwo[23 : 22] == 2'b11);
            state_2 <= State_HANDLE_CALC;
          end
        end
        State_HANDLE_CALC : begin
          addrReg <= (handleDataPtr + handleIndex);
          state_2 <= State_HANDLE_ACCESS;
        end
        State_HANDLE_ACCESS : begin
          if(addrIsIo) begin
            if(!handleIsWrite) begin
              rdDataReg <= io_ioRdData;
            end
            wasStidx <= 1'b0;
            state_2 <= State_IDLE;
          end else begin
            if(io_bmb_cmd_fire) begin
              state_2 <= State_HANDLE_DATA_WAIT;
            end
          end
        end
        State_HANDLE_DATA_WAIT : begin
          if(io_bmb_rsp_fire) begin
            if(when_BmbMemoryController_l885) begin
              rdDataReg <= io_bmb_rsp_payload_fragment_data;
            end
            wasStidx <= 1'b0;
            state_2 <= State_IDLE;
          end
        end
        State_HANDLE_BOUND_READ : begin
          if(io_bmb_cmd_fire) begin
            state_2 <= State_HANDLE_BOUND_WAIT;
          end
        end
        State_HANDLE_BOUND_WAIT : begin
          if(io_bmb_rsp_fire) begin
            if(when_BmbMemoryController_l927) begin
              state_2 <= State_AB_EXC;
            end else begin
              state_2 <= State_HANDLE_CALC;
            end
          end
        end
        State_NP_EXC : begin
          wasStidx <= 1'b0;
          state_2 <= State_IDLE;
        end
        State_AB_EXC : begin
          wasStidx <= 1'b0;
          state_2 <= State_IDLE;
        end
        State_GS_READ : begin
          if(io_bmb_cmd_fire) begin
            state_2 <= State_LAST;
          end
        end
        State_PS_WRITE : begin
          if(io_bmb_cmd_fire) begin
            state_2 <= State_LAST;
          end
        end
        State_LAST : begin
          if(io_bmb_rsp_fire) begin
            rdDataReg <= io_bmb_rsp_payload_fragment_data;
            wasStidx <= 1'b0;
            state_2 <= State_IDLE;
          end
        end
        State_CP_SETUP : begin
          offsetReg <= (io_bout[23 : 0] - baseReg);
          if(cpStopBit) begin
            state_2 <= State_CP_STOP;
          end else begin
            state_2 <= State_CP_READ;
          end
        end
        State_CP_READ : begin
          if(io_bmb_cmd_fire) begin
            state_2 <= State_CP_READ_WAIT;
          end
        end
        State_CP_READ_WAIT : begin
          if(io_bmb_rsp_fire) begin
            valueReg <= io_bmb_rsp_payload_fragment_data;
            addrReg <= (posReg + offsetReg);
            posReg <= (posReg + 24'h000001);
            state_2 <= State_CP_WRITE;
          end
        end
        State_CP_WRITE : begin
          if(io_bmb_cmd_fire) begin
            state_2 <= State_LAST;
          end
        end
        default : begin
          posReg <= baseReg;
          state_2 <= State_IDLE;
        end
      endcase
    end
  end


endmodule

module JopPipeline (
  input  wire [31:0]   io_memRdData,
  input  wire [11:0]   io_memBcStart,
  input  wire          io_memBusy,
  input  wire [8:0]    io_jbcWrAddr,
  input  wire [31:0]   io_jbcWrData,
  input  wire          io_jbcWrEn,
  output wire          io_memCtrl_rd,
  output wire          io_memCtrl_rdc,
  output wire          io_memCtrl_rdf,
  output wire          io_memCtrl_wr,
  output wire          io_memCtrl_wrf,
  output wire          io_memCtrl_addrWr,
  output wire          io_memCtrl_bcRd,
  output wire          io_memCtrl_stidx,
  output wire          io_memCtrl_iaload,
  output wire          io_memCtrl_iastore,
  output wire          io_memCtrl_getfield,
  output wire          io_memCtrl_putfield,
  output wire          io_memCtrl_putref,
  output wire          io_memCtrl_getstatic,
  output wire          io_memCtrl_putstatic,
  output wire          io_memCtrl_copy,
  output wire          io_memCtrl_cinval,
  output wire          io_memCtrl_atmstart,
  output wire          io_memCtrl_atmend,
  output wire [15:0]   io_memCtrl_bcopd,
  output wire [31:0]   io_aout,
  output wire [31:0]   io_bout,
  output wire [15:0]   io_bcopd,
  input  wire          io_irq,
  input  wire          io_irqEna,
  input  wire          io_exc,
  output wire [10:0]   io_pc,
  output wire [11:0]   io_jpc,
  output wire [9:0]    io_instr,
  output wire          io_jfetch,
  output wire          io_jopdfetch,
  output wire          io_memBusyOut,
  output wire          io_debugBcRd,
  output wire          io_debugAddrWr,
  output wire          io_debugRdc,
  output wire          io_debugRd,
  input  wire [7:0]    io_debugRamAddr,
  output wire [31:0]   io_debugRamData,
  input  wire          reset,
  input  wire          clk
);

  wire                fetch_io_bsy;
  wire       [7:0]    stack_io_dirAddr;
  wire       [31:0]   mul_1_io_ain;
  wire       [31:0]   mul_1_io_bin;
  wire                bcfetch_io_ack_irq;
  wire                bcfetch_io_ack_exc;
  wire       [10:0]   bcfetch_io_jpaddr;
  wire       [15:0]   bcfetch_io_opd;
  wire       [11:0]   bcfetch_io_jpc_out;
  wire                fetch_io_nxt;
  wire                fetch_io_opd;
  wire       [9:0]    fetch_io_dout;
  wire       [10:0]   fetch_io_pc_out;
  wire       [9:0]    fetch_io_ir_out;
  wire                decode_io_br;
  wire                decode_io_jmp;
  wire                decode_io_jbr;
  wire                decode_io_memIn_rd;
  wire                decode_io_memIn_wr;
  wire                decode_io_memIn_addrWr;
  wire                decode_io_memIn_bcRd;
  wire                decode_io_memIn_stidx;
  wire                decode_io_memIn_iaload;
  wire                decode_io_memIn_iastore;
  wire                decode_io_memIn_getfield;
  wire                decode_io_memIn_putfield;
  wire                decode_io_memIn_putref;
  wire                decode_io_memIn_getstatic;
  wire                decode_io_memIn_putstatic;
  wire                decode_io_memIn_rdc;
  wire                decode_io_memIn_rdf;
  wire                decode_io_memIn_wrf;
  wire                decode_io_memIn_copy;
  wire                decode_io_memIn_cinval;
  wire                decode_io_memIn_atmstart;
  wire                decode_io_memIn_atmend;
  wire       [15:0]   decode_io_memIn_bcopd;
  wire       [3:0]    decode_io_mmuInstr;
  wire       [7:0]    decode_io_dirAddr;
  wire                decode_io_mulWr;
  wire                decode_io_wrDly;
  wire                decode_io_selSub;
  wire                decode_io_selAmux;
  wire                decode_io_enaA;
  wire                decode_io_selBmux;
  wire       [1:0]    decode_io_selLog;
  wire       [1:0]    decode_io_selShf;
  wire       [2:0]    decode_io_selLmux;
  wire       [1:0]    decode_io_selImux;
  wire       [1:0]    decode_io_selRmux;
  wire       [1:0]    decode_io_selSmux;
  wire                decode_io_selMmux;
  wire       [2:0]    decode_io_selRda;
  wire       [2:0]    decode_io_selWra;
  wire                decode_io_wrEna;
  wire                decode_io_enaB;
  wire                decode_io_enaVp;
  wire                decode_io_enaJpc;
  wire                decode_io_enaAr;
  wire       [31:0]   stack_io_debugRamData;
  wire       [7:0]    stack_io_debugSp;
  wire       [7:0]    stack_io_debugWrAddr;
  wire                stack_io_debugWrEn;
  wire       [7:0]    stack_io_debugRdAddrReg;
  wire       [31:0]   stack_io_debugRamDout;
  wire                stack_io_spOv;
  wire                stack_io_zf;
  wire                stack_io_nf;
  wire                stack_io_eq;
  wire                stack_io_lt;
  wire       [31:0]   stack_io_aout;
  wire       [31:0]   stack_io_bout;
  wire       [31:0]   mul_1_io_dout;
  wire       [11:0]   _zz__zz_io_din;
  reg        [1:0]    dinMuxSel;
  reg        [31:0]   _zz_io_din;

  assign _zz__zz_io_din = io_memBcStart;
  BytecodeFetchStage bcfetch (
    .io_jpc_wr    (decode_io_enaJpc        ), //i
    .io_din       (stack_io_aout[31:0]     ), //i
    .io_jfetch    (fetch_io_nxt            ), //i
    .io_jopdfetch (fetch_io_opd            ), //i
    .io_jbr       (decode_io_jbr           ), //i
    .io_zf        (stack_io_zf             ), //i
    .io_nf        (stack_io_nf             ), //i
    .io_eq        (stack_io_eq             ), //i
    .io_lt        (stack_io_lt             ), //i
    .io_jbcWrAddr (io_jbcWrAddr[8:0]       ), //i
    .io_jbcWrData (io_jbcWrData[31:0]      ), //i
    .io_jbcWrEn   (io_jbcWrEn              ), //i
    .io_irq       (io_irq                  ), //i
    .io_exc       (io_exc                  ), //i
    .io_ena       (io_irqEna               ), //i
    .io_ack_irq   (bcfetch_io_ack_irq      ), //o
    .io_ack_exc   (bcfetch_io_ack_exc      ), //o
    .io_jpaddr    (bcfetch_io_jpaddr[10:0] ), //o
    .io_opd       (bcfetch_io_opd[15:0]    ), //o
    .io_jpc_out   (bcfetch_io_jpc_out[11:0]), //o
    .clk          (clk                     ), //i
    .reset        (reset                   )  //i
  );
  FetchStage fetch (
    .io_br     (decode_io_br           ), //i
    .io_jmp    (decode_io_jmp          ), //i
    .io_bsy    (fetch_io_bsy           ), //i
    .io_jpaddr (bcfetch_io_jpaddr[10:0]), //i
    .io_nxt    (fetch_io_nxt           ), //o
    .io_opd    (fetch_io_opd           ), //o
    .io_dout   (fetch_io_dout[9:0]     ), //o
    .io_pc_out (fetch_io_pc_out[10:0]  ), //o
    .io_ir_out (fetch_io_ir_out[9:0]   ), //o
    .reset     (reset                  ), //i
    .clk       (clk                    )  //i
  );
  DecodeStage decode (
    .io_instr           (fetch_io_dout[9:0]         ), //i
    .io_zf              (stack_io_zf                ), //i
    .io_nf              (stack_io_nf                ), //i
    .io_eq              (stack_io_eq                ), //i
    .io_lt              (stack_io_lt                ), //i
    .io_bcopd           (bcfetch_io_opd[15:0]       ), //i
    .io_br              (decode_io_br               ), //o
    .io_jmp             (decode_io_jmp              ), //o
    .io_jbr             (decode_io_jbr              ), //o
    .io_memIn_rd        (decode_io_memIn_rd         ), //o
    .io_memIn_wr        (decode_io_memIn_wr         ), //o
    .io_memIn_addrWr    (decode_io_memIn_addrWr     ), //o
    .io_memIn_bcRd      (decode_io_memIn_bcRd       ), //o
    .io_memIn_stidx     (decode_io_memIn_stidx      ), //o
    .io_memIn_iaload    (decode_io_memIn_iaload     ), //o
    .io_memIn_iastore   (decode_io_memIn_iastore    ), //o
    .io_memIn_getfield  (decode_io_memIn_getfield   ), //o
    .io_memIn_putfield  (decode_io_memIn_putfield   ), //o
    .io_memIn_putref    (decode_io_memIn_putref     ), //o
    .io_memIn_getstatic (decode_io_memIn_getstatic  ), //o
    .io_memIn_putstatic (decode_io_memIn_putstatic  ), //o
    .io_memIn_rdc       (decode_io_memIn_rdc        ), //o
    .io_memIn_rdf       (decode_io_memIn_rdf        ), //o
    .io_memIn_wrf       (decode_io_memIn_wrf        ), //o
    .io_memIn_copy      (decode_io_memIn_copy       ), //o
    .io_memIn_cinval    (decode_io_memIn_cinval     ), //o
    .io_memIn_atmstart  (decode_io_memIn_atmstart   ), //o
    .io_memIn_atmend    (decode_io_memIn_atmend     ), //o
    .io_memIn_bcopd     (decode_io_memIn_bcopd[15:0]), //o
    .io_mmuInstr        (decode_io_mmuInstr[3:0]    ), //o
    .io_dirAddr         (decode_io_dirAddr[7:0]     ), //o
    .io_mulWr           (decode_io_mulWr            ), //o
    .io_wrDly           (decode_io_wrDly            ), //o
    .io_selSub          (decode_io_selSub           ), //o
    .io_selAmux         (decode_io_selAmux          ), //o
    .io_enaA            (decode_io_enaA             ), //o
    .io_selBmux         (decode_io_selBmux          ), //o
    .io_selLog          (decode_io_selLog[1:0]      ), //o
    .io_selShf          (decode_io_selShf[1:0]      ), //o
    .io_selLmux         (decode_io_selLmux[2:0]     ), //o
    .io_selImux         (decode_io_selImux[1:0]     ), //o
    .io_selRmux         (decode_io_selRmux[1:0]     ), //o
    .io_selSmux         (decode_io_selSmux[1:0]     ), //o
    .io_selMmux         (decode_io_selMmux          ), //o
    .io_selRda          (decode_io_selRda[2:0]      ), //o
    .io_selWra          (decode_io_selWra[2:0]      ), //o
    .io_wrEna           (decode_io_wrEna            ), //o
    .io_enaB            (decode_io_enaB             ), //o
    .io_enaVp           (decode_io_enaVp            ), //o
    .io_enaJpc          (decode_io_enaJpc           ), //o
    .io_enaAr           (decode_io_enaAr            ), //o
    .clk                (clk                        ), //i
    .reset              (reset                      )  //i
  );
  StackStage stack (
    .io_din            (_zz_io_din[31:0]            ), //i
    .io_dirAddr        (stack_io_dirAddr[7:0]       ), //i
    .io_opd            (bcfetch_io_opd[15:0]        ), //i
    .io_jpc            (bcfetch_io_jpc_out[11:0]    ), //i
    .io_selSub         (decode_io_selSub            ), //i
    .io_selAmux        (decode_io_selAmux           ), //i
    .io_enaA           (decode_io_enaA              ), //i
    .io_selBmux        (decode_io_selBmux           ), //i
    .io_selLog         (decode_io_selLog[1:0]       ), //i
    .io_selShf         (decode_io_selShf[1:0]       ), //i
    .io_selLmux        (decode_io_selLmux[2:0]      ), //i
    .io_selImux        (decode_io_selImux[1:0]      ), //i
    .io_selRmux        (decode_io_selRmux[1:0]      ), //i
    .io_selSmux        (decode_io_selSmux[1:0]      ), //i
    .io_selMmux        (decode_io_selMmux           ), //i
    .io_selRda         (decode_io_selRda[2:0]       ), //i
    .io_selWra         (decode_io_selWra[2:0]       ), //i
    .io_wrEna          (decode_io_wrEna             ), //i
    .io_enaB           (decode_io_enaB              ), //i
    .io_enaVp          (decode_io_enaVp             ), //i
    .io_enaAr          (decode_io_enaAr             ), //i
    .io_debugRamAddr   (io_debugRamAddr[7:0]        ), //i
    .io_debugRamData   (stack_io_debugRamData[31:0] ), //o
    .io_debugRamWrAddr (8'h0                        ), //i
    .io_debugRamWrData (32'h0                       ), //i
    .io_debugRamWrEn   (1'b0                        ), //i
    .io_debugSp        (stack_io_debugSp[7:0]       ), //o
    .io_debugWrAddr    (stack_io_debugWrAddr[7:0]   ), //o
    .io_debugWrEn      (stack_io_debugWrEn          ), //o
    .io_debugRdAddrReg (stack_io_debugRdAddrReg[7:0]), //o
    .io_debugRamDout   (stack_io_debugRamDout[31:0] ), //o
    .io_spOv           (stack_io_spOv               ), //o
    .io_zf             (stack_io_zf                 ), //o
    .io_nf             (stack_io_nf                 ), //o
    .io_eq             (stack_io_eq                 ), //o
    .io_lt             (stack_io_lt                 ), //o
    .io_aout           (stack_io_aout[31:0]         ), //o
    .io_bout           (stack_io_bout[31:0]         ), //o
    .clk               (clk                         ), //i
    .reset             (reset                       )  //i
  );
  Mul mul_1 (
    .io_ain  (mul_1_io_ain[31:0] ), //i
    .io_bin  (mul_1_io_bin[31:0] ), //i
    .io_wr   (decode_io_mulWr    ), //i
    .io_dout (mul_1_io_dout[31:0]), //o
    .clk     (clk                ), //i
    .reset   (reset              )  //i
  );
  assign fetch_io_bsy = (decode_io_wrDly || io_memBusy);
  always @(*) begin
    case(dinMuxSel)
      2'b00 : begin
        _zz_io_din = io_memRdData;
      end
      2'b01 : begin
        _zz_io_din = mul_1_io_dout;
      end
      2'b10 : begin
        _zz_io_din = {20'd0, _zz__zz_io_din};
      end
      default : begin
        _zz_io_din = 32'h0;
      end
    endcase
  end

  assign stack_io_dirAddr = decode_io_dirAddr;
  assign io_debugRamData = stack_io_debugRamData;
  assign mul_1_io_ain = stack_io_aout;
  assign mul_1_io_bin = stack_io_bout;
  assign io_memCtrl_rd = decode_io_memIn_rd;
  assign io_memCtrl_rdc = decode_io_memIn_rdc;
  assign io_memCtrl_rdf = decode_io_memIn_rdf;
  assign io_memCtrl_wr = decode_io_memIn_wr;
  assign io_memCtrl_wrf = decode_io_memIn_wrf;
  assign io_memCtrl_addrWr = decode_io_memIn_addrWr;
  assign io_memCtrl_bcRd = decode_io_memIn_bcRd;
  assign io_memCtrl_stidx = decode_io_memIn_stidx;
  assign io_memCtrl_iaload = decode_io_memIn_iaload;
  assign io_memCtrl_iastore = decode_io_memIn_iastore;
  assign io_memCtrl_getfield = decode_io_memIn_getfield;
  assign io_memCtrl_putfield = decode_io_memIn_putfield;
  assign io_memCtrl_putref = decode_io_memIn_putref;
  assign io_memCtrl_getstatic = decode_io_memIn_getstatic;
  assign io_memCtrl_putstatic = decode_io_memIn_putstatic;
  assign io_memCtrl_copy = decode_io_memIn_copy;
  assign io_memCtrl_cinval = decode_io_memIn_cinval;
  assign io_memCtrl_atmstart = decode_io_memIn_atmstart;
  assign io_memCtrl_atmend = decode_io_memIn_atmend;
  assign io_memCtrl_bcopd = decode_io_memIn_bcopd;
  assign io_aout = stack_io_aout;
  assign io_bout = stack_io_bout;
  assign io_bcopd = bcfetch_io_opd;
  assign io_pc = fetch_io_pc_out;
  assign io_jpc = bcfetch_io_jpc_out;
  assign io_instr = fetch_io_dout;
  assign io_jfetch = fetch_io_nxt;
  assign io_jopdfetch = fetch_io_opd;
  assign io_memBusyOut = io_memBusy;
  assign io_debugBcRd = decode_io_memIn_bcRd;
  assign io_debugAddrWr = decode_io_memIn_addrWr;
  assign io_debugRdc = decode_io_memIn_rdc;
  assign io_debugRd = decode_io_memIn_rd;
  always @(posedge clk or posedge reset) begin
    if(reset) begin
      dinMuxSel <= 2'b00;
    end else begin
      dinMuxSel <= fetch_io_ir_out[1 : 0];
    end
  end


endmodule

module StreamFifoLowLatency (
  input  wire          io_push_valid,
  output wire          io_push_ready,
  input  wire [15:0]   io_push_payload_data,
  input  wire [3:0]    io_push_payload_context_context,
  input  wire          io_push_payload_context_isHigh,
  output wire          io_pop_valid,
  input  wire          io_pop_ready,
  output wire [15:0]   io_pop_payload_data,
  output wire [3:0]    io_pop_payload_context_context,
  output wire          io_pop_payload_context_isHigh,
  input  wire          io_flush,
  output wire [1:0]    io_occupancy,
  output wire [1:0]    io_availability,
  input  wire          clk,
  input  wire          reset
);

  wire                fifo_io_push_ready;
  wire                fifo_io_pop_valid;
  wire       [15:0]   fifo_io_pop_payload_data;
  wire       [3:0]    fifo_io_pop_payload_context_context;
  wire                fifo_io_pop_payload_context_isHigh;
  wire       [1:0]    fifo_io_occupancy;
  wire       [1:0]    fifo_io_availability;

  StreamFifo fifo (
    .io_push_valid                   (io_push_valid                           ), //i
    .io_push_ready                   (fifo_io_push_ready                      ), //o
    .io_push_payload_data            (io_push_payload_data[15:0]              ), //i
    .io_push_payload_context_context (io_push_payload_context_context[3:0]    ), //i
    .io_push_payload_context_isHigh  (io_push_payload_context_isHigh          ), //i
    .io_pop_valid                    (fifo_io_pop_valid                       ), //o
    .io_pop_ready                    (io_pop_ready                            ), //i
    .io_pop_payload_data             (fifo_io_pop_payload_data[15:0]          ), //o
    .io_pop_payload_context_context  (fifo_io_pop_payload_context_context[3:0]), //o
    .io_pop_payload_context_isHigh   (fifo_io_pop_payload_context_isHigh      ), //o
    .io_flush                        (io_flush                                ), //i
    .io_occupancy                    (fifo_io_occupancy[1:0]                  ), //o
    .io_availability                 (fifo_io_availability[1:0]               ), //o
    .clk                             (clk                                     ), //i
    .reset                           (reset                                   )  //i
  );
  assign io_push_ready = fifo_io_push_ready;
  assign io_pop_valid = fifo_io_pop_valid;
  assign io_pop_payload_data = fifo_io_pop_payload_data;
  assign io_pop_payload_context_context = fifo_io_pop_payload_context_context;
  assign io_pop_payload_context_isHigh = fifo_io_pop_payload_context_isHigh;
  assign io_occupancy = fifo_io_occupancy;
  assign io_availability = fifo_io_availability;

endmodule

module ObjectCache (
  input  wire [23:0]   io_handle,
  input  wire [7:0]    io_fieldIdx,
  input  wire          io_chkGf,
  input  wire          io_chkPf,
  output wire          io_hit,
  output wire [31:0]   io_dout,
  input  wire          io_wrGf,
  input  wire          io_wrPf,
  input  wire [31:0]   io_gfVal,
  input  wire [31:0]   io_pfVal,
  input  wire          io_inval,
  input  wire          clk,
  input  wire          reset
);

  reg        [31:0]   dataRam_spinal_port0;
  wire       [3:0]    _zz_when_ObjectCache_l110;
  wire       [3:0]    _zz_when_ObjectCache_l110_1;
  wire       [3:0]    _zz_when_ObjectCache_l110_2;
  wire       [3:0]    _zz_when_ObjectCache_l110_3;
  wire       [3:0]    _zz_when_ObjectCache_l110_4;
  wire       [3:0]    _zz_when_ObjectCache_l110_5;
  wire       [3:0]    _zz_when_ObjectCache_l110_6;
  wire       [3:0]    _zz_when_ObjectCache_l110_7;
  wire       [3:0]    _zz_when_ObjectCache_l110_8;
  wire       [3:0]    _zz_when_ObjectCache_l110_9;
  wire       [3:0]    _zz_when_ObjectCache_l110_10;
  wire       [3:0]    _zz_when_ObjectCache_l110_11;
  wire       [3:0]    _zz_when_ObjectCache_l110_12;
  wire       [3:0]    _zz_when_ObjectCache_l110_13;
  wire       [3:0]    _zz_when_ObjectCache_l110_14;
  wire       [3:0]    _zz_when_ObjectCache_l110_15;
  wire       [3:0]    _zz_when_ObjectCache_l110_16;
  wire       [3:0]    _zz_when_ObjectCache_l110_17;
  wire       [3:0]    _zz_when_ObjectCache_l110_18;
  wire       [3:0]    _zz_when_ObjectCache_l110_19;
  wire       [3:0]    _zz_when_ObjectCache_l110_20;
  wire       [3:0]    _zz_when_ObjectCache_l110_21;
  wire       [3:0]    _zz_when_ObjectCache_l110_22;
  wire       [3:0]    _zz_when_ObjectCache_l110_23;
  wire       [3:0]    _zz_when_ObjectCache_l110_24;
  wire       [3:0]    _zz_when_ObjectCache_l110_25;
  wire       [3:0]    _zz_when_ObjectCache_l110_26;
  wire       [3:0]    _zz_when_ObjectCache_l110_27;
  wire       [3:0]    _zz_when_ObjectCache_l110_28;
  wire       [3:0]    _zz_when_ObjectCache_l110_29;
  wire       [3:0]    _zz_when_ObjectCache_l110_30;
  wire       [3:0]    _zz_when_ObjectCache_l110_31;
  wire       [3:0]    _zz_when_ObjectCache_l110_32;
  wire       [3:0]    _zz_when_ObjectCache_l110_33;
  wire       [3:0]    _zz_when_ObjectCache_l110_34;
  wire       [3:0]    _zz_when_ObjectCache_l110_35;
  wire       [3:0]    _zz_when_ObjectCache_l110_36;
  wire       [3:0]    _zz_when_ObjectCache_l110_37;
  wire       [3:0]    _zz_when_ObjectCache_l110_38;
  wire       [3:0]    _zz_when_ObjectCache_l110_39;
  wire       [3:0]    _zz_when_ObjectCache_l110_40;
  wire       [3:0]    _zz_when_ObjectCache_l110_41;
  wire       [3:0]    _zz_when_ObjectCache_l110_42;
  wire       [3:0]    _zz_when_ObjectCache_l110_43;
  wire       [3:0]    _zz_when_ObjectCache_l110_44;
  wire       [3:0]    _zz_when_ObjectCache_l110_45;
  wire       [3:0]    _zz_when_ObjectCache_l110_46;
  wire       [3:0]    _zz_when_ObjectCache_l110_47;
  wire       [3:0]    _zz_when_ObjectCache_l110_48;
  wire       [3:0]    _zz_when_ObjectCache_l110_49;
  wire       [3:0]    _zz_when_ObjectCache_l110_50;
  wire       [3:0]    _zz_when_ObjectCache_l110_51;
  wire       [3:0]    _zz_when_ObjectCache_l110_52;
  wire       [3:0]    _zz_when_ObjectCache_l110_53;
  wire       [3:0]    _zz_when_ObjectCache_l110_54;
  wire       [3:0]    _zz_when_ObjectCache_l110_55;
  wire       [3:0]    _zz_when_ObjectCache_l110_56;
  wire       [3:0]    _zz_when_ObjectCache_l110_57;
  wire       [3:0]    _zz_when_ObjectCache_l110_58;
  wire       [3:0]    _zz_when_ObjectCache_l110_59;
  wire       [3:0]    _zz_when_ObjectCache_l110_60;
  wire       [3:0]    _zz_when_ObjectCache_l110_61;
  wire       [3:0]    _zz_when_ObjectCache_l110_62;
  wire       [3:0]    _zz_when_ObjectCache_l110_63;
  wire                _zz_dataRam_port;
  wire                _zz_ramDout;
  reg        [7:0]    _zz__zz_valid_0;
  reg                 _zz_1;
  reg                 _zz_lineEnc;
  reg                 _zz_lineEnc_1;
  reg                 _zz_lineEnc_2;
  reg                 _zz_lineEnc_3;
  reg                 _zz_lineEnc_4;
  reg                 _zz_lineEnc_5;
  reg                 _zz_lineEnc_6;
  reg                 _zz_lineEnc_7;
  reg                 _zz_lineEnc_8;
  reg                 _zz_lineEnc_9;
  reg                 _zz_lineEnc_10;
  reg                 _zz_lineEnc_11;
  reg                 _zz_lineEnc_12;
  reg                 _zz_lineEnc_13;
  reg                 _zz_lineEnc_14;
  reg                 _zz_lineEnc_15;
  reg                 _zz_lineEnc_16;
  reg                 _zz_lineEnc_17;
  reg                 _zz_lineEnc_18;
  reg                 _zz_lineEnc_19;
  reg                 _zz_lineEnc_20;
  reg                 _zz_lineEnc_21;
  reg                 _zz_lineEnc_22;
  reg                 _zz_lineEnc_23;
  reg                 _zz_lineEnc_24;
  reg                 _zz_lineEnc_25;
  reg                 _zz_lineEnc_26;
  reg                 _zz_lineEnc_27;
  reg                 _zz_lineEnc_28;
  reg                 _zz_lineEnc_29;
  reg                 _zz_lineEnc_30;
  reg                 _zz_lineEnc_31;
  reg                 _zz_lineEnc_32;
  reg                 _zz_lineEnc_33;
  reg                 _zz_lineEnc_34;
  reg                 _zz_lineEnc_35;
  reg                 _zz_lineEnc_36;
  reg                 _zz_lineEnc_37;
  reg                 _zz_lineEnc_38;
  reg                 _zz_lineEnc_39;
  reg                 _zz_lineEnc_40;
  reg                 _zz_lineEnc_41;
  reg                 _zz_lineEnc_42;
  reg                 _zz_lineEnc_43;
  reg                 _zz_lineEnc_44;
  reg                 _zz_lineEnc_45;
  reg                 _zz_lineEnc_46;
  reg                 _zz_lineEnc_47;
  reg                 _zz_lineEnc_48;
  reg                 _zz_lineEnc_49;
  reg                 _zz_lineEnc_50;
  reg                 _zz_lineEnc_51;
  reg                 _zz_lineEnc_52;
  reg                 _zz_lineEnc_53;
  reg                 _zz_lineEnc_54;
  reg                 _zz_lineEnc_55;
  reg                 _zz_lineEnc_56;
  reg                 _zz_lineEnc_57;
  reg                 _zz_lineEnc_58;
  reg                 _zz_lineEnc_59;
  reg                 _zz_lineEnc_60;
  reg                 _zz_lineEnc_61;
  reg                 _zz_lineEnc_62;
  reg                 _zz_lineEnc_63;
  reg        [23:0]   tag_0;
  reg        [23:0]   tag_1;
  reg        [23:0]   tag_2;
  reg        [23:0]   tag_3;
  reg        [23:0]   tag_4;
  reg        [23:0]   tag_5;
  reg        [23:0]   tag_6;
  reg        [23:0]   tag_7;
  reg        [23:0]   tag_8;
  reg        [23:0]   tag_9;
  reg        [23:0]   tag_10;
  reg        [23:0]   tag_11;
  reg        [23:0]   tag_12;
  reg        [23:0]   tag_13;
  reg        [23:0]   tag_14;
  reg        [23:0]   tag_15;
  reg        [7:0]    valid_0;
  reg        [7:0]    valid_1;
  reg        [7:0]    valid_2;
  reg        [7:0]    valid_3;
  reg        [7:0]    valid_4;
  reg        [7:0]    valid_5;
  reg        [7:0]    valid_6;
  reg        [7:0]    valid_7;
  reg        [7:0]    valid_8;
  reg        [7:0]    valid_9;
  reg        [7:0]    valid_10;
  reg        [7:0]    valid_11;
  reg        [7:0]    valid_12;
  reg        [7:0]    valid_13;
  reg        [7:0]    valid_14;
  reg        [7:0]    valid_15;
  reg        [3:0]    nxt;
  wire       [2:0]    idx;
  reg        [15:0]   hitVec;
  reg        [15:0]   hitTagVec;
  wire                _zz_hitVec;
  wire                _zz_hitVec_1;
  wire                _zz_hitVec_2;
  wire                _zz_hitVec_3;
  wire                _zz_hitVec_4;
  wire                _zz_hitVec_5;
  wire                _zz_hitVec_6;
  wire                _zz_hitVec_7;
  wire                _zz_hitVec_8;
  wire                _zz_hitVec_9;
  wire                _zz_hitVec_10;
  wire                _zz_hitVec_11;
  wire                _zz_hitVec_12;
  wire                _zz_hitVec_13;
  wire                _zz_hitVec_14;
  wire                _zz_hitVec_15;
  wire                cacheable;
  reg        [3:0]    lineEnc;
  wire                when_ObjectCache_l110;
  wire                when_ObjectCache_l110_1;
  wire                when_ObjectCache_l110_2;
  wire                when_ObjectCache_l110_3;
  wire                when_ObjectCache_l110_4;
  wire                when_ObjectCache_l110_5;
  wire                when_ObjectCache_l110_6;
  wire                when_ObjectCache_l110_7;
  wire                when_ObjectCache_l110_8;
  wire                when_ObjectCache_l110_9;
  wire                when_ObjectCache_l110_10;
  wire                when_ObjectCache_l110_11;
  wire                when_ObjectCache_l110_12;
  wire                when_ObjectCache_l110_13;
  wire                when_ObjectCache_l110_14;
  wire                when_ObjectCache_l110_15;
  wire                when_ObjectCache_l110_16;
  wire                when_ObjectCache_l110_17;
  wire                when_ObjectCache_l110_18;
  wire                when_ObjectCache_l110_19;
  wire                when_ObjectCache_l110_20;
  wire                when_ObjectCache_l110_21;
  wire                when_ObjectCache_l110_22;
  wire                when_ObjectCache_l110_23;
  wire                when_ObjectCache_l110_24;
  wire                when_ObjectCache_l110_25;
  wire                when_ObjectCache_l110_26;
  wire                when_ObjectCache_l110_27;
  wire                when_ObjectCache_l110_28;
  wire                when_ObjectCache_l110_29;
  wire                when_ObjectCache_l110_30;
  wire                when_ObjectCache_l110_31;
  wire                when_ObjectCache_l110_32;
  wire                when_ObjectCache_l110_33;
  wire                when_ObjectCache_l110_34;
  wire                when_ObjectCache_l110_35;
  wire                when_ObjectCache_l110_36;
  wire                when_ObjectCache_l110_37;
  wire                when_ObjectCache_l110_38;
  wire                when_ObjectCache_l110_39;
  wire                when_ObjectCache_l110_40;
  wire                when_ObjectCache_l110_41;
  wire                when_ObjectCache_l110_42;
  wire                when_ObjectCache_l110_43;
  wire                when_ObjectCache_l110_44;
  wire                when_ObjectCache_l110_45;
  wire                when_ObjectCache_l110_46;
  wire                when_ObjectCache_l110_47;
  wire                when_ObjectCache_l110_48;
  wire                when_ObjectCache_l110_49;
  wire                when_ObjectCache_l110_50;
  wire                when_ObjectCache_l110_51;
  wire                when_ObjectCache_l110_52;
  wire                when_ObjectCache_l110_53;
  wire                when_ObjectCache_l110_54;
  wire                when_ObjectCache_l110_55;
  wire                when_ObjectCache_l110_56;
  wire                when_ObjectCache_l110_57;
  wire                when_ObjectCache_l110_58;
  wire                when_ObjectCache_l110_59;
  wire                when_ObjectCache_l110_60;
  wire                when_ObjectCache_l110_61;
  wire                when_ObjectCache_l110_62;
  wire                when_ObjectCache_l110_63;
  reg        [3:0]    lineReg;
  reg                 incNxtReg;
  reg                 hitTagReg;
  reg                 cacheableReg;
  reg        [23:0]   handleReg;
  reg        [7:0]    indexReg;
  wire                when_ObjectCache_l131;
  wire                when_ObjectCache_l140;
  wire       [6:0]    ramRdAddr;
  wire       [31:0]   ramDout;
  reg                 chkGfDly;
  reg        [31:0]   ramDoutStore;
  wire                updateCache;
  wire       [31:0]   ramDin;
  wire       [6:0]    ramWrAddr;
  wire       [15:0]   _zz_4;
  reg        [7:0]    _zz_valid_0;
  wire       [15:0]   _zz_5;
  wire                when_ObjectCache_l204;
  reg [31:0] dataRam [0:127];

  assign _zz_when_ObjectCache_l110 = 4'b0000;
  assign _zz_when_ObjectCache_l110_1 = 4'b0001;
  assign _zz_when_ObjectCache_l110_2 = 4'b0010;
  assign _zz_when_ObjectCache_l110_3 = 4'b0011;
  assign _zz_when_ObjectCache_l110_4 = 4'b0100;
  assign _zz_when_ObjectCache_l110_5 = 4'b0101;
  assign _zz_when_ObjectCache_l110_6 = 4'b0110;
  assign _zz_when_ObjectCache_l110_7 = 4'b0111;
  assign _zz_when_ObjectCache_l110_8 = 4'b1000;
  assign _zz_when_ObjectCache_l110_9 = 4'b1001;
  assign _zz_when_ObjectCache_l110_10 = 4'b1010;
  assign _zz_when_ObjectCache_l110_11 = 4'b1011;
  assign _zz_when_ObjectCache_l110_12 = 4'b1100;
  assign _zz_when_ObjectCache_l110_13 = 4'b1101;
  assign _zz_when_ObjectCache_l110_14 = 4'b1110;
  assign _zz_when_ObjectCache_l110_15 = 4'b1111;
  assign _zz_when_ObjectCache_l110_16 = 4'b0000;
  assign _zz_when_ObjectCache_l110_17 = 4'b0001;
  assign _zz_when_ObjectCache_l110_18 = 4'b0010;
  assign _zz_when_ObjectCache_l110_19 = 4'b0011;
  assign _zz_when_ObjectCache_l110_20 = 4'b0100;
  assign _zz_when_ObjectCache_l110_21 = 4'b0101;
  assign _zz_when_ObjectCache_l110_22 = 4'b0110;
  assign _zz_when_ObjectCache_l110_23 = 4'b0111;
  assign _zz_when_ObjectCache_l110_24 = 4'b1000;
  assign _zz_when_ObjectCache_l110_25 = 4'b1001;
  assign _zz_when_ObjectCache_l110_26 = 4'b1010;
  assign _zz_when_ObjectCache_l110_27 = 4'b1011;
  assign _zz_when_ObjectCache_l110_28 = 4'b1100;
  assign _zz_when_ObjectCache_l110_29 = 4'b1101;
  assign _zz_when_ObjectCache_l110_30 = 4'b1110;
  assign _zz_when_ObjectCache_l110_31 = 4'b1111;
  assign _zz_when_ObjectCache_l110_32 = 4'b0000;
  assign _zz_when_ObjectCache_l110_33 = 4'b0001;
  assign _zz_when_ObjectCache_l110_34 = 4'b0010;
  assign _zz_when_ObjectCache_l110_35 = 4'b0011;
  assign _zz_when_ObjectCache_l110_36 = 4'b0100;
  assign _zz_when_ObjectCache_l110_37 = 4'b0101;
  assign _zz_when_ObjectCache_l110_38 = 4'b0110;
  assign _zz_when_ObjectCache_l110_39 = 4'b0111;
  assign _zz_when_ObjectCache_l110_40 = 4'b1000;
  assign _zz_when_ObjectCache_l110_41 = 4'b1001;
  assign _zz_when_ObjectCache_l110_42 = 4'b1010;
  assign _zz_when_ObjectCache_l110_43 = 4'b1011;
  assign _zz_when_ObjectCache_l110_44 = 4'b1100;
  assign _zz_when_ObjectCache_l110_45 = 4'b1101;
  assign _zz_when_ObjectCache_l110_46 = 4'b1110;
  assign _zz_when_ObjectCache_l110_47 = 4'b1111;
  assign _zz_when_ObjectCache_l110_48 = 4'b0000;
  assign _zz_when_ObjectCache_l110_49 = 4'b0001;
  assign _zz_when_ObjectCache_l110_50 = 4'b0010;
  assign _zz_when_ObjectCache_l110_51 = 4'b0011;
  assign _zz_when_ObjectCache_l110_52 = 4'b0100;
  assign _zz_when_ObjectCache_l110_53 = 4'b0101;
  assign _zz_when_ObjectCache_l110_54 = 4'b0110;
  assign _zz_when_ObjectCache_l110_55 = 4'b0111;
  assign _zz_when_ObjectCache_l110_56 = 4'b1000;
  assign _zz_when_ObjectCache_l110_57 = 4'b1001;
  assign _zz_when_ObjectCache_l110_58 = 4'b1010;
  assign _zz_when_ObjectCache_l110_59 = 4'b1011;
  assign _zz_when_ObjectCache_l110_60 = 4'b1100;
  assign _zz_when_ObjectCache_l110_61 = 4'b1101;
  assign _zz_when_ObjectCache_l110_62 = 4'b1110;
  assign _zz_when_ObjectCache_l110_63 = 4'b1111;
  assign _zz_ramDout = 1'b1;
  always @(posedge clk) begin
    if(_zz_ramDout) begin
      dataRam_spinal_port0 <= dataRam[ramRdAddr];
    end
  end

  always @(posedge clk) begin
    if(_zz_1) begin
      dataRam[ramWrAddr] <= ramDin;
    end
  end

  always @(*) begin
    case(lineReg)
      4'b0000 : _zz__zz_valid_0 = valid_0;
      4'b0001 : _zz__zz_valid_0 = valid_1;
      4'b0010 : _zz__zz_valid_0 = valid_2;
      4'b0011 : _zz__zz_valid_0 = valid_3;
      4'b0100 : _zz__zz_valid_0 = valid_4;
      4'b0101 : _zz__zz_valid_0 = valid_5;
      4'b0110 : _zz__zz_valid_0 = valid_6;
      4'b0111 : _zz__zz_valid_0 = valid_7;
      4'b1000 : _zz__zz_valid_0 = valid_8;
      4'b1001 : _zz__zz_valid_0 = valid_9;
      4'b1010 : _zz__zz_valid_0 = valid_10;
      4'b1011 : _zz__zz_valid_0 = valid_11;
      4'b1100 : _zz__zz_valid_0 = valid_12;
      4'b1101 : _zz__zz_valid_0 = valid_13;
      4'b1110 : _zz__zz_valid_0 = valid_14;
      default : _zz__zz_valid_0 = valid_15;
    endcase
  end

  always @(*) begin
    _zz_1 = 1'b0;
    if(updateCache) begin
      _zz_1 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc = _zz_lineEnc_1;
    if(when_ObjectCache_l110_63) begin
      _zz_lineEnc = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_1 = _zz_lineEnc_2;
    if(when_ObjectCache_l110_62) begin
      _zz_lineEnc_1 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_2 = _zz_lineEnc_3;
    if(when_ObjectCache_l110_61) begin
      _zz_lineEnc_2 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_3 = _zz_lineEnc_4;
    if(when_ObjectCache_l110_60) begin
      _zz_lineEnc_3 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_4 = _zz_lineEnc_5;
    if(when_ObjectCache_l110_59) begin
      _zz_lineEnc_4 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_5 = _zz_lineEnc_6;
    if(when_ObjectCache_l110_58) begin
      _zz_lineEnc_5 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_6 = _zz_lineEnc_7;
    if(when_ObjectCache_l110_57) begin
      _zz_lineEnc_6 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_7 = _zz_lineEnc_8;
    if(when_ObjectCache_l110_56) begin
      _zz_lineEnc_7 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_8 = _zz_lineEnc_9;
    if(when_ObjectCache_l110_55) begin
      _zz_lineEnc_8 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_9 = _zz_lineEnc_10;
    if(when_ObjectCache_l110_54) begin
      _zz_lineEnc_9 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_10 = _zz_lineEnc_11;
    if(when_ObjectCache_l110_53) begin
      _zz_lineEnc_10 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_11 = _zz_lineEnc_12;
    if(when_ObjectCache_l110_52) begin
      _zz_lineEnc_11 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_12 = _zz_lineEnc_13;
    if(when_ObjectCache_l110_51) begin
      _zz_lineEnc_12 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_13 = _zz_lineEnc_14;
    if(when_ObjectCache_l110_50) begin
      _zz_lineEnc_13 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_14 = _zz_lineEnc_15;
    if(when_ObjectCache_l110_49) begin
      _zz_lineEnc_14 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_15 = 1'b0;
    if(when_ObjectCache_l110_48) begin
      _zz_lineEnc_15 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_16 = _zz_lineEnc_17;
    if(when_ObjectCache_l110_47) begin
      _zz_lineEnc_16 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_17 = _zz_lineEnc_18;
    if(when_ObjectCache_l110_46) begin
      _zz_lineEnc_17 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_18 = _zz_lineEnc_19;
    if(when_ObjectCache_l110_45) begin
      _zz_lineEnc_18 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_19 = _zz_lineEnc_20;
    if(when_ObjectCache_l110_44) begin
      _zz_lineEnc_19 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_20 = _zz_lineEnc_21;
    if(when_ObjectCache_l110_43) begin
      _zz_lineEnc_20 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_21 = _zz_lineEnc_22;
    if(when_ObjectCache_l110_42) begin
      _zz_lineEnc_21 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_22 = _zz_lineEnc_23;
    if(when_ObjectCache_l110_41) begin
      _zz_lineEnc_22 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_23 = _zz_lineEnc_24;
    if(when_ObjectCache_l110_40) begin
      _zz_lineEnc_23 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_24 = _zz_lineEnc_25;
    if(when_ObjectCache_l110_39) begin
      _zz_lineEnc_24 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_25 = _zz_lineEnc_26;
    if(when_ObjectCache_l110_38) begin
      _zz_lineEnc_25 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_26 = _zz_lineEnc_27;
    if(when_ObjectCache_l110_37) begin
      _zz_lineEnc_26 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_27 = _zz_lineEnc_28;
    if(when_ObjectCache_l110_36) begin
      _zz_lineEnc_27 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_28 = _zz_lineEnc_29;
    if(when_ObjectCache_l110_35) begin
      _zz_lineEnc_28 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_29 = _zz_lineEnc_30;
    if(when_ObjectCache_l110_34) begin
      _zz_lineEnc_29 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_30 = _zz_lineEnc_31;
    if(when_ObjectCache_l110_33) begin
      _zz_lineEnc_30 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_31 = 1'b0;
    if(when_ObjectCache_l110_32) begin
      _zz_lineEnc_31 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_32 = _zz_lineEnc_33;
    if(when_ObjectCache_l110_31) begin
      _zz_lineEnc_32 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_33 = _zz_lineEnc_34;
    if(when_ObjectCache_l110_30) begin
      _zz_lineEnc_33 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_34 = _zz_lineEnc_35;
    if(when_ObjectCache_l110_29) begin
      _zz_lineEnc_34 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_35 = _zz_lineEnc_36;
    if(when_ObjectCache_l110_28) begin
      _zz_lineEnc_35 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_36 = _zz_lineEnc_37;
    if(when_ObjectCache_l110_27) begin
      _zz_lineEnc_36 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_37 = _zz_lineEnc_38;
    if(when_ObjectCache_l110_26) begin
      _zz_lineEnc_37 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_38 = _zz_lineEnc_39;
    if(when_ObjectCache_l110_25) begin
      _zz_lineEnc_38 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_39 = _zz_lineEnc_40;
    if(when_ObjectCache_l110_24) begin
      _zz_lineEnc_39 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_40 = _zz_lineEnc_41;
    if(when_ObjectCache_l110_23) begin
      _zz_lineEnc_40 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_41 = _zz_lineEnc_42;
    if(when_ObjectCache_l110_22) begin
      _zz_lineEnc_41 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_42 = _zz_lineEnc_43;
    if(when_ObjectCache_l110_21) begin
      _zz_lineEnc_42 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_43 = _zz_lineEnc_44;
    if(when_ObjectCache_l110_20) begin
      _zz_lineEnc_43 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_44 = _zz_lineEnc_45;
    if(when_ObjectCache_l110_19) begin
      _zz_lineEnc_44 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_45 = _zz_lineEnc_46;
    if(when_ObjectCache_l110_18) begin
      _zz_lineEnc_45 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_46 = _zz_lineEnc_47;
    if(when_ObjectCache_l110_17) begin
      _zz_lineEnc_46 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_47 = 1'b0;
    if(when_ObjectCache_l110_16) begin
      _zz_lineEnc_47 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_48 = _zz_lineEnc_49;
    if(when_ObjectCache_l110_15) begin
      _zz_lineEnc_48 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_49 = _zz_lineEnc_50;
    if(when_ObjectCache_l110_14) begin
      _zz_lineEnc_49 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_50 = _zz_lineEnc_51;
    if(when_ObjectCache_l110_13) begin
      _zz_lineEnc_50 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_51 = _zz_lineEnc_52;
    if(when_ObjectCache_l110_12) begin
      _zz_lineEnc_51 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_52 = _zz_lineEnc_53;
    if(when_ObjectCache_l110_11) begin
      _zz_lineEnc_52 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_53 = _zz_lineEnc_54;
    if(when_ObjectCache_l110_10) begin
      _zz_lineEnc_53 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_54 = _zz_lineEnc_55;
    if(when_ObjectCache_l110_9) begin
      _zz_lineEnc_54 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_55 = _zz_lineEnc_56;
    if(when_ObjectCache_l110_8) begin
      _zz_lineEnc_55 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_56 = _zz_lineEnc_57;
    if(when_ObjectCache_l110_7) begin
      _zz_lineEnc_56 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_57 = _zz_lineEnc_58;
    if(when_ObjectCache_l110_6) begin
      _zz_lineEnc_57 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_58 = _zz_lineEnc_59;
    if(when_ObjectCache_l110_5) begin
      _zz_lineEnc_58 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_59 = _zz_lineEnc_60;
    if(when_ObjectCache_l110_4) begin
      _zz_lineEnc_59 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_60 = _zz_lineEnc_61;
    if(when_ObjectCache_l110_3) begin
      _zz_lineEnc_60 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_61 = _zz_lineEnc_62;
    if(when_ObjectCache_l110_2) begin
      _zz_lineEnc_61 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_62 = _zz_lineEnc_63;
    if(when_ObjectCache_l110_1) begin
      _zz_lineEnc_62 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_63 = 1'b0;
    if(when_ObjectCache_l110) begin
      _zz_lineEnc_63 = 1'b1;
    end
  end

  assign idx = io_fieldIdx[2 : 0];
  assign _zz_hitVec = (tag_0 == io_handle);
  always @(*) begin
    hitVec[0] = (_zz_hitVec && valid_0[idx]);
    hitVec[1] = (_zz_hitVec_1 && valid_1[idx]);
    hitVec[2] = (_zz_hitVec_2 && valid_2[idx]);
    hitVec[3] = (_zz_hitVec_3 && valid_3[idx]);
    hitVec[4] = (_zz_hitVec_4 && valid_4[idx]);
    hitVec[5] = (_zz_hitVec_5 && valid_5[idx]);
    hitVec[6] = (_zz_hitVec_6 && valid_6[idx]);
    hitVec[7] = (_zz_hitVec_7 && valid_7[idx]);
    hitVec[8] = (_zz_hitVec_8 && valid_8[idx]);
    hitVec[9] = (_zz_hitVec_9 && valid_9[idx]);
    hitVec[10] = (_zz_hitVec_10 && valid_10[idx]);
    hitVec[11] = (_zz_hitVec_11 && valid_11[idx]);
    hitVec[12] = (_zz_hitVec_12 && valid_12[idx]);
    hitVec[13] = (_zz_hitVec_13 && valid_13[idx]);
    hitVec[14] = (_zz_hitVec_14 && valid_14[idx]);
    hitVec[15] = (_zz_hitVec_15 && valid_15[idx]);
  end

  always @(*) begin
    hitTagVec[0] = (_zz_hitVec && (|valid_0));
    hitTagVec[1] = (_zz_hitVec_1 && (|valid_1));
    hitTagVec[2] = (_zz_hitVec_2 && (|valid_2));
    hitTagVec[3] = (_zz_hitVec_3 && (|valid_3));
    hitTagVec[4] = (_zz_hitVec_4 && (|valid_4));
    hitTagVec[5] = (_zz_hitVec_5 && (|valid_5));
    hitTagVec[6] = (_zz_hitVec_6 && (|valid_6));
    hitTagVec[7] = (_zz_hitVec_7 && (|valid_7));
    hitTagVec[8] = (_zz_hitVec_8 && (|valid_8));
    hitTagVec[9] = (_zz_hitVec_9 && (|valid_9));
    hitTagVec[10] = (_zz_hitVec_10 && (|valid_10));
    hitTagVec[11] = (_zz_hitVec_11 && (|valid_11));
    hitTagVec[12] = (_zz_hitVec_12 && (|valid_12));
    hitTagVec[13] = (_zz_hitVec_13 && (|valid_13));
    hitTagVec[14] = (_zz_hitVec_14 && (|valid_14));
    hitTagVec[15] = (_zz_hitVec_15 && (|valid_15));
  end

  assign _zz_hitVec_1 = (tag_1 == io_handle);
  assign _zz_hitVec_2 = (tag_2 == io_handle);
  assign _zz_hitVec_3 = (tag_3 == io_handle);
  assign _zz_hitVec_4 = (tag_4 == io_handle);
  assign _zz_hitVec_5 = (tag_5 == io_handle);
  assign _zz_hitVec_6 = (tag_6 == io_handle);
  assign _zz_hitVec_7 = (tag_7 == io_handle);
  assign _zz_hitVec_8 = (tag_8 == io_handle);
  assign _zz_hitVec_9 = (tag_9 == io_handle);
  assign _zz_hitVec_10 = (tag_10 == io_handle);
  assign _zz_hitVec_11 = (tag_11 == io_handle);
  assign _zz_hitVec_12 = (tag_12 == io_handle);
  assign _zz_hitVec_13 = (tag_13 == io_handle);
  assign _zz_hitVec_14 = (tag_14 == io_handle);
  assign _zz_hitVec_15 = (tag_15 == io_handle);
  assign cacheable = (io_fieldIdx[7 : 3] == 5'h0);
  assign io_hit = ((|hitVec) && cacheable);
  assign when_ObjectCache_l110 = (_zz_when_ObjectCache_l110[0] && hitTagVec[0]);
  assign when_ObjectCache_l110_1 = (_zz_when_ObjectCache_l110_1[0] && hitTagVec[1]);
  assign when_ObjectCache_l110_2 = (_zz_when_ObjectCache_l110_2[0] && hitTagVec[2]);
  assign when_ObjectCache_l110_3 = (_zz_when_ObjectCache_l110_3[0] && hitTagVec[3]);
  assign when_ObjectCache_l110_4 = (_zz_when_ObjectCache_l110_4[0] && hitTagVec[4]);
  assign when_ObjectCache_l110_5 = (_zz_when_ObjectCache_l110_5[0] && hitTagVec[5]);
  assign when_ObjectCache_l110_6 = (_zz_when_ObjectCache_l110_6[0] && hitTagVec[6]);
  assign when_ObjectCache_l110_7 = (_zz_when_ObjectCache_l110_7[0] && hitTagVec[7]);
  assign when_ObjectCache_l110_8 = (_zz_when_ObjectCache_l110_8[0] && hitTagVec[8]);
  assign when_ObjectCache_l110_9 = (_zz_when_ObjectCache_l110_9[0] && hitTagVec[9]);
  assign when_ObjectCache_l110_10 = (_zz_when_ObjectCache_l110_10[0] && hitTagVec[10]);
  assign when_ObjectCache_l110_11 = (_zz_when_ObjectCache_l110_11[0] && hitTagVec[11]);
  assign when_ObjectCache_l110_12 = (_zz_when_ObjectCache_l110_12[0] && hitTagVec[12]);
  assign when_ObjectCache_l110_13 = (_zz_when_ObjectCache_l110_13[0] && hitTagVec[13]);
  assign when_ObjectCache_l110_14 = (_zz_when_ObjectCache_l110_14[0] && hitTagVec[14]);
  assign when_ObjectCache_l110_15 = (_zz_when_ObjectCache_l110_15[0] && hitTagVec[15]);
  always @(*) begin
    lineEnc[0] = _zz_lineEnc_48;
    lineEnc[1] = _zz_lineEnc_32;
    lineEnc[2] = _zz_lineEnc_16;
    lineEnc[3] = _zz_lineEnc;
  end

  assign when_ObjectCache_l110_16 = (_zz_when_ObjectCache_l110_16[1] && hitTagVec[0]);
  assign when_ObjectCache_l110_17 = (_zz_when_ObjectCache_l110_17[1] && hitTagVec[1]);
  assign when_ObjectCache_l110_18 = (_zz_when_ObjectCache_l110_18[1] && hitTagVec[2]);
  assign when_ObjectCache_l110_19 = (_zz_when_ObjectCache_l110_19[1] && hitTagVec[3]);
  assign when_ObjectCache_l110_20 = (_zz_when_ObjectCache_l110_20[1] && hitTagVec[4]);
  assign when_ObjectCache_l110_21 = (_zz_when_ObjectCache_l110_21[1] && hitTagVec[5]);
  assign when_ObjectCache_l110_22 = (_zz_when_ObjectCache_l110_22[1] && hitTagVec[6]);
  assign when_ObjectCache_l110_23 = (_zz_when_ObjectCache_l110_23[1] && hitTagVec[7]);
  assign when_ObjectCache_l110_24 = (_zz_when_ObjectCache_l110_24[1] && hitTagVec[8]);
  assign when_ObjectCache_l110_25 = (_zz_when_ObjectCache_l110_25[1] && hitTagVec[9]);
  assign when_ObjectCache_l110_26 = (_zz_when_ObjectCache_l110_26[1] && hitTagVec[10]);
  assign when_ObjectCache_l110_27 = (_zz_when_ObjectCache_l110_27[1] && hitTagVec[11]);
  assign when_ObjectCache_l110_28 = (_zz_when_ObjectCache_l110_28[1] && hitTagVec[12]);
  assign when_ObjectCache_l110_29 = (_zz_when_ObjectCache_l110_29[1] && hitTagVec[13]);
  assign when_ObjectCache_l110_30 = (_zz_when_ObjectCache_l110_30[1] && hitTagVec[14]);
  assign when_ObjectCache_l110_31 = (_zz_when_ObjectCache_l110_31[1] && hitTagVec[15]);
  assign when_ObjectCache_l110_32 = (_zz_when_ObjectCache_l110_32[2] && hitTagVec[0]);
  assign when_ObjectCache_l110_33 = (_zz_when_ObjectCache_l110_33[2] && hitTagVec[1]);
  assign when_ObjectCache_l110_34 = (_zz_when_ObjectCache_l110_34[2] && hitTagVec[2]);
  assign when_ObjectCache_l110_35 = (_zz_when_ObjectCache_l110_35[2] && hitTagVec[3]);
  assign when_ObjectCache_l110_36 = (_zz_when_ObjectCache_l110_36[2] && hitTagVec[4]);
  assign when_ObjectCache_l110_37 = (_zz_when_ObjectCache_l110_37[2] && hitTagVec[5]);
  assign when_ObjectCache_l110_38 = (_zz_when_ObjectCache_l110_38[2] && hitTagVec[6]);
  assign when_ObjectCache_l110_39 = (_zz_when_ObjectCache_l110_39[2] && hitTagVec[7]);
  assign when_ObjectCache_l110_40 = (_zz_when_ObjectCache_l110_40[2] && hitTagVec[8]);
  assign when_ObjectCache_l110_41 = (_zz_when_ObjectCache_l110_41[2] && hitTagVec[9]);
  assign when_ObjectCache_l110_42 = (_zz_when_ObjectCache_l110_42[2] && hitTagVec[10]);
  assign when_ObjectCache_l110_43 = (_zz_when_ObjectCache_l110_43[2] && hitTagVec[11]);
  assign when_ObjectCache_l110_44 = (_zz_when_ObjectCache_l110_44[2] && hitTagVec[12]);
  assign when_ObjectCache_l110_45 = (_zz_when_ObjectCache_l110_45[2] && hitTagVec[13]);
  assign when_ObjectCache_l110_46 = (_zz_when_ObjectCache_l110_46[2] && hitTagVec[14]);
  assign when_ObjectCache_l110_47 = (_zz_when_ObjectCache_l110_47[2] && hitTagVec[15]);
  assign when_ObjectCache_l110_48 = (_zz_when_ObjectCache_l110_48[3] && hitTagVec[0]);
  assign when_ObjectCache_l110_49 = (_zz_when_ObjectCache_l110_49[3] && hitTagVec[1]);
  assign when_ObjectCache_l110_50 = (_zz_when_ObjectCache_l110_50[3] && hitTagVec[2]);
  assign when_ObjectCache_l110_51 = (_zz_when_ObjectCache_l110_51[3] && hitTagVec[3]);
  assign when_ObjectCache_l110_52 = (_zz_when_ObjectCache_l110_52[3] && hitTagVec[4]);
  assign when_ObjectCache_l110_53 = (_zz_when_ObjectCache_l110_53[3] && hitTagVec[5]);
  assign when_ObjectCache_l110_54 = (_zz_when_ObjectCache_l110_54[3] && hitTagVec[6]);
  assign when_ObjectCache_l110_55 = (_zz_when_ObjectCache_l110_55[3] && hitTagVec[7]);
  assign when_ObjectCache_l110_56 = (_zz_when_ObjectCache_l110_56[3] && hitTagVec[8]);
  assign when_ObjectCache_l110_57 = (_zz_when_ObjectCache_l110_57[3] && hitTagVec[9]);
  assign when_ObjectCache_l110_58 = (_zz_when_ObjectCache_l110_58[3] && hitTagVec[10]);
  assign when_ObjectCache_l110_59 = (_zz_when_ObjectCache_l110_59[3] && hitTagVec[11]);
  assign when_ObjectCache_l110_60 = (_zz_when_ObjectCache_l110_60[3] && hitTagVec[12]);
  assign when_ObjectCache_l110_61 = (_zz_when_ObjectCache_l110_61[3] && hitTagVec[13]);
  assign when_ObjectCache_l110_62 = (_zz_when_ObjectCache_l110_62[3] && hitTagVec[14]);
  assign when_ObjectCache_l110_63 = (_zz_when_ObjectCache_l110_63[3] && hitTagVec[15]);
  assign when_ObjectCache_l131 = (io_chkGf || io_chkPf);
  assign when_ObjectCache_l140 = (|hitTagVec);
  assign ramRdAddr = {((|hitTagVec) ? lineEnc : nxt),idx};
  assign ramDout = dataRam_spinal_port0;
  assign io_dout = ramDoutStore;
  assign updateCache = ((io_wrGf || (io_wrPf && hitTagReg)) && cacheableReg);
  assign ramDin = (io_wrGf ? io_gfVal : io_pfVal);
  assign ramWrAddr = {lineReg,indexReg[2 : 0]};
  assign _zz_4 = ({15'd0,1'b1} <<< lineReg);
  assign _zz_5 = ({15'd0,1'b1} <<< lineReg);
  always @(*) begin
    _zz_valid_0 = _zz__zz_valid_0;
    if(incNxtReg) begin
      _zz_valid_0 = 8'h0;
    end
    _zz_valid_0[indexReg[2 : 0]] = 1'b1;
  end

  assign when_ObjectCache_l204 = ((io_wrGf && cacheableReg) && incNxtReg);
  always @(posedge clk or posedge reset) begin
    if(reset) begin
      tag_0 <= 24'h0;
      tag_1 <= 24'h0;
      tag_2 <= 24'h0;
      tag_3 <= 24'h0;
      tag_4 <= 24'h0;
      tag_5 <= 24'h0;
      tag_6 <= 24'h0;
      tag_7 <= 24'h0;
      tag_8 <= 24'h0;
      tag_9 <= 24'h0;
      tag_10 <= 24'h0;
      tag_11 <= 24'h0;
      tag_12 <= 24'h0;
      tag_13 <= 24'h0;
      tag_14 <= 24'h0;
      tag_15 <= 24'h0;
      valid_0 <= 8'h0;
      valid_1 <= 8'h0;
      valid_2 <= 8'h0;
      valid_3 <= 8'h0;
      valid_4 <= 8'h0;
      valid_5 <= 8'h0;
      valid_6 <= 8'h0;
      valid_7 <= 8'h0;
      valid_8 <= 8'h0;
      valid_9 <= 8'h0;
      valid_10 <= 8'h0;
      valid_11 <= 8'h0;
      valid_12 <= 8'h0;
      valid_13 <= 8'h0;
      valid_14 <= 8'h0;
      valid_15 <= 8'h0;
      nxt <= 4'b0000;
      lineReg <= 4'b0000;
      incNxtReg <= 1'b0;
      hitTagReg <= 1'b0;
      cacheableReg <= 1'b0;
      handleReg <= 24'h0;
      indexReg <= 8'h0;
      chkGfDly <= 1'b0;
      ramDoutStore <= 32'h0;
    end else begin
      if(when_ObjectCache_l131) begin
        hitTagReg <= ((|hitTagVec) && cacheable);
        handleReg <= io_handle;
        indexReg <= io_fieldIdx;
        cacheableReg <= cacheable;
      end
      if(io_chkGf) begin
        if(when_ObjectCache_l140) begin
          lineReg <= lineEnc;
          incNxtReg <= 1'b0;
        end else begin
          lineReg <= nxt;
          incNxtReg <= 1'b1;
        end
      end
      if(io_chkPf) begin
        lineReg <= lineEnc;
        incNxtReg <= 1'b0;
      end
      chkGfDly <= io_chkGf;
      if(chkGfDly) begin
        ramDoutStore <= ramDout;
      end
      if(updateCache) begin
        if(_zz_4[0]) begin
          tag_0 <= handleReg;
        end
        if(_zz_4[1]) begin
          tag_1 <= handleReg;
        end
        if(_zz_4[2]) begin
          tag_2 <= handleReg;
        end
        if(_zz_4[3]) begin
          tag_3 <= handleReg;
        end
        if(_zz_4[4]) begin
          tag_4 <= handleReg;
        end
        if(_zz_4[5]) begin
          tag_5 <= handleReg;
        end
        if(_zz_4[6]) begin
          tag_6 <= handleReg;
        end
        if(_zz_4[7]) begin
          tag_7 <= handleReg;
        end
        if(_zz_4[8]) begin
          tag_8 <= handleReg;
        end
        if(_zz_4[9]) begin
          tag_9 <= handleReg;
        end
        if(_zz_4[10]) begin
          tag_10 <= handleReg;
        end
        if(_zz_4[11]) begin
          tag_11 <= handleReg;
        end
        if(_zz_4[12]) begin
          tag_12 <= handleReg;
        end
        if(_zz_4[13]) begin
          tag_13 <= handleReg;
        end
        if(_zz_4[14]) begin
          tag_14 <= handleReg;
        end
        if(_zz_4[15]) begin
          tag_15 <= handleReg;
        end
        if(_zz_5[0]) begin
          valid_0 <= _zz_valid_0;
        end
        if(_zz_5[1]) begin
          valid_1 <= _zz_valid_0;
        end
        if(_zz_5[2]) begin
          valid_2 <= _zz_valid_0;
        end
        if(_zz_5[3]) begin
          valid_3 <= _zz_valid_0;
        end
        if(_zz_5[4]) begin
          valid_4 <= _zz_valid_0;
        end
        if(_zz_5[5]) begin
          valid_5 <= _zz_valid_0;
        end
        if(_zz_5[6]) begin
          valid_6 <= _zz_valid_0;
        end
        if(_zz_5[7]) begin
          valid_7 <= _zz_valid_0;
        end
        if(_zz_5[8]) begin
          valid_8 <= _zz_valid_0;
        end
        if(_zz_5[9]) begin
          valid_9 <= _zz_valid_0;
        end
        if(_zz_5[10]) begin
          valid_10 <= _zz_valid_0;
        end
        if(_zz_5[11]) begin
          valid_11 <= _zz_valid_0;
        end
        if(_zz_5[12]) begin
          valid_12 <= _zz_valid_0;
        end
        if(_zz_5[13]) begin
          valid_13 <= _zz_valid_0;
        end
        if(_zz_5[14]) begin
          valid_14 <= _zz_valid_0;
        end
        if(_zz_5[15]) begin
          valid_15 <= _zz_valid_0;
        end
      end
      if(when_ObjectCache_l204) begin
        nxt <= (nxt + 4'b0001);
      end
      if(io_inval) begin
        nxt <= 4'b0000;
        valid_0 <= 8'h0;
        valid_1 <= 8'h0;
        valid_2 <= 8'h0;
        valid_3 <= 8'h0;
        valid_4 <= 8'h0;
        valid_5 <= 8'h0;
        valid_6 <= 8'h0;
        valid_7 <= 8'h0;
        valid_8 <= 8'h0;
        valid_9 <= 8'h0;
        valid_10 <= 8'h0;
        valid_11 <= 8'h0;
        valid_12 <= 8'h0;
        valid_13 <= 8'h0;
        valid_14 <= 8'h0;
        valid_15 <= 8'h0;
      end
    end
  end


endmodule

module MethodCache (
  input  wire [9:0]    io_bcLen,
  input  wire [17:0]   io_bcAddr,
  input  wire          io_find,
  output wire [8:0]    io_bcStart,
  output wire          io_rdy,
  output wire          io_inCache,
  input  wire          clk,
  input  wire          reset
);
  localparam State_1_IDLE = 2'd0;
  localparam State_1_S1 = 2'd1;
  localparam State_1_S2 = 2'd2;

  wire       [4:0]    _zz_nrOfBlks;
  wire       [3:0]    _zz_clrVal;
  wire       [3:0]    _zz_clrVal_1;
  wire       [3:0]    _zz_clrVal_2;
  wire       [3:0]    _zz_clrVal_3;
  wire       [3:0]    _zz_clrVal_4;
  wire       [3:0]    _zz_clrVal_5;
  wire       [3:0]    _zz_clrVal_6;
  wire       [3:0]    _zz_clrVal_7;
  wire       [3:0]    _zz_clrVal_8;
  wire       [3:0]    _zz_clrVal_9;
  wire       [3:0]    _zz_clrVal_10;
  wire       [3:0]    _zz_clrVal_11;
  wire       [3:0]    _zz_clrVal_12;
  wire       [3:0]    _zz_clrVal_13;
  wire       [3:0]    _zz_clrVal_14;
  wire       [3:0]    _zz_clrVal_15;
  wire       [3:0]    _zz_nxt;
  reg        [1:0]    state_2;
  reg        [17:0]   tag_0;
  reg        [17:0]   tag_1;
  reg        [17:0]   tag_2;
  reg        [17:0]   tag_3;
  reg        [17:0]   tag_4;
  reg        [17:0]   tag_5;
  reg        [17:0]   tag_6;
  reg        [17:0]   tag_7;
  reg        [17:0]   tag_8;
  reg        [17:0]   tag_9;
  reg        [17:0]   tag_10;
  reg        [17:0]   tag_11;
  reg        [17:0]   tag_12;
  reg        [17:0]   tag_13;
  reg        [17:0]   tag_14;
  reg        [17:0]   tag_15;
  reg        [3:0]    nxt;
  reg        [3:0]    blockAddr;
  reg                 inCache;
  wire       [3:0]    nrOfBlks;
  wire       [17:0]   useAddr;
  reg        [15:0]   clrVal;
  wire                when_MethodCache_l121;
  wire                when_MethodCache_l121_1;
  wire                when_MethodCache_l121_2;
  wire                when_MethodCache_l121_3;
  wire                when_MethodCache_l121_4;
  wire                when_MethodCache_l121_5;
  wire                when_MethodCache_l121_6;
  wire                when_MethodCache_l121_7;
  wire                when_MethodCache_l121_8;
  wire                when_MethodCache_l121_9;
  wire                when_MethodCache_l121_10;
  wire                when_MethodCache_l121_11;
  wire                when_MethodCache_l121_12;
  wire                when_MethodCache_l121_13;
  wire                when_MethodCache_l121_14;
  wire                when_MethodCache_l121_15;
  wire                when_MethodCache_l132;
  wire                when_MethodCache_l132_1;
  wire                when_MethodCache_l132_2;
  wire                when_MethodCache_l132_3;
  wire                when_MethodCache_l132_4;
  wire                when_MethodCache_l132_5;
  wire                when_MethodCache_l132_6;
  wire                when_MethodCache_l132_7;
  wire                when_MethodCache_l132_8;
  wire                when_MethodCache_l132_9;
  wire                when_MethodCache_l132_10;
  wire                when_MethodCache_l132_11;
  wire                when_MethodCache_l132_12;
  wire                when_MethodCache_l132_13;
  wire                when_MethodCache_l132_14;
  wire                when_MethodCache_l132_15;
  wire       [15:0]   _zz_1;
  `ifndef SYNTHESIS
  reg [31:0] state_2_string;
  `endif


  assign _zz_nrOfBlks = io_bcLen[9 : 5];
  assign _zz_clrVal = (4'b0000 - nxt);
  assign _zz_clrVal_1 = (4'b0001 - nxt);
  assign _zz_clrVal_2 = (4'b0010 - nxt);
  assign _zz_clrVal_3 = (4'b0011 - nxt);
  assign _zz_clrVal_4 = (4'b0100 - nxt);
  assign _zz_clrVal_5 = (4'b0101 - nxt);
  assign _zz_clrVal_6 = (4'b0110 - nxt);
  assign _zz_clrVal_7 = (4'b0111 - nxt);
  assign _zz_clrVal_8 = (4'b1000 - nxt);
  assign _zz_clrVal_9 = (4'b1001 - nxt);
  assign _zz_clrVal_10 = (4'b1010 - nxt);
  assign _zz_clrVal_11 = (4'b1011 - nxt);
  assign _zz_clrVal_12 = (4'b1100 - nxt);
  assign _zz_clrVal_13 = (4'b1101 - nxt);
  assign _zz_clrVal_14 = (4'b1110 - nxt);
  assign _zz_clrVal_15 = (4'b1111 - nxt);
  assign _zz_nxt = (nxt + nrOfBlks);
  `ifndef SYNTHESIS
  always @(*) begin
    case(state_2)
      State_1_IDLE : state_2_string = "IDLE";
      State_1_S1 : state_2_string = "S1  ";
      State_1_S2 : state_2_string = "S2  ";
      default : state_2_string = "????";
    endcase
  end
  `endif

  assign nrOfBlks = _zz_nrOfBlks[3:0];
  assign useAddr = io_bcAddr;
  assign io_bcStart = {blockAddr,5'h0};
  assign io_rdy = (state_2 == State_1_IDLE);
  assign io_inCache = inCache;
  assign when_MethodCache_l121 = (tag_0 == useAddr);
  assign when_MethodCache_l121_1 = (tag_1 == useAddr);
  assign when_MethodCache_l121_2 = (tag_2 == useAddr);
  assign when_MethodCache_l121_3 = (tag_3 == useAddr);
  assign when_MethodCache_l121_4 = (tag_4 == useAddr);
  assign when_MethodCache_l121_5 = (tag_5 == useAddr);
  assign when_MethodCache_l121_6 = (tag_6 == useAddr);
  assign when_MethodCache_l121_7 = (tag_7 == useAddr);
  assign when_MethodCache_l121_8 = (tag_8 == useAddr);
  assign when_MethodCache_l121_9 = (tag_9 == useAddr);
  assign when_MethodCache_l121_10 = (tag_10 == useAddr);
  assign when_MethodCache_l121_11 = (tag_11 == useAddr);
  assign when_MethodCache_l121_12 = (tag_12 == useAddr);
  assign when_MethodCache_l121_13 = (tag_13 == useAddr);
  assign when_MethodCache_l121_14 = (tag_14 == useAddr);
  assign when_MethodCache_l121_15 = (tag_15 == useAddr);
  assign when_MethodCache_l132 = clrVal[0];
  assign when_MethodCache_l132_1 = clrVal[1];
  assign when_MethodCache_l132_2 = clrVal[2];
  assign when_MethodCache_l132_3 = clrVal[3];
  assign when_MethodCache_l132_4 = clrVal[4];
  assign when_MethodCache_l132_5 = clrVal[5];
  assign when_MethodCache_l132_6 = clrVal[6];
  assign when_MethodCache_l132_7 = clrVal[7];
  assign when_MethodCache_l132_8 = clrVal[8];
  assign when_MethodCache_l132_9 = clrVal[9];
  assign when_MethodCache_l132_10 = clrVal[10];
  assign when_MethodCache_l132_11 = clrVal[11];
  assign when_MethodCache_l132_12 = clrVal[12];
  assign when_MethodCache_l132_13 = clrVal[13];
  assign when_MethodCache_l132_14 = clrVal[14];
  assign when_MethodCache_l132_15 = clrVal[15];
  assign _zz_1 = ({15'd0,1'b1} <<< nxt);
  always @(posedge clk or posedge reset) begin
    if(reset) begin
      state_2 <= State_1_IDLE;
      tag_0 <= 18'h0;
      tag_1 <= 18'h0;
      tag_2 <= 18'h0;
      tag_3 <= 18'h0;
      tag_4 <= 18'h0;
      tag_5 <= 18'h0;
      tag_6 <= 18'h0;
      tag_7 <= 18'h0;
      tag_8 <= 18'h0;
      tag_9 <= 18'h0;
      tag_10 <= 18'h0;
      tag_11 <= 18'h0;
      tag_12 <= 18'h0;
      tag_13 <= 18'h0;
      tag_14 <= 18'h0;
      tag_15 <= 18'h0;
      nxt <= 4'b0000;
      blockAddr <= 4'b0000;
      inCache <= 1'b0;
      clrVal <= 16'h0;
    end else begin
      clrVal[0] <= (_zz_clrVal <= nrOfBlks);
      clrVal[1] <= (_zz_clrVal_1 <= nrOfBlks);
      clrVal[2] <= (_zz_clrVal_2 <= nrOfBlks);
      clrVal[3] <= (_zz_clrVal_3 <= nrOfBlks);
      clrVal[4] <= (_zz_clrVal_4 <= nrOfBlks);
      clrVal[5] <= (_zz_clrVal_5 <= nrOfBlks);
      clrVal[6] <= (_zz_clrVal_6 <= nrOfBlks);
      clrVal[7] <= (_zz_clrVal_7 <= nrOfBlks);
      clrVal[8] <= (_zz_clrVal_8 <= nrOfBlks);
      clrVal[9] <= (_zz_clrVal_9 <= nrOfBlks);
      clrVal[10] <= (_zz_clrVal_10 <= nrOfBlks);
      clrVal[11] <= (_zz_clrVal_11 <= nrOfBlks);
      clrVal[12] <= (_zz_clrVal_12 <= nrOfBlks);
      clrVal[13] <= (_zz_clrVal_13 <= nrOfBlks);
      clrVal[14] <= (_zz_clrVal_14 <= nrOfBlks);
      clrVal[15] <= (_zz_clrVal_15 <= nrOfBlks);
      case(state_2)
        State_1_IDLE : begin
          if(io_find) begin
            state_2 <= State_1_S1;
          end
        end
        State_1_S1 : begin
          inCache <= 1'b0;
          state_2 <= State_1_S2;
          blockAddr <= nxt;
          if(when_MethodCache_l121) begin
            blockAddr <= 4'b0000;
            inCache <= 1'b1;
            state_2 <= State_1_IDLE;
          end
          if(when_MethodCache_l121_1) begin
            blockAddr <= 4'b0001;
            inCache <= 1'b1;
            state_2 <= State_1_IDLE;
          end
          if(when_MethodCache_l121_2) begin
            blockAddr <= 4'b0010;
            inCache <= 1'b1;
            state_2 <= State_1_IDLE;
          end
          if(when_MethodCache_l121_3) begin
            blockAddr <= 4'b0011;
            inCache <= 1'b1;
            state_2 <= State_1_IDLE;
          end
          if(when_MethodCache_l121_4) begin
            blockAddr <= 4'b0100;
            inCache <= 1'b1;
            state_2 <= State_1_IDLE;
          end
          if(when_MethodCache_l121_5) begin
            blockAddr <= 4'b0101;
            inCache <= 1'b1;
            state_2 <= State_1_IDLE;
          end
          if(when_MethodCache_l121_6) begin
            blockAddr <= 4'b0110;
            inCache <= 1'b1;
            state_2 <= State_1_IDLE;
          end
          if(when_MethodCache_l121_7) begin
            blockAddr <= 4'b0111;
            inCache <= 1'b1;
            state_2 <= State_1_IDLE;
          end
          if(when_MethodCache_l121_8) begin
            blockAddr <= 4'b1000;
            inCache <= 1'b1;
            state_2 <= State_1_IDLE;
          end
          if(when_MethodCache_l121_9) begin
            blockAddr <= 4'b1001;
            inCache <= 1'b1;
            state_2 <= State_1_IDLE;
          end
          if(when_MethodCache_l121_10) begin
            blockAddr <= 4'b1010;
            inCache <= 1'b1;
            state_2 <= State_1_IDLE;
          end
          if(when_MethodCache_l121_11) begin
            blockAddr <= 4'b1011;
            inCache <= 1'b1;
            state_2 <= State_1_IDLE;
          end
          if(when_MethodCache_l121_12) begin
            blockAddr <= 4'b1100;
            inCache <= 1'b1;
            state_2 <= State_1_IDLE;
          end
          if(when_MethodCache_l121_13) begin
            blockAddr <= 4'b1101;
            inCache <= 1'b1;
            state_2 <= State_1_IDLE;
          end
          if(when_MethodCache_l121_14) begin
            blockAddr <= 4'b1110;
            inCache <= 1'b1;
            state_2 <= State_1_IDLE;
          end
          if(when_MethodCache_l121_15) begin
            blockAddr <= 4'b1111;
            inCache <= 1'b1;
            state_2 <= State_1_IDLE;
          end
        end
        default : begin
          if(when_MethodCache_l132) begin
            tag_0 <= 18'h0;
          end
          if(when_MethodCache_l132_1) begin
            tag_1 <= 18'h0;
          end
          if(when_MethodCache_l132_2) begin
            tag_2 <= 18'h0;
          end
          if(when_MethodCache_l132_3) begin
            tag_3 <= 18'h0;
          end
          if(when_MethodCache_l132_4) begin
            tag_4 <= 18'h0;
          end
          if(when_MethodCache_l132_5) begin
            tag_5 <= 18'h0;
          end
          if(when_MethodCache_l132_6) begin
            tag_6 <= 18'h0;
          end
          if(when_MethodCache_l132_7) begin
            tag_7 <= 18'h0;
          end
          if(when_MethodCache_l132_8) begin
            tag_8 <= 18'h0;
          end
          if(when_MethodCache_l132_9) begin
            tag_9 <= 18'h0;
          end
          if(when_MethodCache_l132_10) begin
            tag_10 <= 18'h0;
          end
          if(when_MethodCache_l132_11) begin
            tag_11 <= 18'h0;
          end
          if(when_MethodCache_l132_12) begin
            tag_12 <= 18'h0;
          end
          if(when_MethodCache_l132_13) begin
            tag_13 <= 18'h0;
          end
          if(when_MethodCache_l132_14) begin
            tag_14 <= 18'h0;
          end
          if(when_MethodCache_l132_15) begin
            tag_15 <= 18'h0;
          end
          if(_zz_1[0]) begin
            tag_0 <= useAddr;
          end
          if(_zz_1[1]) begin
            tag_1 <= useAddr;
          end
          if(_zz_1[2]) begin
            tag_2 <= useAddr;
          end
          if(_zz_1[3]) begin
            tag_3 <= useAddr;
          end
          if(_zz_1[4]) begin
            tag_4 <= useAddr;
          end
          if(_zz_1[5]) begin
            tag_5 <= useAddr;
          end
          if(_zz_1[6]) begin
            tag_6 <= useAddr;
          end
          if(_zz_1[7]) begin
            tag_7 <= useAddr;
          end
          if(_zz_1[8]) begin
            tag_8 <= useAddr;
          end
          if(_zz_1[9]) begin
            tag_9 <= useAddr;
          end
          if(_zz_1[10]) begin
            tag_10 <= useAddr;
          end
          if(_zz_1[11]) begin
            tag_11 <= useAddr;
          end
          if(_zz_1[12]) begin
            tag_12 <= useAddr;
          end
          if(_zz_1[13]) begin
            tag_13 <= useAddr;
          end
          if(_zz_1[14]) begin
            tag_14 <= useAddr;
          end
          if(_zz_1[15]) begin
            tag_15 <= useAddr;
          end
          nxt <= (_zz_nxt + 4'b0001);
          state_2 <= State_1_IDLE;
        end
      endcase
    end
  end


endmodule

module Mul (
  input  wire [31:0]   io_ain,
  input  wire [31:0]   io_bin,
  input  wire          io_wr,
  output wire [31:0]   io_dout,
  input  wire          clk,
  input  wire          reset
);

  wire       [30:0]   _zz__zz_p_2;
  wire       [31:0]   _zz__zz_p_2_1;
  reg        [31:0]   p;
  reg        [31:0]   a;
  reg        [31:0]   b;
  wire       [31:0]   _zz_p;
  reg        [31:0]   _zz_p_1;
  wire                when_Mul_l92;
  reg        [31:0]   _zz_p_2;
  wire                when_Mul_l102;

  assign _zz__zz_p_2_1 = ({1'b0,_zz_p_1[31 : 1]} + {1'b0,a[30 : 0]});
  assign _zz__zz_p_2 = _zz__zz_p_2_1[30:0];
  assign _zz_p = p;
  assign when_Mul_l92 = b[0];
  always @(*) begin
    if(when_Mul_l92) begin
      _zz_p_1 = (_zz_p + a);
    end else begin
      _zz_p_1 = _zz_p;
    end
  end

  assign when_Mul_l102 = b[1];
  always @(*) begin
    if(when_Mul_l102) begin
      _zz_p_2 = {_zz__zz_p_2,_zz_p_1[0]};
    end else begin
      _zz_p_2 = _zz_p_1;
    end
  end

  assign io_dout = p;
  always @(posedge clk or posedge reset) begin
    if(reset) begin
      p <= 32'h0;
      a <= 32'h0;
      b <= 32'h0;
    end else begin
      if(io_wr) begin
        p <= 32'h0;
        a <= io_ain;
        b <= io_bin;
      end else begin
        p <= _zz_p_2;
        a <= {a[29 : 0],2'b00};
        b <= {2'b00,b[31 : 2]};
      end
    end
  end


endmodule

module StackStage (
  input  wire [31:0]   io_din,
  input  wire [7:0]    io_dirAddr,
  input  wire [15:0]   io_opd,
  input  wire [11:0]   io_jpc,
  input  wire          io_selSub,
  input  wire          io_selAmux,
  input  wire          io_enaA,
  input  wire          io_selBmux,
  input  wire [1:0]    io_selLog,
  input  wire [1:0]    io_selShf,
  input  wire [2:0]    io_selLmux,
  input  wire [1:0]    io_selImux,
  input  wire [1:0]    io_selRmux,
  input  wire [1:0]    io_selSmux,
  input  wire          io_selMmux,
  input  wire [2:0]    io_selRda,
  input  wire [2:0]    io_selWra,
  input  wire          io_wrEna,
  input  wire          io_enaB,
  input  wire          io_enaVp,
  input  wire          io_enaAr,
  input  wire [7:0]    io_debugRamAddr,
  output wire [31:0]   io_debugRamData,
  input  wire [7:0]    io_debugRamWrAddr,
  input  wire [31:0]   io_debugRamWrData,
  input  wire          io_debugRamWrEn,
  output wire [7:0]    io_debugSp,
  output wire [7:0]    io_debugWrAddr,
  output wire          io_debugWrEn,
  output wire [7:0]    io_debugRdAddrReg,
  output wire [31:0]   io_debugRamDout,
  output wire          io_spOv,
  output wire          io_zf,
  output wire          io_nf,
  output wire          io_eq,
  output wire          io_lt,
  output wire [31:0]   io_aout,
  output wire [31:0]   io_bout,
  input  wire          clk,
  input  wire          reset
);

  wire       [31:0]   shifter_io_din;
  wire       [4:0]    shifter_io_off;
  wire       [31:0]   stackRam_spinal_port1;
  wire       [31:0]   stackRam_spinal_port2;
  wire       [31:0]   shifter_io_dout;
  wire       [31:0]   _zz_imux;
  wire       [7:0]    _zz_imux_1;
  wire       [31:0]   _zz_imux_2;
  wire       [15:0]   _zz_imux_3;
  wire       [11:0]   _zz_lmux;
  wire       [31:0]   _zz_amux;
  wire       [7:0]    _zz_vpadd;
  wire       [6:0]    _zz_vpadd_1;
  reg        [31:0]   a;
  reg        [31:0]   b;
  reg        [7:0]    sp;
  reg        [7:0]    spp;
  reg        [7:0]    spm;
  reg        [7:0]    vp0;
  reg        [7:0]    vp1;
  reg        [7:0]    vp2;
  reg        [7:0]    vp3;
  reg        [7:0]    ar;
  reg        [7:0]    vpadd;
  reg        [15:0]   opddly;
  reg        [31:0]   immval;
  reg                 spOvReg;
  wire       [31:0]   sout;
  reg        [7:0]    rdaddr;
  reg        [7:0]    wraddr;
  reg        [31:0]   mmux;
  wire                when_StackStage_l196;
  reg                 wrEnaDly;
  reg        [7:0]    wrAddrDly;
  wire       [7:0]    effectiveWrAddr;
  wire       [31:0]   effectiveWrData;
  wire                effectiveWrEn;
  reg        [7:0]    ramRdaddrReg;
  wire       [31:0]   ramDout;
  reg        [32:0]   sum;
  wire       [32:0]   aExt;
  wire       [32:0]   bExt;
  wire       [32:0]   aSigned;
  wire       [32:0]   bSigned;
  reg        [31:0]   log;
  reg        [11:0]   rmux;
  reg        [31:0]   imux;
  reg        [31:0]   lmux;
  reg        [31:0]   amux;
  wire                when_StackStage_l382;
  reg        [7:0]    smux;
  wire                when_StackStage_l462;
  wire                when_StackStage_l479;
  (* ram_style = "distributed" *) reg [31:0] stackRam [0:255];

  assign _zz_imux_1 = opddly[7 : 0];
  assign _zz_imux = {{24{_zz_imux_1[7]}}, _zz_imux_1};
  assign _zz_imux_3 = opddly;
  assign _zz_imux_2 = {{16{_zz_imux_3[15]}}, _zz_imux_3};
  assign _zz_lmux = rmux;
  assign _zz_amux = sum[31 : 0];
  assign _zz_vpadd_1 = io_opd[6 : 0];
  assign _zz_vpadd = {1'd0, _zz_vpadd_1};
  initial begin
    $readmemb("JopCoreWithSdramTestHarness.v_toplevel_jopSystem_jopCore_1_pipeline_stack_stackRam.bin",stackRam);
  end
  always @(posedge clk) begin
    if(effectiveWrEn) begin
      stackRam[effectiveWrAddr] <= effectiveWrData;
    end
  end

  assign stackRam_spinal_port1 = stackRam[ramRdaddrReg];
  assign stackRam_spinal_port2 = stackRam[io_debugRamAddr];
  Shift shifter (
    .io_din   (shifter_io_din[31:0] ), //i
    .io_off   (shifter_io_off[4:0]  ), //i
    .io_shtyp (io_selShf[1:0]       ), //i
    .io_dout  (shifter_io_dout[31:0])  //o
  );
  assign shifter_io_din = b;
  assign shifter_io_off = a[4 : 0];
  assign sout = shifter_io_dout;
  assign when_StackStage_l196 = (io_selMmux == 1'b0);
  always @(*) begin
    if(when_StackStage_l196) begin
      mmux = a;
    end else begin
      mmux = b;
    end
  end

  assign effectiveWrAddr = (io_debugRamWrEn ? io_debugRamWrAddr : wrAddrDly);
  assign effectiveWrData = (io_debugRamWrEn ? io_debugRamWrData : mmux);
  assign effectiveWrEn = (io_debugRamWrEn || wrEnaDly);
  assign ramDout = stackRam_spinal_port1;
  assign io_debugRamData = stackRam_spinal_port2;
  assign aExt = {1'b0,a};
  assign bExt = {1'b0,b};
  assign aSigned = {a[31],a};
  assign bSigned = {b[31],b};
  always @(*) begin
    if(io_selSub) begin
      sum = ($signed(bSigned) - $signed(aSigned));
    end else begin
      sum = ($signed(bSigned) + $signed(aSigned));
    end
  end

  assign io_lt = sum[32];
  always @(*) begin
    case(io_selLog)
      2'b00 : begin
        log = b;
      end
      2'b01 : begin
        log = (a & b);
      end
      2'b10 : begin
        log = (a | b);
      end
      default : begin
        log = (a ^ b);
      end
    endcase
  end

  always @(*) begin
    case(io_selRmux)
      2'b00 : begin
        rmux = {4'd0, sp};
      end
      2'b01 : begin
        rmux = {4'd0, vp0};
      end
      default : begin
        rmux = io_jpc;
      end
    endcase
  end

  always @(*) begin
    case(io_selImux)
      2'b00 : begin
        imux = {24'h0,opddly[7 : 0]};
      end
      2'b01 : begin
        imux = _zz_imux;
      end
      2'b10 : begin
        imux = {16'h0,opddly};
      end
      default : begin
        imux = _zz_imux_2;
      end
    endcase
  end

  always @(*) begin
    case(io_selLmux)
      3'b000 : begin
        lmux = log;
      end
      3'b001 : begin
        lmux = sout;
      end
      3'b010 : begin
        lmux = ramDout;
      end
      3'b011 : begin
        lmux = immval;
      end
      3'b100 : begin
        lmux = io_din;
      end
      default : begin
        lmux = {20'd0, _zz_lmux};
      end
    endcase
  end

  assign when_StackStage_l382 = (io_selAmux == 1'b0);
  always @(*) begin
    if(when_StackStage_l382) begin
      amux = _zz_amux;
    end else begin
      amux = lmux;
    end
  end

  always @(*) begin
    case(io_selSmux)
      2'b00 : begin
        smux = sp;
      end
      2'b01 : begin
        smux = spm;
      end
      2'b10 : begin
        smux = spp;
      end
      default : begin
        smux = a[7 : 0];
      end
    endcase
  end

  always @(*) begin
    case(io_selRda)
      3'b000 : begin
        rdaddr = vp0;
      end
      3'b001 : begin
        rdaddr = vp1;
      end
      3'b010 : begin
        rdaddr = vp2;
      end
      3'b011 : begin
        rdaddr = vp3;
      end
      3'b100 : begin
        rdaddr = vpadd;
      end
      3'b101 : begin
        rdaddr = ar;
      end
      3'b110 : begin
        rdaddr = sp;
      end
      default : begin
        rdaddr = io_dirAddr;
      end
    endcase
  end

  always @(*) begin
    case(io_selWra)
      3'b000 : begin
        wraddr = vp0;
      end
      3'b001 : begin
        wraddr = vp1;
      end
      3'b010 : begin
        wraddr = vp2;
      end
      3'b011 : begin
        wraddr = vp3;
      end
      3'b100 : begin
        wraddr = vpadd;
      end
      3'b101 : begin
        wraddr = ar;
      end
      3'b110 : begin
        wraddr = spp;
      end
      default : begin
        wraddr = io_dirAddr;
      end
    endcase
  end

  assign io_zf = (a == 32'h0);
  assign io_nf = a[31];
  assign io_eq = (a == b);
  assign when_StackStage_l462 = (io_selBmux == 1'b0);
  assign when_StackStage_l479 = (sp == 8'hef);
  assign io_spOv = spOvReg;
  assign io_aout = a;
  assign io_bout = b;
  assign io_debugSp = sp;
  assign io_debugWrAddr = wrAddrDly;
  assign io_debugWrEn = wrEnaDly;
  assign io_debugRdAddrReg = ramRdaddrReg;
  assign io_debugRamDout = ramDout;
  always @(posedge clk or posedge reset) begin
    if(reset) begin
      a <= 32'h0;
      b <= 32'h0;
      sp <= 8'h80;
      spp <= 8'h81;
      spm <= 8'h7f;
      vp0 <= 8'h0;
      vp1 <= 8'h0;
      vp2 <= 8'h0;
      vp3 <= 8'h0;
      ar <= 8'h0;
      vpadd <= 8'h0;
      opddly <= 16'h0;
      immval <= 32'h0;
      spOvReg <= 1'b0;
      wrEnaDly <= 1'b0;
      wrAddrDly <= 8'h0;
      ramRdaddrReg <= 8'h0;
    end else begin
      wrEnaDly <= io_wrEna;
      wrAddrDly <= wraddr;
      ramRdaddrReg <= rdaddr;
      if(io_enaA) begin
        a <= amux;
      end
      if(io_enaB) begin
        if(when_StackStage_l462) begin
          b <= a;
        end else begin
          b <= ramDout;
        end
      end
      spp <= (smux + 8'h01);
      spm <= (smux - 8'h01);
      sp <= smux;
      if(when_StackStage_l479) begin
        spOvReg <= 1'b1;
      end
      if(io_enaVp) begin
        vp0 <= a[7 : 0];
        vp1 <= (a[7 : 0] + 8'h01);
        vp2 <= (a[7 : 0] + 8'h02);
        vp3 <= (a[7 : 0] + 8'h03);
      end
      if(io_enaAr) begin
        ar <= a[7 : 0];
      end
      vpadd <= (vp0 + _zz_vpadd);
      opddly <= io_opd;
      immval <= imux;
    end
  end


endmodule

module DecodeStage (
  input  wire [9:0]    io_instr,
  input  wire          io_zf,
  input  wire          io_nf,
  input  wire          io_eq,
  input  wire          io_lt,
  input  wire [15:0]   io_bcopd,
  output wire          io_br,
  output wire          io_jmp,
  output reg           io_jbr,
  output wire          io_memIn_rd,
  output wire          io_memIn_wr,
  output wire          io_memIn_addrWr,
  output wire          io_memIn_bcRd,
  output wire          io_memIn_stidx,
  output wire          io_memIn_iaload,
  output wire          io_memIn_iastore,
  output wire          io_memIn_getfield,
  output wire          io_memIn_putfield,
  output wire          io_memIn_putref,
  output wire          io_memIn_getstatic,
  output wire          io_memIn_putstatic,
  output wire          io_memIn_rdc,
  output wire          io_memIn_rdf,
  output wire          io_memIn_wrf,
  output wire          io_memIn_copy,
  output wire          io_memIn_cinval,
  output wire          io_memIn_atmstart,
  output wire          io_memIn_atmend,
  output wire [15:0]   io_memIn_bcopd,
  output wire [3:0]    io_mmuInstr,
  output reg  [7:0]    io_dirAddr,
  output wire          io_mulWr,
  output wire          io_wrDly,
  output wire          io_selSub,
  output wire          io_selAmux,
  output wire          io_enaA,
  output wire          io_selBmux,
  output wire [1:0]    io_selLog,
  output wire [1:0]    io_selShf,
  output wire [2:0]    io_selLmux,
  output wire [1:0]    io_selImux,
  output wire [1:0]    io_selRmux,
  output reg  [1:0]    io_selSmux,
  output wire          io_selMmux,
  output reg  [2:0]    io_selRda,
  output reg  [2:0]    io_selWra,
  output reg           io_wrEna,
  output wire          io_enaB,
  output wire          io_enaVp,
  output wire          io_enaJpc,
  output wire          io_enaAr,
  input  wire          clk,
  input  wire          reset
);

  wire                outputNode_ready;
  wire                outputNode_valid;
  reg                 isPop;
  reg                 isPush;
  wire       [3:0]    switch_DecodeStage_l295;
  wire                when_DecodeStage_l318;
  wire                when_DecodeStage_l328;
  wire       [7:0]    combinationalDecode_dirDefault;
  wire                when_DecodeStage_l345;
  wire                when_DecodeStage_l354;
  wire                when_DecodeStage_l357;
  wire                when_DecodeStage_l360;
  wire                when_DecodeStage_l369;
  wire                when_DecodeStage_l372;
  wire                when_DecodeStage_l387;
  reg                 branchDecode_brReg;
  reg                 branchDecode_jmpReg;
  wire                when_DecodeStage_l407;
  wire                when_DecodeStage_l412;
  reg                 aluControlDecode_selSubReg;
  reg                 aluControlDecode_selAmuxReg;
  reg                 aluControlDecode_enaAReg;
  reg                 aluControlDecode_selBmuxReg;
  reg        [1:0]    aluControlDecode_selLogReg;
  reg        [1:0]    aluControlDecode_selShfReg;
  reg        [2:0]    aluControlDecode_selLmuxReg;
  reg        [1:0]    aluControlDecode_selRmuxReg;
  reg                 aluControlDecode_selMmuxReg;
  reg                 aluControlDecode_enaBReg;
  reg                 aluControlDecode_enaVpReg;
  reg                 aluControlDecode_enaJpcReg;
  reg                 aluControlDecode_enaArReg;
  wire                when_DecodeStage_l444;
  wire                when_DecodeStage_l575;
  wire                when_DecodeStage_l584;
  wire                when_DecodeStage_l588;
  wire                when_DecodeStage_l591;
  wire                when_DecodeStage_l595;
  wire                when_DecodeStage_l599;
  wire                when_DecodeStage_l603;
  wire                when_DecodeStage_l607;
  wire                when_DecodeStage_l618;
  wire                when_DecodeStage_l627;
  reg                 mmuControlDecode_memRdReg;
  reg                 mmuControlDecode_memWrReg;
  reg                 mmuControlDecode_memAddrWrReg;
  reg                 mmuControlDecode_memBcRdReg;
  reg                 mmuControlDecode_memStidxReg;
  reg                 mmuControlDecode_memIaloadReg;
  reg                 mmuControlDecode_memIastoreReg;
  reg                 mmuControlDecode_memGetfieldReg;
  reg                 mmuControlDecode_memPutfieldReg;
  reg                 mmuControlDecode_memPutrefReg;
  reg                 mmuControlDecode_memGetstaticReg;
  reg                 mmuControlDecode_memPutstaticReg;
  reg                 mmuControlDecode_memRdcReg;
  reg                 mmuControlDecode_memRdfReg;
  reg                 mmuControlDecode_memWrfReg;
  reg                 mmuControlDecode_memCopyReg;
  reg                 mmuControlDecode_memCinvalReg;
  reg                 mmuControlDecode_memAtmstartReg;
  reg                 mmuControlDecode_memAtmendReg;
  reg                 mmuControlDecode_mulWrReg;
  reg                 mmuControlDecode_wrDlyReg;
  wire                when_DecodeStage_l708;
  wire       [3:0]    switch_DecodeStage_l710;
  wire                when_DecodeStage_l737;
  wire       [3:0]    switch_DecodeStage_l739;

  assign io_mmuInstr = io_instr[3 : 0];
  always @(*) begin
    isPop = 1'b0;
    case(switch_DecodeStage_l295)
      4'b0000 : begin
        isPop = 1'b1;
      end
      4'b0001 : begin
        isPop = 1'b1;
      end
      4'b0010 : begin
      end
      4'b0011 : begin
      end
      4'b0100 : begin
      end
      4'b0101 : begin
      end
      4'b0110 : begin
        isPop = 1'b1;
      end
      4'b0111 : begin
        isPop = 1'b1;
      end
      default : begin
      end
    endcase
  end

  always @(*) begin
    isPush = 1'b0;
    case(switch_DecodeStage_l295)
      4'b0000 : begin
      end
      4'b0001 : begin
      end
      4'b0010 : begin
        isPush = 1'b1;
      end
      4'b0011 : begin
        isPush = 1'b1;
      end
      4'b0100 : begin
      end
      4'b0101 : begin
      end
      4'b0110 : begin
      end
      4'b0111 : begin
      end
      default : begin
      end
    endcase
  end

  assign switch_DecodeStage_l295 = io_instr[9 : 6];
  always @(*) begin
    io_jbr = 1'b0;
    if(when_DecodeStage_l318) begin
      io_jbr = 1'b1;
    end
  end

  assign when_DecodeStage_l318 = (io_instr == 10'h102);
  always @(*) begin
    io_wrEna = 1'b0;
    if(when_DecodeStage_l328) begin
      io_wrEna = 1'b1;
    end
  end

  assign when_DecodeStage_l328 = ((isPush || (io_instr[9 : 5] == 5'h01)) || (io_instr[9 : 3] == 7'h02));
  assign io_selImux = io_instr[1 : 0];
  assign combinationalDecode_dirDefault = {3'b000,io_instr[4 : 0]};
  always @(*) begin
    io_dirAddr = combinationalDecode_dirDefault;
    if(when_DecodeStage_l345) begin
      io_dirAddr = {3'b001,io_instr[4 : 0]};
    end
  end

  assign when_DecodeStage_l345 = (io_instr[9 : 5] == 5'h06);
  always @(*) begin
    io_selRda = 3'b110;
    if(when_DecodeStage_l354) begin
      io_selRda = io_instr[2 : 0];
    end
    if(when_DecodeStage_l357) begin
      io_selRda = 3'b111;
    end
    if(when_DecodeStage_l360) begin
      io_selRda = 3'b111;
    end
  end

  assign when_DecodeStage_l354 = (io_instr[9 : 3] == 7'h1d);
  assign when_DecodeStage_l357 = (io_instr[9 : 5] == 5'h05);
  assign when_DecodeStage_l360 = (io_instr[9 : 5] == 5'h06);
  always @(*) begin
    io_selWra = 3'b110;
    if(when_DecodeStage_l369) begin
      io_selWra = io_instr[2 : 0];
    end
    if(when_DecodeStage_l372) begin
      io_selWra = 3'b111;
    end
  end

  assign when_DecodeStage_l369 = (io_instr[9 : 3] == 7'h02);
  assign when_DecodeStage_l372 = (io_instr[9 : 5] == 5'h01);
  always @(*) begin
    io_selSmux = 2'b00;
    if(isPop) begin
      io_selSmux = 2'b01;
    end
    if(isPush) begin
      io_selSmux = 2'b10;
    end
    if(when_DecodeStage_l387) begin
      io_selSmux = 2'b11;
    end
  end

  assign when_DecodeStage_l387 = (io_instr == 10'h01b);
  assign when_DecodeStage_l407 = (((io_instr[9 : 6] == 4'b0110) && io_zf) || ((io_instr[9 : 6] == 4'b0111) && (! io_zf)));
  assign when_DecodeStage_l412 = io_instr[9];
  assign io_br = branchDecode_brReg;
  assign io_jmp = branchDecode_jmpReg;
  assign when_DecodeStage_l444 = (io_instr[9 : 2] == 8'h0);
  assign when_DecodeStage_l575 = io_instr[9];
  assign when_DecodeStage_l584 = (io_instr[9 : 2] == 8'h07);
  assign when_DecodeStage_l588 = (io_instr[9 : 5] == 5'h05);
  assign when_DecodeStage_l591 = (io_instr[9 : 5] == 5'h06);
  assign when_DecodeStage_l595 = (io_instr[9 : 3] == 7'h1d);
  assign when_DecodeStage_l599 = (io_instr[9 : 2] == 8'h3d);
  assign when_DecodeStage_l603 = (io_instr[9 : 3] == 7'h1c);
  assign when_DecodeStage_l607 = (io_instr[9 : 2] == 8'h3c);
  assign when_DecodeStage_l618 = (! isPop);
  assign when_DecodeStage_l627 = ((! isPush) && (! isPop));
  assign io_selSub = aluControlDecode_selSubReg;
  assign io_selAmux = aluControlDecode_selAmuxReg;
  assign io_enaA = aluControlDecode_enaAReg;
  assign io_selBmux = aluControlDecode_selBmuxReg;
  assign io_selLog = aluControlDecode_selLogReg;
  assign io_selShf = aluControlDecode_selShfReg;
  assign io_selLmux = aluControlDecode_selLmuxReg;
  assign io_selRmux = aluControlDecode_selRmuxReg;
  assign io_selMmux = aluControlDecode_selMmuxReg;
  assign io_enaB = aluControlDecode_enaBReg;
  assign io_enaVp = aluControlDecode_enaVpReg;
  assign io_enaJpc = aluControlDecode_enaJpcReg;
  assign io_enaAr = aluControlDecode_enaArReg;
  assign when_DecodeStage_l708 = (io_instr[9 : 4] == 6'h04);
  assign switch_DecodeStage_l710 = io_instr[3 : 0];
  assign when_DecodeStage_l737 = (io_instr[9 : 4] == 6'h11);
  assign switch_DecodeStage_l739 = io_instr[3 : 0];
  assign io_memIn_rd = mmuControlDecode_memRdReg;
  assign io_memIn_wr = mmuControlDecode_memWrReg;
  assign io_memIn_addrWr = mmuControlDecode_memAddrWrReg;
  assign io_memIn_bcRd = mmuControlDecode_memBcRdReg;
  assign io_memIn_stidx = mmuControlDecode_memStidxReg;
  assign io_memIn_iaload = mmuControlDecode_memIaloadReg;
  assign io_memIn_iastore = mmuControlDecode_memIastoreReg;
  assign io_memIn_getfield = mmuControlDecode_memGetfieldReg;
  assign io_memIn_putfield = mmuControlDecode_memPutfieldReg;
  assign io_memIn_putref = mmuControlDecode_memPutrefReg;
  assign io_memIn_getstatic = mmuControlDecode_memGetstaticReg;
  assign io_memIn_putstatic = mmuControlDecode_memPutstaticReg;
  assign io_memIn_rdc = mmuControlDecode_memRdcReg;
  assign io_memIn_rdf = mmuControlDecode_memRdfReg;
  assign io_memIn_wrf = mmuControlDecode_memWrfReg;
  assign io_memIn_copy = mmuControlDecode_memCopyReg;
  assign io_memIn_cinval = mmuControlDecode_memCinvalReg;
  assign io_memIn_atmstart = mmuControlDecode_memAtmstartReg;
  assign io_memIn_atmend = mmuControlDecode_memAtmendReg;
  assign io_mulWr = mmuControlDecode_mulWrReg;
  assign io_wrDly = mmuControlDecode_wrDlyReg;
  assign io_memIn_bcopd = io_bcopd;
  assign outputNode_valid = 1'b1;
  assign outputNode_ready = 1'b1;
  always @(posedge clk or posedge reset) begin
    if(reset) begin
      branchDecode_brReg <= 1'b0;
      branchDecode_jmpReg <= 1'b0;
      aluControlDecode_selSubReg <= 1'b0;
      aluControlDecode_selAmuxReg <= 1'b0;
      aluControlDecode_enaAReg <= 1'b0;
      aluControlDecode_selBmuxReg <= 1'b0;
      aluControlDecode_selLogReg <= 2'b00;
      aluControlDecode_selShfReg <= 2'b00;
      aluControlDecode_selLmuxReg <= 3'b000;
      aluControlDecode_selRmuxReg <= 2'b00;
      aluControlDecode_selMmuxReg <= 1'b0;
      aluControlDecode_enaBReg <= 1'b0;
      aluControlDecode_enaVpReg <= 1'b0;
      aluControlDecode_enaJpcReg <= 1'b0;
      aluControlDecode_enaArReg <= 1'b0;
      mmuControlDecode_memRdReg <= 1'b0;
      mmuControlDecode_memWrReg <= 1'b0;
      mmuControlDecode_memAddrWrReg <= 1'b0;
      mmuControlDecode_memBcRdReg <= 1'b0;
      mmuControlDecode_memStidxReg <= 1'b0;
      mmuControlDecode_memIaloadReg <= 1'b0;
      mmuControlDecode_memIastoreReg <= 1'b0;
      mmuControlDecode_memGetfieldReg <= 1'b0;
      mmuControlDecode_memPutfieldReg <= 1'b0;
      mmuControlDecode_memPutrefReg <= 1'b0;
      mmuControlDecode_memGetstaticReg <= 1'b0;
      mmuControlDecode_memPutstaticReg <= 1'b0;
      mmuControlDecode_memRdcReg <= 1'b0;
      mmuControlDecode_memRdfReg <= 1'b0;
      mmuControlDecode_memWrfReg <= 1'b0;
      mmuControlDecode_memCopyReg <= 1'b0;
      mmuControlDecode_memCinvalReg <= 1'b0;
      mmuControlDecode_memAtmstartReg <= 1'b0;
      mmuControlDecode_memAtmendReg <= 1'b0;
      mmuControlDecode_mulWrReg <= 1'b0;
      mmuControlDecode_wrDlyReg <= 1'b0;
    end else begin
      branchDecode_brReg <= 1'b0;
      branchDecode_jmpReg <= 1'b0;
      if(when_DecodeStage_l407) begin
        branchDecode_brReg <= 1'b1;
      end
      if(when_DecodeStage_l412) begin
        branchDecode_jmpReg <= 1'b1;
      end
      aluControlDecode_selLogReg <= 2'b00;
      if(when_DecodeStage_l444) begin
        aluControlDecode_selLogReg <= io_instr[1 : 0];
      end
      aluControlDecode_selShfReg <= io_instr[1 : 0];
      aluControlDecode_selSubReg <= 1'b1;
      aluControlDecode_selAmuxReg <= 1'b1;
      aluControlDecode_enaAReg <= 1'b1;
      aluControlDecode_enaVpReg <= 1'b0;
      aluControlDecode_enaJpcReg <= 1'b0;
      aluControlDecode_enaArReg <= 1'b0;
      case(io_instr)
        10'h0 : begin
        end
        10'h001 : begin
        end
        10'h002 : begin
        end
        10'h003 : begin
        end
        10'h004 : begin
          aluControlDecode_selSubReg <= 1'b0;
          aluControlDecode_selAmuxReg <= 1'b0;
        end
        10'h005 : begin
          aluControlDecode_selAmuxReg <= 1'b0;
        end
        10'h010 : begin
        end
        10'h011 : begin
        end
        10'h012 : begin
        end
        10'h013 : begin
        end
        10'h014 : begin
        end
        10'h015 : begin
        end
        10'h018 : begin
          aluControlDecode_enaVpReg <= 1'b1;
        end
        10'h019 : begin
          aluControlDecode_enaJpcReg <= 1'b1;
        end
        10'h01a : begin
          aluControlDecode_enaArReg <= 1'b1;
        end
        10'h01b : begin
        end
        10'h01c : begin
        end
        10'h01d : begin
        end
        10'h01e : begin
        end
        10'h040 : begin
        end
        10'h041 : begin
        end
        10'h042 : begin
        end
        10'h043 : begin
        end
        10'h044 : begin
        end
        10'h045 : begin
        end
        10'h046 : begin
        end
        10'h047 : begin
        end
        10'h04f : begin
        end
        10'h048 : begin
        end
        10'h049 : begin
        end
        10'h04a : begin
        end
        10'h04b : begin
        end
        10'h04c : begin
        end
        10'h04d : begin
        end
        10'h04e : begin
        end
        10'h0e0 : begin
        end
        10'h0e1 : begin
        end
        10'h0e2 : begin
        end
        10'h0e8 : begin
        end
        10'h0e9 : begin
        end
        10'h0ea : begin
        end
        10'h0eb : begin
        end
        10'h0ec : begin
        end
        10'h0ed : begin
        end
        10'h0f0 : begin
        end
        10'h0f1 : begin
        end
        10'h0f2 : begin
        end
        10'h0f4 : begin
        end
        10'h0f5 : begin
        end
        10'h0f6 : begin
        end
        10'h0f7 : begin
        end
        10'h0f8 : begin
          aluControlDecode_enaAReg <= 1'b0;
        end
        10'h100 : begin
          aluControlDecode_enaAReg <= 1'b0;
        end
        10'h101 : begin
          aluControlDecode_enaAReg <= 1'b0;
        end
        10'h102 : begin
          aluControlDecode_enaAReg <= 1'b0;
        end
        10'h110 : begin
          aluControlDecode_enaAReg <= 1'b0;
        end
        10'h111 : begin
          aluControlDecode_enaAReg <= 1'b0;
        end
        10'h112 : begin
          aluControlDecode_enaAReg <= 1'b0;
        end
        10'h113 : begin
          aluControlDecode_enaAReg <= 1'b0;
        end
        default : begin
        end
      endcase
      if(when_DecodeStage_l575) begin
        aluControlDecode_enaAReg <= 1'b0;
      end
      aluControlDecode_selLmuxReg <= 3'b000;
      if(when_DecodeStage_l584) begin
        aluControlDecode_selLmuxReg <= 3'b001;
      end
      if(when_DecodeStage_l588) begin
        aluControlDecode_selLmuxReg <= 3'b010;
      end
      if(when_DecodeStage_l591) begin
        aluControlDecode_selLmuxReg <= 3'b010;
      end
      if(when_DecodeStage_l595) begin
        aluControlDecode_selLmuxReg <= 3'b010;
      end
      if(when_DecodeStage_l599) begin
        aluControlDecode_selLmuxReg <= 3'b011;
      end
      if(when_DecodeStage_l603) begin
        aluControlDecode_selLmuxReg <= 3'b100;
      end
      if(when_DecodeStage_l607) begin
        aluControlDecode_selLmuxReg <= 3'b101;
      end
      aluControlDecode_selBmuxReg <= 1'b1;
      aluControlDecode_selMmuxReg <= 1'b0;
      if(when_DecodeStage_l618) begin
        aluControlDecode_selBmuxReg <= 1'b0;
        aluControlDecode_selMmuxReg <= 1'b1;
      end
      aluControlDecode_enaBReg <= 1'b1;
      if(when_DecodeStage_l627) begin
        aluControlDecode_enaBReg <= 1'b0;
      end
      aluControlDecode_selRmuxReg <= io_instr[1 : 0];
      mmuControlDecode_memRdReg <= 1'b0;
      mmuControlDecode_memWrReg <= 1'b0;
      mmuControlDecode_memAddrWrReg <= 1'b0;
      mmuControlDecode_memBcRdReg <= 1'b0;
      mmuControlDecode_memStidxReg <= 1'b0;
      mmuControlDecode_memIaloadReg <= 1'b0;
      mmuControlDecode_memIastoreReg <= 1'b0;
      mmuControlDecode_memGetfieldReg <= 1'b0;
      mmuControlDecode_memPutfieldReg <= 1'b0;
      mmuControlDecode_memPutrefReg <= 1'b0;
      mmuControlDecode_memGetstaticReg <= 1'b0;
      mmuControlDecode_memPutstaticReg <= 1'b0;
      mmuControlDecode_memRdcReg <= 1'b0;
      mmuControlDecode_memRdfReg <= 1'b0;
      mmuControlDecode_memWrfReg <= 1'b0;
      mmuControlDecode_memCopyReg <= 1'b0;
      mmuControlDecode_memCinvalReg <= 1'b0;
      mmuControlDecode_memAtmstartReg <= 1'b0;
      mmuControlDecode_memAtmendReg <= 1'b0;
      mmuControlDecode_mulWrReg <= 1'b0;
      mmuControlDecode_wrDlyReg <= 1'b0;
      if(when_DecodeStage_l708) begin
        mmuControlDecode_wrDlyReg <= 1'b1;
        case(switch_DecodeStage_l710)
          4'b0000 : begin
            mmuControlDecode_mulWrReg <= 1'b1;
          end
          4'b0001 : begin
            mmuControlDecode_memAddrWrReg <= 1'b1;
          end
          4'b0010 : begin
            mmuControlDecode_memRdReg <= 1'b1;
          end
          4'b0011 : begin
            mmuControlDecode_memWrReg <= 1'b1;
          end
          4'b0100 : begin
            mmuControlDecode_memIaloadReg <= 1'b1;
          end
          4'b0101 : begin
            mmuControlDecode_memIastoreReg <= 1'b1;
          end
          4'b0110 : begin
            mmuControlDecode_memGetfieldReg <= 1'b1;
          end
          4'b0111 : begin
            mmuControlDecode_memPutfieldReg <= 1'b1;
          end
          4'b1111 : begin
            mmuControlDecode_memPutfieldReg <= 1'b1;
            mmuControlDecode_memPutrefReg <= 1'b1;
          end
          4'b1000 : begin
            mmuControlDecode_memCopyReg <= 1'b1;
          end
          4'b1001 : begin
            mmuControlDecode_memBcRdReg <= 1'b1;
          end
          4'b1010 : begin
            mmuControlDecode_memStidxReg <= 1'b1;
          end
          4'b1011 : begin
            mmuControlDecode_memPutstaticReg <= 1'b1;
          end
          4'b1100 : begin
            mmuControlDecode_memRdcReg <= 1'b1;
          end
          4'b1101 : begin
            mmuControlDecode_memRdfReg <= 1'b1;
          end
          default : begin
            mmuControlDecode_memWrfReg <= 1'b1;
          end
        endcase
      end
      if(when_DecodeStage_l737) begin
        mmuControlDecode_wrDlyReg <= 1'b1;
        case(switch_DecodeStage_l739)
          4'b0000 : begin
            mmuControlDecode_memGetstaticReg <= 1'b1;
          end
          4'b0001 : begin
            mmuControlDecode_memCinvalReg <= 1'b1;
          end
          4'b0010 : begin
            mmuControlDecode_memAtmstartReg <= 1'b1;
          end
          4'b0011 : begin
            mmuControlDecode_memAtmendReg <= 1'b1;
          end
          default : begin
          end
        endcase
      end
    end
  end


endmodule

module FetchStage (
  input  wire          io_br,
  input  wire          io_jmp,
  input  wire          io_bsy,
  input  wire [10:0]   io_jpaddr,
  output wire          io_nxt,
  output wire          io_opd,
  output wire [9:0]    io_dout,
  output wire [10:0]   io_pc_out,
  output wire [9:0]    io_ir_out,
  input  wire          reset,
  input  wire          clk
);

  wire       [11:0]   rom_spinal_port0;
  wire       [10:0]   _zz_brdly;
  wire       [10:0]   _zz_brdly_1;
  wire       [10:0]   _zz_brdly_2;
  wire       [5:0]    _zz_brdly_3;
  wire       [10:0]   _zz_jpdly;
  wire       [10:0]   _zz_jpdly_1;
  wire       [10:0]   _zz_jpdly_2;
  wire       [8:0]    _zz_jpdly_3;
  wire                outputNode_ready;
  wire                outputNode_valid;
  wire       [9:0]    outputNode_INSTR_PAYLOAD;
  wire       [10:0]   outputNode_PC_PAYLOAD;
  reg        [10:0]   romAddrReg;
  wire       [11:0]   romData;
  wire                jfetch;
  wire                jopdfetch;
  wire       [9:0]    romInstr;
  reg        [10:0]   pc;
  reg        [10:0]   brdly;
  reg        [10:0]   jpdly;
  reg        [9:0]    ir;
  reg                 pcwait;
  wire       [10:0]   pcInc;
  reg        [10:0]   pcMux;
  wire                when_FetchStage_l159;
  wire                when_FetchStage_l177;
  wire                when_FetchStage_l215;
  reg [11:0] rom [0:2047];

  assign _zz_brdly = ($signed(_zz_brdly_1) + $signed(_zz_brdly_2));
  assign _zz_brdly_1 = pc;
  assign _zz_brdly_3 = ir[5 : 0];
  assign _zz_brdly_2 = {{5{_zz_brdly_3[5]}}, _zz_brdly_3};
  assign _zz_jpdly = ($signed(_zz_jpdly_1) + $signed(_zz_jpdly_2));
  assign _zz_jpdly_1 = pc;
  assign _zz_jpdly_3 = ir[8 : 0];
  assign _zz_jpdly_2 = {{2{_zz_jpdly_3[8]}}, _zz_jpdly_3};
  initial begin
    $readmemb("JopCoreWithSdramTestHarness.v_toplevel_jopSystem_jopCore_1_pipeline_fetch_rom.bin",rom);
  end
  assign rom_spinal_port0 = rom[romAddrReg];
  assign romData = rom_spinal_port0;
  assign jfetch = romData[11];
  assign jopdfetch = romData[10];
  assign romInstr = romData[9 : 0];
  assign pcInc = (pc + 11'h001);
  always @(*) begin
    if(jfetch) begin
      pcMux = io_jpaddr;
    end else begin
      if(io_br) begin
        pcMux = brdly;
      end else begin
        if(io_jmp) begin
          pcMux = jpdly;
        end else begin
          if(when_FetchStage_l159) begin
            pcMux = pc;
          end else begin
            pcMux = pcInc;
          end
        end
      end
    end
  end

  assign when_FetchStage_l159 = (pcwait && io_bsy);
  assign when_FetchStage_l177 = (romInstr == 10'h101);
  assign when_FetchStage_l215 = (pcwait && io_bsy);
  assign io_nxt = jfetch;
  assign io_opd = jopdfetch;
  assign io_dout = ir;
  assign io_pc_out = pc;
  assign io_ir_out = ir;
  assign outputNode_PC_PAYLOAD = pc;
  assign outputNode_INSTR_PAYLOAD = ir;
  assign outputNode_valid = 1'b1;
  assign outputNode_ready = 1'b1;
  always @(posedge clk or posedge reset) begin
    if(reset) begin
      romAddrReg <= 11'h0;
      pc <= 11'h0;
      brdly <= 11'h0;
      jpdly <= 11'h0;
      ir <= 10'h0;
      pcwait <= 1'b0;
    end else begin
      romAddrReg <= pcMux;
      ir <= romInstr;
      pcwait <= 1'b0;
      if(when_FetchStage_l177) begin
        pcwait <= 1'b1;
      end
      if(reset) begin
        pc <= 11'h0;
        brdly <= 11'h0;
        jpdly <= 11'h0;
      end else begin
        brdly <= _zz_brdly;
        jpdly <= _zz_jpdly;
        pc <= pcMux;
      end
      if(when_FetchStage_l215) begin
        romAddrReg <= romAddrReg;
        ir <= ir;
        pcwait <= 1'b1;
        pc <= pc;
      end
    end
  end


endmodule

module BytecodeFetchStage (
  input  wire          io_jpc_wr,
  input  wire [31:0]   io_din,
  input  wire          io_jfetch,
  input  wire          io_jopdfetch,
  input  wire          io_jbr,
  input  wire          io_zf,
  input  wire          io_nf,
  input  wire          io_eq,
  input  wire          io_lt,
  input  wire [8:0]    io_jbcWrAddr,
  input  wire [31:0]   io_jbcWrData,
  input  wire          io_jbcWrEn,
  input  wire          io_irq,
  input  wire          io_exc,
  input  wire          io_ena,
  output wire          io_ack_irq,
  output wire          io_ack_exc,
  output wire [10:0]   io_jpaddr,
  output wire [15:0]   io_opd,
  output wire [11:0]   io_jpc_out,
  input  wire          clk,
  input  wire          reset
);

  wire                jumpTable_1_io_intPend;
  reg        [31:0]   jbcRamWord_spinal_port1;
  wire       [10:0]   jumpTable_1_io_jpaddr;
  wire       [11:0]   _zz_jbcAddr;
  wire                _zz_jbcRamWord_port;
  wire                _zz_jbcWordDataRaw;
  wire       [11:0]   _zz_jmp_addr;
  wire       [11:0]   _zz_jmp_addr_1;
  reg        [11:0]   jpc;
  reg        [15:0]   jopd;
  reg        [11:0]   jpc_br;
  reg        [7:0]    jinstr;
  reg        [11:0]   jmp_addr;
  reg        [10:0]   jbcAddr;
  reg                 jmp;
  wire                when_BytecodeFetchStage_l143;
  wire       [8:0]    jbcWordAddr;
  reg        [1:0]    jbcByteSelect;
  wire       [31:0]   jbcWordDataRaw;
  reg                 bypassWrEn;
  reg        [8:0]    bypassWrAddr;
  reg        [31:0]   bypassWrData;
  reg        [8:0]    bypassRdAddr;
  wire                doBypass;
  wire       [31:0]   jbcWordData;
  reg        [7:0]    jbcData;
  wire                when_BytecodeFetchStage_l188;
  wire       [3:0]    tp;
  wire       [11:0]   branchOffset;
  wire                when_BytecodeFetchStage_l241;
  wire                when_BytecodeFetchStage_l247;
  wire                when_BytecodeFetchStage_l250;
  wire                when_BytecodeFetchStage_l253;
  wire                when_BytecodeFetchStage_l259;
  wire                when_BytecodeFetchStage_l265;
  wire                when_BytecodeFetchStage_l268;
  wire                when_BytecodeFetchStage_l271;
  reg                 intPend;
  reg                 excPend;
  wire                doAckIrq;
  wire                doAckExc;
  reg [31:0] jbcRamWord [0:511];

  assign _zz_jbcAddr = (jpc + 12'h001);
  assign _zz_jmp_addr = ($signed(_zz_jmp_addr_1) + $signed(branchOffset));
  assign _zz_jmp_addr_1 = jpc_br;
  assign _zz_jbcWordDataRaw = 1'b1;
  initial begin
    $readmemb("JopCoreWithSdramTestHarness.v_toplevel_jopSystem_jopCore_1_pipeline_bcfetch_jbcRamWord.bin",jbcRamWord);
  end
  always @(posedge clk) begin
    if(io_jbcWrEn) begin
      jbcRamWord[io_jbcWrAddr] <= io_jbcWrData;
    end
  end

  always @(posedge clk) begin
    if(_zz_jbcWordDataRaw) begin
      jbcRamWord_spinal_port1 <= jbcRamWord[jbcWordAddr];
    end
  end

  JumpTable jumpTable_1 (
    .io_bytecode (jbcData[7:0]               ), //i
    .io_jpaddr   (jumpTable_1_io_jpaddr[10:0]), //o
    .io_intPend  (jumpTable_1_io_intPend     ), //i
    .io_excPend  (excPend                    )  //i
  );
  always @(*) begin
    if(jmp) begin
      jbcAddr = jmp_addr[10 : 0];
    end else begin
      if(when_BytecodeFetchStage_l143) begin
        jbcAddr = _zz_jbcAddr[10 : 0];
      end else begin
        jbcAddr = jpc[10 : 0];
      end
    end
  end

  assign when_BytecodeFetchStage_l143 = (io_jfetch || io_jopdfetch);
  assign jbcWordAddr = jbcAddr[10 : 2];
  assign jbcWordDataRaw = jbcRamWord_spinal_port1;
  assign doBypass = (bypassWrEn && (bypassWrAddr == bypassRdAddr));
  assign jbcWordData = (doBypass ? bypassWrData : jbcWordDataRaw);
  always @(*) begin
    case(jbcByteSelect)
      2'b00 : begin
        jbcData = jbcWordData[7 : 0];
      end
      2'b01 : begin
        jbcData = jbcWordData[15 : 8];
      end
      2'b10 : begin
        jbcData = jbcWordData[23 : 16];
      end
      default : begin
        jbcData = jbcWordData[31 : 24];
      end
    endcase
  end

  assign when_BytecodeFetchStage_l188 = (io_jfetch || io_jopdfetch);
  assign io_jpc_out = jpc;
  assign io_opd = jopd;
  assign tp = jinstr[3 : 0];
  assign branchOffset = {jopd[3 : 0],jbcData};
  always @(*) begin
    jmp = 1'b0;
    if(io_jbr) begin
      case(tp)
        4'b1001 : begin
          if(io_zf) begin
            jmp = 1'b1;
          end
        end
        4'b1010 : begin
          if(when_BytecodeFetchStage_l241) begin
            jmp = 1'b1;
          end
        end
        4'b1011 : begin
          if(io_nf) begin
            jmp = 1'b1;
          end
        end
        4'b1100 : begin
          if(when_BytecodeFetchStage_l247) begin
            jmp = 1'b1;
          end
        end
        4'b1101 : begin
          if(when_BytecodeFetchStage_l250) begin
            jmp = 1'b1;
          end
        end
        4'b1110 : begin
          if(when_BytecodeFetchStage_l253) begin
            jmp = 1'b1;
          end
        end
        4'b1111 : begin
          if(io_eq) begin
            jmp = 1'b1;
          end
        end
        4'b0000 : begin
          if(when_BytecodeFetchStage_l259) begin
            jmp = 1'b1;
          end
        end
        4'b0001 : begin
          if(io_lt) begin
            jmp = 1'b1;
          end
        end
        4'b0010 : begin
          if(when_BytecodeFetchStage_l265) begin
            jmp = 1'b1;
          end
        end
        4'b0011 : begin
          if(when_BytecodeFetchStage_l268) begin
            jmp = 1'b1;
          end
        end
        4'b0100 : begin
          if(when_BytecodeFetchStage_l271) begin
            jmp = 1'b1;
          end
        end
        4'b0111 : begin
          jmp = 1'b1;
        end
        default : begin
        end
      endcase
    end
  end

  assign when_BytecodeFetchStage_l241 = (! io_zf);
  assign when_BytecodeFetchStage_l247 = (! io_nf);
  assign when_BytecodeFetchStage_l250 = ((! io_zf) && (! io_nf));
  assign when_BytecodeFetchStage_l253 = (io_zf || io_nf);
  assign when_BytecodeFetchStage_l259 = (! io_eq);
  assign when_BytecodeFetchStage_l265 = (! io_lt);
  assign when_BytecodeFetchStage_l268 = ((! io_eq) && (! io_lt));
  assign when_BytecodeFetchStage_l271 = (io_eq || io_lt);
  assign doAckExc = (excPend && io_jfetch);
  assign doAckIrq = (((intPend && io_ena) && (! excPend)) && io_jfetch);
  assign io_ack_irq = doAckIrq;
  assign io_ack_exc = doAckExc;
  assign jumpTable_1_io_intPend = (intPend && io_ena);
  assign io_jpaddr = jumpTable_1_io_jpaddr;
  always @(posedge clk or posedge reset) begin
    if(reset) begin
      jpc <= 12'h0;
      jopd <= 16'h0;
      jpc_br <= 12'h0;
      jinstr <= 8'h0;
      jmp_addr <= 12'h0;
      bypassWrEn <= 1'b0;
      intPend <= 1'b0;
      excPend <= 1'b0;
    end else begin
      bypassWrEn <= io_jbcWrEn;
      if(io_jpc_wr) begin
        jpc <= io_din[11 : 0];
      end else begin
        if(jmp) begin
          jpc <= jmp_addr;
        end else begin
          if(when_BytecodeFetchStage_l188) begin
            jpc <= (jpc + 12'h001);
          end
        end
      end
      jopd[7 : 0] <= jbcData;
      if(io_jopdfetch) begin
        jopd[15 : 8] <= jopd[7 : 0];
      end
      if(io_jfetch) begin
        jinstr <= jbcData;
        jpc_br <= jpc;
      end
      jmp_addr <= _zz_jmp_addr;
      if(doAckExc) begin
        excPend <= 1'b0;
      end else begin
        if(io_exc) begin
          excPend <= 1'b1;
        end
      end
      if(doAckIrq) begin
        intPend <= 1'b0;
      end else begin
        if(io_irq) begin
          intPend <= 1'b1;
        end
      end
    end
  end

  always @(posedge clk) begin
    jbcByteSelect <= jbcAddr[1 : 0];
    bypassWrAddr <= io_jbcWrAddr;
    bypassWrData <= io_jbcWrData;
    bypassRdAddr <= jbcWordAddr;
  end


endmodule

module StreamFifo (
  input  wire          io_push_valid,
  output wire          io_push_ready,
  input  wire [15:0]   io_push_payload_data,
  input  wire [3:0]    io_push_payload_context_context,
  input  wire          io_push_payload_context_isHigh,
  output reg           io_pop_valid,
  input  wire          io_pop_ready,
  output reg  [15:0]   io_pop_payload_data,
  output reg  [3:0]    io_pop_payload_context_context,
  output reg           io_pop_payload_context_isHigh,
  input  wire          io_flush,
  output wire [1:0]    io_occupancy,
  output wire [1:0]    io_availability,
  input  wire          clk,
  input  wire          reset
);

  wire       [20:0]   logic_ram_spinal_port1;
  wire       [20:0]   _zz_logic_ram_port;
  reg                 _zz_1;
  reg                 logic_ptr_doPush;
  wire                logic_ptr_doPop;
  wire                logic_ptr_full;
  wire                logic_ptr_empty;
  reg        [1:0]    logic_ptr_push;
  reg        [1:0]    logic_ptr_pop;
  wire       [1:0]    logic_ptr_occupancy;
  wire       [1:0]    logic_ptr_popOnIo;
  wire                when_Stream_l1383;
  reg                 logic_ptr_wentUp;
  wire                io_push_fire;
  wire                logic_push_onRam_write_valid;
  wire       [0:0]    logic_push_onRam_write_payload_address;
  wire       [15:0]   logic_push_onRam_write_payload_data_data;
  wire       [3:0]    logic_push_onRam_write_payload_data_context_context;
  wire                logic_push_onRam_write_payload_data_context_isHigh;
  wire                logic_pop_addressGen_valid;
  wire                logic_pop_addressGen_ready;
  wire       [0:0]    logic_pop_addressGen_payload;
  wire                logic_pop_addressGen_fire;
  wire       [15:0]   logic_pop_async_readed_data;
  wire       [3:0]    logic_pop_async_readed_context_context;
  wire                logic_pop_async_readed_context_isHigh;
  wire       [20:0]   _zz_logic_pop_async_readed_data;
  wire       [4:0]    _zz_logic_pop_async_readed_context_context;
  wire                logic_pop_addressGen_translated_valid;
  wire                logic_pop_addressGen_translated_ready;
  wire       [15:0]   logic_pop_addressGen_translated_payload_data;
  wire       [3:0]    logic_pop_addressGen_translated_payload_context_context;
  wire                logic_pop_addressGen_translated_payload_context_isHigh;
  (* ram_style = "distributed" *) reg [20:0] logic_ram [0:1];

  assign _zz_logic_ram_port = {{logic_push_onRam_write_payload_data_context_isHigh,logic_push_onRam_write_payload_data_context_context},logic_push_onRam_write_payload_data_data};
  always @(posedge clk) begin
    if(_zz_1) begin
      logic_ram[logic_push_onRam_write_payload_address] <= _zz_logic_ram_port;
    end
  end

  assign logic_ram_spinal_port1 = logic_ram[logic_pop_addressGen_payload];
  always @(*) begin
    _zz_1 = 1'b0;
    if(logic_push_onRam_write_valid) begin
      _zz_1 = 1'b1;
    end
  end

  assign when_Stream_l1383 = (logic_ptr_doPush != logic_ptr_doPop);
  assign logic_ptr_full = (((logic_ptr_push ^ logic_ptr_popOnIo) ^ 2'b10) == 2'b00);
  assign logic_ptr_empty = (logic_ptr_push == logic_ptr_pop);
  assign logic_ptr_occupancy = (logic_ptr_push - logic_ptr_popOnIo);
  assign io_push_ready = (! logic_ptr_full);
  assign io_push_fire = (io_push_valid && io_push_ready);
  always @(*) begin
    logic_ptr_doPush = io_push_fire;
    if(logic_ptr_empty) begin
      if(io_pop_ready) begin
        logic_ptr_doPush = 1'b0;
      end
    end
  end

  assign logic_push_onRam_write_valid = io_push_fire;
  assign logic_push_onRam_write_payload_address = logic_ptr_push[0:0];
  assign logic_push_onRam_write_payload_data_data = io_push_payload_data;
  assign logic_push_onRam_write_payload_data_context_context = io_push_payload_context_context;
  assign logic_push_onRam_write_payload_data_context_isHigh = io_push_payload_context_isHigh;
  assign logic_pop_addressGen_valid = (! logic_ptr_empty);
  assign logic_pop_addressGen_payload = logic_ptr_pop[0:0];
  assign logic_pop_addressGen_fire = (logic_pop_addressGen_valid && logic_pop_addressGen_ready);
  assign logic_ptr_doPop = logic_pop_addressGen_fire;
  assign _zz_logic_pop_async_readed_data = logic_ram_spinal_port1;
  assign logic_pop_async_readed_data = _zz_logic_pop_async_readed_data[15 : 0];
  assign _zz_logic_pop_async_readed_context_context = _zz_logic_pop_async_readed_data[20 : 16];
  assign logic_pop_async_readed_context_context = _zz_logic_pop_async_readed_context_context[3 : 0];
  assign logic_pop_async_readed_context_isHigh = _zz_logic_pop_async_readed_context_context[4];
  assign logic_pop_addressGen_translated_valid = logic_pop_addressGen_valid;
  assign logic_pop_addressGen_ready = logic_pop_addressGen_translated_ready;
  assign logic_pop_addressGen_translated_payload_data = logic_pop_async_readed_data;
  assign logic_pop_addressGen_translated_payload_context_context = logic_pop_async_readed_context_context;
  assign logic_pop_addressGen_translated_payload_context_isHigh = logic_pop_async_readed_context_isHigh;
  always @(*) begin
    io_pop_valid = logic_pop_addressGen_translated_valid;
    if(logic_ptr_empty) begin
      io_pop_valid = io_push_valid;
    end
  end

  assign logic_pop_addressGen_translated_ready = io_pop_ready;
  always @(*) begin
    io_pop_payload_data = logic_pop_addressGen_translated_payload_data;
    if(logic_ptr_empty) begin
      io_pop_payload_data = io_push_payload_data;
    end
  end

  always @(*) begin
    io_pop_payload_context_context = logic_pop_addressGen_translated_payload_context_context;
    if(logic_ptr_empty) begin
      io_pop_payload_context_context = io_push_payload_context_context;
    end
  end

  always @(*) begin
    io_pop_payload_context_isHigh = logic_pop_addressGen_translated_payload_context_isHigh;
    if(logic_ptr_empty) begin
      io_pop_payload_context_isHigh = io_push_payload_context_isHigh;
    end
  end

  assign logic_ptr_popOnIo = logic_ptr_pop;
  assign io_occupancy = logic_ptr_occupancy;
  assign io_availability = (2'b10 - logic_ptr_occupancy);
  always @(posedge clk or posedge reset) begin
    if(reset) begin
      logic_ptr_push <= 2'b00;
      logic_ptr_pop <= 2'b00;
      logic_ptr_wentUp <= 1'b0;
    end else begin
      if(when_Stream_l1383) begin
        logic_ptr_wentUp <= logic_ptr_doPush;
      end
      if(io_flush) begin
        logic_ptr_wentUp <= 1'b0;
      end
      if(logic_ptr_doPush) begin
        logic_ptr_push <= (logic_ptr_push + 2'b01);
      end
      if(logic_ptr_doPop) begin
        logic_ptr_pop <= (logic_ptr_pop + 2'b01);
      end
      if(io_flush) begin
        logic_ptr_push <= 2'b00;
        logic_ptr_pop <= 2'b00;
      end
    end
  end


endmodule

module Shift (
  input  wire [31:0]   io_din,
  input  wire [4:0]    io_off,
  input  wire [1:0]    io_shtyp,
  output wire [31:0]   io_dout
);

  wire       [1:0]    USHR;
  wire       [1:0]    SHL;
  wire       [1:0]    SHR;
  reg        [63:0]   shiftin;
  reg        [4:0]    shiftcnt;
  wire       [31:0]   zero32;
  wire                when_Shift_l96;
  reg        [63:0]   s0;
  wire                when_Shift_l126;
  reg        [63:0]   s1;
  wire                when_Shift_l135;
  reg        [63:0]   s2;
  wire                when_Shift_l144;
  reg        [63:0]   s3;
  wire                when_Shift_l153;
  reg        [63:0]   s4;
  wire                when_Shift_l162;

  assign USHR = 2'b00;
  assign SHL = 2'b01;
  assign SHR = 2'b10;
  assign zero32 = 32'h0;
  always @(*) begin
    if((io_shtyp == SHL)) begin
        shiftin = {{1'b0,io_din},31'h0};
    end else if((io_shtyp == SHR)) begin
        if(when_Shift_l96) begin
          shiftin = {32'hffffffff,io_din};
        end else begin
          shiftin = {zero32,io_din};
        end
    end else begin
        shiftin = {zero32,io_din};
    end
  end

  always @(*) begin
    if((io_shtyp == SHL)) begin
        shiftcnt = (~ io_off);
    end else if((io_shtyp == SHR)) begin
        shiftcnt = io_off;
    end else begin
        shiftcnt = io_off;
    end
  end

  assign when_Shift_l96 = io_din[31];
  assign when_Shift_l126 = shiftcnt[4];
  always @(*) begin
    if(when_Shift_l126) begin
      s0 = (shiftin >>> 16);
    end else begin
      s0 = shiftin;
    end
  end

  assign when_Shift_l135 = shiftcnt[3];
  always @(*) begin
    if(when_Shift_l135) begin
      s1 = (s0 >>> 8);
    end else begin
      s1 = s0;
    end
  end

  assign when_Shift_l144 = shiftcnt[2];
  always @(*) begin
    if(when_Shift_l144) begin
      s2 = (s1 >>> 4);
    end else begin
      s2 = s1;
    end
  end

  assign when_Shift_l153 = shiftcnt[1];
  always @(*) begin
    if(when_Shift_l153) begin
      s3 = (s2 >>> 2);
    end else begin
      s3 = s2;
    end
  end

  assign when_Shift_l162 = shiftcnt[0];
  always @(*) begin
    if(when_Shift_l162) begin
      s4 = (s3 >>> 1);
    end else begin
      s4 = s3;
    end
  end

  assign io_dout = s4[31 : 0];

endmodule

module JumpTable (
  input  wire [7:0]    io_bytecode,
  output reg  [10:0]   io_jpaddr,
  input  wire          io_intPend,
  input  wire          io_excPend
);

  wire       [10:0]   rom_spinal_port0;
  wire       [7:0]    _zz_normalAddr;
  wire       [10:0]   normalAddr;
  reg [10:0] rom [0:255];

  initial begin
    $readmemb("JopCoreWithSdramTestHarness.v_toplevel_jopSystem_jopCore_1_pipeline_bcfetch_jumpTable_1_rom.bin",rom);
  end
  assign rom_spinal_port0 = rom[_zz_normalAddr];
  assign _zz_normalAddr = io_bytecode;
  assign normalAddr = rom_spinal_port0;
  always @(*) begin
    if(io_excPend) begin
      io_jpaddr = 11'h0a7;
    end else begin
      if(io_intPend) begin
        io_jpaddr = 11'h09f;
      end else begin
        io_jpaddr = normalAddr;
      end
    end
  end


endmodule
