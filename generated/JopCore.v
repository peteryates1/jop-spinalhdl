// Generator : SpinalHDL v1.12.2    git head : f25edbcee624ef41548345cfb91c42060e33313f
// Component : JopCore
// Git hash  : 63c1ed527155450edece46f456c9a0cea61fb19a

`timescale 1ns/1ps

module JopCore (
  output wire          io_bmb_cmd_valid,
  input  wire          io_bmb_cmd_ready,
  output wire          io_bmb_cmd_payload_last,
  output wire [0:0]    io_bmb_cmd_payload_fragment_opcode,
  output wire [25:0]   io_bmb_cmd_payload_fragment_address,
  output wire [1:0]    io_bmb_cmd_payload_fragment_length,
  output wire [31:0]   io_bmb_cmd_payload_fragment_data,
  output wire [3:0]    io_bmb_cmd_payload_fragment_mask,
  output wire [3:0]    io_bmb_cmd_payload_fragment_context,
  input  wire          io_bmb_rsp_valid,
  output wire          io_bmb_rsp_ready,
  input  wire          io_bmb_rsp_payload_last,
  input  wire [0:0]    io_bmb_rsp_payload_fragment_opcode,
  input  wire [31:0]   io_bmb_rsp_payload_fragment_data,
  input  wire [3:0]    io_bmb_rsp_payload_fragment_context,
  input  wire          io_syncIn_halted,
  input  wire          io_syncIn_s_out,
  input  wire          io_syncIn_status,
  output wire          io_syncOut_req,
  output wire          io_syncOut_reqPulse,
  output wire          io_syncOut_s_in,
  output wire          io_syncOut_gcHalt,
  output wire [31:0]   io_syncOut_data,
  output wire          io_syncOut_op,
  output wire [31:0]   io_wd,
  output wire [11:0]   io_pc,
  output wire [11:0]   io_jpc,
  output wire [9:0]    io_instr,
  output wire          io_jfetch,
  output wire          io_jopdfetch,
  output wire [31:0]   io_aout,
  output wire [31:0]   io_bout,
  output wire          io_memBusy,
  output wire [7:0]    io_uartTxData,
  output wire          io_uartTxValid,
  output wire          io_debugExc,
  output wire          io_debugBcRd,
  output wire [4:0]    io_debugMemState,
  output wire          io_debugMemHandleActive,
  output wire [23:0]   io_debugBcFillAddr,
  output wire [9:0]    io_debugBcFillLen,
  output wire [9:0]    io_debugBcFillCount,
  output wire [31:0]   io_debugBcRdCapture,
  output wire          io_debugAddrWr,
  output wire          io_debugRdc,
  output wire          io_debugRd,
  input  wire [7:0]    io_debugRamAddr,
  output wire [31:0]   io_debugRamData,
  output wire [15:0]   io_debugIoRdCount,
  output wire [15:0]   io_debugIoWrCount,
  output wire          io_debugHalted,
  input  wire          io_debugHalt,
  output wire [7:0]    io_debugSp,
  output wire [13:0]   io_rootSel,
  input  wire [31:0]   io_rootData,
  output wire [31:0]   io_stackA,
  output wire [31:0]   io_stackB,
  output wire [7:0]    io_debugVp,
  output wire [7:0]    io_debugAr,
  output wire [3:0]    io_debugFlags,
  output wire [31:0]   io_debugMulResult,
  output wire [23:0]   io_debugAddrReg,
  output wire [31:0]   io_debugRdDataReg,
  output wire [9:0]    io_debugInstr,
  output wire [15:0]   io_debugBcopd,
  output wire          io_snoopOut_valid,
  output wire          io_snoopOut_isArray,
  output wire [23:0]   io_snoopOut_handle,
  output wire [23:0]   io_snoopOut_index,
  input  wire          io_snoopIn_valid,
  input  wire          io_snoopIn_isArray,
  input  wire [23:0]   io_snoopIn_handle,
  input  wire [23:0]   io_snoopIn_index,
  input  wire          reset,
  input  wire          clk
);

  wire                pipeline_io_memBusy;
  wire       [3:0]    sys_1_io_addr;
  wire                sys_1_io_rd;
  wire                sys_1_io_wr;
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
  wire       [15:0]   pipeline_io_memCtrl_bcopd;
  wire       [31:0]   pipeline_io_aout;
  wire       [31:0]   pipeline_io_bout;
  wire       [15:0]   pipeline_io_bcopd;
  wire                pipeline_io_ackIrq;
  wire                pipeline_io_ackExc;
  wire                pipeline_io_hwBusy;
  wire       [11:0]   pipeline_io_pc;
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
  wire       [7:0]    pipeline_io_debugSp;
  wire       [7:0]    pipeline_io_debugVp;
  wire       [7:0]    pipeline_io_debugAr;
  wire       [3:0]    pipeline_io_debugFlags;
  wire       [31:0]   pipeline_io_debugMulResult;
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
  wire       [1:0]    memCtrl_io_bmb_cmd_payload_fragment_length;
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
  wire       [23:0]   memCtrl_io_debug_addrReg;
  wire       [31:0]   memCtrl_io_debug_rdDataReg;
  wire       [23:0]   memCtrl_io_debug_bcFillAddr;
  wire       [9:0]    memCtrl_io_debug_bcFillLen;
  wire       [9:0]    memCtrl_io_debug_bcFillCount;
  wire       [31:0]   memCtrl_io_debug_bcRdCapture;
  wire                memCtrl_io_snoopOut_valid;
  wire                memCtrl_io_snoopOut_isArray;
  wire       [23:0]   memCtrl_io_snoopOut_handle;
  wire       [23:0]   memCtrl_io_snoopOut_index;
  wire       [31:0]   sys_1_io_rdData;
  wire       [31:0]   sys_1_io_wd;
  wire                sys_1_io_exc;
  wire                sys_1_io_irq;
  wire                sys_1_io_irqEna;
  wire                sys_1_io_syncOut_req;
  wire                sys_1_io_syncOut_reqPulse;
  wire                sys_1_io_syncOut_s_in;
  wire                sys_1_io_syncOut_gcHalt;
  wire       [31:0]   sys_1_io_syncOut_data;
  wire                sys_1_io_syncOut_op;
  wire                sys_1_io_halted;
  wire       [13:0]   sys_1_io_rootSel;
  wire                extBusy;
  wire       [31:0]   cardRdData;
  reg        [31:0]   ioRdData;
  wire                when_JopCore_l348;
  wire                when_JopCore_l351;
  wire                uartTxFire;
  reg                 uartTxValidReg;
  reg        [7:0]    uartTxDataReg;
  reg        [15:0]   ioRdCounter;
  reg        [15:0]   ioWrCounter;

  JopPipeline pipeline (
    .io_memRdData         (memCtrl_io_memOut_rdData[31:0]  ), //i
    .io_memBcStart        (memCtrl_io_memOut_bcStart[11:0] ), //i
    .io_memBusy           (pipeline_io_memBusy             ), //i
    .io_jbcWrAddr         (memCtrl_io_jbcWrite_addr[8:0]   ), //i
    .io_jbcWrData         (memCtrl_io_jbcWrite_data[31:0]  ), //i
    .io_jbcWrEn           (memCtrl_io_jbcWrite_enable      ), //i
    .io_memCtrl_rd        (pipeline_io_memCtrl_rd          ), //o
    .io_memCtrl_rdc       (pipeline_io_memCtrl_rdc         ), //o
    .io_memCtrl_rdf       (pipeline_io_memCtrl_rdf         ), //o
    .io_memCtrl_wr        (pipeline_io_memCtrl_wr          ), //o
    .io_memCtrl_wrf       (pipeline_io_memCtrl_wrf         ), //o
    .io_memCtrl_addrWr    (pipeline_io_memCtrl_addrWr      ), //o
    .io_memCtrl_bcRd      (pipeline_io_memCtrl_bcRd        ), //o
    .io_memCtrl_stidx     (pipeline_io_memCtrl_stidx       ), //o
    .io_memCtrl_iaload    (pipeline_io_memCtrl_iaload      ), //o
    .io_memCtrl_iastore   (pipeline_io_memCtrl_iastore     ), //o
    .io_memCtrl_getfield  (pipeline_io_memCtrl_getfield    ), //o
    .io_memCtrl_putfield  (pipeline_io_memCtrl_putfield    ), //o
    .io_memCtrl_putref    (pipeline_io_memCtrl_putref      ), //o
    .io_memCtrl_getstatic (pipeline_io_memCtrl_getstatic   ), //o
    .io_memCtrl_putstatic (pipeline_io_memCtrl_putstatic   ), //o
    .io_memCtrl_copy      (pipeline_io_memCtrl_copy        ), //o
    .io_memCtrl_cinval    (pipeline_io_memCtrl_cinval      ), //o
    .io_memCtrl_bcopd     (pipeline_io_memCtrl_bcopd[15:0] ), //o
    .io_aout              (pipeline_io_aout[31:0]          ), //o
    .io_bout              (pipeline_io_bout[31:0]          ), //o
    .io_bcopd             (pipeline_io_bcopd[15:0]         ), //o
    .io_irq               (sys_1_io_irq                    ), //i
    .io_irqEna            (sys_1_io_irqEna                 ), //i
    .io_exc               (sys_1_io_exc                    ), //i
    .io_ackIrq            (pipeline_io_ackIrq              ), //o
    .io_ackExc            (pipeline_io_ackExc              ), //o
    .io_hwBusy            (pipeline_io_hwBusy              ), //o
    .io_pc                (pipeline_io_pc[11:0]            ), //o
    .io_jpc               (pipeline_io_jpc[11:0]           ), //o
    .io_instr             (pipeline_io_instr[9:0]          ), //o
    .io_jfetch            (pipeline_io_jfetch              ), //o
    .io_jopdfetch         (pipeline_io_jopdfetch           ), //o
    .io_memBusyOut        (pipeline_io_memBusyOut          ), //o
    .io_debugBcRd         (pipeline_io_debugBcRd           ), //o
    .io_debugAddrWr       (pipeline_io_debugAddrWr         ), //o
    .io_debugRdc          (pipeline_io_debugRdc            ), //o
    .io_debugRd           (pipeline_io_debugRd             ), //o
    .io_debugRamAddr      (io_debugRamAddr[7:0]            ), //i
    .io_debugRamData      (pipeline_io_debugRamData[31:0]  ), //o
    .io_debugSp           (pipeline_io_debugSp[7:0]        ), //o
    .io_debugVp           (pipeline_io_debugVp[7:0]        ), //o
    .io_debugAr           (pipeline_io_debugAr[7:0]        ), //o
    .io_debugFlags        (pipeline_io_debugFlags[3:0]     ), //o
    .io_debugMulResult    (pipeline_io_debugMulResult[31:0]), //o
    .reset                (reset                           ), //i
    .clk                  (clk                             )  //i
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
    .io_bmb_cmd_payload_fragment_length  (memCtrl_io_bmb_cmd_payload_fragment_length[1:0]  ), //o
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
    .io_ioRdData                         (ioRdData[31:0]                                   ), //i
    .io_debug_state                      (memCtrl_io_debug_state[4:0]                      ), //o
    .io_debug_busy                       (memCtrl_io_debug_busy                            ), //o
    .io_debug_handleActive               (memCtrl_io_debug_handleActive                    ), //o
    .io_debug_addrReg                    (memCtrl_io_debug_addrReg[23:0]                   ), //o
    .io_debug_rdDataReg                  (memCtrl_io_debug_rdDataReg[31:0]                 ), //o
    .io_debug_bcFillAddr                 (memCtrl_io_debug_bcFillAddr[23:0]                ), //o
    .io_debug_bcFillLen                  (memCtrl_io_debug_bcFillLen[9:0]                  ), //o
    .io_debug_bcFillCount                (memCtrl_io_debug_bcFillCount[9:0]                ), //o
    .io_debug_bcRdCapture                (memCtrl_io_debug_bcRdCapture[31:0]               ), //o
    .io_snoopOut_valid                   (memCtrl_io_snoopOut_valid                        ), //o
    .io_snoopOut_isArray                 (memCtrl_io_snoopOut_isArray                      ), //o
    .io_snoopOut_handle                  (memCtrl_io_snoopOut_handle[23:0]                 ), //o
    .io_snoopOut_index                   (memCtrl_io_snoopOut_index[23:0]                  ), //o
    .io_snoopIn_valid                    (io_snoopIn_valid                                 ), //i
    .io_snoopIn_isArray                  (io_snoopIn_isArray                               ), //i
    .io_snoopIn_handle                   (io_snoopIn_handle[23:0]                          ), //i
    .io_snoopIn_index                    (io_snoopIn_index[23:0]                           ), //i
    .clk                                 (clk                                              ), //i
    .reset                               (reset                                            )  //i
  );
  Sys sys_1 (
    .io_addr             (sys_1_io_addr[3:0]         ), //i
    .io_rd               (sys_1_io_rd                ), //i
    .io_wr               (sys_1_io_wr                ), //i
    .io_wrData           (memCtrl_io_ioWrData[31:0]  ), //i
    .io_rdData           (sys_1_io_rdData[31:0]      ), //o
    .io_wd               (sys_1_io_wd[31:0]          ), //o
    .io_exc              (sys_1_io_exc               ), //o
    .io_irq              (sys_1_io_irq               ), //o
    .io_irqEna           (sys_1_io_irqEna            ), //o
    .io_ackIrq           (pipeline_io_ackIrq         ), //i
    .io_ackExc           (pipeline_io_ackExc         ), //i
    .io_ioInt            (2'b00                      ), //i
    .io_syncIn_halted    (io_syncIn_halted           ), //i
    .io_syncIn_s_out     (io_syncIn_s_out            ), //i
    .io_syncIn_status    (io_syncIn_status           ), //i
    .io_syncOut_req      (sys_1_io_syncOut_req       ), //o
    .io_syncOut_reqPulse (sys_1_io_syncOut_reqPulse  ), //o
    .io_syncOut_s_in     (sys_1_io_syncOut_s_in      ), //o
    .io_syncOut_gcHalt   (sys_1_io_syncOut_gcHalt    ), //o
    .io_syncOut_data     (sys_1_io_syncOut_data[31:0]), //o
    .io_syncOut_op       (sys_1_io_syncOut_op        ), //o
    .io_halted           (sys_1_io_halted            ), //o
    .io_rootSel          (sys_1_io_rootSel[13:0]     ), //o
    .io_rootData         (io_rootData[31:0]          ), //i
    .clk                 (clk                        ), //i
    .reset               (reset                      )  //i
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
  assign io_snoopOut_valid = memCtrl_io_snoopOut_valid;
  assign io_snoopOut_isArray = memCtrl_io_snoopOut_isArray;
  assign io_snoopOut_handle = memCtrl_io_snoopOut_handle;
  assign io_snoopOut_index = memCtrl_io_snoopOut_index;
  assign sys_1_io_addr = memCtrl_io_ioAddr[3 : 0];
  assign sys_1_io_rd = (memCtrl_io_ioRd && (memCtrl_io_ioAddr[7 : 4] == 4'b1111));
  assign sys_1_io_wr = (memCtrl_io_ioWr && (memCtrl_io_ioAddr[7 : 4] == 4'b1111));
  assign io_syncOut_req = sys_1_io_syncOut_req;
  assign io_syncOut_reqPulse = sys_1_io_syncOut_reqPulse;
  assign io_syncOut_s_in = sys_1_io_syncOut_s_in;
  assign io_syncOut_gcHalt = sys_1_io_syncOut_gcHalt;
  assign io_syncOut_data = sys_1_io_syncOut_data;
  assign io_syncOut_op = sys_1_io_syncOut_op;
  assign extBusy = 1'b0;
  assign pipeline_io_memBusy = ((((memCtrl_io_memOut_busy || sys_1_io_halted) || io_debugHalt) || pipeline_io_hwBusy) || extBusy);
  assign cardRdData = 32'h0;
  always @(*) begin
    ioRdData = 32'h0;
    if(when_JopCore_l348) begin
      ioRdData = sys_1_io_rdData;
    end
    if(when_JopCore_l351) begin
      ioRdData = 32'h00000001;
    end
  end

  assign when_JopCore_l348 = (memCtrl_io_ioAddr[7 : 4] == 4'b1111);
  assign when_JopCore_l351 = (memCtrl_io_ioAddr[7 : 1] == 7'h77);
  assign io_wd = sys_1_io_wd;
  assign uartTxFire = ((memCtrl_io_ioWr && (memCtrl_io_ioAddr[7 : 1] == 7'h77)) && (memCtrl_io_ioAddr[0 : 0] == 1'b1));
  assign io_uartTxValid = uartTxValidReg;
  assign io_uartTxData = uartTxDataReg;
  assign io_debugRamData = pipeline_io_debugRamData;
  assign io_pc = pipeline_io_pc;
  assign io_jpc = pipeline_io_jpc;
  assign io_instr = pipeline_io_instr;
  assign io_jfetch = pipeline_io_jfetch;
  assign io_jopdfetch = pipeline_io_jopdfetch;
  assign io_aout = pipeline_io_aout;
  assign io_bout = pipeline_io_bout;
  assign io_memBusy = memCtrl_io_memOut_busy;
  assign io_debugExc = sys_1_io_exc;
  assign io_debugBcRd = pipeline_io_debugBcRd;
  assign io_debugMemState = memCtrl_io_debug_state;
  assign io_debugBcFillAddr = memCtrl_io_debug_bcFillAddr;
  assign io_debugBcFillLen = memCtrl_io_debug_bcFillLen;
  assign io_debugBcFillCount = memCtrl_io_debug_bcFillCount;
  assign io_debugBcRdCapture = memCtrl_io_debug_bcRdCapture;
  assign io_debugMemHandleActive = memCtrl_io_debug_handleActive;
  assign io_debugAddrWr = pipeline_io_debugAddrWr;
  assign io_debugRdc = pipeline_io_debugRdc;
  assign io_debugRd = pipeline_io_debugRd;
  assign io_debugIoRdCount = ioRdCounter;
  assign io_debugIoWrCount = ioWrCounter;
  assign io_debugHalted = io_debugHalt;
  assign io_debugSp = pipeline_io_debugSp;
  assign io_rootSel = sys_1_io_rootSel;
  assign io_stackA = pipeline_io_aout;
  assign io_stackB = pipeline_io_bout;
  assign io_debugVp = pipeline_io_debugVp;
  assign io_debugAr = pipeline_io_debugAr;
  assign io_debugFlags = pipeline_io_debugFlags;
  assign io_debugMulResult = pipeline_io_debugMulResult;
  assign io_debugAddrReg = memCtrl_io_debug_addrReg;
  assign io_debugRdDataReg = memCtrl_io_debug_rdDataReg;
  assign io_debugInstr = pipeline_io_instr;
  assign io_debugBcopd = pipeline_io_bcopd;
  always @(posedge clk or posedge reset) begin
    if(reset) begin
      uartTxValidReg <= 1'b0;
      uartTxDataReg <= 8'h0;
      ioRdCounter <= 16'h0;
      ioWrCounter <= 16'h0;
    end else begin
      uartTxValidReg <= uartTxFire;
      if(uartTxFire) begin
        uartTxDataReg <= memCtrl_io_ioWrData[7 : 0];
      end
      if(memCtrl_io_ioRd) begin
        ioRdCounter <= (ioRdCounter + 16'h0001);
      end
      if(memCtrl_io_ioWr) begin
        ioWrCounter <= (ioWrCounter + 16'h0001);
      end
    end
  end


endmodule

module Sys (
  input  wire [3:0]    io_addr,
  input  wire          io_rd,
  input  wire          io_wr,
  input  wire [31:0]   io_wrData,
  output reg  [31:0]   io_rdData,
  output wire [31:0]   io_wd,
  output wire          io_exc,
  output wire          io_irq,
  output wire          io_irqEna,
  input  wire          io_ackIrq,
  input  wire          io_ackExc,
  input  wire [1:0]    io_ioInt,
  input  wire          io_syncIn_halted,
  input  wire          io_syncIn_s_out,
  input  wire          io_syncIn_status,
  output wire          io_syncOut_req,
  output wire          io_syncOut_reqPulse,
  output wire          io_syncOut_s_in,
  output wire          io_syncOut_gcHalt,
  output wire [31:0]   io_syncOut_data,
  output wire          io_syncOut_op,
  output wire          io_halted,
  output wire [13:0]   io_rootSel,
  input  wire [31:0]   io_rootData,
  input  wire          clk,
  input  wire          reset
);

  wire       [0:0]    _zz_io_rdData;
  reg        [31:0]   clockCntReg;
  reg        [7:0]    preScale;
  reg        [31:0]   usCntReg;
  wire                when_Sys_l90;
  reg        [31:0]   timerReg;
  wire                timerEqu;
  reg                 timerDly;
  wire                timerInt;
  reg        [2:0]    hwReq;
  reg        [2:0]    swReq;
  reg        [2:0]    mask;
  reg                 clearAll;
  reg        [4:0]    prioInt;
  reg        [2:0]    ack;
  wire       [2:0]    intReq;
  reg        [2:0]    flag;
  reg        [2:0]    pending;
  wire                when_Sys_l145;
  wire                when_Sys_l147;
  wire                when_Sys_l145_1;
  wire                when_Sys_l147_1;
  wire                when_Sys_l145_2;
  wire                when_Sys_l147_2;
  reg                 intPend;
  wire                when_Sys_l162;
  wire                when_Sys_l162_1;
  wire                when_Sys_l162_2;
  reg                 intEna;
  wire                irqGate;
  reg                 irqDly;
  reg        [4:0]    intNr;
  wire                when_Sys_l192;
  reg        [31:0]   wdReg;
  reg        [7:0]    excTypeReg;
  reg                 excPend;
  reg                 excDly;
  reg                 lockReqReg;
  reg                 lockReqPulseReg;
  reg        [31:0]   lockDataReg;
  reg                 lockOpReg;
  reg                 signalReg;
  reg                 gcHaltReg;
  reg        [13:0]   rootSelReg;
  wire                when_Sys_l312;
  wire                when_Sys_l312_1;
  wire                when_Sys_l312_2;

  assign _zz_io_rdData = io_syncIn_s_out;
  assign when_Sys_l90 = (preScale == 8'h0);
  assign timerEqu = (usCntReg == timerReg);
  assign timerInt = (timerEqu && (! timerDly));
  always @(*) begin
    hwReq[0] = timerInt;
    hwReq[2 : 1] = io_ioInt;
  end

  always @(*) begin
    ack[0] = (io_ackIrq && (prioInt == 5'h0));
    ack[1] = (io_ackIrq && (prioInt == 5'h01));
    ack[2] = (io_ackIrq && (prioInt == 5'h02));
  end

  assign intReq = (hwReq | swReq);
  assign when_Sys_l145 = (ack[0] || clearAll);
  assign when_Sys_l147 = intReq[0];
  always @(*) begin
    pending[0] = (flag[0] && mask[0]);
    pending[1] = (flag[1] && mask[1]);
    pending[2] = (flag[2] && mask[2]);
  end

  assign when_Sys_l145_1 = (ack[1] || clearAll);
  assign when_Sys_l147_1 = intReq[1];
  assign when_Sys_l145_2 = (ack[2] || clearAll);
  assign when_Sys_l147_2 = intReq[2];
  always @(*) begin
    intPend = 1'b0;
    if(when_Sys_l162) begin
      intPend = 1'b1;
    end
    if(when_Sys_l162_1) begin
      intPend = 1'b1;
    end
    if(when_Sys_l162_2) begin
      intPend = 1'b1;
    end
  end

  always @(*) begin
    prioInt = 5'h0;
    if(when_Sys_l162) begin
      prioInt = 5'h0;
    end
    if(when_Sys_l162_1) begin
      prioInt = 5'h01;
    end
    if(when_Sys_l162_2) begin
      prioInt = 5'h02;
    end
  end

  assign when_Sys_l162 = pending[0];
  assign when_Sys_l162_1 = pending[1];
  assign when_Sys_l162_2 = pending[2];
  assign irqGate = (intPend && intEna);
  assign io_irq = (irqGate && (! irqDly));
  assign io_irqEna = intEna;
  assign when_Sys_l192 = (io_ackIrq || io_ackExc);
  assign io_exc = (excPend && (! excDly));
  assign io_syncOut_req = lockReqReg;
  assign io_syncOut_reqPulse = lockReqPulseReg;
  assign io_syncOut_s_in = signalReg;
  assign io_syncOut_gcHalt = gcHaltReg;
  assign io_syncOut_data = lockDataReg;
  assign io_syncOut_op = lockOpReg;
  assign io_halted = io_syncIn_halted;
  always @(*) begin
    io_rdData = 32'h0;
    case(io_addr)
      4'b0000 : begin
        io_rdData = clockCntReg;
      end
      4'b0001 : begin
        io_rdData = usCntReg;
      end
      4'b0010 : begin
        io_rdData[4 : 0] = intNr;
        io_rdData[31 : 5] = 27'h0;
      end
      4'b0100 : begin
        io_rdData = {24'd0, excTypeReg};
      end
      4'b0101 : begin
        io_rdData[0] = io_syncIn_halted;
        io_rdData[1] = io_syncIn_status;
        io_rdData[31 : 2] = 30'h0;
      end
      4'b0110 : begin
        io_rdData = 32'h0;
      end
      4'b0111 : begin
        io_rdData = {31'd0, _zz_io_rdData};
      end
      4'b1011 : begin
        io_rdData = 32'h00000001;
      end
      4'b1110 : begin
        io_rdData = 32'h00200000;
      end
      4'b1111 : begin
        io_rdData = 32'h0;
      end
      4'b1101 : begin
        io_rdData = io_rootData;
      end
      default : begin
      end
    endcase
  end

  assign when_Sys_l312 = (io_wrData[1 : 0] == 2'b00);
  assign when_Sys_l312_1 = (io_wrData[1 : 0] == 2'b01);
  assign when_Sys_l312_2 = (io_wrData[1 : 0] == 2'b10);
  assign io_wd = wdReg;
  assign io_rootSel = rootSelReg;
  always @(posedge clk or posedge reset) begin
    if(reset) begin
      clockCntReg <= 32'h0;
      preScale <= 8'h63;
      usCntReg <= 32'h0;
      timerReg <= 32'h0;
      timerDly <= 1'b0;
      swReq <= 3'b000;
      mask <= 3'b000;
      clearAll <= 1'b0;
      flag <= 3'b000;
      intEna <= 1'b0;
      irqDly <= 1'b0;
      intNr <= 5'h0;
      wdReg <= 32'h0;
      excTypeReg <= 8'h0;
      excPend <= 1'b0;
      excDly <= 1'b0;
      lockReqReg <= 1'b0;
      lockReqPulseReg <= 1'b0;
      lockDataReg <= 32'h0;
      lockOpReg <= 1'b0;
      signalReg <= 1'b0;
      gcHaltReg <= 1'b0;
      rootSelReg <= 14'h0;
    end else begin
      clockCntReg <= (clockCntReg + 32'h00000001);
      preScale <= (preScale - 8'h01);
      if(when_Sys_l90) begin
        preScale <= 8'h63;
        usCntReg <= (usCntReg + 32'h00000001);
      end
      timerDly <= timerEqu;
      if(when_Sys_l145) begin
        flag[0] <= 1'b0;
      end else begin
        if(when_Sys_l147) begin
          flag[0] <= 1'b1;
        end
      end
      if(when_Sys_l145_1) begin
        flag[1] <= 1'b0;
      end else begin
        if(when_Sys_l147_1) begin
          flag[1] <= 1'b1;
        end
      end
      if(when_Sys_l145_2) begin
        flag[2] <= 1'b0;
      end else begin
        if(when_Sys_l147_2) begin
          flag[2] <= 1'b1;
        end
      end
      irqDly <= irqGate;
      if(io_ackIrq) begin
        intNr <= prioInt;
      end
      if(when_Sys_l192) begin
        intEna <= 1'b0;
      end
      excPend <= 1'b0;
      excDly <= excPend;
      lockReqPulseReg <= 1'b0;
      swReq <= 3'b000;
      clearAll <= 1'b0;
      if(io_wr) begin
        case(io_addr)
          4'b0000 : begin
            intEna <= io_wrData[0];
          end
          4'b0001 : begin
            timerReg <= io_wrData;
          end
          4'b0010 : begin
            if(when_Sys_l312) begin
              swReq[0] <= 1'b1;
            end
            if(when_Sys_l312_1) begin
              swReq[1] <= 1'b1;
            end
            if(when_Sys_l312_2) begin
              swReq[2] <= 1'b1;
            end
          end
          4'b0011 : begin
            wdReg <= io_wrData;
          end
          4'b0100 : begin
            excTypeReg <= io_wrData[7 : 0];
            excPend <= 1'b1;
          end
          4'b0101 : begin
            lockReqReg <= 1'b1;
            lockReqPulseReg <= 1'b1;
            lockDataReg <= io_wrData;
            lockOpReg <= 1'b0;
          end
          4'b0110 : begin
            lockReqReg <= 1'b0;
            lockReqPulseReg <= 1'b1;
            lockDataReg <= io_wrData;
            lockOpReg <= 1'b1;
          end
          4'b0111 : begin
            signalReg <= io_wrData[0];
          end
          4'b1000 : begin
            mask <= io_wrData[2 : 0];
          end
          4'b1001 : begin
            clearAll <= 1'b1;
          end
          4'b1101 : begin
            gcHaltReg <= io_wrData[0];
          end
          4'b1110 : begin
            rootSelReg <= io_wrData[13 : 0];
          end
          default : begin
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
  output wire [1:0]    io_bmb_cmd_payload_fragment_length,
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
  output wire [23:0]   io_debug_addrReg,
  output wire [31:0]   io_debug_rdDataReg,
  output wire [23:0]   io_debug_bcFillAddr,
  output wire [9:0]    io_debug_bcFillLen,
  output wire [9:0]    io_debug_bcFillCount,
  output wire [31:0]   io_debug_bcRdCapture,
  output reg           io_snoopOut_valid,
  output reg           io_snoopOut_isArray,
  output reg  [23:0]   io_snoopOut_handle,
  output reg  [23:0]   io_snoopOut_index,
  input  wire          io_snoopIn_valid,
  input  wire          io_snoopIn_isArray,
  input  wire [23:0]   io_snoopIn_handle,
  input  wire [23:0]   io_snoopIn_index,
  input  wire          clk,
  input  wire          reset
);
  localparam State_4_IDLE = 5'd0;
  localparam State_4_READ_WAIT = 5'd1;
  localparam State_4_WRITE_WAIT = 5'd2;
  localparam State_4_IAST_WAIT = 5'd3;
  localparam State_4_PF_WAIT = 5'd4;
  localparam State_4_HANDLE_READ = 5'd5;
  localparam State_4_HANDLE_WAIT = 5'd6;
  localparam State_4_HANDLE_CALC = 5'd7;
  localparam State_4_HANDLE_ACCESS = 5'd8;
  localparam State_4_HANDLE_DATA_WAIT = 5'd9;
  localparam State_4_HANDLE_BOUND_READ = 5'd10;
  localparam State_4_HANDLE_BOUND_WAIT = 5'd11;
  localparam State_4_NP_EXC = 5'd12;
  localparam State_4_AB_EXC = 5'd13;
  localparam State_4_BC_CACHE_CHECK = 5'd14;
  localparam State_4_BC_FILL_R1 = 5'd15;
  localparam State_4_BC_FILL_LOOP = 5'd16;
  localparam State_4_BC_FILL_CMD = 5'd17;
  localparam State_4_AC_FILL_CMD = 5'd18;
  localparam State_4_AC_FILL_WAIT = 5'd19;
  localparam State_4_CP_SETUP = 5'd20;
  localparam State_4_CP_READ = 5'd21;
  localparam State_4_CP_READ_WAIT = 5'd22;
  localparam State_4_CP_WRITE = 5'd23;
  localparam State_4_CP_STOP = 5'd24;
  localparam State_4_ZERO_RUN = 5'd25;
  localparam State_4_ZERO_WAIT = 5'd26;
  localparam State_4_FILL_REQ = 5'd27;
  localparam State_4_FILL_WAIT = 5'd28;
  localparam State_4_GS_READ = 5'd29;
  localparam State_4_PS_WRITE = 5'd30;
  localparam State_4_LAST = 5'd31;

  wire       [17:0]   methodCache_1_io_bcAddr;
  reg        [23:0]   objectCache_1_io_handle;
  wire       [7:0]    objectCache_1_io_fieldIdx;
  wire                objectCache_1_io_chkGf;
  reg                 objectCache_1_io_chkPf;
  reg                 objectCache_1_io_wrGf;
  reg                 objectCache_1_io_wrPf;
  wire                objectCache_1_io_inval;
  wire                objectCache_1_io_snoopValid;
  wire       [7:0]    objectCache_1_io_snoopFieldIdx;
  wire       [23:0]   arrayCache_1_io_handle;
  wire                arrayCache_1_io_chkIal;
  reg                 arrayCache_1_io_chkIas;
  reg                 arrayCache_1_io_wrIal;
  reg                 arrayCache_1_io_wrIas;
  reg        [31:0]   arrayCache_1_io_ialVal;
  wire                arrayCache_1_io_inval;
  wire                arrayCache_1_io_snoopValid;
  wire       [8:0]    methodCache_1_io_bcStart;
  wire                methodCache_1_io_rdy;
  wire                methodCache_1_io_inCache;
  wire                objectCache_1_io_hit;
  wire       [31:0]   objectCache_1_io_dout;
  wire                arrayCache_1_io_hit;
  wire       [31:0]   arrayCache_1_io_dout;
  wire       [7:0]    _zz_when_BmbMemoryController_l590;
  wire       [7:0]    _zz_when_BmbMemoryController_l593;
  wire       [23:0]   _zz_addrReg;
  wire       [15:0]   _zz_addrReg_1;
  wire       [23:0]   _zz_addrReg_2;
  wire       [15:0]   _zz_addrReg_3;
  wire       [21:0]   _zz_bcFillAddr_1;
  wire       [31:0]   _zz_bcFillLen;
  wire       [23:0]   _zz_handleIndex;
  wire       [15:0]   _zz_handleIndex_1;
  wire       [10:0]   _zz_bcStartReg;
  wire       [9:0]    _zz_jbcWrAddrReg;
  wire       [9:0]    _zz_jbcWrAddrReg_1;
  wire       [23:0]   _zz_handleIndex_2;
  wire       [15:0]   _zz_handleIndex_3;
  wire       [23:0]   _zz_wasHwo;
  wire       [23:0]   _zz_acFillAddr;
  wire       [21:0]   _zz_acFillAddr_1;
  wire       [23:0]   _zz_io_bmb_cmd_payload_fragment_address;
  wire       [23:0]   _zz_io_bmb_cmd_payload_fragment_address_1;
  wire       [23:0]   _zz_io_bmb_cmd_payload_fragment_address_2;
  reg        [4:0]    state_6;
  reg        [23:0]   addrReg;
  reg        [23:0]   zeroCur;
  reg        [23:0]   zeroEnd;
  reg        [31:0]   rdDataReg;
  reg                 ioRdPending;
  reg        [7:0]    ioRdSavedAddr;
  reg        [23:0]   handleDataPtr;
  reg        [23:0]   handleIndex;
  reg                 handleIsWrite;
  reg                 handleIsArray;
  reg        [31:0]   handleWriteData;
  reg        [23:0]   bcFillAddr;
  reg        [9:0]    bcFillLen;
  reg        [9:0]    bcFillCount;
  reg        [11:0]   bcStartReg;
  reg        [31:0]   bcRdCaptureReg;
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
  reg                 readObjectCache;
  reg                 ocWasGetfield;
  reg                 wasHwo;
  reg        [23:0]   handleAddrReg;
  reg        [23:0]   snoopHandleReg;
  reg                 readArrayCache;
  reg        [23:0]   acFillAddr;
  reg        [1:0]    acFillCount;
  reg        [1:0]    acFillRequestedIdx;
  wire                notBusy;
  wire                io_bmb_rsp_fire;
  wire                when_BmbMemoryController_l298;
  wire       [23:0]   aoutAddr;
  wire                aoutIsIo;
  wire                addrIsIo;
  wire                memReadRequested;
  reg                 mcacheFind;
  wire                when_BmbMemoryController_l469;
  wire                when_BmbMemoryController_l474;
  wire                when_BmbMemoryController_l496;
  wire                when_BmbMemoryController_l501;
  wire                when_BmbMemoryController_l556;
  wire                when_BmbMemoryController_l590;
  wire                when_BmbMemoryController_l593;
  wire       [23:0]   _zz_zeroEnd;
  wire                when_BmbMemoryController_l602;
  wire       [31:0]   _zz_bcFillAddr;
  wire                when_BmbMemoryController_l687;
  wire                when_BmbMemoryController_l587;
  wire                when_BmbMemoryController_l753;
  wire                when_BmbMemoryController_l760;
  wire                when_BmbMemoryController_l767;
  wire                io_bmb_cmd_fire;
  wire                when_BmbMemoryController_l790;
  wire                when_BmbMemoryController_l797;
  wire                when_BmbMemoryController_l804;
  wire       [9:0]    _zz_bcFillCount;
  wire                when_BmbMemoryController_l899;
  wire                when_BmbMemoryController_l987;
  wire                when_BmbMemoryController_l1017;
  wire                when_BmbMemoryController_l1022;
  wire                when_BmbMemoryController_l1067;
  wire                when_BmbMemoryController_l1119;
  wire                when_BmbMemoryController_l1126;
  wire                when_BmbMemoryController_l1141;
  wire                when_BmbMemoryController_l1199;
  wire                when_BmbMemoryController_l1213;
  wire                when_BmbMemoryController_l1244;
  wire                when_BmbMemoryController_l1379;
  wire                when_BmbMemoryController_l1434;
  wire                when_BmbMemoryController_l1440;
  reg                 _zz_io_debug_handleActive;
  `ifndef SYNTHESIS
  reg [135:0] state_6_string;
  `endif


  assign _zz_when_BmbMemoryController_l590 = addrReg[7 : 0];
  assign _zz_when_BmbMemoryController_l593 = addrReg[7 : 0];
  assign _zz_addrReg_1 = io_bcopd;
  assign _zz_addrReg = {8'd0, _zz_addrReg_1};
  assign _zz_addrReg_3 = io_bcopd;
  assign _zz_addrReg_2 = {8'd0, _zz_addrReg_3};
  assign _zz_bcFillAddr_1 = (_zz_bcFillAddr >>> 4'd10);
  assign _zz_bcFillLen = (_zz_bcFillAddr & 32'h000003ff);
  assign _zz_handleIndex_1 = io_bcopd[15 : 0];
  assign _zz_handleIndex = {8'd0, _zz_handleIndex_1};
  assign _zz_bcStartReg = {methodCache_1_io_bcStart,2'b00};
  assign _zz_jbcWrAddrReg = (_zz_jbcWrAddrReg_1 + bcFillCount);
  assign _zz_jbcWrAddrReg_1 = {1'd0, bcCacheStartReg};
  assign _zz_handleIndex_3 = io_bcopd[15 : 0];
  assign _zz_handleIndex_2 = {8'd0, _zz_handleIndex_3};
  assign _zz_wasHwo = io_bmb_rsp_payload_fragment_data[23 : 0];
  assign _zz_acFillAddr = ({2'd0,_zz_acFillAddr_1} <<< 2'd2);
  assign _zz_acFillAddr_1 = (handleIndex >>> 2'd2);
  assign _zz_io_bmb_cmd_payload_fragment_address = (acFillAddr + _zz_io_bmb_cmd_payload_fragment_address_1);
  assign _zz_io_bmb_cmd_payload_fragment_address_1 = {22'd0, acFillCount};
  assign _zz_io_bmb_cmd_payload_fragment_address_2 = (addrReg + 24'h000001);
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
  ObjectCache objectCache_1 (
    .io_handle        (objectCache_1_io_handle[23:0]         ), //i
    .io_fieldIdx      (objectCache_1_io_fieldIdx[7:0]        ), //i
    .io_chkGf         (objectCache_1_io_chkGf                ), //i
    .io_chkPf         (objectCache_1_io_chkPf                ), //i
    .io_hit           (objectCache_1_io_hit                  ), //o
    .io_dout          (objectCache_1_io_dout[31:0]           ), //o
    .io_wrGf          (objectCache_1_io_wrGf                 ), //i
    .io_wrPf          (objectCache_1_io_wrPf                 ), //i
    .io_gfVal         (io_bmb_rsp_payload_fragment_data[31:0]), //i
    .io_pfVal         (handleWriteData[31:0]                 ), //i
    .io_inval         (objectCache_1_io_inval                ), //i
    .io_snoopValid    (objectCache_1_io_snoopValid           ), //i
    .io_snoopHandle   (io_snoopIn_handle[23:0]               ), //i
    .io_snoopFieldIdx (objectCache_1_io_snoopFieldIdx[7:0]   ), //i
    .clk              (clk                                   ), //i
    .reset            (reset                                 )  //i
  );
  ArrayCache arrayCache_1 (
    .io_handle      (arrayCache_1_io_handle[23:0]), //i
    .io_index       (aoutAddr[23:0]              ), //i
    .io_chkIal      (arrayCache_1_io_chkIal      ), //i
    .io_chkIas      (arrayCache_1_io_chkIas      ), //i
    .io_hit         (arrayCache_1_io_hit         ), //o
    .io_dout        (arrayCache_1_io_dout[31:0]  ), //o
    .io_wrIal       (arrayCache_1_io_wrIal       ), //i
    .io_wrIas       (arrayCache_1_io_wrIas       ), //i
    .io_ialVal      (arrayCache_1_io_ialVal[31:0]), //i
    .io_iasVal      (handleWriteData[31:0]       ), //i
    .io_inval       (arrayCache_1_io_inval       ), //i
    .io_snoopValid  (arrayCache_1_io_snoopValid  ), //i
    .io_snoopHandle (io_snoopIn_handle[23:0]     ), //i
    .io_snoopIndex  (io_snoopIn_index[23:0]      ), //i
    .clk            (clk                         ), //i
    .reset          (reset                       )  //i
  );
  `ifndef SYNTHESIS
  always @(*) begin
    case(state_6)
      State_4_IDLE : state_6_string = "IDLE             ";
      State_4_READ_WAIT : state_6_string = "READ_WAIT        ";
      State_4_WRITE_WAIT : state_6_string = "WRITE_WAIT       ";
      State_4_IAST_WAIT : state_6_string = "IAST_WAIT        ";
      State_4_PF_WAIT : state_6_string = "PF_WAIT          ";
      State_4_HANDLE_READ : state_6_string = "HANDLE_READ      ";
      State_4_HANDLE_WAIT : state_6_string = "HANDLE_WAIT      ";
      State_4_HANDLE_CALC : state_6_string = "HANDLE_CALC      ";
      State_4_HANDLE_ACCESS : state_6_string = "HANDLE_ACCESS    ";
      State_4_HANDLE_DATA_WAIT : state_6_string = "HANDLE_DATA_WAIT ";
      State_4_HANDLE_BOUND_READ : state_6_string = "HANDLE_BOUND_READ";
      State_4_HANDLE_BOUND_WAIT : state_6_string = "HANDLE_BOUND_WAIT";
      State_4_NP_EXC : state_6_string = "NP_EXC           ";
      State_4_AB_EXC : state_6_string = "AB_EXC           ";
      State_4_BC_CACHE_CHECK : state_6_string = "BC_CACHE_CHECK   ";
      State_4_BC_FILL_R1 : state_6_string = "BC_FILL_R1       ";
      State_4_BC_FILL_LOOP : state_6_string = "BC_FILL_LOOP     ";
      State_4_BC_FILL_CMD : state_6_string = "BC_FILL_CMD      ";
      State_4_AC_FILL_CMD : state_6_string = "AC_FILL_CMD      ";
      State_4_AC_FILL_WAIT : state_6_string = "AC_FILL_WAIT     ";
      State_4_CP_SETUP : state_6_string = "CP_SETUP         ";
      State_4_CP_READ : state_6_string = "CP_READ          ";
      State_4_CP_READ_WAIT : state_6_string = "CP_READ_WAIT     ";
      State_4_CP_WRITE : state_6_string = "CP_WRITE         ";
      State_4_CP_STOP : state_6_string = "CP_STOP          ";
      State_4_ZERO_RUN : state_6_string = "ZERO_RUN         ";
      State_4_ZERO_WAIT : state_6_string = "ZERO_WAIT        ";
      State_4_FILL_REQ : state_6_string = "FILL_REQ         ";
      State_4_FILL_WAIT : state_6_string = "FILL_WAIT        ";
      State_4_GS_READ : state_6_string = "GS_READ          ";
      State_4_PS_WRITE : state_6_string = "PS_WRITE         ";
      State_4_LAST : state_6_string = "LAST             ";
      default : state_6_string = "?????????????????";
    endcase
  end
  `endif

  assign notBusy = ((((state_6 == State_4_IDLE) || (((state_6 == State_4_READ_WAIT) || (state_6 == State_4_WRITE_WAIT)) && io_bmb_rsp_valid)) || (state_6 == State_4_NP_EXC)) || (state_6 == State_4_AB_EXC));
  assign io_memOut_busy = (! notBusy);
  always @(*) begin
    io_memOut_rdData = rdDataReg;
    if(when_BmbMemoryController_l298) begin
      io_memOut_rdData = io_bmb_rsp_payload_fragment_data;
    end
    if(readArrayCache) begin
      io_memOut_rdData = arrayCache_1_io_dout;
    end
    if(readObjectCache) begin
      io_memOut_rdData = objectCache_1_io_dout;
    end
  end

  assign io_bmb_rsp_fire = (io_bmb_rsp_valid && io_bmb_rsp_ready);
  assign when_BmbMemoryController_l298 = (io_bmb_rsp_fire && (state_6 == State_4_READ_WAIT));
  assign io_memOut_bcStart = bcStartReg;
  always @(*) begin
    io_bmb_cmd_valid = 1'b0;
    case(state_6)
      State_4_IDLE : begin
        if(memReadRequested) begin
          if(!aoutIsIo) begin
            io_bmb_cmd_valid = 1'b1;
          end
        end else begin
          if(when_BmbMemoryController_l587) begin
            if(!addrIsIo) begin
              io_bmb_cmd_valid = 1'b1;
            end
          end
        end
      end
      State_4_READ_WAIT : begin
        if(when_BmbMemoryController_l767) begin
          io_bmb_cmd_valid = 1'b1;
        end
      end
      State_4_WRITE_WAIT : begin
        if(when_BmbMemoryController_l804) begin
          io_bmb_cmd_valid = 1'b1;
        end
      end
      State_4_IAST_WAIT : begin
      end
      State_4_BC_CACHE_CHECK : begin
      end
      State_4_BC_FILL_R1 : begin
        io_bmb_cmd_valid = 1'b1;
      end
      State_4_BC_FILL_LOOP : begin
        if(io_bmb_rsp_fire) begin
          if(!when_BmbMemoryController_l899) begin
            io_bmb_cmd_valid = 1'b1;
          end
        end
      end
      State_4_BC_FILL_CMD : begin
        io_bmb_cmd_valid = 1'b1;
      end
      State_4_PF_WAIT : begin
      end
      State_4_HANDLE_READ : begin
        if(!when_BmbMemoryController_l1017) begin
          if(!when_BmbMemoryController_l1022) begin
            io_bmb_cmd_valid = 1'b1;
          end
        end
      end
      State_4_HANDLE_WAIT : begin
      end
      State_4_HANDLE_CALC : begin
      end
      State_4_HANDLE_ACCESS : begin
        if(!addrIsIo) begin
          io_bmb_cmd_valid = 1'b1;
        end
      end
      State_4_HANDLE_DATA_WAIT : begin
      end
      State_4_AC_FILL_CMD : begin
        io_bmb_cmd_valid = 1'b1;
      end
      State_4_AC_FILL_WAIT : begin
      end
      State_4_HANDLE_BOUND_READ : begin
        io_bmb_cmd_valid = 1'b1;
      end
      State_4_HANDLE_BOUND_WAIT : begin
      end
      State_4_NP_EXC : begin
      end
      State_4_AB_EXC : begin
      end
      State_4_GS_READ : begin
        io_bmb_cmd_valid = 1'b1;
      end
      State_4_PS_WRITE : begin
        io_bmb_cmd_valid = 1'b1;
      end
      State_4_LAST : begin
      end
      State_4_CP_SETUP : begin
      end
      State_4_CP_READ : begin
        io_bmb_cmd_valid = 1'b1;
      end
      State_4_CP_READ_WAIT : begin
      end
      State_4_CP_WRITE : begin
        io_bmb_cmd_valid = 1'b1;
      end
      State_4_CP_STOP : begin
      end
      State_4_ZERO_RUN : begin
        if(!when_BmbMemoryController_l1379) begin
          io_bmb_cmd_valid = 1'b1;
        end
      end
      State_4_ZERO_WAIT : begin
      end
      State_4_FILL_REQ : begin
      end
      default : begin
      end
    endcase
  end

  assign io_bmb_cmd_payload_last = 1'b1;
  always @(*) begin
    io_bmb_cmd_payload_fragment_opcode = 1'b0;
    case(state_6)
      State_4_IDLE : begin
        if(memReadRequested) begin
          if(!aoutIsIo) begin
            io_bmb_cmd_payload_fragment_opcode = 1'b0;
          end
        end else begin
          if(when_BmbMemoryController_l587) begin
            if(!addrIsIo) begin
              io_bmb_cmd_payload_fragment_opcode = 1'b1;
            end
          end
        end
      end
      State_4_READ_WAIT : begin
        if(when_BmbMemoryController_l767) begin
          io_bmb_cmd_payload_fragment_opcode = 1'b0;
        end
      end
      State_4_WRITE_WAIT : begin
        if(when_BmbMemoryController_l804) begin
          io_bmb_cmd_payload_fragment_opcode = 1'b1;
        end
      end
      State_4_IAST_WAIT : begin
      end
      State_4_BC_CACHE_CHECK : begin
      end
      State_4_BC_FILL_R1 : begin
        io_bmb_cmd_payload_fragment_opcode = 1'b0;
      end
      State_4_BC_FILL_LOOP : begin
        if(io_bmb_rsp_fire) begin
          if(!when_BmbMemoryController_l899) begin
            io_bmb_cmd_payload_fragment_opcode = 1'b0;
          end
        end
      end
      State_4_BC_FILL_CMD : begin
        io_bmb_cmd_payload_fragment_opcode = 1'b0;
      end
      State_4_PF_WAIT : begin
      end
      State_4_HANDLE_READ : begin
        if(!when_BmbMemoryController_l1017) begin
          if(!when_BmbMemoryController_l1022) begin
            io_bmb_cmd_payload_fragment_opcode = 1'b0;
          end
        end
      end
      State_4_HANDLE_WAIT : begin
      end
      State_4_HANDLE_CALC : begin
      end
      State_4_HANDLE_ACCESS : begin
        if(!addrIsIo) begin
          if(handleIsWrite) begin
            io_bmb_cmd_payload_fragment_opcode = 1'b1;
          end else begin
            io_bmb_cmd_payload_fragment_opcode = 1'b0;
          end
        end
      end
      State_4_HANDLE_DATA_WAIT : begin
      end
      State_4_AC_FILL_CMD : begin
        io_bmb_cmd_payload_fragment_opcode = 1'b0;
      end
      State_4_AC_FILL_WAIT : begin
      end
      State_4_HANDLE_BOUND_READ : begin
        io_bmb_cmd_payload_fragment_opcode = 1'b0;
      end
      State_4_HANDLE_BOUND_WAIT : begin
      end
      State_4_NP_EXC : begin
      end
      State_4_AB_EXC : begin
      end
      State_4_GS_READ : begin
        io_bmb_cmd_payload_fragment_opcode = 1'b0;
      end
      State_4_PS_WRITE : begin
        io_bmb_cmd_payload_fragment_opcode = 1'b1;
      end
      State_4_LAST : begin
      end
      State_4_CP_SETUP : begin
      end
      State_4_CP_READ : begin
        io_bmb_cmd_payload_fragment_opcode = 1'b0;
      end
      State_4_CP_READ_WAIT : begin
      end
      State_4_CP_WRITE : begin
        io_bmb_cmd_payload_fragment_opcode = 1'b1;
      end
      State_4_CP_STOP : begin
      end
      State_4_ZERO_RUN : begin
        if(!when_BmbMemoryController_l1379) begin
          io_bmb_cmd_payload_fragment_opcode = 1'b1;
        end
      end
      State_4_ZERO_WAIT : begin
      end
      State_4_FILL_REQ : begin
      end
      default : begin
      end
    endcase
  end

  always @(*) begin
    io_bmb_cmd_payload_fragment_address = 26'h0;
    case(state_6)
      State_4_IDLE : begin
        if(memReadRequested) begin
          if(!aoutIsIo) begin
            io_bmb_cmd_payload_fragment_address = ({2'd0,aoutAddr} <<< 2'd2);
          end
        end else begin
          if(when_BmbMemoryController_l587) begin
            if(!addrIsIo) begin
              io_bmb_cmd_payload_fragment_address = ({2'd0,addrReg} <<< 2'd2);
            end
          end
        end
      end
      State_4_READ_WAIT : begin
        if(when_BmbMemoryController_l767) begin
          io_bmb_cmd_payload_fragment_address = pendingCmdAddr;
        end
      end
      State_4_WRITE_WAIT : begin
        if(when_BmbMemoryController_l804) begin
          io_bmb_cmd_payload_fragment_address = pendingCmdAddr;
        end
      end
      State_4_IAST_WAIT : begin
      end
      State_4_BC_CACHE_CHECK : begin
      end
      State_4_BC_FILL_R1 : begin
        io_bmb_cmd_payload_fragment_address = ({2'd0,bcFillAddr} <<< 2'd2);
      end
      State_4_BC_FILL_LOOP : begin
        if(io_bmb_rsp_fire) begin
          if(!when_BmbMemoryController_l899) begin
            io_bmb_cmd_payload_fragment_address = ({2'd0,bcFillAddr} <<< 2'd2);
          end
        end
      end
      State_4_BC_FILL_CMD : begin
        io_bmb_cmd_payload_fragment_address = ({2'd0,bcFillAddr} <<< 2'd2);
      end
      State_4_PF_WAIT : begin
      end
      State_4_HANDLE_READ : begin
        if(!when_BmbMemoryController_l1017) begin
          if(!when_BmbMemoryController_l1022) begin
            io_bmb_cmd_payload_fragment_address = ({2'd0,addrReg} <<< 2'd2);
          end
        end
      end
      State_4_HANDLE_WAIT : begin
      end
      State_4_HANDLE_CALC : begin
      end
      State_4_HANDLE_ACCESS : begin
        if(!addrIsIo) begin
          io_bmb_cmd_payload_fragment_address = ({2'd0,addrReg} <<< 2'd2);
        end
      end
      State_4_HANDLE_DATA_WAIT : begin
      end
      State_4_AC_FILL_CMD : begin
        io_bmb_cmd_payload_fragment_address = ({2'd0,_zz_io_bmb_cmd_payload_fragment_address} <<< 2'd2);
      end
      State_4_AC_FILL_WAIT : begin
      end
      State_4_HANDLE_BOUND_READ : begin
        io_bmb_cmd_payload_fragment_address = ({2'd0,_zz_io_bmb_cmd_payload_fragment_address_2} <<< 2'd2);
      end
      State_4_HANDLE_BOUND_WAIT : begin
      end
      State_4_NP_EXC : begin
      end
      State_4_AB_EXC : begin
      end
      State_4_GS_READ : begin
        io_bmb_cmd_payload_fragment_address = ({2'd0,addrReg} <<< 2'd2);
      end
      State_4_PS_WRITE : begin
        io_bmb_cmd_payload_fragment_address = ({2'd0,addrReg} <<< 2'd2);
      end
      State_4_LAST : begin
      end
      State_4_CP_SETUP : begin
      end
      State_4_CP_READ : begin
        io_bmb_cmd_payload_fragment_address = ({2'd0,posReg} <<< 2'd2);
      end
      State_4_CP_READ_WAIT : begin
      end
      State_4_CP_WRITE : begin
        io_bmb_cmd_payload_fragment_address = ({2'd0,addrReg} <<< 2'd2);
      end
      State_4_CP_STOP : begin
      end
      State_4_ZERO_RUN : begin
        if(!when_BmbMemoryController_l1379) begin
          io_bmb_cmd_payload_fragment_address = ({2'd0,zeroCur} <<< 2'd2);
        end
      end
      State_4_ZERO_WAIT : begin
      end
      State_4_FILL_REQ : begin
      end
      default : begin
      end
    endcase
  end

  assign io_bmb_cmd_payload_fragment_length = 2'b11;
  assign io_bmb_cmd_payload_fragment_context = 4'b0000;
  always @(*) begin
    io_bmb_cmd_payload_fragment_data = 32'h0;
    case(state_6)
      State_4_IDLE : begin
        if(!memReadRequested) begin
          if(when_BmbMemoryController_l587) begin
            if(!addrIsIo) begin
              io_bmb_cmd_payload_fragment_data = io_aout;
            end
          end
        end
      end
      State_4_READ_WAIT : begin
      end
      State_4_WRITE_WAIT : begin
        if(when_BmbMemoryController_l804) begin
          io_bmb_cmd_payload_fragment_data = pendingCmdData;
        end
      end
      State_4_IAST_WAIT : begin
      end
      State_4_BC_CACHE_CHECK : begin
      end
      State_4_BC_FILL_R1 : begin
      end
      State_4_BC_FILL_LOOP : begin
      end
      State_4_BC_FILL_CMD : begin
      end
      State_4_PF_WAIT : begin
      end
      State_4_HANDLE_READ : begin
      end
      State_4_HANDLE_WAIT : begin
      end
      State_4_HANDLE_CALC : begin
      end
      State_4_HANDLE_ACCESS : begin
        if(!addrIsIo) begin
          if(handleIsWrite) begin
            io_bmb_cmd_payload_fragment_data = handleWriteData;
          end
        end
      end
      State_4_HANDLE_DATA_WAIT : begin
      end
      State_4_AC_FILL_CMD : begin
      end
      State_4_AC_FILL_WAIT : begin
      end
      State_4_HANDLE_BOUND_READ : begin
      end
      State_4_HANDLE_BOUND_WAIT : begin
      end
      State_4_NP_EXC : begin
      end
      State_4_AB_EXC : begin
      end
      State_4_GS_READ : begin
      end
      State_4_PS_WRITE : begin
        io_bmb_cmd_payload_fragment_data = valueReg;
      end
      State_4_LAST : begin
      end
      State_4_CP_SETUP : begin
      end
      State_4_CP_READ : begin
      end
      State_4_CP_READ_WAIT : begin
      end
      State_4_CP_WRITE : begin
        io_bmb_cmd_payload_fragment_data = valueReg;
      end
      State_4_CP_STOP : begin
      end
      State_4_ZERO_RUN : begin
      end
      State_4_ZERO_WAIT : begin
      end
      State_4_FILL_REQ : begin
      end
      default : begin
      end
    endcase
  end

  assign io_bmb_cmd_payload_fragment_mask = 4'b1111;
  assign io_bmb_rsp_ready = 1'b1;
  always @(*) begin
    io_ioAddr = addrReg[7 : 0];
    case(state_6)
      State_4_IDLE : begin
        if(memReadRequested) begin
          if(aoutIsIo) begin
            io_ioAddr = io_aout[7 : 0];
          end
        end else begin
          if(when_BmbMemoryController_l587) begin
            if(addrIsIo) begin
              if(!when_BmbMemoryController_l590) begin
                io_ioAddr = addrReg[7 : 0];
              end
            end
          end
        end
      end
      State_4_READ_WAIT : begin
        if(when_BmbMemoryController_l753) begin
          io_ioAddr = io_aout[7 : 0];
        end
        if(when_BmbMemoryController_l760) begin
          io_ioAddr = addrReg[7 : 0];
        end
      end
      State_4_WRITE_WAIT : begin
        if(when_BmbMemoryController_l790) begin
          io_ioAddr = io_aout[7 : 0];
        end
        if(when_BmbMemoryController_l797) begin
          io_ioAddr = addrReg[7 : 0];
        end
      end
      State_4_IAST_WAIT : begin
      end
      State_4_BC_CACHE_CHECK : begin
      end
      State_4_BC_FILL_R1 : begin
      end
      State_4_BC_FILL_LOOP : begin
      end
      State_4_BC_FILL_CMD : begin
      end
      State_4_PF_WAIT : begin
      end
      State_4_HANDLE_READ : begin
        if(when_BmbMemoryController_l1017) begin
          io_ioAddr = 8'hf4;
        end else begin
          if(when_BmbMemoryController_l1022) begin
            io_ioAddr = 8'hf4;
          end
        end
      end
      State_4_HANDLE_WAIT : begin
      end
      State_4_HANDLE_CALC : begin
      end
      State_4_HANDLE_ACCESS : begin
        if(addrIsIo) begin
          io_ioAddr = addrReg[7 : 0];
        end
      end
      State_4_HANDLE_DATA_WAIT : begin
      end
      State_4_AC_FILL_CMD : begin
      end
      State_4_AC_FILL_WAIT : begin
      end
      State_4_HANDLE_BOUND_READ : begin
      end
      State_4_HANDLE_BOUND_WAIT : begin
        if(io_bmb_rsp_fire) begin
          if(when_BmbMemoryController_l1244) begin
            io_ioAddr = 8'hf4;
          end
        end
      end
      State_4_NP_EXC : begin
      end
      State_4_AB_EXC : begin
      end
      State_4_GS_READ : begin
      end
      State_4_PS_WRITE : begin
      end
      State_4_LAST : begin
      end
      State_4_CP_SETUP : begin
      end
      State_4_CP_READ : begin
      end
      State_4_CP_READ_WAIT : begin
      end
      State_4_CP_WRITE : begin
      end
      State_4_CP_STOP : begin
      end
      State_4_ZERO_RUN : begin
      end
      State_4_ZERO_WAIT : begin
      end
      State_4_FILL_REQ : begin
      end
      default : begin
      end
    endcase
    if(when_BmbMemoryController_l1434) begin
      io_ioAddr = ioRdSavedAddr;
    end
  end

  always @(*) begin
    io_ioRd = 1'b0;
    case(state_6)
      State_4_IDLE : begin
        if(memReadRequested) begin
          if(aoutIsIo) begin
            io_ioRd = 1'b1;
          end
        end
      end
      State_4_READ_WAIT : begin
        if(when_BmbMemoryController_l753) begin
          io_ioRd = 1'b1;
        end
      end
      State_4_WRITE_WAIT : begin
        if(when_BmbMemoryController_l790) begin
          io_ioRd = 1'b1;
        end
      end
      State_4_IAST_WAIT : begin
      end
      State_4_BC_CACHE_CHECK : begin
      end
      State_4_BC_FILL_R1 : begin
      end
      State_4_BC_FILL_LOOP : begin
      end
      State_4_BC_FILL_CMD : begin
      end
      State_4_PF_WAIT : begin
      end
      State_4_HANDLE_READ : begin
      end
      State_4_HANDLE_WAIT : begin
      end
      State_4_HANDLE_CALC : begin
      end
      State_4_HANDLE_ACCESS : begin
        if(addrIsIo) begin
          if(!handleIsWrite) begin
            io_ioRd = 1'b1;
          end
        end
      end
      State_4_HANDLE_DATA_WAIT : begin
      end
      State_4_AC_FILL_CMD : begin
      end
      State_4_AC_FILL_WAIT : begin
      end
      State_4_HANDLE_BOUND_READ : begin
      end
      State_4_HANDLE_BOUND_WAIT : begin
      end
      State_4_NP_EXC : begin
      end
      State_4_AB_EXC : begin
      end
      State_4_GS_READ : begin
      end
      State_4_PS_WRITE : begin
      end
      State_4_LAST : begin
      end
      State_4_CP_SETUP : begin
      end
      State_4_CP_READ : begin
      end
      State_4_CP_READ_WAIT : begin
      end
      State_4_CP_WRITE : begin
      end
      State_4_CP_STOP : begin
      end
      State_4_ZERO_RUN : begin
      end
      State_4_ZERO_WAIT : begin
      end
      State_4_FILL_REQ : begin
      end
      default : begin
      end
    endcase
  end

  always @(*) begin
    io_ioWr = 1'b0;
    case(state_6)
      State_4_IDLE : begin
        if(!memReadRequested) begin
          if(when_BmbMemoryController_l587) begin
            if(addrIsIo) begin
              if(!when_BmbMemoryController_l590) begin
                io_ioWr = 1'b1;
              end
            end
          end
        end
      end
      State_4_READ_WAIT : begin
        if(when_BmbMemoryController_l760) begin
          io_ioWr = 1'b1;
        end
      end
      State_4_WRITE_WAIT : begin
        if(when_BmbMemoryController_l797) begin
          io_ioWr = 1'b1;
        end
      end
      State_4_IAST_WAIT : begin
      end
      State_4_BC_CACHE_CHECK : begin
      end
      State_4_BC_FILL_R1 : begin
      end
      State_4_BC_FILL_LOOP : begin
      end
      State_4_BC_FILL_CMD : begin
      end
      State_4_PF_WAIT : begin
      end
      State_4_HANDLE_READ : begin
        if(when_BmbMemoryController_l1017) begin
          io_ioWr = 1'b1;
        end else begin
          if(when_BmbMemoryController_l1022) begin
            io_ioWr = 1'b1;
          end
        end
      end
      State_4_HANDLE_WAIT : begin
      end
      State_4_HANDLE_CALC : begin
      end
      State_4_HANDLE_ACCESS : begin
        if(addrIsIo) begin
          if(handleIsWrite) begin
            io_ioWr = 1'b1;
          end
        end
      end
      State_4_HANDLE_DATA_WAIT : begin
      end
      State_4_AC_FILL_CMD : begin
      end
      State_4_AC_FILL_WAIT : begin
      end
      State_4_HANDLE_BOUND_READ : begin
      end
      State_4_HANDLE_BOUND_WAIT : begin
        if(io_bmb_rsp_fire) begin
          if(when_BmbMemoryController_l1244) begin
            io_ioWr = 1'b1;
          end
        end
      end
      State_4_NP_EXC : begin
      end
      State_4_AB_EXC : begin
      end
      State_4_GS_READ : begin
      end
      State_4_PS_WRITE : begin
      end
      State_4_LAST : begin
      end
      State_4_CP_SETUP : begin
      end
      State_4_CP_READ : begin
      end
      State_4_CP_READ_WAIT : begin
      end
      State_4_CP_WRITE : begin
      end
      State_4_CP_STOP : begin
      end
      State_4_ZERO_RUN : begin
      end
      State_4_ZERO_WAIT : begin
      end
      State_4_FILL_REQ : begin
      end
      default : begin
      end
    endcase
  end

  always @(*) begin
    io_ioWrData = io_aout;
    case(state_6)
      State_4_IDLE : begin
        if(!memReadRequested) begin
          if(when_BmbMemoryController_l587) begin
            if(addrIsIo) begin
              if(!when_BmbMemoryController_l590) begin
                io_ioWrData = io_aout;
              end
            end
          end
        end
      end
      State_4_READ_WAIT : begin
        if(when_BmbMemoryController_l760) begin
          io_ioWrData = io_aout;
        end
      end
      State_4_WRITE_WAIT : begin
        if(when_BmbMemoryController_l797) begin
          io_ioWrData = io_aout;
        end
      end
      State_4_IAST_WAIT : begin
      end
      State_4_BC_CACHE_CHECK : begin
      end
      State_4_BC_FILL_R1 : begin
      end
      State_4_BC_FILL_LOOP : begin
      end
      State_4_BC_FILL_CMD : begin
      end
      State_4_PF_WAIT : begin
      end
      State_4_HANDLE_READ : begin
        if(when_BmbMemoryController_l1017) begin
          io_ioWrData = 32'h00000002;
        end else begin
          if(when_BmbMemoryController_l1022) begin
            io_ioWrData = 32'h00000003;
          end
        end
      end
      State_4_HANDLE_WAIT : begin
      end
      State_4_HANDLE_CALC : begin
      end
      State_4_HANDLE_ACCESS : begin
        if(addrIsIo) begin
          if(handleIsWrite) begin
            io_ioWrData = handleWriteData;
          end
        end
      end
      State_4_HANDLE_DATA_WAIT : begin
      end
      State_4_AC_FILL_CMD : begin
      end
      State_4_AC_FILL_WAIT : begin
      end
      State_4_HANDLE_BOUND_READ : begin
      end
      State_4_HANDLE_BOUND_WAIT : begin
        if(io_bmb_rsp_fire) begin
          if(when_BmbMemoryController_l1244) begin
            io_ioWrData = 32'h00000003;
          end
        end
      end
      State_4_NP_EXC : begin
      end
      State_4_AB_EXC : begin
      end
      State_4_GS_READ : begin
      end
      State_4_PS_WRITE : begin
      end
      State_4_LAST : begin
      end
      State_4_CP_SETUP : begin
      end
      State_4_CP_READ : begin
      end
      State_4_CP_READ_WAIT : begin
      end
      State_4_CP_WRITE : begin
      end
      State_4_CP_STOP : begin
      end
      State_4_ZERO_RUN : begin
      end
      State_4_ZERO_WAIT : begin
      end
      State_4_FILL_REQ : begin
      end
      default : begin
      end
    endcase
  end

  assign io_jbcWrite_addr = jbcWrAddrReg;
  assign io_jbcWrite_data = jbcWrDataReg;
  assign io_jbcWrite_enable = jbcWrEnReg;
  always @(*) begin
    io_snoopOut_valid = 1'b0;
    case(state_6)
      State_4_IDLE : begin
      end
      State_4_READ_WAIT : begin
      end
      State_4_WRITE_WAIT : begin
      end
      State_4_IAST_WAIT : begin
      end
      State_4_BC_CACHE_CHECK : begin
      end
      State_4_BC_FILL_R1 : begin
      end
      State_4_BC_FILL_LOOP : begin
      end
      State_4_BC_FILL_CMD : begin
      end
      State_4_PF_WAIT : begin
      end
      State_4_HANDLE_READ : begin
      end
      State_4_HANDLE_WAIT : begin
      end
      State_4_HANDLE_CALC : begin
      end
      State_4_HANDLE_ACCESS : begin
      end
      State_4_HANDLE_DATA_WAIT : begin
        if(io_bmb_rsp_fire) begin
          if(handleIsWrite) begin
            io_snoopOut_valid = 1'b1;
          end
        end
      end
      State_4_AC_FILL_CMD : begin
      end
      State_4_AC_FILL_WAIT : begin
      end
      State_4_HANDLE_BOUND_READ : begin
      end
      State_4_HANDLE_BOUND_WAIT : begin
      end
      State_4_NP_EXC : begin
      end
      State_4_AB_EXC : begin
      end
      State_4_GS_READ : begin
      end
      State_4_PS_WRITE : begin
      end
      State_4_LAST : begin
      end
      State_4_CP_SETUP : begin
      end
      State_4_CP_READ : begin
      end
      State_4_CP_READ_WAIT : begin
      end
      State_4_CP_WRITE : begin
      end
      State_4_CP_STOP : begin
      end
      State_4_ZERO_RUN : begin
      end
      State_4_ZERO_WAIT : begin
      end
      State_4_FILL_REQ : begin
      end
      default : begin
      end
    endcase
  end

  always @(*) begin
    io_snoopOut_isArray = 1'b0;
    case(state_6)
      State_4_IDLE : begin
      end
      State_4_READ_WAIT : begin
      end
      State_4_WRITE_WAIT : begin
      end
      State_4_IAST_WAIT : begin
      end
      State_4_BC_CACHE_CHECK : begin
      end
      State_4_BC_FILL_R1 : begin
      end
      State_4_BC_FILL_LOOP : begin
      end
      State_4_BC_FILL_CMD : begin
      end
      State_4_PF_WAIT : begin
      end
      State_4_HANDLE_READ : begin
      end
      State_4_HANDLE_WAIT : begin
      end
      State_4_HANDLE_CALC : begin
      end
      State_4_HANDLE_ACCESS : begin
      end
      State_4_HANDLE_DATA_WAIT : begin
        if(io_bmb_rsp_fire) begin
          if(handleIsWrite) begin
            io_snoopOut_isArray = handleIsArray;
          end
        end
      end
      State_4_AC_FILL_CMD : begin
      end
      State_4_AC_FILL_WAIT : begin
      end
      State_4_HANDLE_BOUND_READ : begin
      end
      State_4_HANDLE_BOUND_WAIT : begin
      end
      State_4_NP_EXC : begin
      end
      State_4_AB_EXC : begin
      end
      State_4_GS_READ : begin
      end
      State_4_PS_WRITE : begin
      end
      State_4_LAST : begin
      end
      State_4_CP_SETUP : begin
      end
      State_4_CP_READ : begin
      end
      State_4_CP_READ_WAIT : begin
      end
      State_4_CP_WRITE : begin
      end
      State_4_CP_STOP : begin
      end
      State_4_ZERO_RUN : begin
      end
      State_4_ZERO_WAIT : begin
      end
      State_4_FILL_REQ : begin
      end
      default : begin
      end
    endcase
  end

  always @(*) begin
    io_snoopOut_handle = 24'h0;
    case(state_6)
      State_4_IDLE : begin
      end
      State_4_READ_WAIT : begin
      end
      State_4_WRITE_WAIT : begin
      end
      State_4_IAST_WAIT : begin
      end
      State_4_BC_CACHE_CHECK : begin
      end
      State_4_BC_FILL_R1 : begin
      end
      State_4_BC_FILL_LOOP : begin
      end
      State_4_BC_FILL_CMD : begin
      end
      State_4_PF_WAIT : begin
      end
      State_4_HANDLE_READ : begin
      end
      State_4_HANDLE_WAIT : begin
      end
      State_4_HANDLE_CALC : begin
      end
      State_4_HANDLE_ACCESS : begin
      end
      State_4_HANDLE_DATA_WAIT : begin
        if(io_bmb_rsp_fire) begin
          if(handleIsWrite) begin
            io_snoopOut_handle = snoopHandleReg;
          end
        end
      end
      State_4_AC_FILL_CMD : begin
      end
      State_4_AC_FILL_WAIT : begin
      end
      State_4_HANDLE_BOUND_READ : begin
      end
      State_4_HANDLE_BOUND_WAIT : begin
      end
      State_4_NP_EXC : begin
      end
      State_4_AB_EXC : begin
      end
      State_4_GS_READ : begin
      end
      State_4_PS_WRITE : begin
      end
      State_4_LAST : begin
      end
      State_4_CP_SETUP : begin
      end
      State_4_CP_READ : begin
      end
      State_4_CP_READ_WAIT : begin
      end
      State_4_CP_WRITE : begin
      end
      State_4_CP_STOP : begin
      end
      State_4_ZERO_RUN : begin
      end
      State_4_ZERO_WAIT : begin
      end
      State_4_FILL_REQ : begin
      end
      default : begin
      end
    endcase
  end

  always @(*) begin
    io_snoopOut_index = 24'h0;
    case(state_6)
      State_4_IDLE : begin
      end
      State_4_READ_WAIT : begin
      end
      State_4_WRITE_WAIT : begin
      end
      State_4_IAST_WAIT : begin
      end
      State_4_BC_CACHE_CHECK : begin
      end
      State_4_BC_FILL_R1 : begin
      end
      State_4_BC_FILL_LOOP : begin
      end
      State_4_BC_FILL_CMD : begin
      end
      State_4_PF_WAIT : begin
      end
      State_4_HANDLE_READ : begin
      end
      State_4_HANDLE_WAIT : begin
      end
      State_4_HANDLE_CALC : begin
      end
      State_4_HANDLE_ACCESS : begin
      end
      State_4_HANDLE_DATA_WAIT : begin
        if(io_bmb_rsp_fire) begin
          if(handleIsWrite) begin
            io_snoopOut_index = handleIndex;
          end
        end
      end
      State_4_AC_FILL_CMD : begin
      end
      State_4_AC_FILL_WAIT : begin
      end
      State_4_HANDLE_BOUND_READ : begin
      end
      State_4_HANDLE_BOUND_WAIT : begin
      end
      State_4_NP_EXC : begin
      end
      State_4_AB_EXC : begin
      end
      State_4_GS_READ : begin
      end
      State_4_PS_WRITE : begin
      end
      State_4_LAST : begin
      end
      State_4_CP_SETUP : begin
      end
      State_4_CP_READ : begin
      end
      State_4_CP_READ_WAIT : begin
      end
      State_4_CP_WRITE : begin
      end
      State_4_CP_STOP : begin
      end
      State_4_ZERO_RUN : begin
      end
      State_4_ZERO_WAIT : begin
      end
      State_4_FILL_REQ : begin
      end
      default : begin
      end
    endcase
  end

  assign aoutAddr = io_aout[23 : 0];
  assign aoutIsIo = (aoutAddr[23 : 22] == 2'b11);
  assign addrIsIo = (addrReg[23 : 22] == 2'b11);
  assign memReadRequested = ((io_memIn_rd || io_memIn_rdc) || io_memIn_rdf);
  assign methodCache_1_io_bcAddr = bcFillAddr[17 : 0];
  always @(*) begin
    mcacheFind = 1'b0;
    case(state_6)
      State_4_IDLE : begin
        if(!memReadRequested) begin
          if(!when_BmbMemoryController_l587) begin
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
      State_4_READ_WAIT : begin
      end
      State_4_WRITE_WAIT : begin
      end
      State_4_IAST_WAIT : begin
      end
      State_4_BC_CACHE_CHECK : begin
      end
      State_4_BC_FILL_R1 : begin
      end
      State_4_BC_FILL_LOOP : begin
      end
      State_4_BC_FILL_CMD : begin
      end
      State_4_PF_WAIT : begin
      end
      State_4_HANDLE_READ : begin
      end
      State_4_HANDLE_WAIT : begin
      end
      State_4_HANDLE_CALC : begin
      end
      State_4_HANDLE_ACCESS : begin
      end
      State_4_HANDLE_DATA_WAIT : begin
      end
      State_4_AC_FILL_CMD : begin
      end
      State_4_AC_FILL_WAIT : begin
      end
      State_4_HANDLE_BOUND_READ : begin
      end
      State_4_HANDLE_BOUND_WAIT : begin
      end
      State_4_NP_EXC : begin
      end
      State_4_AB_EXC : begin
      end
      State_4_GS_READ : begin
      end
      State_4_PS_WRITE : begin
      end
      State_4_LAST : begin
      end
      State_4_CP_SETUP : begin
      end
      State_4_CP_READ : begin
      end
      State_4_CP_READ_WAIT : begin
      end
      State_4_CP_WRITE : begin
      end
      State_4_CP_STOP : begin
      end
      State_4_ZERO_RUN : begin
      end
      State_4_ZERO_WAIT : begin
      end
      State_4_FILL_REQ : begin
      end
      default : begin
      end
    endcase
  end

  always @(*) begin
    objectCache_1_io_handle = aoutAddr;
    case(state_6)
      State_4_IDLE : begin
      end
      State_4_READ_WAIT : begin
      end
      State_4_WRITE_WAIT : begin
      end
      State_4_IAST_WAIT : begin
      end
      State_4_BC_CACHE_CHECK : begin
      end
      State_4_BC_FILL_R1 : begin
      end
      State_4_BC_FILL_LOOP : begin
      end
      State_4_BC_FILL_CMD : begin
      end
      State_4_PF_WAIT : begin
        if(when_BmbMemoryController_l987) begin
          objectCache_1_io_handle = addrReg;
        end
      end
      State_4_HANDLE_READ : begin
      end
      State_4_HANDLE_WAIT : begin
      end
      State_4_HANDLE_CALC : begin
      end
      State_4_HANDLE_ACCESS : begin
      end
      State_4_HANDLE_DATA_WAIT : begin
      end
      State_4_AC_FILL_CMD : begin
      end
      State_4_AC_FILL_WAIT : begin
      end
      State_4_HANDLE_BOUND_READ : begin
      end
      State_4_HANDLE_BOUND_WAIT : begin
      end
      State_4_NP_EXC : begin
      end
      State_4_AB_EXC : begin
      end
      State_4_GS_READ : begin
      end
      State_4_PS_WRITE : begin
      end
      State_4_LAST : begin
      end
      State_4_CP_SETUP : begin
      end
      State_4_CP_READ : begin
      end
      State_4_CP_READ_WAIT : begin
      end
      State_4_CP_WRITE : begin
      end
      State_4_CP_STOP : begin
      end
      State_4_ZERO_RUN : begin
      end
      State_4_ZERO_WAIT : begin
      end
      State_4_FILL_REQ : begin
      end
      default : begin
      end
    endcase
  end

  assign objectCache_1_io_fieldIdx = io_bcopd[7 : 0];
  assign objectCache_1_io_chkGf = ((io_memIn_getfield && (! wasStidx)) && (state_6 == State_4_IDLE));
  always @(*) begin
    objectCache_1_io_chkPf = 1'b0;
    case(state_6)
      State_4_IDLE : begin
      end
      State_4_READ_WAIT : begin
      end
      State_4_WRITE_WAIT : begin
      end
      State_4_IAST_WAIT : begin
      end
      State_4_BC_CACHE_CHECK : begin
      end
      State_4_BC_FILL_R1 : begin
      end
      State_4_BC_FILL_LOOP : begin
      end
      State_4_BC_FILL_CMD : begin
      end
      State_4_PF_WAIT : begin
        if(when_BmbMemoryController_l987) begin
          objectCache_1_io_chkPf = 1'b1;
        end
      end
      State_4_HANDLE_READ : begin
      end
      State_4_HANDLE_WAIT : begin
      end
      State_4_HANDLE_CALC : begin
      end
      State_4_HANDLE_ACCESS : begin
      end
      State_4_HANDLE_DATA_WAIT : begin
      end
      State_4_AC_FILL_CMD : begin
      end
      State_4_AC_FILL_WAIT : begin
      end
      State_4_HANDLE_BOUND_READ : begin
      end
      State_4_HANDLE_BOUND_WAIT : begin
      end
      State_4_NP_EXC : begin
      end
      State_4_AB_EXC : begin
      end
      State_4_GS_READ : begin
      end
      State_4_PS_WRITE : begin
      end
      State_4_LAST : begin
      end
      State_4_CP_SETUP : begin
      end
      State_4_CP_READ : begin
      end
      State_4_CP_READ_WAIT : begin
      end
      State_4_CP_WRITE : begin
      end
      State_4_CP_STOP : begin
      end
      State_4_ZERO_RUN : begin
      end
      State_4_ZERO_WAIT : begin
      end
      State_4_FILL_REQ : begin
      end
      default : begin
      end
    endcase
  end

  assign objectCache_1_io_inval = (io_memIn_stidx || io_memIn_cinval);
  always @(*) begin
    objectCache_1_io_wrGf = 1'b0;
    case(state_6)
      State_4_IDLE : begin
      end
      State_4_READ_WAIT : begin
      end
      State_4_WRITE_WAIT : begin
      end
      State_4_IAST_WAIT : begin
      end
      State_4_BC_CACHE_CHECK : begin
      end
      State_4_BC_FILL_R1 : begin
      end
      State_4_BC_FILL_LOOP : begin
      end
      State_4_BC_FILL_CMD : begin
      end
      State_4_PF_WAIT : begin
      end
      State_4_HANDLE_READ : begin
      end
      State_4_HANDLE_WAIT : begin
      end
      State_4_HANDLE_CALC : begin
      end
      State_4_HANDLE_ACCESS : begin
      end
      State_4_HANDLE_DATA_WAIT : begin
        if(io_bmb_rsp_fire) begin
          if(when_BmbMemoryController_l1126) begin
            if(ocWasGetfield) begin
              objectCache_1_io_wrGf = 1'b1;
            end
          end
        end
      end
      State_4_AC_FILL_CMD : begin
      end
      State_4_AC_FILL_WAIT : begin
      end
      State_4_HANDLE_BOUND_READ : begin
      end
      State_4_HANDLE_BOUND_WAIT : begin
      end
      State_4_NP_EXC : begin
      end
      State_4_AB_EXC : begin
      end
      State_4_GS_READ : begin
      end
      State_4_PS_WRITE : begin
      end
      State_4_LAST : begin
      end
      State_4_CP_SETUP : begin
      end
      State_4_CP_READ : begin
      end
      State_4_CP_READ_WAIT : begin
      end
      State_4_CP_WRITE : begin
      end
      State_4_CP_STOP : begin
      end
      State_4_ZERO_RUN : begin
      end
      State_4_ZERO_WAIT : begin
      end
      State_4_FILL_REQ : begin
      end
      default : begin
      end
    endcase
  end

  always @(*) begin
    objectCache_1_io_wrPf = 1'b0;
    case(state_6)
      State_4_IDLE : begin
      end
      State_4_READ_WAIT : begin
      end
      State_4_WRITE_WAIT : begin
      end
      State_4_IAST_WAIT : begin
      end
      State_4_BC_CACHE_CHECK : begin
      end
      State_4_BC_FILL_R1 : begin
      end
      State_4_BC_FILL_LOOP : begin
      end
      State_4_BC_FILL_CMD : begin
      end
      State_4_PF_WAIT : begin
      end
      State_4_HANDLE_READ : begin
      end
      State_4_HANDLE_WAIT : begin
      end
      State_4_HANDLE_CALC : begin
      end
      State_4_HANDLE_ACCESS : begin
      end
      State_4_HANDLE_DATA_WAIT : begin
        if(io_bmb_rsp_fire) begin
          if(when_BmbMemoryController_l1126) begin
            if(!ocWasGetfield) begin
              objectCache_1_io_wrPf = 1'b1;
            end
          end
        end
      end
      State_4_AC_FILL_CMD : begin
      end
      State_4_AC_FILL_WAIT : begin
      end
      State_4_HANDLE_BOUND_READ : begin
      end
      State_4_HANDLE_BOUND_WAIT : begin
      end
      State_4_NP_EXC : begin
      end
      State_4_AB_EXC : begin
      end
      State_4_GS_READ : begin
      end
      State_4_PS_WRITE : begin
      end
      State_4_LAST : begin
      end
      State_4_CP_SETUP : begin
      end
      State_4_CP_READ : begin
      end
      State_4_CP_READ_WAIT : begin
      end
      State_4_CP_WRITE : begin
      end
      State_4_CP_STOP : begin
      end
      State_4_ZERO_RUN : begin
      end
      State_4_ZERO_WAIT : begin
      end
      State_4_FILL_REQ : begin
      end
      default : begin
      end
    endcase
  end

  assign arrayCache_1_io_handle = io_bout[23 : 0];
  assign arrayCache_1_io_chkIal = (io_memIn_iaload && (state_6 == State_4_IDLE));
  always @(*) begin
    arrayCache_1_io_chkIas = 1'b0;
    case(state_6)
      State_4_IDLE : begin
      end
      State_4_READ_WAIT : begin
      end
      State_4_WRITE_WAIT : begin
      end
      State_4_IAST_WAIT : begin
        arrayCache_1_io_chkIas = 1'b1;
      end
      State_4_BC_CACHE_CHECK : begin
      end
      State_4_BC_FILL_R1 : begin
      end
      State_4_BC_FILL_LOOP : begin
      end
      State_4_BC_FILL_CMD : begin
      end
      State_4_PF_WAIT : begin
      end
      State_4_HANDLE_READ : begin
      end
      State_4_HANDLE_WAIT : begin
      end
      State_4_HANDLE_CALC : begin
      end
      State_4_HANDLE_ACCESS : begin
      end
      State_4_HANDLE_DATA_WAIT : begin
      end
      State_4_AC_FILL_CMD : begin
      end
      State_4_AC_FILL_WAIT : begin
      end
      State_4_HANDLE_BOUND_READ : begin
      end
      State_4_HANDLE_BOUND_WAIT : begin
      end
      State_4_NP_EXC : begin
      end
      State_4_AB_EXC : begin
      end
      State_4_GS_READ : begin
      end
      State_4_PS_WRITE : begin
      end
      State_4_LAST : begin
      end
      State_4_CP_SETUP : begin
      end
      State_4_CP_READ : begin
      end
      State_4_CP_READ_WAIT : begin
      end
      State_4_CP_WRITE : begin
      end
      State_4_CP_STOP : begin
      end
      State_4_ZERO_RUN : begin
      end
      State_4_ZERO_WAIT : begin
      end
      State_4_FILL_REQ : begin
      end
      default : begin
      end
    endcase
  end

  always @(*) begin
    arrayCache_1_io_ialVal = io_bmb_rsp_payload_fragment_data;
    case(state_6)
      State_4_IDLE : begin
      end
      State_4_READ_WAIT : begin
      end
      State_4_WRITE_WAIT : begin
      end
      State_4_IAST_WAIT : begin
      end
      State_4_BC_CACHE_CHECK : begin
      end
      State_4_BC_FILL_R1 : begin
      end
      State_4_BC_FILL_LOOP : begin
      end
      State_4_BC_FILL_CMD : begin
      end
      State_4_PF_WAIT : begin
      end
      State_4_HANDLE_READ : begin
      end
      State_4_HANDLE_WAIT : begin
      end
      State_4_HANDLE_CALC : begin
      end
      State_4_HANDLE_ACCESS : begin
      end
      State_4_HANDLE_DATA_WAIT : begin
      end
      State_4_AC_FILL_CMD : begin
      end
      State_4_AC_FILL_WAIT : begin
        if(io_bmb_rsp_fire) begin
          arrayCache_1_io_ialVal = io_bmb_rsp_payload_fragment_data;
        end
      end
      State_4_HANDLE_BOUND_READ : begin
      end
      State_4_HANDLE_BOUND_WAIT : begin
      end
      State_4_NP_EXC : begin
      end
      State_4_AB_EXC : begin
      end
      State_4_GS_READ : begin
      end
      State_4_PS_WRITE : begin
      end
      State_4_LAST : begin
      end
      State_4_CP_SETUP : begin
      end
      State_4_CP_READ : begin
      end
      State_4_CP_READ_WAIT : begin
      end
      State_4_CP_WRITE : begin
      end
      State_4_CP_STOP : begin
      end
      State_4_ZERO_RUN : begin
      end
      State_4_ZERO_WAIT : begin
      end
      State_4_FILL_REQ : begin
      end
      default : begin
      end
    endcase
  end

  assign arrayCache_1_io_inval = (io_memIn_stidx || io_memIn_cinval);
  always @(*) begin
    arrayCache_1_io_wrIal = 1'b0;
    case(state_6)
      State_4_IDLE : begin
      end
      State_4_READ_WAIT : begin
      end
      State_4_WRITE_WAIT : begin
      end
      State_4_IAST_WAIT : begin
      end
      State_4_BC_CACHE_CHECK : begin
      end
      State_4_BC_FILL_R1 : begin
      end
      State_4_BC_FILL_LOOP : begin
      end
      State_4_BC_FILL_CMD : begin
      end
      State_4_PF_WAIT : begin
      end
      State_4_HANDLE_READ : begin
      end
      State_4_HANDLE_WAIT : begin
      end
      State_4_HANDLE_CALC : begin
      end
      State_4_HANDLE_ACCESS : begin
      end
      State_4_HANDLE_DATA_WAIT : begin
      end
      State_4_AC_FILL_CMD : begin
      end
      State_4_AC_FILL_WAIT : begin
        if(io_bmb_rsp_fire) begin
          arrayCache_1_io_wrIal = 1'b1;
        end
      end
      State_4_HANDLE_BOUND_READ : begin
      end
      State_4_HANDLE_BOUND_WAIT : begin
      end
      State_4_NP_EXC : begin
      end
      State_4_AB_EXC : begin
      end
      State_4_GS_READ : begin
      end
      State_4_PS_WRITE : begin
      end
      State_4_LAST : begin
      end
      State_4_CP_SETUP : begin
      end
      State_4_CP_READ : begin
      end
      State_4_CP_READ_WAIT : begin
      end
      State_4_CP_WRITE : begin
      end
      State_4_CP_STOP : begin
      end
      State_4_ZERO_RUN : begin
      end
      State_4_ZERO_WAIT : begin
      end
      State_4_FILL_REQ : begin
      end
      default : begin
      end
    endcase
  end

  always @(*) begin
    arrayCache_1_io_wrIas = 1'b0;
    case(state_6)
      State_4_IDLE : begin
      end
      State_4_READ_WAIT : begin
      end
      State_4_WRITE_WAIT : begin
      end
      State_4_IAST_WAIT : begin
      end
      State_4_BC_CACHE_CHECK : begin
      end
      State_4_BC_FILL_R1 : begin
      end
      State_4_BC_FILL_LOOP : begin
      end
      State_4_BC_FILL_CMD : begin
      end
      State_4_PF_WAIT : begin
      end
      State_4_HANDLE_READ : begin
      end
      State_4_HANDLE_WAIT : begin
      end
      State_4_HANDLE_CALC : begin
      end
      State_4_HANDLE_ACCESS : begin
      end
      State_4_HANDLE_DATA_WAIT : begin
        if(io_bmb_rsp_fire) begin
          if(when_BmbMemoryController_l1141) begin
            arrayCache_1_io_wrIas = 1'b1;
          end
        end
      end
      State_4_AC_FILL_CMD : begin
      end
      State_4_AC_FILL_WAIT : begin
      end
      State_4_HANDLE_BOUND_READ : begin
      end
      State_4_HANDLE_BOUND_WAIT : begin
      end
      State_4_NP_EXC : begin
      end
      State_4_AB_EXC : begin
      end
      State_4_GS_READ : begin
      end
      State_4_PS_WRITE : begin
      end
      State_4_LAST : begin
      end
      State_4_CP_SETUP : begin
      end
      State_4_CP_READ : begin
      end
      State_4_CP_READ_WAIT : begin
      end
      State_4_CP_WRITE : begin
      end
      State_4_CP_STOP : begin
      end
      State_4_ZERO_RUN : begin
      end
      State_4_ZERO_WAIT : begin
      end
      State_4_FILL_REQ : begin
      end
      default : begin
      end
    endcase
  end

  assign when_BmbMemoryController_l469 = (state_6 != State_4_IDLE);
  assign when_BmbMemoryController_l474 = ((state_6 == State_4_IDLE) && ((((((((((memReadRequested || io_memIn_wr) || io_memIn_wrf) || io_memIn_getfield) || io_memIn_putfield) || io_memIn_iaload) || io_memIn_iastore) || io_memIn_bcRd) || io_memIn_getstatic) || io_memIn_putstatic) || io_memIn_copy));
  assign when_BmbMemoryController_l496 = (state_6 != State_4_IDLE);
  assign when_BmbMemoryController_l501 = ((state_6 == State_4_IDLE) && ((((((((((memReadRequested || io_memIn_wr) || io_memIn_wrf) || io_memIn_getfield) || io_memIn_putfield) || io_memIn_iaload) || io_memIn_iastore) || io_memIn_bcRd) || io_memIn_getstatic) || io_memIn_putstatic) || io_memIn_copy));
  assign arrayCache_1_io_snoopValid = (io_snoopIn_valid && io_snoopIn_isArray);
  assign objectCache_1_io_snoopValid = (io_snoopIn_valid && (! io_snoopIn_isArray));
  assign objectCache_1_io_snoopFieldIdx = io_snoopIn_index[7:0];
  assign when_BmbMemoryController_l556 = ((io_memIn_iastore || io_memIn_putfield) || io_memIn_putstatic);
  assign when_BmbMemoryController_l590 = (_zz_when_BmbMemoryController_l590[7 : 1] == 7'h76);
  assign when_BmbMemoryController_l593 = (_zz_when_BmbMemoryController_l593[0 : 0] == 1'b0);
  assign _zz_zeroEnd = io_aout[23 : 0];
  assign when_BmbMemoryController_l602 = (_zz_zeroEnd <= zeroCur);
  assign _zz_bcFillAddr = io_aout;
  assign when_BmbMemoryController_l687 = (objectCache_1_io_hit && (! wasStidx));
  assign when_BmbMemoryController_l587 = (io_memIn_wr || io_memIn_wrf);
  assign when_BmbMemoryController_l753 = (memReadRequested && aoutIsIo);
  assign when_BmbMemoryController_l760 = ((io_memIn_wr || io_memIn_wrf) && addrIsIo);
  assign when_BmbMemoryController_l767 = (! cmdAccepted);
  assign io_bmb_cmd_fire = (io_bmb_cmd_valid && io_bmb_cmd_ready);
  assign when_BmbMemoryController_l790 = (memReadRequested && aoutIsIo);
  assign when_BmbMemoryController_l797 = ((io_memIn_wr || io_memIn_wrf) && addrIsIo);
  assign when_BmbMemoryController_l804 = (! cmdAccepted);
  assign _zz_bcFillCount = (bcFillCount + 10'h001);
  assign when_BmbMemoryController_l899 = (bcFillLen <= _zz_bcFillCount);
  assign when_BmbMemoryController_l987 = (! wasStidx);
  assign when_BmbMemoryController_l1017 = (addrReg == 24'h0);
  assign when_BmbMemoryController_l1022 = (handleIsArray && handleIndex[23]);
  assign when_BmbMemoryController_l1067 = (handleIsArray && (! handleIsWrite));
  assign when_BmbMemoryController_l1119 = (! handleIsWrite);
  assign when_BmbMemoryController_l1126 = (((! handleIsArray) && (! wasStidx)) && (! wasHwo));
  assign when_BmbMemoryController_l1141 = (handleIsArray && handleIsWrite);
  assign when_BmbMemoryController_l1199 = (acFillCount == acFillRequestedIdx);
  assign when_BmbMemoryController_l1213 = (acFillCount == 2'b11);
  assign when_BmbMemoryController_l1244 = (io_bmb_rsp_payload_fragment_data[23 : 0] <= handleIndex);
  assign when_BmbMemoryController_l1379 = (zeroCur == zeroEnd);
  assign when_BmbMemoryController_l1434 = ((ioRdPending && (! memReadRequested)) && (! (io_memIn_wr || io_memIn_wrf)));
  assign when_BmbMemoryController_l1440 = (state_6 != State_4_IDLE);
  assign io_debug_state = state_6;
  assign io_debug_busy = (! notBusy);
  assign io_debug_addrReg = addrReg;
  assign io_debug_rdDataReg = rdDataReg;
  assign io_debug_bcFillAddr = bcFillAddr;
  assign io_debug_bcFillLen = bcFillLen;
  assign io_debug_bcFillCount = bcFillCount;
  assign io_debug_bcRdCapture = bcRdCaptureReg;
  always @(*) begin
    case(state_6)
      State_4_PF_WAIT : begin
        _zz_io_debug_handleActive = 1'b1;
      end
      State_4_HANDLE_READ : begin
        _zz_io_debug_handleActive = 1'b1;
      end
      State_4_HANDLE_WAIT : begin
        _zz_io_debug_handleActive = 1'b1;
      end
      State_4_HANDLE_CALC : begin
        _zz_io_debug_handleActive = 1'b1;
      end
      State_4_HANDLE_ACCESS : begin
        _zz_io_debug_handleActive = 1'b1;
      end
      State_4_HANDLE_DATA_WAIT : begin
        _zz_io_debug_handleActive = 1'b1;
      end
      State_4_HANDLE_BOUND_READ : begin
        _zz_io_debug_handleActive = 1'b1;
      end
      State_4_HANDLE_BOUND_WAIT : begin
        _zz_io_debug_handleActive = 1'b1;
      end
      State_4_AC_FILL_CMD : begin
        _zz_io_debug_handleActive = 1'b1;
      end
      State_4_AC_FILL_WAIT : begin
        _zz_io_debug_handleActive = 1'b1;
      end
      State_4_NP_EXC : begin
        _zz_io_debug_handleActive = 1'b1;
      end
      State_4_AB_EXC : begin
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
      state_6 <= State_4_IDLE;
      addrReg <= 24'h0;
      zeroCur <= 24'h0;
      zeroEnd <= 24'h0;
      rdDataReg <= 32'h0;
      ioRdPending <= 1'b0;
      ioRdSavedAddr <= 8'h0;
      handleDataPtr <= 24'h0;
      handleIndex <= 24'h0;
      handleIsWrite <= 1'b0;
      handleIsArray <= 1'b0;
      handleWriteData <= 32'h0;
      bcFillAddr <= 24'h0;
      bcFillLen <= 10'h0;
      bcFillCount <= 10'h0;
      bcStartReg <= 12'h0;
      bcRdCaptureReg <= 32'h0;
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
      readObjectCache <= 1'b0;
      ocWasGetfield <= 1'b0;
      wasHwo <= 1'b0;
      handleAddrReg <= 24'h0;
      snoopHandleReg <= 24'h0;
      readArrayCache <= 1'b0;
      acFillAddr <= 24'h0;
      acFillCount <= 2'b00;
      acFillRequestedIdx <= 2'b00;
    end else begin
      jbcWrEnReg <= 1'b0;
      if(when_BmbMemoryController_l469) begin
        readArrayCache <= 1'b0;
      end
      if(when_BmbMemoryController_l474) begin
        readArrayCache <= 1'b0;
      end
      if(when_BmbMemoryController_l496) begin
        readObjectCache <= 1'b0;
      end
      if(when_BmbMemoryController_l501) begin
        readObjectCache <= 1'b0;
      end
      case(state_6)
        State_4_IDLE : begin
          if(io_memIn_addrWr) begin
            addrReg <= aoutAddr;
          end
          if(io_memIn_stidx) begin
            indexReg <= aoutAddr;
            wasStidx <= 1'b1;
          end
          if(when_BmbMemoryController_l556) begin
            valueReg <= io_aout;
          end
          if(memReadRequested) begin
            if(aoutIsIo) begin
              rdDataReg <= io_ioRdData;
              ioRdPending <= 1'b1;
              ioRdSavedAddr <= io_aout[7 : 0];
            end else begin
              pendingCmdAddr <= ({2'd0,aoutAddr} <<< 2'd2);
              pendingCmdIsWrite <= 1'b0;
              cmdAccepted <= io_bmb_cmd_ready;
              state_6 <= State_4_READ_WAIT;
              ioRdPending <= 1'b0;
            end
          end else begin
            if(when_BmbMemoryController_l587) begin
              if(addrIsIo) begin
                if(when_BmbMemoryController_l590) begin
                  if(when_BmbMemoryController_l593) begin
                    zeroCur <= io_aout[23 : 0];
                  end else begin
                    zeroEnd <= _zz_zeroEnd;
                    if(when_BmbMemoryController_l602) begin
                      state_6 <= State_4_IDLE;
                    end else begin
                      state_6 <= State_4_ZERO_RUN;
                    end
                  end
                end
              end else begin
                pendingCmdAddr <= ({2'd0,addrReg} <<< 2'd2);
                pendingCmdData <= io_aout;
                pendingCmdIsWrite <= 1'b1;
                cmdAccepted <= io_bmb_cmd_ready;
                state_6 <= State_4_WRITE_WAIT;
              end
            end else begin
              if(io_memIn_putstatic) begin
                addrReg <= (wasStidx ? indexReg : _zz_addrReg);
                state_6 <= State_4_PS_WRITE;
              end else begin
                if(io_memIn_getstatic) begin
                  addrReg <= (wasStidx ? indexReg : _zz_addrReg_2);
                  state_6 <= State_4_GS_READ;
                end else begin
                  if(io_memIn_bcRd) begin
                    bcFillAddr <= {2'd0, _zz_bcFillAddr_1};
                    bcFillLen <= _zz_bcFillLen[9:0];
                    bcFillCount <= 10'h0;
                    bcRdCaptureReg <= io_aout;
                    state_6 <= State_4_BC_CACHE_CHECK;
                  end else begin
                    if(io_memIn_iaload) begin
                      if(arrayCache_1_io_hit) begin
                        readArrayCache <= 1'b1;
                      end else begin
                        addrReg <= io_bout[23 : 0];
                        handleIndex <= aoutAddr;
                        indexReg <= aoutAddr;
                        handleIsWrite <= 1'b0;
                        handleIsArray <= 1'b1;
                        state_6 <= State_4_HANDLE_READ;
                      end
                    end else begin
                      if(io_memIn_getfield) begin
                        if(when_BmbMemoryController_l687) begin
                          readObjectCache <= 1'b1;
                          wasStidx <= 1'b0;
                        end else begin
                          addrReg <= aoutAddr;
                          handleIndex <= (wasStidx ? indexReg : _zz_handleIndex);
                          handleIsWrite <= 1'b0;
                          handleIsArray <= 1'b0;
                          ocWasGetfield <= 1'b1;
                          state_6 <= State_4_HANDLE_READ;
                        end
                      end else begin
                        if(io_memIn_putfield) begin
                          addrReg <= io_bout[23 : 0];
                          handleIsWrite <= 1'b1;
                          handleIsArray <= 1'b0;
                          handleWriteData <= io_aout;
                          ocWasGetfield <= 1'b0;
                          state_6 <= State_4_PF_WAIT;
                        end else begin
                          if(io_memIn_copy) begin
                            baseReg <= io_bout[23 : 0];
                            posReg <= (aoutAddr + io_bout[23 : 0]);
                            cpStopBit <= io_aout[31];
                            state_6 <= State_4_CP_SETUP;
                          end else begin
                            if(io_memIn_iastore) begin
                              handleIsWrite <= 1'b1;
                              handleIsArray <= 1'b1;
                              state_6 <= State_4_IAST_WAIT;
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
        State_4_READ_WAIT : begin
          if(io_memIn_addrWr) begin
            addrReg <= aoutAddr;
          end
          if(when_BmbMemoryController_l753) begin
            rdDataReg <= io_ioRdData;
          end
          if(when_BmbMemoryController_l767) begin
            if(io_bmb_cmd_fire) begin
              cmdAccepted <= 1'b1;
            end
          end
          if(io_bmb_rsp_fire) begin
            rdDataReg <= io_bmb_rsp_payload_fragment_data;
            state_6 <= State_4_IDLE;
          end
        end
        State_4_WRITE_WAIT : begin
          if(io_memIn_addrWr) begin
            addrReg <= aoutAddr;
          end
          if(when_BmbMemoryController_l790) begin
            rdDataReg <= io_ioRdData;
          end
          if(when_BmbMemoryController_l804) begin
            if(io_bmb_cmd_fire) begin
              cmdAccepted <= 1'b1;
            end
          end
          if(io_bmb_rsp_fire) begin
            state_6 <= State_4_IDLE;
          end
        end
        State_4_IAST_WAIT : begin
          addrReg <= io_bout[23 : 0];
          handleIndex <= aoutAddr;
          handleWriteData <= valueReg;
          state_6 <= State_4_HANDLE_READ;
        end
        State_4_BC_CACHE_CHECK : begin
          if(methodCache_1_io_rdy) begin
            bcCacheStartReg <= methodCache_1_io_bcStart;
            bcStartReg <= {1'd0, _zz_bcStartReg};
            if(methodCache_1_io_inCache) begin
              state_6 <= State_4_IDLE;
            end else begin
              state_6 <= State_4_BC_FILL_R1;
            end
          end
        end
        State_4_BC_FILL_R1 : begin
          if(io_bmb_cmd_fire) begin
            bcFillAddr <= (bcFillAddr + 24'h000001);
            state_6 <= State_4_BC_FILL_LOOP;
          end
        end
        State_4_BC_FILL_LOOP : begin
          if(io_bmb_rsp_fire) begin
            jbcWrDataReg <= {{{io_bmb_rsp_payload_fragment_data[7 : 0],io_bmb_rsp_payload_fragment_data[15 : 8]},io_bmb_rsp_payload_fragment_data[23 : 16]},io_bmb_rsp_payload_fragment_data[31 : 24]};
            jbcWrEnReg <= 1'b1;
            jbcWrAddrReg <= _zz_jbcWrAddrReg[8:0];
            if(when_BmbMemoryController_l899) begin
              state_6 <= State_4_IDLE;
            end else begin
              bcFillCount <= _zz_bcFillCount;
              if(io_bmb_cmd_fire) begin
                bcFillAddr <= (bcFillAddr + 24'h000001);
              end else begin
                state_6 <= State_4_BC_FILL_CMD;
              end
            end
          end
        end
        State_4_BC_FILL_CMD : begin
          if(io_bmb_cmd_fire) begin
            bcFillAddr <= (bcFillAddr + 24'h000001);
            state_6 <= State_4_BC_FILL_LOOP;
          end
        end
        State_4_PF_WAIT : begin
          handleIndex <= (wasStidx ? indexReg : _zz_handleIndex_2);
          state_6 <= State_4_HANDLE_READ;
        end
        State_4_HANDLE_READ : begin
          handleAddrReg <= addrReg;
          snoopHandleReg <= addrReg;
          if(when_BmbMemoryController_l1017) begin
            state_6 <= State_4_NP_EXC;
          end else begin
            if(when_BmbMemoryController_l1022) begin
              state_6 <= State_4_AB_EXC;
            end else begin
              if(io_bmb_cmd_fire) begin
                state_6 <= State_4_HANDLE_WAIT;
              end
            end
          end
        end
        State_4_HANDLE_WAIT : begin
          if(io_bmb_rsp_fire) begin
            handleDataPtr <= io_bmb_rsp_payload_fragment_data[23 : 0];
            wasHwo <= (_zz_wasHwo[23 : 22] == 2'b11);
            if(handleIsArray) begin
              state_6 <= State_4_HANDLE_BOUND_READ;
            end else begin
              state_6 <= State_4_HANDLE_CALC;
            end
          end
        end
        State_4_HANDLE_CALC : begin
          addrReg <= (handleDataPtr + handleIndex);
          if(when_BmbMemoryController_l1067) begin
            acFillAddr <= (handleDataPtr + _zz_acFillAddr);
            acFillCount <= 2'b00;
            acFillRequestedIdx <= handleIndex[1 : 0];
            state_6 <= State_4_AC_FILL_CMD;
          end else begin
            state_6 <= State_4_HANDLE_ACCESS;
          end
        end
        State_4_HANDLE_ACCESS : begin
          if(addrIsIo) begin
            if(!handleIsWrite) begin
              rdDataReg <= io_ioRdData;
            end
            wasStidx <= 1'b0;
            state_6 <= State_4_IDLE;
          end else begin
            if(io_bmb_cmd_fire) begin
              state_6 <= State_4_HANDLE_DATA_WAIT;
            end
          end
        end
        State_4_HANDLE_DATA_WAIT : begin
          if(io_bmb_rsp_fire) begin
            if(when_BmbMemoryController_l1119) begin
              rdDataReg <= io_bmb_rsp_payload_fragment_data;
            end
            wasStidx <= 1'b0;
            state_6 <= State_4_IDLE;
          end
        end
        State_4_AC_FILL_CMD : begin
          if(io_bmb_cmd_fire) begin
            state_6 <= State_4_AC_FILL_WAIT;
          end
        end
        State_4_AC_FILL_WAIT : begin
          if(io_bmb_rsp_fire) begin
            if(when_BmbMemoryController_l1199) begin
              rdDataReg <= io_bmb_rsp_payload_fragment_data;
            end
            if(when_BmbMemoryController_l1213) begin
              wasStidx <= 1'b0;
              state_6 <= State_4_IDLE;
            end else begin
              acFillCount <= (acFillCount + 2'b01);
              state_6 <= State_4_AC_FILL_CMD;
            end
          end
        end
        State_4_HANDLE_BOUND_READ : begin
          if(io_bmb_cmd_fire) begin
            state_6 <= State_4_HANDLE_BOUND_WAIT;
          end
        end
        State_4_HANDLE_BOUND_WAIT : begin
          if(io_bmb_rsp_fire) begin
            if(when_BmbMemoryController_l1244) begin
              state_6 <= State_4_AB_EXC;
            end else begin
              state_6 <= State_4_HANDLE_CALC;
            end
          end
        end
        State_4_NP_EXC : begin
          wasStidx <= 1'b0;
          state_6 <= State_4_IDLE;
        end
        State_4_AB_EXC : begin
          wasStidx <= 1'b0;
          state_6 <= State_4_IDLE;
        end
        State_4_GS_READ : begin
          if(io_bmb_cmd_fire) begin
            state_6 <= State_4_LAST;
          end
        end
        State_4_PS_WRITE : begin
          if(io_bmb_cmd_fire) begin
            state_6 <= State_4_LAST;
          end
        end
        State_4_LAST : begin
          if(io_bmb_rsp_fire) begin
            rdDataReg <= io_bmb_rsp_payload_fragment_data;
            wasStidx <= 1'b0;
            state_6 <= State_4_IDLE;
          end
        end
        State_4_CP_SETUP : begin
          offsetReg <= (io_bout[23 : 0] - baseReg);
          if(cpStopBit) begin
            state_6 <= State_4_CP_STOP;
          end else begin
            state_6 <= State_4_CP_READ;
          end
        end
        State_4_CP_READ : begin
          if(io_bmb_cmd_fire) begin
            state_6 <= State_4_CP_READ_WAIT;
          end
        end
        State_4_CP_READ_WAIT : begin
          if(io_bmb_rsp_fire) begin
            valueReg <= io_bmb_rsp_payload_fragment_data;
            addrReg <= (posReg + offsetReg);
            posReg <= (posReg + 24'h000001);
            state_6 <= State_4_CP_WRITE;
          end
        end
        State_4_CP_WRITE : begin
          if(io_bmb_cmd_fire) begin
            state_6 <= State_4_LAST;
          end
        end
        State_4_CP_STOP : begin
          posReg <= baseReg;
          state_6 <= State_4_IDLE;
        end
        State_4_ZERO_RUN : begin
          if(when_BmbMemoryController_l1379) begin
            state_6 <= State_4_IDLE;
          end else begin
            if(io_bmb_cmd_fire) begin
              state_6 <= State_4_ZERO_WAIT;
            end
          end
        end
        State_4_ZERO_WAIT : begin
          if(io_bmb_rsp_fire) begin
            zeroCur <= (zeroCur + 24'h000001);
            state_6 <= State_4_ZERO_RUN;
          end
        end
        State_4_FILL_REQ : begin
        end
        default : begin
        end
      endcase
      if(when_BmbMemoryController_l1434) begin
        rdDataReg <= io_ioRdData;
      end
      if(when_BmbMemoryController_l1440) begin
        ioRdPending <= 1'b0;
      end
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
  output wire [15:0]   io_memCtrl_bcopd,
  output wire [31:0]   io_aout,
  output wire [31:0]   io_bout,
  output wire [15:0]   io_bcopd,
  input  wire          io_irq,
  input  wire          io_irqEna,
  input  wire          io_exc,
  output wire          io_ackIrq,
  output wire          io_ackExc,
  output wire          io_hwBusy,
  output wire [11:0]   io_pc,
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
  output wire [7:0]    io_debugSp,
  output wire [7:0]    io_debugVp,
  output wire [7:0]    io_debugAr,
  output wire [3:0]    io_debugFlags,
  output wire [31:0]   io_debugMulResult,
  input  wire          reset,
  input  wire          clk
);

  wire                fetch_io_bsy;
  wire       [7:0]    stackStg_io_dirAddr;
  wire       [31:0]   cu_io_din;
  wire                bcfetch_io_ack_irq;
  wire                bcfetch_io_ack_exc;
  wire       [11:0]   bcfetch_io_jpaddr;
  wire       [15:0]   bcfetch_io_opd;
  wire       [11:0]   bcfetch_io_jpc_out;
  wire       [7:0]    bcfetch_io_jinstr_out;
  wire                fetch_io_nxt;
  wire                fetch_io_opd;
  wire       [9:0]    fetch_io_dout;
  wire       [11:0]   fetch_io_pc_out;
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
  wire       [15:0]   decode_io_memIn_bcopd;
  wire       [3:0]    decode_io_mmuInstr;
  wire       [7:0]    decode_io_dirAddr;
  wire                decode_io_hwWr;
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
  wire                decode_io_cuPush;
  wire                decode_io_cuStart;
  wire       [5:0]    decode_io_cuOpcode;
  wire                decode_io_cuPop;
  wire       [31:0]   stackStg_io_debugRamData;
  wire       [7:0]    stackStg_io_debugSp;
  wire       [7:0]    stackStg_io_debugVp;
  wire       [7:0]    stackStg_io_debugAr;
  wire       [7:0]    stackStg_io_debugWrAddr;
  wire                stackStg_io_debugWrEn;
  wire       [7:0]    stackStg_io_debugRdAddrReg;
  wire       [31:0]   stackStg_io_debugRamDout;
  wire                stackStg_io_spOv;
  wire                stackStg_io_zf;
  wire                stackStg_io_nf;
  wire                stackStg_io_eq;
  wire                stackStg_io_lt;
  wire       [31:0]   stackStg_io_aout;
  wire       [31:0]   stackStg_io_bout;
  wire                stackStg_io_debugEnaA;
  wire       [2:0]    stackStg_io_debugSelLmux;
  wire                stackStg_io_debugEnaB;
  wire                stackStg_io_debugSelBmux;
  wire       [31:0]   stackStg_io_debugRamDoutVal;
  wire       [31:0]   stackStg_io_debugLmuxVal;
  wire       [31:0]   cu_io_dout;
  wire                cu_io_busy;
  wire       [11:0]   _zz__zz_io_din;
  wire                stackRotBusy;
  reg        [1:0]    dinMuxSel;
  reg        [31:0]   _zz_io_din;

  assign _zz__zz_io_din = io_memBcStart;
  BytecodeFetchStage bcfetch (
    .io_jpc_wr     (decode_io_enaJpc          ), //i
    .io_din        (stackStg_io_aout[31:0]    ), //i
    .io_jfetch     (fetch_io_nxt              ), //i
    .io_jopdfetch  (fetch_io_opd              ), //i
    .io_jbr        (decode_io_jbr             ), //i
    .io_zf         (stackStg_io_zf            ), //i
    .io_nf         (stackStg_io_nf            ), //i
    .io_eq         (stackStg_io_eq            ), //i
    .io_lt         (stackStg_io_lt            ), //i
    .io_jbcWrAddr  (io_jbcWrAddr[8:0]         ), //i
    .io_jbcWrData  (io_jbcWrData[31:0]        ), //i
    .io_jbcWrEn    (io_jbcWrEn                ), //i
    .io_stall      (stackRotBusy              ), //i
    .io_irq        (io_irq                    ), //i
    .io_exc        (io_exc                    ), //i
    .io_ena        (io_irqEna                 ), //i
    .io_ack_irq    (bcfetch_io_ack_irq        ), //o
    .io_ack_exc    (bcfetch_io_ack_exc        ), //o
    .io_jpaddr     (bcfetch_io_jpaddr[11:0]   ), //o
    .io_opd        (bcfetch_io_opd[15:0]      ), //o
    .io_jpc_out    (bcfetch_io_jpc_out[11:0]  ), //o
    .io_jinstr_out (bcfetch_io_jinstr_out[7:0]), //o
    .clk           (clk                       ), //i
    .reset         (reset                     )  //i
  );
  FetchStage fetch (
    .io_br       (decode_io_br           ), //i
    .io_jmp      (decode_io_jmp          ), //i
    .io_bsy      (fetch_io_bsy           ), //i
    .io_jpaddr   (bcfetch_io_jpaddr[11:0]), //i
    .io_extStall (stackRotBusy           ), //i
    .io_nxt      (fetch_io_nxt           ), //o
    .io_opd      (fetch_io_opd           ), //o
    .io_dout     (fetch_io_dout[9:0]     ), //o
    .io_pc_out   (fetch_io_pc_out[11:0]  ), //o
    .io_ir_out   (fetch_io_ir_out[9:0]   ), //o
    .reset       (reset                  ), //i
    .clk         (clk                    )  //i
  );
  DecodeStage decode (
    .io_instr           (fetch_io_dout[9:0]         ), //i
    .io_zf              (stackStg_io_zf             ), //i
    .io_nf              (stackStg_io_nf             ), //i
    .io_eq              (stackStg_io_eq             ), //i
    .io_lt              (stackStg_io_lt             ), //i
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
    .io_memIn_bcopd     (decode_io_memIn_bcopd[15:0]), //o
    .io_mmuInstr        (decode_io_mmuInstr[3:0]    ), //o
    .io_dirAddr         (decode_io_dirAddr[7:0]     ), //o
    .io_hwWr            (decode_io_hwWr             ), //o
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
    .io_stall           (stackRotBusy               ), //i
    .io_cuPush          (decode_io_cuPush           ), //o
    .io_cuStart         (decode_io_cuStart          ), //o
    .io_cuOpcode        (decode_io_cuOpcode[5:0]    ), //o
    .io_cuPop           (decode_io_cuPop            ), //o
    .clk                (clk                        ), //i
    .reset              (reset                      )  //i
  );
  StackStage stackStg (
    .io_din             (_zz_io_din[31:0]                 ), //i
    .io_dirAddr         (stackStg_io_dirAddr[7:0]         ), //i
    .io_opd             (bcfetch_io_opd[15:0]             ), //i
    .io_jpc             (bcfetch_io_jpc_out[11:0]         ), //i
    .io_selSub          (decode_io_selSub                 ), //i
    .io_selAmux         (decode_io_selAmux                ), //i
    .io_enaA            (decode_io_enaA                   ), //i
    .io_selBmux         (decode_io_selBmux                ), //i
    .io_selLog          (decode_io_selLog[1:0]            ), //i
    .io_selShf          (decode_io_selShf[1:0]            ), //i
    .io_selLmux         (decode_io_selLmux[2:0]           ), //i
    .io_selImux         (decode_io_selImux[1:0]           ), //i
    .io_selRmux         (decode_io_selRmux[1:0]           ), //i
    .io_selSmux         (decode_io_selSmux[1:0]           ), //i
    .io_selMmux         (decode_io_selMmux                ), //i
    .io_selRda          (decode_io_selRda[2:0]            ), //i
    .io_selWra          (decode_io_selWra[2:0]            ), //i
    .io_wrEna           (decode_io_wrEna                  ), //i
    .io_enaB            (decode_io_enaB                   ), //i
    .io_enaVp           (decode_io_enaVp                  ), //i
    .io_enaAr           (decode_io_enaAr                  ), //i
    .io_debugRamAddr    (io_debugRamAddr[7:0]             ), //i
    .io_debugRamData    (stackStg_io_debugRamData[31:0]   ), //o
    .io_debugRamWrAddr  (8'h0                             ), //i
    .io_debugRamWrData  (32'h0                            ), //i
    .io_debugRamWrEn    (1'b0                             ), //i
    .io_debugSp         (stackStg_io_debugSp[7:0]         ), //o
    .io_debugVp         (stackStg_io_debugVp[7:0]         ), //o
    .io_debugAr         (stackStg_io_debugAr[7:0]         ), //o
    .io_debugWrAddr     (stackStg_io_debugWrAddr[7:0]     ), //o
    .io_debugWrEn       (stackStg_io_debugWrEn            ), //o
    .io_debugRdAddrReg  (stackStg_io_debugRdAddrReg[7:0]  ), //o
    .io_debugRamDout    (stackStg_io_debugRamDout[31:0]   ), //o
    .io_spOv            (stackStg_io_spOv                 ), //o
    .io_zf              (stackStg_io_zf                   ), //o
    .io_nf              (stackStg_io_nf                   ), //o
    .io_eq              (stackStg_io_eq                   ), //o
    .io_lt              (stackStg_io_lt                   ), //o
    .io_aout            (stackStg_io_aout[31:0]           ), //o
    .io_bout            (stackStg_io_bout[31:0]           ), //o
    .io_debugEnaA       (stackStg_io_debugEnaA            ), //o
    .io_debugSelLmux    (stackStg_io_debugSelLmux[2:0]    ), //o
    .io_debugEnaB       (stackStg_io_debugEnaB            ), //o
    .io_debugSelBmux    (stackStg_io_debugSelBmux         ), //o
    .io_debugRamDoutVal (stackStg_io_debugRamDoutVal[31:0]), //o
    .io_debugLmuxVal    (stackStg_io_debugLmuxVal[31:0]   ), //o
    .clk                (clk                              ), //i
    .reset              (reset                            )  //i
  );
  ComputeUnitTop cu (
    .io_din    (cu_io_din[31:0]        ), //i
    .io_push   (decode_io_cuPush       ), //i
    .io_opcode (decode_io_cuOpcode[5:0]), //i
    .io_start  (decode_io_cuStart      ), //i
    .io_dout   (cu_io_dout[31:0]       ), //o
    .io_pop    (decode_io_cuPop        ), //i
    .io_busy   (cu_io_busy             ), //o
    .clk       (clk                    ), //i
    .reset     (reset                  )  //i
  );
  assign io_ackIrq = bcfetch_io_ack_irq;
  assign io_ackExc = bcfetch_io_ack_exc;
  assign stackRotBusy = 1'b0;
  assign fetch_io_bsy = (((decode_io_wrDly || io_memBusy) || stackRotBusy) || cu_io_busy);
  always @(*) begin
    case(dinMuxSel)
      2'b00 : begin
        _zz_io_din = io_memRdData;
      end
      2'b01 : begin
        _zz_io_din = cu_io_dout;
      end
      2'b10 : begin
        _zz_io_din = {20'd0, _zz__zz_io_din};
      end
      default : begin
        _zz_io_din = 32'h0;
      end
    endcase
  end

  assign stackStg_io_dirAddr = decode_io_dirAddr;
  assign io_debugRamData = stackStg_io_debugRamData;
  assign cu_io_din = stackStg_io_aout;
  assign io_hwBusy = cu_io_busy;
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
  assign io_memCtrl_bcopd = decode_io_memIn_bcopd;
  assign io_aout = stackStg_io_aout;
  assign io_bout = stackStg_io_bout;
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
  assign io_debugSp = stackStg_io_debugSp;
  assign io_debugVp = stackStg_io_debugVp;
  assign io_debugAr = stackStg_io_debugAr;
  assign io_debugFlags = {{{stackStg_io_zf,stackStg_io_nf},stackStg_io_eq},stackStg_io_lt};
  assign io_debugMulResult = cu_io_dout;
  always @(posedge clk or posedge reset) begin
    if(reset) begin
      dinMuxSel <= 2'b00;
    end else begin
      dinMuxSel <= fetch_io_ir_out[1 : 0];
    end
  end


endmodule

module ArrayCache (
  input  wire [23:0]   io_handle,
  input  wire [23:0]   io_index,
  input  wire          io_chkIal,
  input  wire          io_chkIas,
  output wire          io_hit,
  output wire [31:0]   io_dout,
  input  wire          io_wrIal,
  input  wire          io_wrIas,
  input  wire [31:0]   io_ialVal,
  input  wire [31:0]   io_iasVal,
  input  wire          io_inval,
  input  wire          io_snoopValid,
  input  wire [23:0]   io_snoopHandle,
  input  wire [23:0]   io_snoopIndex,
  input  wire          clk,
  input  wire          reset
);

  reg        [31:0]   dataRam_spinal_port0;
  wire       [3:0]    _zz_when_ArrayCache_l133;
  wire       [3:0]    _zz_when_ArrayCache_l133_1;
  wire       [3:0]    _zz_when_ArrayCache_l133_2;
  wire       [3:0]    _zz_when_ArrayCache_l133_3;
  wire       [3:0]    _zz_when_ArrayCache_l133_4;
  wire       [3:0]    _zz_when_ArrayCache_l133_5;
  wire       [3:0]    _zz_when_ArrayCache_l133_6;
  wire       [3:0]    _zz_when_ArrayCache_l133_7;
  wire       [3:0]    _zz_when_ArrayCache_l133_8;
  wire       [3:0]    _zz_when_ArrayCache_l133_9;
  wire       [3:0]    _zz_when_ArrayCache_l133_10;
  wire       [3:0]    _zz_when_ArrayCache_l133_11;
  wire       [3:0]    _zz_when_ArrayCache_l133_12;
  wire       [3:0]    _zz_when_ArrayCache_l133_13;
  wire       [3:0]    _zz_when_ArrayCache_l133_14;
  wire       [3:0]    _zz_when_ArrayCache_l133_15;
  wire       [3:0]    _zz_when_ArrayCache_l133_16;
  wire       [3:0]    _zz_when_ArrayCache_l133_17;
  wire       [3:0]    _zz_when_ArrayCache_l133_18;
  wire       [3:0]    _zz_when_ArrayCache_l133_19;
  wire       [3:0]    _zz_when_ArrayCache_l133_20;
  wire       [3:0]    _zz_when_ArrayCache_l133_21;
  wire       [3:0]    _zz_when_ArrayCache_l133_22;
  wire       [3:0]    _zz_when_ArrayCache_l133_23;
  wire       [3:0]    _zz_when_ArrayCache_l133_24;
  wire       [3:0]    _zz_when_ArrayCache_l133_25;
  wire       [3:0]    _zz_when_ArrayCache_l133_26;
  wire       [3:0]    _zz_when_ArrayCache_l133_27;
  wire       [3:0]    _zz_when_ArrayCache_l133_28;
  wire       [3:0]    _zz_when_ArrayCache_l133_29;
  wire       [3:0]    _zz_when_ArrayCache_l133_30;
  wire       [3:0]    _zz_when_ArrayCache_l133_31;
  wire       [3:0]    _zz_when_ArrayCache_l133_32;
  wire       [3:0]    _zz_when_ArrayCache_l133_33;
  wire       [3:0]    _zz_when_ArrayCache_l133_34;
  wire       [3:0]    _zz_when_ArrayCache_l133_35;
  wire       [3:0]    _zz_when_ArrayCache_l133_36;
  wire       [3:0]    _zz_when_ArrayCache_l133_37;
  wire       [3:0]    _zz_when_ArrayCache_l133_38;
  wire       [3:0]    _zz_when_ArrayCache_l133_39;
  wire       [3:0]    _zz_when_ArrayCache_l133_40;
  wire       [3:0]    _zz_when_ArrayCache_l133_41;
  wire       [3:0]    _zz_when_ArrayCache_l133_42;
  wire       [3:0]    _zz_when_ArrayCache_l133_43;
  wire       [3:0]    _zz_when_ArrayCache_l133_44;
  wire       [3:0]    _zz_when_ArrayCache_l133_45;
  wire       [3:0]    _zz_when_ArrayCache_l133_46;
  wire       [3:0]    _zz_when_ArrayCache_l133_47;
  wire       [3:0]    _zz_when_ArrayCache_l133_48;
  wire       [3:0]    _zz_when_ArrayCache_l133_49;
  wire       [3:0]    _zz_when_ArrayCache_l133_50;
  wire       [3:0]    _zz_when_ArrayCache_l133_51;
  wire       [3:0]    _zz_when_ArrayCache_l133_52;
  wire       [3:0]    _zz_when_ArrayCache_l133_53;
  wire       [3:0]    _zz_when_ArrayCache_l133_54;
  wire       [3:0]    _zz_when_ArrayCache_l133_55;
  wire       [3:0]    _zz_when_ArrayCache_l133_56;
  wire       [3:0]    _zz_when_ArrayCache_l133_57;
  wire       [3:0]    _zz_when_ArrayCache_l133_58;
  wire       [3:0]    _zz_when_ArrayCache_l133_59;
  wire       [3:0]    _zz_when_ArrayCache_l133_60;
  wire       [3:0]    _zz_when_ArrayCache_l133_61;
  wire       [3:0]    _zz_when_ArrayCache_l133_62;
  wire       [3:0]    _zz_when_ArrayCache_l133_63;
  wire                _zz_dataRam_port;
  wire                _zz_ramDout;
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
  reg        [21:0]   tagIdx_0;
  reg        [21:0]   tagIdx_1;
  reg        [21:0]   tagIdx_2;
  reg        [21:0]   tagIdx_3;
  reg        [21:0]   tagIdx_4;
  reg        [21:0]   tagIdx_5;
  reg        [21:0]   tagIdx_6;
  reg        [21:0]   tagIdx_7;
  reg        [21:0]   tagIdx_8;
  reg        [21:0]   tagIdx_9;
  reg        [21:0]   tagIdx_10;
  reg        [21:0]   tagIdx_11;
  reg        [21:0]   tagIdx_12;
  reg        [21:0]   tagIdx_13;
  reg        [21:0]   tagIdx_14;
  reg        [21:0]   tagIdx_15;
  reg                 valid_0;
  reg                 valid_1;
  reg                 valid_2;
  reg                 valid_3;
  reg                 valid_4;
  reg                 valid_5;
  reg                 valid_6;
  reg                 valid_7;
  reg                 valid_8;
  reg                 valid_9;
  reg                 valid_10;
  reg                 valid_11;
  reg                 valid_12;
  reg                 valid_13;
  reg                 valid_14;
  reg                 valid_15;
  reg        [3:0]    nxt;
  wire       [1:0]    idxLower;
  wire       [21:0]   idxUpper;
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
  reg        [3:0]    lineEnc;
  wire                when_ArrayCache_l133;
  wire                when_ArrayCache_l133_1;
  wire                when_ArrayCache_l133_2;
  wire                when_ArrayCache_l133_3;
  wire                when_ArrayCache_l133_4;
  wire                when_ArrayCache_l133_5;
  wire                when_ArrayCache_l133_6;
  wire                when_ArrayCache_l133_7;
  wire                when_ArrayCache_l133_8;
  wire                when_ArrayCache_l133_9;
  wire                when_ArrayCache_l133_10;
  wire                when_ArrayCache_l133_11;
  wire                when_ArrayCache_l133_12;
  wire                when_ArrayCache_l133_13;
  wire                when_ArrayCache_l133_14;
  wire                when_ArrayCache_l133_15;
  wire                when_ArrayCache_l133_16;
  wire                when_ArrayCache_l133_17;
  wire                when_ArrayCache_l133_18;
  wire                when_ArrayCache_l133_19;
  wire                when_ArrayCache_l133_20;
  wire                when_ArrayCache_l133_21;
  wire                when_ArrayCache_l133_22;
  wire                when_ArrayCache_l133_23;
  wire                when_ArrayCache_l133_24;
  wire                when_ArrayCache_l133_25;
  wire                when_ArrayCache_l133_26;
  wire                when_ArrayCache_l133_27;
  wire                when_ArrayCache_l133_28;
  wire                when_ArrayCache_l133_29;
  wire                when_ArrayCache_l133_30;
  wire                when_ArrayCache_l133_31;
  wire                when_ArrayCache_l133_32;
  wire                when_ArrayCache_l133_33;
  wire                when_ArrayCache_l133_34;
  wire                when_ArrayCache_l133_35;
  wire                when_ArrayCache_l133_36;
  wire                when_ArrayCache_l133_37;
  wire                when_ArrayCache_l133_38;
  wire                when_ArrayCache_l133_39;
  wire                when_ArrayCache_l133_40;
  wire                when_ArrayCache_l133_41;
  wire                when_ArrayCache_l133_42;
  wire                when_ArrayCache_l133_43;
  wire                when_ArrayCache_l133_44;
  wire                when_ArrayCache_l133_45;
  wire                when_ArrayCache_l133_46;
  wire                when_ArrayCache_l133_47;
  wire                when_ArrayCache_l133_48;
  wire                when_ArrayCache_l133_49;
  wire                when_ArrayCache_l133_50;
  wire                when_ArrayCache_l133_51;
  wire                when_ArrayCache_l133_52;
  wire                when_ArrayCache_l133_53;
  wire                when_ArrayCache_l133_54;
  wire                when_ArrayCache_l133_55;
  wire                when_ArrayCache_l133_56;
  wire                when_ArrayCache_l133_57;
  wire                when_ArrayCache_l133_58;
  wire                when_ArrayCache_l133_59;
  wire                when_ArrayCache_l133_60;
  wire                when_ArrayCache_l133_61;
  wire                when_ArrayCache_l133_62;
  wire                when_ArrayCache_l133_63;
  reg        [3:0]    lineReg;
  reg                 incNxtReg;
  reg                 hitTagReg;
  reg                 cacheableReg;
  reg                 snoopDuringFill;
  reg        [23:0]   handleReg;
  reg        [23:0]   indexReg;
  reg        [1:0]    idxReg;
  wire                when_ArrayCache_l168;
  wire                when_ArrayCache_l181;
  wire       [5:0]    ramRdAddr;
  wire       [31:0]   ramDout;
  reg                 chkIalDly;
  reg        [31:0]   ramDoutStore;
  wire                updateCache;
  wire       [31:0]   ramDin;
  wire       [5:0]    ramWrAddr;
  wire       [15:0]   _zz_4;
  wire       [15:0]   _zz_5;
  wire       [21:0]   _zz_tagIdx_0;
  wire       [15:0]   _zz_6;
  wire                when_ArrayCache_l250;
  wire                when_ArrayCache_l268;
  wire                when_ArrayCache_l272;
  wire                when_ArrayCache_l268_1;
  wire                when_ArrayCache_l272_1;
  wire                when_ArrayCache_l268_2;
  wire                when_ArrayCache_l272_2;
  wire                when_ArrayCache_l268_3;
  wire                when_ArrayCache_l272_3;
  wire                when_ArrayCache_l268_4;
  wire                when_ArrayCache_l272_4;
  wire                when_ArrayCache_l268_5;
  wire                when_ArrayCache_l272_5;
  wire                when_ArrayCache_l268_6;
  wire                when_ArrayCache_l272_6;
  wire                when_ArrayCache_l268_7;
  wire                when_ArrayCache_l272_7;
  wire                when_ArrayCache_l268_8;
  wire                when_ArrayCache_l272_8;
  wire                when_ArrayCache_l268_9;
  wire                when_ArrayCache_l272_9;
  wire                when_ArrayCache_l268_10;
  wire                when_ArrayCache_l272_10;
  wire                when_ArrayCache_l268_11;
  wire                when_ArrayCache_l272_11;
  wire                when_ArrayCache_l268_12;
  wire                when_ArrayCache_l272_12;
  wire                when_ArrayCache_l268_13;
  wire                when_ArrayCache_l272_13;
  wire                when_ArrayCache_l268_14;
  wire                when_ArrayCache_l272_14;
  wire                when_ArrayCache_l268_15;
  wire                when_ArrayCache_l272_15;
  reg [31:0] dataRam [0:63];

  assign _zz_when_ArrayCache_l133 = 4'b0000;
  assign _zz_when_ArrayCache_l133_1 = 4'b0001;
  assign _zz_when_ArrayCache_l133_2 = 4'b0010;
  assign _zz_when_ArrayCache_l133_3 = 4'b0011;
  assign _zz_when_ArrayCache_l133_4 = 4'b0100;
  assign _zz_when_ArrayCache_l133_5 = 4'b0101;
  assign _zz_when_ArrayCache_l133_6 = 4'b0110;
  assign _zz_when_ArrayCache_l133_7 = 4'b0111;
  assign _zz_when_ArrayCache_l133_8 = 4'b1000;
  assign _zz_when_ArrayCache_l133_9 = 4'b1001;
  assign _zz_when_ArrayCache_l133_10 = 4'b1010;
  assign _zz_when_ArrayCache_l133_11 = 4'b1011;
  assign _zz_when_ArrayCache_l133_12 = 4'b1100;
  assign _zz_when_ArrayCache_l133_13 = 4'b1101;
  assign _zz_when_ArrayCache_l133_14 = 4'b1110;
  assign _zz_when_ArrayCache_l133_15 = 4'b1111;
  assign _zz_when_ArrayCache_l133_16 = 4'b0000;
  assign _zz_when_ArrayCache_l133_17 = 4'b0001;
  assign _zz_when_ArrayCache_l133_18 = 4'b0010;
  assign _zz_when_ArrayCache_l133_19 = 4'b0011;
  assign _zz_when_ArrayCache_l133_20 = 4'b0100;
  assign _zz_when_ArrayCache_l133_21 = 4'b0101;
  assign _zz_when_ArrayCache_l133_22 = 4'b0110;
  assign _zz_when_ArrayCache_l133_23 = 4'b0111;
  assign _zz_when_ArrayCache_l133_24 = 4'b1000;
  assign _zz_when_ArrayCache_l133_25 = 4'b1001;
  assign _zz_when_ArrayCache_l133_26 = 4'b1010;
  assign _zz_when_ArrayCache_l133_27 = 4'b1011;
  assign _zz_when_ArrayCache_l133_28 = 4'b1100;
  assign _zz_when_ArrayCache_l133_29 = 4'b1101;
  assign _zz_when_ArrayCache_l133_30 = 4'b1110;
  assign _zz_when_ArrayCache_l133_31 = 4'b1111;
  assign _zz_when_ArrayCache_l133_32 = 4'b0000;
  assign _zz_when_ArrayCache_l133_33 = 4'b0001;
  assign _zz_when_ArrayCache_l133_34 = 4'b0010;
  assign _zz_when_ArrayCache_l133_35 = 4'b0011;
  assign _zz_when_ArrayCache_l133_36 = 4'b0100;
  assign _zz_when_ArrayCache_l133_37 = 4'b0101;
  assign _zz_when_ArrayCache_l133_38 = 4'b0110;
  assign _zz_when_ArrayCache_l133_39 = 4'b0111;
  assign _zz_when_ArrayCache_l133_40 = 4'b1000;
  assign _zz_when_ArrayCache_l133_41 = 4'b1001;
  assign _zz_when_ArrayCache_l133_42 = 4'b1010;
  assign _zz_when_ArrayCache_l133_43 = 4'b1011;
  assign _zz_when_ArrayCache_l133_44 = 4'b1100;
  assign _zz_when_ArrayCache_l133_45 = 4'b1101;
  assign _zz_when_ArrayCache_l133_46 = 4'b1110;
  assign _zz_when_ArrayCache_l133_47 = 4'b1111;
  assign _zz_when_ArrayCache_l133_48 = 4'b0000;
  assign _zz_when_ArrayCache_l133_49 = 4'b0001;
  assign _zz_when_ArrayCache_l133_50 = 4'b0010;
  assign _zz_when_ArrayCache_l133_51 = 4'b0011;
  assign _zz_when_ArrayCache_l133_52 = 4'b0100;
  assign _zz_when_ArrayCache_l133_53 = 4'b0101;
  assign _zz_when_ArrayCache_l133_54 = 4'b0110;
  assign _zz_when_ArrayCache_l133_55 = 4'b0111;
  assign _zz_when_ArrayCache_l133_56 = 4'b1000;
  assign _zz_when_ArrayCache_l133_57 = 4'b1001;
  assign _zz_when_ArrayCache_l133_58 = 4'b1010;
  assign _zz_when_ArrayCache_l133_59 = 4'b1011;
  assign _zz_when_ArrayCache_l133_60 = 4'b1100;
  assign _zz_when_ArrayCache_l133_61 = 4'b1101;
  assign _zz_when_ArrayCache_l133_62 = 4'b1110;
  assign _zz_when_ArrayCache_l133_63 = 4'b1111;
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
    _zz_1 = 1'b0;
    if(updateCache) begin
      _zz_1 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc = _zz_lineEnc_1;
    if(when_ArrayCache_l133_63) begin
      _zz_lineEnc = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_1 = _zz_lineEnc_2;
    if(when_ArrayCache_l133_62) begin
      _zz_lineEnc_1 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_2 = _zz_lineEnc_3;
    if(when_ArrayCache_l133_61) begin
      _zz_lineEnc_2 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_3 = _zz_lineEnc_4;
    if(when_ArrayCache_l133_60) begin
      _zz_lineEnc_3 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_4 = _zz_lineEnc_5;
    if(when_ArrayCache_l133_59) begin
      _zz_lineEnc_4 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_5 = _zz_lineEnc_6;
    if(when_ArrayCache_l133_58) begin
      _zz_lineEnc_5 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_6 = _zz_lineEnc_7;
    if(when_ArrayCache_l133_57) begin
      _zz_lineEnc_6 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_7 = _zz_lineEnc_8;
    if(when_ArrayCache_l133_56) begin
      _zz_lineEnc_7 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_8 = _zz_lineEnc_9;
    if(when_ArrayCache_l133_55) begin
      _zz_lineEnc_8 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_9 = _zz_lineEnc_10;
    if(when_ArrayCache_l133_54) begin
      _zz_lineEnc_9 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_10 = _zz_lineEnc_11;
    if(when_ArrayCache_l133_53) begin
      _zz_lineEnc_10 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_11 = _zz_lineEnc_12;
    if(when_ArrayCache_l133_52) begin
      _zz_lineEnc_11 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_12 = _zz_lineEnc_13;
    if(when_ArrayCache_l133_51) begin
      _zz_lineEnc_12 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_13 = _zz_lineEnc_14;
    if(when_ArrayCache_l133_50) begin
      _zz_lineEnc_13 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_14 = _zz_lineEnc_15;
    if(when_ArrayCache_l133_49) begin
      _zz_lineEnc_14 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_15 = 1'b0;
    if(when_ArrayCache_l133_48) begin
      _zz_lineEnc_15 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_16 = _zz_lineEnc_17;
    if(when_ArrayCache_l133_47) begin
      _zz_lineEnc_16 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_17 = _zz_lineEnc_18;
    if(when_ArrayCache_l133_46) begin
      _zz_lineEnc_17 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_18 = _zz_lineEnc_19;
    if(when_ArrayCache_l133_45) begin
      _zz_lineEnc_18 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_19 = _zz_lineEnc_20;
    if(when_ArrayCache_l133_44) begin
      _zz_lineEnc_19 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_20 = _zz_lineEnc_21;
    if(when_ArrayCache_l133_43) begin
      _zz_lineEnc_20 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_21 = _zz_lineEnc_22;
    if(when_ArrayCache_l133_42) begin
      _zz_lineEnc_21 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_22 = _zz_lineEnc_23;
    if(when_ArrayCache_l133_41) begin
      _zz_lineEnc_22 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_23 = _zz_lineEnc_24;
    if(when_ArrayCache_l133_40) begin
      _zz_lineEnc_23 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_24 = _zz_lineEnc_25;
    if(when_ArrayCache_l133_39) begin
      _zz_lineEnc_24 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_25 = _zz_lineEnc_26;
    if(when_ArrayCache_l133_38) begin
      _zz_lineEnc_25 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_26 = _zz_lineEnc_27;
    if(when_ArrayCache_l133_37) begin
      _zz_lineEnc_26 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_27 = _zz_lineEnc_28;
    if(when_ArrayCache_l133_36) begin
      _zz_lineEnc_27 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_28 = _zz_lineEnc_29;
    if(when_ArrayCache_l133_35) begin
      _zz_lineEnc_28 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_29 = _zz_lineEnc_30;
    if(when_ArrayCache_l133_34) begin
      _zz_lineEnc_29 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_30 = _zz_lineEnc_31;
    if(when_ArrayCache_l133_33) begin
      _zz_lineEnc_30 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_31 = 1'b0;
    if(when_ArrayCache_l133_32) begin
      _zz_lineEnc_31 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_32 = _zz_lineEnc_33;
    if(when_ArrayCache_l133_31) begin
      _zz_lineEnc_32 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_33 = _zz_lineEnc_34;
    if(when_ArrayCache_l133_30) begin
      _zz_lineEnc_33 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_34 = _zz_lineEnc_35;
    if(when_ArrayCache_l133_29) begin
      _zz_lineEnc_34 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_35 = _zz_lineEnc_36;
    if(when_ArrayCache_l133_28) begin
      _zz_lineEnc_35 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_36 = _zz_lineEnc_37;
    if(when_ArrayCache_l133_27) begin
      _zz_lineEnc_36 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_37 = _zz_lineEnc_38;
    if(when_ArrayCache_l133_26) begin
      _zz_lineEnc_37 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_38 = _zz_lineEnc_39;
    if(when_ArrayCache_l133_25) begin
      _zz_lineEnc_38 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_39 = _zz_lineEnc_40;
    if(when_ArrayCache_l133_24) begin
      _zz_lineEnc_39 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_40 = _zz_lineEnc_41;
    if(when_ArrayCache_l133_23) begin
      _zz_lineEnc_40 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_41 = _zz_lineEnc_42;
    if(when_ArrayCache_l133_22) begin
      _zz_lineEnc_41 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_42 = _zz_lineEnc_43;
    if(when_ArrayCache_l133_21) begin
      _zz_lineEnc_42 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_43 = _zz_lineEnc_44;
    if(when_ArrayCache_l133_20) begin
      _zz_lineEnc_43 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_44 = _zz_lineEnc_45;
    if(when_ArrayCache_l133_19) begin
      _zz_lineEnc_44 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_45 = _zz_lineEnc_46;
    if(when_ArrayCache_l133_18) begin
      _zz_lineEnc_45 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_46 = _zz_lineEnc_47;
    if(when_ArrayCache_l133_17) begin
      _zz_lineEnc_46 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_47 = 1'b0;
    if(when_ArrayCache_l133_16) begin
      _zz_lineEnc_47 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_48 = _zz_lineEnc_49;
    if(when_ArrayCache_l133_15) begin
      _zz_lineEnc_48 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_49 = _zz_lineEnc_50;
    if(when_ArrayCache_l133_14) begin
      _zz_lineEnc_49 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_50 = _zz_lineEnc_51;
    if(when_ArrayCache_l133_13) begin
      _zz_lineEnc_50 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_51 = _zz_lineEnc_52;
    if(when_ArrayCache_l133_12) begin
      _zz_lineEnc_51 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_52 = _zz_lineEnc_53;
    if(when_ArrayCache_l133_11) begin
      _zz_lineEnc_52 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_53 = _zz_lineEnc_54;
    if(when_ArrayCache_l133_10) begin
      _zz_lineEnc_53 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_54 = _zz_lineEnc_55;
    if(when_ArrayCache_l133_9) begin
      _zz_lineEnc_54 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_55 = _zz_lineEnc_56;
    if(when_ArrayCache_l133_8) begin
      _zz_lineEnc_55 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_56 = _zz_lineEnc_57;
    if(when_ArrayCache_l133_7) begin
      _zz_lineEnc_56 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_57 = _zz_lineEnc_58;
    if(when_ArrayCache_l133_6) begin
      _zz_lineEnc_57 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_58 = _zz_lineEnc_59;
    if(when_ArrayCache_l133_5) begin
      _zz_lineEnc_58 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_59 = _zz_lineEnc_60;
    if(when_ArrayCache_l133_4) begin
      _zz_lineEnc_59 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_60 = _zz_lineEnc_61;
    if(when_ArrayCache_l133_3) begin
      _zz_lineEnc_60 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_61 = _zz_lineEnc_62;
    if(when_ArrayCache_l133_2) begin
      _zz_lineEnc_61 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_62 = _zz_lineEnc_63;
    if(when_ArrayCache_l133_1) begin
      _zz_lineEnc_62 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_63 = 1'b0;
    if(when_ArrayCache_l133) begin
      _zz_lineEnc_63 = 1'b1;
    end
  end

  assign idxLower = io_index[1 : 0];
  assign idxUpper = io_index[23 : 2];
  assign _zz_hitVec = ((tag_0 == io_handle) && valid_0);
  always @(*) begin
    hitTagVec[0] = _zz_hitVec;
    hitTagVec[1] = _zz_hitVec_1;
    hitTagVec[2] = _zz_hitVec_2;
    hitTagVec[3] = _zz_hitVec_3;
    hitTagVec[4] = _zz_hitVec_4;
    hitTagVec[5] = _zz_hitVec_5;
    hitTagVec[6] = _zz_hitVec_6;
    hitTagVec[7] = _zz_hitVec_7;
    hitTagVec[8] = _zz_hitVec_8;
    hitTagVec[9] = _zz_hitVec_9;
    hitTagVec[10] = _zz_hitVec_10;
    hitTagVec[11] = _zz_hitVec_11;
    hitTagVec[12] = _zz_hitVec_12;
    hitTagVec[13] = _zz_hitVec_13;
    hitTagVec[14] = _zz_hitVec_14;
    hitTagVec[15] = _zz_hitVec_15;
  end

  always @(*) begin
    hitVec[0] = (_zz_hitVec && (tagIdx_0 == idxUpper));
    hitVec[1] = (_zz_hitVec_1 && (tagIdx_1 == idxUpper));
    hitVec[2] = (_zz_hitVec_2 && (tagIdx_2 == idxUpper));
    hitVec[3] = (_zz_hitVec_3 && (tagIdx_3 == idxUpper));
    hitVec[4] = (_zz_hitVec_4 && (tagIdx_4 == idxUpper));
    hitVec[5] = (_zz_hitVec_5 && (tagIdx_5 == idxUpper));
    hitVec[6] = (_zz_hitVec_6 && (tagIdx_6 == idxUpper));
    hitVec[7] = (_zz_hitVec_7 && (tagIdx_7 == idxUpper));
    hitVec[8] = (_zz_hitVec_8 && (tagIdx_8 == idxUpper));
    hitVec[9] = (_zz_hitVec_9 && (tagIdx_9 == idxUpper));
    hitVec[10] = (_zz_hitVec_10 && (tagIdx_10 == idxUpper));
    hitVec[11] = (_zz_hitVec_11 && (tagIdx_11 == idxUpper));
    hitVec[12] = (_zz_hitVec_12 && (tagIdx_12 == idxUpper));
    hitVec[13] = (_zz_hitVec_13 && (tagIdx_13 == idxUpper));
    hitVec[14] = (_zz_hitVec_14 && (tagIdx_14 == idxUpper));
    hitVec[15] = (_zz_hitVec_15 && (tagIdx_15 == idxUpper));
  end

  assign _zz_hitVec_1 = ((tag_1 == io_handle) && valid_1);
  assign _zz_hitVec_2 = ((tag_2 == io_handle) && valid_2);
  assign _zz_hitVec_3 = ((tag_3 == io_handle) && valid_3);
  assign _zz_hitVec_4 = ((tag_4 == io_handle) && valid_4);
  assign _zz_hitVec_5 = ((tag_5 == io_handle) && valid_5);
  assign _zz_hitVec_6 = ((tag_6 == io_handle) && valid_6);
  assign _zz_hitVec_7 = ((tag_7 == io_handle) && valid_7);
  assign _zz_hitVec_8 = ((tag_8 == io_handle) && valid_8);
  assign _zz_hitVec_9 = ((tag_9 == io_handle) && valid_9);
  assign _zz_hitVec_10 = ((tag_10 == io_handle) && valid_10);
  assign _zz_hitVec_11 = ((tag_11 == io_handle) && valid_11);
  assign _zz_hitVec_12 = ((tag_12 == io_handle) && valid_12);
  assign _zz_hitVec_13 = ((tag_13 == io_handle) && valid_13);
  assign _zz_hitVec_14 = ((tag_14 == io_handle) && valid_14);
  assign _zz_hitVec_15 = ((tag_15 == io_handle) && valid_15);
  assign io_hit = (|hitVec);
  assign when_ArrayCache_l133 = (_zz_when_ArrayCache_l133[0] && hitVec[0]);
  assign when_ArrayCache_l133_1 = (_zz_when_ArrayCache_l133_1[0] && hitVec[1]);
  assign when_ArrayCache_l133_2 = (_zz_when_ArrayCache_l133_2[0] && hitVec[2]);
  assign when_ArrayCache_l133_3 = (_zz_when_ArrayCache_l133_3[0] && hitVec[3]);
  assign when_ArrayCache_l133_4 = (_zz_when_ArrayCache_l133_4[0] && hitVec[4]);
  assign when_ArrayCache_l133_5 = (_zz_when_ArrayCache_l133_5[0] && hitVec[5]);
  assign when_ArrayCache_l133_6 = (_zz_when_ArrayCache_l133_6[0] && hitVec[6]);
  assign when_ArrayCache_l133_7 = (_zz_when_ArrayCache_l133_7[0] && hitVec[7]);
  assign when_ArrayCache_l133_8 = (_zz_when_ArrayCache_l133_8[0] && hitVec[8]);
  assign when_ArrayCache_l133_9 = (_zz_when_ArrayCache_l133_9[0] && hitVec[9]);
  assign when_ArrayCache_l133_10 = (_zz_when_ArrayCache_l133_10[0] && hitVec[10]);
  assign when_ArrayCache_l133_11 = (_zz_when_ArrayCache_l133_11[0] && hitVec[11]);
  assign when_ArrayCache_l133_12 = (_zz_when_ArrayCache_l133_12[0] && hitVec[12]);
  assign when_ArrayCache_l133_13 = (_zz_when_ArrayCache_l133_13[0] && hitVec[13]);
  assign when_ArrayCache_l133_14 = (_zz_when_ArrayCache_l133_14[0] && hitVec[14]);
  assign when_ArrayCache_l133_15 = (_zz_when_ArrayCache_l133_15[0] && hitVec[15]);
  always @(*) begin
    lineEnc[0] = _zz_lineEnc_48;
    lineEnc[1] = _zz_lineEnc_32;
    lineEnc[2] = _zz_lineEnc_16;
    lineEnc[3] = _zz_lineEnc;
  end

  assign when_ArrayCache_l133_16 = (_zz_when_ArrayCache_l133_16[1] && hitVec[0]);
  assign when_ArrayCache_l133_17 = (_zz_when_ArrayCache_l133_17[1] && hitVec[1]);
  assign when_ArrayCache_l133_18 = (_zz_when_ArrayCache_l133_18[1] && hitVec[2]);
  assign when_ArrayCache_l133_19 = (_zz_when_ArrayCache_l133_19[1] && hitVec[3]);
  assign when_ArrayCache_l133_20 = (_zz_when_ArrayCache_l133_20[1] && hitVec[4]);
  assign when_ArrayCache_l133_21 = (_zz_when_ArrayCache_l133_21[1] && hitVec[5]);
  assign when_ArrayCache_l133_22 = (_zz_when_ArrayCache_l133_22[1] && hitVec[6]);
  assign when_ArrayCache_l133_23 = (_zz_when_ArrayCache_l133_23[1] && hitVec[7]);
  assign when_ArrayCache_l133_24 = (_zz_when_ArrayCache_l133_24[1] && hitVec[8]);
  assign when_ArrayCache_l133_25 = (_zz_when_ArrayCache_l133_25[1] && hitVec[9]);
  assign when_ArrayCache_l133_26 = (_zz_when_ArrayCache_l133_26[1] && hitVec[10]);
  assign when_ArrayCache_l133_27 = (_zz_when_ArrayCache_l133_27[1] && hitVec[11]);
  assign when_ArrayCache_l133_28 = (_zz_when_ArrayCache_l133_28[1] && hitVec[12]);
  assign when_ArrayCache_l133_29 = (_zz_when_ArrayCache_l133_29[1] && hitVec[13]);
  assign when_ArrayCache_l133_30 = (_zz_when_ArrayCache_l133_30[1] && hitVec[14]);
  assign when_ArrayCache_l133_31 = (_zz_when_ArrayCache_l133_31[1] && hitVec[15]);
  assign when_ArrayCache_l133_32 = (_zz_when_ArrayCache_l133_32[2] && hitVec[0]);
  assign when_ArrayCache_l133_33 = (_zz_when_ArrayCache_l133_33[2] && hitVec[1]);
  assign when_ArrayCache_l133_34 = (_zz_when_ArrayCache_l133_34[2] && hitVec[2]);
  assign when_ArrayCache_l133_35 = (_zz_when_ArrayCache_l133_35[2] && hitVec[3]);
  assign when_ArrayCache_l133_36 = (_zz_when_ArrayCache_l133_36[2] && hitVec[4]);
  assign when_ArrayCache_l133_37 = (_zz_when_ArrayCache_l133_37[2] && hitVec[5]);
  assign when_ArrayCache_l133_38 = (_zz_when_ArrayCache_l133_38[2] && hitVec[6]);
  assign when_ArrayCache_l133_39 = (_zz_when_ArrayCache_l133_39[2] && hitVec[7]);
  assign when_ArrayCache_l133_40 = (_zz_when_ArrayCache_l133_40[2] && hitVec[8]);
  assign when_ArrayCache_l133_41 = (_zz_when_ArrayCache_l133_41[2] && hitVec[9]);
  assign when_ArrayCache_l133_42 = (_zz_when_ArrayCache_l133_42[2] && hitVec[10]);
  assign when_ArrayCache_l133_43 = (_zz_when_ArrayCache_l133_43[2] && hitVec[11]);
  assign when_ArrayCache_l133_44 = (_zz_when_ArrayCache_l133_44[2] && hitVec[12]);
  assign when_ArrayCache_l133_45 = (_zz_when_ArrayCache_l133_45[2] && hitVec[13]);
  assign when_ArrayCache_l133_46 = (_zz_when_ArrayCache_l133_46[2] && hitVec[14]);
  assign when_ArrayCache_l133_47 = (_zz_when_ArrayCache_l133_47[2] && hitVec[15]);
  assign when_ArrayCache_l133_48 = (_zz_when_ArrayCache_l133_48[3] && hitVec[0]);
  assign when_ArrayCache_l133_49 = (_zz_when_ArrayCache_l133_49[3] && hitVec[1]);
  assign when_ArrayCache_l133_50 = (_zz_when_ArrayCache_l133_50[3] && hitVec[2]);
  assign when_ArrayCache_l133_51 = (_zz_when_ArrayCache_l133_51[3] && hitVec[3]);
  assign when_ArrayCache_l133_52 = (_zz_when_ArrayCache_l133_52[3] && hitVec[4]);
  assign when_ArrayCache_l133_53 = (_zz_when_ArrayCache_l133_53[3] && hitVec[5]);
  assign when_ArrayCache_l133_54 = (_zz_when_ArrayCache_l133_54[3] && hitVec[6]);
  assign when_ArrayCache_l133_55 = (_zz_when_ArrayCache_l133_55[3] && hitVec[7]);
  assign when_ArrayCache_l133_56 = (_zz_when_ArrayCache_l133_56[3] && hitVec[8]);
  assign when_ArrayCache_l133_57 = (_zz_when_ArrayCache_l133_57[3] && hitVec[9]);
  assign when_ArrayCache_l133_58 = (_zz_when_ArrayCache_l133_58[3] && hitVec[10]);
  assign when_ArrayCache_l133_59 = (_zz_when_ArrayCache_l133_59[3] && hitVec[11]);
  assign when_ArrayCache_l133_60 = (_zz_when_ArrayCache_l133_60[3] && hitVec[12]);
  assign when_ArrayCache_l133_61 = (_zz_when_ArrayCache_l133_61[3] && hitVec[13]);
  assign when_ArrayCache_l133_62 = (_zz_when_ArrayCache_l133_62[3] && hitVec[14]);
  assign when_ArrayCache_l133_63 = (_zz_when_ArrayCache_l133_63[3] && hitVec[15]);
  assign when_ArrayCache_l168 = (io_chkIal || io_chkIas);
  assign when_ArrayCache_l181 = (|hitVec);
  assign ramRdAddr = {lineEnc,idxLower};
  assign ramDout = dataRam_spinal_port0;
  assign io_dout = ramDoutStore;
  assign updateCache = (((io_wrIal && (! snoopDuringFill)) || (io_wrIas && hitTagReg)) && cacheableReg);
  assign ramDin = (io_wrIal ? io_ialVal : io_iasVal);
  assign ramWrAddr = {lineReg,idxReg};
  assign _zz_4 = ({15'd0,1'b1} <<< lineReg);
  assign _zz_5 = ({15'd0,1'b1} <<< lineReg);
  assign _zz_tagIdx_0 = indexReg[23 : 2];
  assign _zz_6 = ({15'd0,1'b1} <<< lineReg);
  assign when_ArrayCache_l250 = ((io_wrIal && cacheableReg) && incNxtReg);
  assign when_ArrayCache_l268 = ((io_snoopValid && ((tag_0 == io_snoopHandle) && valid_0)) && (tagIdx_0 == io_snoopIndex[23 : 2]));
  assign when_ArrayCache_l272 = (lineReg == 4'b0000);
  assign when_ArrayCache_l268_1 = ((io_snoopValid && ((tag_1 == io_snoopHandle) && valid_1)) && (tagIdx_1 == io_snoopIndex[23 : 2]));
  assign when_ArrayCache_l272_1 = (lineReg == 4'b0001);
  assign when_ArrayCache_l268_2 = ((io_snoopValid && ((tag_2 == io_snoopHandle) && valid_2)) && (tagIdx_2 == io_snoopIndex[23 : 2]));
  assign when_ArrayCache_l272_2 = (lineReg == 4'b0010);
  assign when_ArrayCache_l268_3 = ((io_snoopValid && ((tag_3 == io_snoopHandle) && valid_3)) && (tagIdx_3 == io_snoopIndex[23 : 2]));
  assign when_ArrayCache_l272_3 = (lineReg == 4'b0011);
  assign when_ArrayCache_l268_4 = ((io_snoopValid && ((tag_4 == io_snoopHandle) && valid_4)) && (tagIdx_4 == io_snoopIndex[23 : 2]));
  assign when_ArrayCache_l272_4 = (lineReg == 4'b0100);
  assign when_ArrayCache_l268_5 = ((io_snoopValid && ((tag_5 == io_snoopHandle) && valid_5)) && (tagIdx_5 == io_snoopIndex[23 : 2]));
  assign when_ArrayCache_l272_5 = (lineReg == 4'b0101);
  assign when_ArrayCache_l268_6 = ((io_snoopValid && ((tag_6 == io_snoopHandle) && valid_6)) && (tagIdx_6 == io_snoopIndex[23 : 2]));
  assign when_ArrayCache_l272_6 = (lineReg == 4'b0110);
  assign when_ArrayCache_l268_7 = ((io_snoopValid && ((tag_7 == io_snoopHandle) && valid_7)) && (tagIdx_7 == io_snoopIndex[23 : 2]));
  assign when_ArrayCache_l272_7 = (lineReg == 4'b0111);
  assign when_ArrayCache_l268_8 = ((io_snoopValid && ((tag_8 == io_snoopHandle) && valid_8)) && (tagIdx_8 == io_snoopIndex[23 : 2]));
  assign when_ArrayCache_l272_8 = (lineReg == 4'b1000);
  assign when_ArrayCache_l268_9 = ((io_snoopValid && ((tag_9 == io_snoopHandle) && valid_9)) && (tagIdx_9 == io_snoopIndex[23 : 2]));
  assign when_ArrayCache_l272_9 = (lineReg == 4'b1001);
  assign when_ArrayCache_l268_10 = ((io_snoopValid && ((tag_10 == io_snoopHandle) && valid_10)) && (tagIdx_10 == io_snoopIndex[23 : 2]));
  assign when_ArrayCache_l272_10 = (lineReg == 4'b1010);
  assign when_ArrayCache_l268_11 = ((io_snoopValid && ((tag_11 == io_snoopHandle) && valid_11)) && (tagIdx_11 == io_snoopIndex[23 : 2]));
  assign when_ArrayCache_l272_11 = (lineReg == 4'b1011);
  assign when_ArrayCache_l268_12 = ((io_snoopValid && ((tag_12 == io_snoopHandle) && valid_12)) && (tagIdx_12 == io_snoopIndex[23 : 2]));
  assign when_ArrayCache_l272_12 = (lineReg == 4'b1100);
  assign when_ArrayCache_l268_13 = ((io_snoopValid && ((tag_13 == io_snoopHandle) && valid_13)) && (tagIdx_13 == io_snoopIndex[23 : 2]));
  assign when_ArrayCache_l272_13 = (lineReg == 4'b1101);
  assign when_ArrayCache_l268_14 = ((io_snoopValid && ((tag_14 == io_snoopHandle) && valid_14)) && (tagIdx_14 == io_snoopIndex[23 : 2]));
  assign when_ArrayCache_l272_14 = (lineReg == 4'b1110);
  assign when_ArrayCache_l268_15 = ((io_snoopValid && ((tag_15 == io_snoopHandle) && valid_15)) && (tagIdx_15 == io_snoopIndex[23 : 2]));
  assign when_ArrayCache_l272_15 = (lineReg == 4'b1111);
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
      tagIdx_0 <= 22'h0;
      tagIdx_1 <= 22'h0;
      tagIdx_2 <= 22'h0;
      tagIdx_3 <= 22'h0;
      tagIdx_4 <= 22'h0;
      tagIdx_5 <= 22'h0;
      tagIdx_6 <= 22'h0;
      tagIdx_7 <= 22'h0;
      tagIdx_8 <= 22'h0;
      tagIdx_9 <= 22'h0;
      tagIdx_10 <= 22'h0;
      tagIdx_11 <= 22'h0;
      tagIdx_12 <= 22'h0;
      tagIdx_13 <= 22'h0;
      tagIdx_14 <= 22'h0;
      tagIdx_15 <= 22'h0;
      valid_0 <= 1'b0;
      valid_1 <= 1'b0;
      valid_2 <= 1'b0;
      valid_3 <= 1'b0;
      valid_4 <= 1'b0;
      valid_5 <= 1'b0;
      valid_6 <= 1'b0;
      valid_7 <= 1'b0;
      valid_8 <= 1'b0;
      valid_9 <= 1'b0;
      valid_10 <= 1'b0;
      valid_11 <= 1'b0;
      valid_12 <= 1'b0;
      valid_13 <= 1'b0;
      valid_14 <= 1'b0;
      valid_15 <= 1'b0;
      nxt <= 4'b0000;
      lineReg <= 4'b0000;
      incNxtReg <= 1'b0;
      hitTagReg <= 1'b0;
      cacheableReg <= 1'b1;
      snoopDuringFill <= 1'b0;
      handleReg <= 24'h0;
      indexReg <= 24'h0;
      idxReg <= 2'b00;
      chkIalDly <= 1'b0;
      ramDoutStore <= 32'h0;
    end else begin
      if(when_ArrayCache_l168) begin
        hitTagReg <= (|hitVec);
        handleReg <= io_handle;
        indexReg <= io_index;
        cacheableReg <= 1'b1;
        snoopDuringFill <= 1'b0;
      end
      if(io_chkIal) begin
        if(when_ArrayCache_l181) begin
          lineReg <= lineEnc;
          incNxtReg <= 1'b0;
        end else begin
          lineReg <= nxt;
          incNxtReg <= 1'b1;
        end
        idxReg <= 2'b00;
      end
      if(io_chkIas) begin
        lineReg <= lineEnc;
        incNxtReg <= 1'b0;
        idxReg <= io_index[1 : 0];
      end
      if(io_wrIal) begin
        idxReg <= (idxReg + 2'b01);
      end
      chkIalDly <= io_chkIal;
      if(chkIalDly) begin
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
          tagIdx_0 <= _zz_tagIdx_0;
        end
        if(_zz_5[1]) begin
          tagIdx_1 <= _zz_tagIdx_0;
        end
        if(_zz_5[2]) begin
          tagIdx_2 <= _zz_tagIdx_0;
        end
        if(_zz_5[3]) begin
          tagIdx_3 <= _zz_tagIdx_0;
        end
        if(_zz_5[4]) begin
          tagIdx_4 <= _zz_tagIdx_0;
        end
        if(_zz_5[5]) begin
          tagIdx_5 <= _zz_tagIdx_0;
        end
        if(_zz_5[6]) begin
          tagIdx_6 <= _zz_tagIdx_0;
        end
        if(_zz_5[7]) begin
          tagIdx_7 <= _zz_tagIdx_0;
        end
        if(_zz_5[8]) begin
          tagIdx_8 <= _zz_tagIdx_0;
        end
        if(_zz_5[9]) begin
          tagIdx_9 <= _zz_tagIdx_0;
        end
        if(_zz_5[10]) begin
          tagIdx_10 <= _zz_tagIdx_0;
        end
        if(_zz_5[11]) begin
          tagIdx_11 <= _zz_tagIdx_0;
        end
        if(_zz_5[12]) begin
          tagIdx_12 <= _zz_tagIdx_0;
        end
        if(_zz_5[13]) begin
          tagIdx_13 <= _zz_tagIdx_0;
        end
        if(_zz_5[14]) begin
          tagIdx_14 <= _zz_tagIdx_0;
        end
        if(_zz_5[15]) begin
          tagIdx_15 <= _zz_tagIdx_0;
        end
        if(_zz_6[0]) begin
          valid_0 <= 1'b1;
        end
        if(_zz_6[1]) begin
          valid_1 <= 1'b1;
        end
        if(_zz_6[2]) begin
          valid_2 <= 1'b1;
        end
        if(_zz_6[3]) begin
          valid_3 <= 1'b1;
        end
        if(_zz_6[4]) begin
          valid_4 <= 1'b1;
        end
        if(_zz_6[5]) begin
          valid_5 <= 1'b1;
        end
        if(_zz_6[6]) begin
          valid_6 <= 1'b1;
        end
        if(_zz_6[7]) begin
          valid_7 <= 1'b1;
        end
        if(_zz_6[8]) begin
          valid_8 <= 1'b1;
        end
        if(_zz_6[9]) begin
          valid_9 <= 1'b1;
        end
        if(_zz_6[10]) begin
          valid_10 <= 1'b1;
        end
        if(_zz_6[11]) begin
          valid_11 <= 1'b1;
        end
        if(_zz_6[12]) begin
          valid_12 <= 1'b1;
        end
        if(_zz_6[13]) begin
          valid_13 <= 1'b1;
        end
        if(_zz_6[14]) begin
          valid_14 <= 1'b1;
        end
        if(_zz_6[15]) begin
          valid_15 <= 1'b1;
        end
      end
      if(when_ArrayCache_l250) begin
        nxt <= (nxt + 4'b0001);
        incNxtReg <= 1'b0;
      end
      if(io_inval) begin
        nxt <= 4'b0000;
        valid_0 <= 1'b0;
        valid_1 <= 1'b0;
        valid_2 <= 1'b0;
        valid_3 <= 1'b0;
        valid_4 <= 1'b0;
        valid_5 <= 1'b0;
        valid_6 <= 1'b0;
        valid_7 <= 1'b0;
        valid_8 <= 1'b0;
        valid_9 <= 1'b0;
        valid_10 <= 1'b0;
        valid_11 <= 1'b0;
        valid_12 <= 1'b0;
        valid_13 <= 1'b0;
        valid_14 <= 1'b0;
        valid_15 <= 1'b0;
      end
      if(when_ArrayCache_l268) begin
        valid_0 <= 1'b0;
        if(when_ArrayCache_l272) begin
          snoopDuringFill <= 1'b1;
        end
      end
      if(when_ArrayCache_l268_1) begin
        valid_1 <= 1'b0;
        if(when_ArrayCache_l272_1) begin
          snoopDuringFill <= 1'b1;
        end
      end
      if(when_ArrayCache_l268_2) begin
        valid_2 <= 1'b0;
        if(when_ArrayCache_l272_2) begin
          snoopDuringFill <= 1'b1;
        end
      end
      if(when_ArrayCache_l268_3) begin
        valid_3 <= 1'b0;
        if(when_ArrayCache_l272_3) begin
          snoopDuringFill <= 1'b1;
        end
      end
      if(when_ArrayCache_l268_4) begin
        valid_4 <= 1'b0;
        if(when_ArrayCache_l272_4) begin
          snoopDuringFill <= 1'b1;
        end
      end
      if(when_ArrayCache_l268_5) begin
        valid_5 <= 1'b0;
        if(when_ArrayCache_l272_5) begin
          snoopDuringFill <= 1'b1;
        end
      end
      if(when_ArrayCache_l268_6) begin
        valid_6 <= 1'b0;
        if(when_ArrayCache_l272_6) begin
          snoopDuringFill <= 1'b1;
        end
      end
      if(when_ArrayCache_l268_7) begin
        valid_7 <= 1'b0;
        if(when_ArrayCache_l272_7) begin
          snoopDuringFill <= 1'b1;
        end
      end
      if(when_ArrayCache_l268_8) begin
        valid_8 <= 1'b0;
        if(when_ArrayCache_l272_8) begin
          snoopDuringFill <= 1'b1;
        end
      end
      if(when_ArrayCache_l268_9) begin
        valid_9 <= 1'b0;
        if(when_ArrayCache_l272_9) begin
          snoopDuringFill <= 1'b1;
        end
      end
      if(when_ArrayCache_l268_10) begin
        valid_10 <= 1'b0;
        if(when_ArrayCache_l272_10) begin
          snoopDuringFill <= 1'b1;
        end
      end
      if(when_ArrayCache_l268_11) begin
        valid_11 <= 1'b0;
        if(when_ArrayCache_l272_11) begin
          snoopDuringFill <= 1'b1;
        end
      end
      if(when_ArrayCache_l268_12) begin
        valid_12 <= 1'b0;
        if(when_ArrayCache_l272_12) begin
          snoopDuringFill <= 1'b1;
        end
      end
      if(when_ArrayCache_l268_13) begin
        valid_13 <= 1'b0;
        if(when_ArrayCache_l272_13) begin
          snoopDuringFill <= 1'b1;
        end
      end
      if(when_ArrayCache_l268_14) begin
        valid_14 <= 1'b0;
        if(when_ArrayCache_l272_14) begin
          snoopDuringFill <= 1'b1;
        end
      end
      if(when_ArrayCache_l268_15) begin
        valid_15 <= 1'b0;
        if(when_ArrayCache_l272_15) begin
          snoopDuringFill <= 1'b1;
        end
      end
    end
  end


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
  input  wire          io_snoopValid,
  input  wire [23:0]   io_snoopHandle,
  input  wire [7:0]    io_snoopFieldIdx,
  input  wire          clk,
  input  wire          reset
);

  reg        [31:0]   dataRam_spinal_port0;
  wire       [3:0]    _zz_when_ObjectCache_l115;
  wire       [3:0]    _zz_when_ObjectCache_l115_1;
  wire       [3:0]    _zz_when_ObjectCache_l115_2;
  wire       [3:0]    _zz_when_ObjectCache_l115_3;
  wire       [3:0]    _zz_when_ObjectCache_l115_4;
  wire       [3:0]    _zz_when_ObjectCache_l115_5;
  wire       [3:0]    _zz_when_ObjectCache_l115_6;
  wire       [3:0]    _zz_when_ObjectCache_l115_7;
  wire       [3:0]    _zz_when_ObjectCache_l115_8;
  wire       [3:0]    _zz_when_ObjectCache_l115_9;
  wire       [3:0]    _zz_when_ObjectCache_l115_10;
  wire       [3:0]    _zz_when_ObjectCache_l115_11;
  wire       [3:0]    _zz_when_ObjectCache_l115_12;
  wire       [3:0]    _zz_when_ObjectCache_l115_13;
  wire       [3:0]    _zz_when_ObjectCache_l115_14;
  wire       [3:0]    _zz_when_ObjectCache_l115_15;
  wire       [3:0]    _zz_when_ObjectCache_l115_16;
  wire       [3:0]    _zz_when_ObjectCache_l115_17;
  wire       [3:0]    _zz_when_ObjectCache_l115_18;
  wire       [3:0]    _zz_when_ObjectCache_l115_19;
  wire       [3:0]    _zz_when_ObjectCache_l115_20;
  wire       [3:0]    _zz_when_ObjectCache_l115_21;
  wire       [3:0]    _zz_when_ObjectCache_l115_22;
  wire       [3:0]    _zz_when_ObjectCache_l115_23;
  wire       [3:0]    _zz_when_ObjectCache_l115_24;
  wire       [3:0]    _zz_when_ObjectCache_l115_25;
  wire       [3:0]    _zz_when_ObjectCache_l115_26;
  wire       [3:0]    _zz_when_ObjectCache_l115_27;
  wire       [3:0]    _zz_when_ObjectCache_l115_28;
  wire       [3:0]    _zz_when_ObjectCache_l115_29;
  wire       [3:0]    _zz_when_ObjectCache_l115_30;
  wire       [3:0]    _zz_when_ObjectCache_l115_31;
  wire       [3:0]    _zz_when_ObjectCache_l115_32;
  wire       [3:0]    _zz_when_ObjectCache_l115_33;
  wire       [3:0]    _zz_when_ObjectCache_l115_34;
  wire       [3:0]    _zz_when_ObjectCache_l115_35;
  wire       [3:0]    _zz_when_ObjectCache_l115_36;
  wire       [3:0]    _zz_when_ObjectCache_l115_37;
  wire       [3:0]    _zz_when_ObjectCache_l115_38;
  wire       [3:0]    _zz_when_ObjectCache_l115_39;
  wire       [3:0]    _zz_when_ObjectCache_l115_40;
  wire       [3:0]    _zz_when_ObjectCache_l115_41;
  wire       [3:0]    _zz_when_ObjectCache_l115_42;
  wire       [3:0]    _zz_when_ObjectCache_l115_43;
  wire       [3:0]    _zz_when_ObjectCache_l115_44;
  wire       [3:0]    _zz_when_ObjectCache_l115_45;
  wire       [3:0]    _zz_when_ObjectCache_l115_46;
  wire       [3:0]    _zz_when_ObjectCache_l115_47;
  wire       [3:0]    _zz_when_ObjectCache_l115_48;
  wire       [3:0]    _zz_when_ObjectCache_l115_49;
  wire       [3:0]    _zz_when_ObjectCache_l115_50;
  wire       [3:0]    _zz_when_ObjectCache_l115_51;
  wire       [3:0]    _zz_when_ObjectCache_l115_52;
  wire       [3:0]    _zz_when_ObjectCache_l115_53;
  wire       [3:0]    _zz_when_ObjectCache_l115_54;
  wire       [3:0]    _zz_when_ObjectCache_l115_55;
  wire       [3:0]    _zz_when_ObjectCache_l115_56;
  wire       [3:0]    _zz_when_ObjectCache_l115_57;
  wire       [3:0]    _zz_when_ObjectCache_l115_58;
  wire       [3:0]    _zz_when_ObjectCache_l115_59;
  wire       [3:0]    _zz_when_ObjectCache_l115_60;
  wire       [3:0]    _zz_when_ObjectCache_l115_61;
  wire       [3:0]    _zz_when_ObjectCache_l115_62;
  wire       [3:0]    _zz_when_ObjectCache_l115_63;
  wire                _zz_dataRam_port;
  wire                _zz_ramDout;
  reg        [15:0]   _zz__zz_valid_0;
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
  reg        [15:0]   valid_0;
  reg        [15:0]   valid_1;
  reg        [15:0]   valid_2;
  reg        [15:0]   valid_3;
  reg        [15:0]   valid_4;
  reg        [15:0]   valid_5;
  reg        [15:0]   valid_6;
  reg        [15:0]   valid_7;
  reg        [15:0]   valid_8;
  reg        [15:0]   valid_9;
  reg        [15:0]   valid_10;
  reg        [15:0]   valid_11;
  reg        [15:0]   valid_12;
  reg        [15:0]   valid_13;
  reg        [15:0]   valid_14;
  reg        [15:0]   valid_15;
  reg        [3:0]    nxt;
  wire       [3:0]    idx;
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
  wire                when_ObjectCache_l115;
  wire                when_ObjectCache_l115_1;
  wire                when_ObjectCache_l115_2;
  wire                when_ObjectCache_l115_3;
  wire                when_ObjectCache_l115_4;
  wire                when_ObjectCache_l115_5;
  wire                when_ObjectCache_l115_6;
  wire                when_ObjectCache_l115_7;
  wire                when_ObjectCache_l115_8;
  wire                when_ObjectCache_l115_9;
  wire                when_ObjectCache_l115_10;
  wire                when_ObjectCache_l115_11;
  wire                when_ObjectCache_l115_12;
  wire                when_ObjectCache_l115_13;
  wire                when_ObjectCache_l115_14;
  wire                when_ObjectCache_l115_15;
  wire                when_ObjectCache_l115_16;
  wire                when_ObjectCache_l115_17;
  wire                when_ObjectCache_l115_18;
  wire                when_ObjectCache_l115_19;
  wire                when_ObjectCache_l115_20;
  wire                when_ObjectCache_l115_21;
  wire                when_ObjectCache_l115_22;
  wire                when_ObjectCache_l115_23;
  wire                when_ObjectCache_l115_24;
  wire                when_ObjectCache_l115_25;
  wire                when_ObjectCache_l115_26;
  wire                when_ObjectCache_l115_27;
  wire                when_ObjectCache_l115_28;
  wire                when_ObjectCache_l115_29;
  wire                when_ObjectCache_l115_30;
  wire                when_ObjectCache_l115_31;
  wire                when_ObjectCache_l115_32;
  wire                when_ObjectCache_l115_33;
  wire                when_ObjectCache_l115_34;
  wire                when_ObjectCache_l115_35;
  wire                when_ObjectCache_l115_36;
  wire                when_ObjectCache_l115_37;
  wire                when_ObjectCache_l115_38;
  wire                when_ObjectCache_l115_39;
  wire                when_ObjectCache_l115_40;
  wire                when_ObjectCache_l115_41;
  wire                when_ObjectCache_l115_42;
  wire                when_ObjectCache_l115_43;
  wire                when_ObjectCache_l115_44;
  wire                when_ObjectCache_l115_45;
  wire                when_ObjectCache_l115_46;
  wire                when_ObjectCache_l115_47;
  wire                when_ObjectCache_l115_48;
  wire                when_ObjectCache_l115_49;
  wire                when_ObjectCache_l115_50;
  wire                when_ObjectCache_l115_51;
  wire                when_ObjectCache_l115_52;
  wire                when_ObjectCache_l115_53;
  wire                when_ObjectCache_l115_54;
  wire                when_ObjectCache_l115_55;
  wire                when_ObjectCache_l115_56;
  wire                when_ObjectCache_l115_57;
  wire                when_ObjectCache_l115_58;
  wire                when_ObjectCache_l115_59;
  wire                when_ObjectCache_l115_60;
  wire                when_ObjectCache_l115_61;
  wire                when_ObjectCache_l115_62;
  wire                when_ObjectCache_l115_63;
  reg        [3:0]    lineReg;
  reg                 incNxtReg;
  reg                 hitTagReg;
  reg                 cacheableReg;
  reg        [23:0]   handleReg;
  reg        [7:0]    indexReg;
  wire                when_ObjectCache_l136;
  wire                when_ObjectCache_l145;
  wire       [7:0]    ramRdAddr;
  wire       [31:0]   ramDout;
  reg                 chkGfDly;
  reg        [31:0]   ramDoutStore;
  wire                updateCache;
  wire       [31:0]   ramDin;
  wire       [7:0]    ramWrAddr;
  wire       [15:0]   _zz_4;
  reg        [15:0]   _zz_valid_0;
  wire       [15:0]   _zz_5;
  wire                when_ObjectCache_l209;
  wire       [3:0]    snoopIdx;
  wire                when_ObjectCache_l225;
  reg        [15:0]   _zz_valid_0_1;
  wire                when_ObjectCache_l225_1;
  reg        [15:0]   _zz_valid_1;
  wire                when_ObjectCache_l225_2;
  reg        [15:0]   _zz_valid_2;
  wire                when_ObjectCache_l225_3;
  reg        [15:0]   _zz_valid_3;
  wire                when_ObjectCache_l225_4;
  reg        [15:0]   _zz_valid_4;
  wire                when_ObjectCache_l225_5;
  reg        [15:0]   _zz_valid_5;
  wire                when_ObjectCache_l225_6;
  reg        [15:0]   _zz_valid_6;
  wire                when_ObjectCache_l225_7;
  reg        [15:0]   _zz_valid_7;
  wire                when_ObjectCache_l225_8;
  reg        [15:0]   _zz_valid_8;
  wire                when_ObjectCache_l225_9;
  reg        [15:0]   _zz_valid_9;
  wire                when_ObjectCache_l225_10;
  reg        [15:0]   _zz_valid_10;
  wire                when_ObjectCache_l225_11;
  reg        [15:0]   _zz_valid_11;
  wire                when_ObjectCache_l225_12;
  reg        [15:0]   _zz_valid_12;
  wire                when_ObjectCache_l225_13;
  reg        [15:0]   _zz_valid_13;
  wire                when_ObjectCache_l225_14;
  reg        [15:0]   _zz_valid_14;
  wire                when_ObjectCache_l225_15;
  reg        [15:0]   _zz_valid_15;
  reg [31:0] dataRam [0:255];

  assign _zz_when_ObjectCache_l115 = 4'b0000;
  assign _zz_when_ObjectCache_l115_1 = 4'b0001;
  assign _zz_when_ObjectCache_l115_2 = 4'b0010;
  assign _zz_when_ObjectCache_l115_3 = 4'b0011;
  assign _zz_when_ObjectCache_l115_4 = 4'b0100;
  assign _zz_when_ObjectCache_l115_5 = 4'b0101;
  assign _zz_when_ObjectCache_l115_6 = 4'b0110;
  assign _zz_when_ObjectCache_l115_7 = 4'b0111;
  assign _zz_when_ObjectCache_l115_8 = 4'b1000;
  assign _zz_when_ObjectCache_l115_9 = 4'b1001;
  assign _zz_when_ObjectCache_l115_10 = 4'b1010;
  assign _zz_when_ObjectCache_l115_11 = 4'b1011;
  assign _zz_when_ObjectCache_l115_12 = 4'b1100;
  assign _zz_when_ObjectCache_l115_13 = 4'b1101;
  assign _zz_when_ObjectCache_l115_14 = 4'b1110;
  assign _zz_when_ObjectCache_l115_15 = 4'b1111;
  assign _zz_when_ObjectCache_l115_16 = 4'b0000;
  assign _zz_when_ObjectCache_l115_17 = 4'b0001;
  assign _zz_when_ObjectCache_l115_18 = 4'b0010;
  assign _zz_when_ObjectCache_l115_19 = 4'b0011;
  assign _zz_when_ObjectCache_l115_20 = 4'b0100;
  assign _zz_when_ObjectCache_l115_21 = 4'b0101;
  assign _zz_when_ObjectCache_l115_22 = 4'b0110;
  assign _zz_when_ObjectCache_l115_23 = 4'b0111;
  assign _zz_when_ObjectCache_l115_24 = 4'b1000;
  assign _zz_when_ObjectCache_l115_25 = 4'b1001;
  assign _zz_when_ObjectCache_l115_26 = 4'b1010;
  assign _zz_when_ObjectCache_l115_27 = 4'b1011;
  assign _zz_when_ObjectCache_l115_28 = 4'b1100;
  assign _zz_when_ObjectCache_l115_29 = 4'b1101;
  assign _zz_when_ObjectCache_l115_30 = 4'b1110;
  assign _zz_when_ObjectCache_l115_31 = 4'b1111;
  assign _zz_when_ObjectCache_l115_32 = 4'b0000;
  assign _zz_when_ObjectCache_l115_33 = 4'b0001;
  assign _zz_when_ObjectCache_l115_34 = 4'b0010;
  assign _zz_when_ObjectCache_l115_35 = 4'b0011;
  assign _zz_when_ObjectCache_l115_36 = 4'b0100;
  assign _zz_when_ObjectCache_l115_37 = 4'b0101;
  assign _zz_when_ObjectCache_l115_38 = 4'b0110;
  assign _zz_when_ObjectCache_l115_39 = 4'b0111;
  assign _zz_when_ObjectCache_l115_40 = 4'b1000;
  assign _zz_when_ObjectCache_l115_41 = 4'b1001;
  assign _zz_when_ObjectCache_l115_42 = 4'b1010;
  assign _zz_when_ObjectCache_l115_43 = 4'b1011;
  assign _zz_when_ObjectCache_l115_44 = 4'b1100;
  assign _zz_when_ObjectCache_l115_45 = 4'b1101;
  assign _zz_when_ObjectCache_l115_46 = 4'b1110;
  assign _zz_when_ObjectCache_l115_47 = 4'b1111;
  assign _zz_when_ObjectCache_l115_48 = 4'b0000;
  assign _zz_when_ObjectCache_l115_49 = 4'b0001;
  assign _zz_when_ObjectCache_l115_50 = 4'b0010;
  assign _zz_when_ObjectCache_l115_51 = 4'b0011;
  assign _zz_when_ObjectCache_l115_52 = 4'b0100;
  assign _zz_when_ObjectCache_l115_53 = 4'b0101;
  assign _zz_when_ObjectCache_l115_54 = 4'b0110;
  assign _zz_when_ObjectCache_l115_55 = 4'b0111;
  assign _zz_when_ObjectCache_l115_56 = 4'b1000;
  assign _zz_when_ObjectCache_l115_57 = 4'b1001;
  assign _zz_when_ObjectCache_l115_58 = 4'b1010;
  assign _zz_when_ObjectCache_l115_59 = 4'b1011;
  assign _zz_when_ObjectCache_l115_60 = 4'b1100;
  assign _zz_when_ObjectCache_l115_61 = 4'b1101;
  assign _zz_when_ObjectCache_l115_62 = 4'b1110;
  assign _zz_when_ObjectCache_l115_63 = 4'b1111;
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
    if(when_ObjectCache_l115_63) begin
      _zz_lineEnc = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_1 = _zz_lineEnc_2;
    if(when_ObjectCache_l115_62) begin
      _zz_lineEnc_1 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_2 = _zz_lineEnc_3;
    if(when_ObjectCache_l115_61) begin
      _zz_lineEnc_2 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_3 = _zz_lineEnc_4;
    if(when_ObjectCache_l115_60) begin
      _zz_lineEnc_3 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_4 = _zz_lineEnc_5;
    if(when_ObjectCache_l115_59) begin
      _zz_lineEnc_4 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_5 = _zz_lineEnc_6;
    if(when_ObjectCache_l115_58) begin
      _zz_lineEnc_5 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_6 = _zz_lineEnc_7;
    if(when_ObjectCache_l115_57) begin
      _zz_lineEnc_6 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_7 = _zz_lineEnc_8;
    if(when_ObjectCache_l115_56) begin
      _zz_lineEnc_7 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_8 = _zz_lineEnc_9;
    if(when_ObjectCache_l115_55) begin
      _zz_lineEnc_8 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_9 = _zz_lineEnc_10;
    if(when_ObjectCache_l115_54) begin
      _zz_lineEnc_9 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_10 = _zz_lineEnc_11;
    if(when_ObjectCache_l115_53) begin
      _zz_lineEnc_10 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_11 = _zz_lineEnc_12;
    if(when_ObjectCache_l115_52) begin
      _zz_lineEnc_11 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_12 = _zz_lineEnc_13;
    if(when_ObjectCache_l115_51) begin
      _zz_lineEnc_12 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_13 = _zz_lineEnc_14;
    if(when_ObjectCache_l115_50) begin
      _zz_lineEnc_13 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_14 = _zz_lineEnc_15;
    if(when_ObjectCache_l115_49) begin
      _zz_lineEnc_14 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_15 = 1'b0;
    if(when_ObjectCache_l115_48) begin
      _zz_lineEnc_15 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_16 = _zz_lineEnc_17;
    if(when_ObjectCache_l115_47) begin
      _zz_lineEnc_16 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_17 = _zz_lineEnc_18;
    if(when_ObjectCache_l115_46) begin
      _zz_lineEnc_17 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_18 = _zz_lineEnc_19;
    if(when_ObjectCache_l115_45) begin
      _zz_lineEnc_18 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_19 = _zz_lineEnc_20;
    if(when_ObjectCache_l115_44) begin
      _zz_lineEnc_19 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_20 = _zz_lineEnc_21;
    if(when_ObjectCache_l115_43) begin
      _zz_lineEnc_20 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_21 = _zz_lineEnc_22;
    if(when_ObjectCache_l115_42) begin
      _zz_lineEnc_21 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_22 = _zz_lineEnc_23;
    if(when_ObjectCache_l115_41) begin
      _zz_lineEnc_22 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_23 = _zz_lineEnc_24;
    if(when_ObjectCache_l115_40) begin
      _zz_lineEnc_23 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_24 = _zz_lineEnc_25;
    if(when_ObjectCache_l115_39) begin
      _zz_lineEnc_24 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_25 = _zz_lineEnc_26;
    if(when_ObjectCache_l115_38) begin
      _zz_lineEnc_25 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_26 = _zz_lineEnc_27;
    if(when_ObjectCache_l115_37) begin
      _zz_lineEnc_26 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_27 = _zz_lineEnc_28;
    if(when_ObjectCache_l115_36) begin
      _zz_lineEnc_27 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_28 = _zz_lineEnc_29;
    if(when_ObjectCache_l115_35) begin
      _zz_lineEnc_28 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_29 = _zz_lineEnc_30;
    if(when_ObjectCache_l115_34) begin
      _zz_lineEnc_29 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_30 = _zz_lineEnc_31;
    if(when_ObjectCache_l115_33) begin
      _zz_lineEnc_30 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_31 = 1'b0;
    if(when_ObjectCache_l115_32) begin
      _zz_lineEnc_31 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_32 = _zz_lineEnc_33;
    if(when_ObjectCache_l115_31) begin
      _zz_lineEnc_32 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_33 = _zz_lineEnc_34;
    if(when_ObjectCache_l115_30) begin
      _zz_lineEnc_33 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_34 = _zz_lineEnc_35;
    if(when_ObjectCache_l115_29) begin
      _zz_lineEnc_34 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_35 = _zz_lineEnc_36;
    if(when_ObjectCache_l115_28) begin
      _zz_lineEnc_35 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_36 = _zz_lineEnc_37;
    if(when_ObjectCache_l115_27) begin
      _zz_lineEnc_36 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_37 = _zz_lineEnc_38;
    if(when_ObjectCache_l115_26) begin
      _zz_lineEnc_37 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_38 = _zz_lineEnc_39;
    if(when_ObjectCache_l115_25) begin
      _zz_lineEnc_38 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_39 = _zz_lineEnc_40;
    if(when_ObjectCache_l115_24) begin
      _zz_lineEnc_39 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_40 = _zz_lineEnc_41;
    if(when_ObjectCache_l115_23) begin
      _zz_lineEnc_40 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_41 = _zz_lineEnc_42;
    if(when_ObjectCache_l115_22) begin
      _zz_lineEnc_41 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_42 = _zz_lineEnc_43;
    if(when_ObjectCache_l115_21) begin
      _zz_lineEnc_42 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_43 = _zz_lineEnc_44;
    if(when_ObjectCache_l115_20) begin
      _zz_lineEnc_43 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_44 = _zz_lineEnc_45;
    if(when_ObjectCache_l115_19) begin
      _zz_lineEnc_44 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_45 = _zz_lineEnc_46;
    if(when_ObjectCache_l115_18) begin
      _zz_lineEnc_45 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_46 = _zz_lineEnc_47;
    if(when_ObjectCache_l115_17) begin
      _zz_lineEnc_46 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_47 = 1'b0;
    if(when_ObjectCache_l115_16) begin
      _zz_lineEnc_47 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_48 = _zz_lineEnc_49;
    if(when_ObjectCache_l115_15) begin
      _zz_lineEnc_48 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_49 = _zz_lineEnc_50;
    if(when_ObjectCache_l115_14) begin
      _zz_lineEnc_49 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_50 = _zz_lineEnc_51;
    if(when_ObjectCache_l115_13) begin
      _zz_lineEnc_50 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_51 = _zz_lineEnc_52;
    if(when_ObjectCache_l115_12) begin
      _zz_lineEnc_51 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_52 = _zz_lineEnc_53;
    if(when_ObjectCache_l115_11) begin
      _zz_lineEnc_52 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_53 = _zz_lineEnc_54;
    if(when_ObjectCache_l115_10) begin
      _zz_lineEnc_53 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_54 = _zz_lineEnc_55;
    if(when_ObjectCache_l115_9) begin
      _zz_lineEnc_54 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_55 = _zz_lineEnc_56;
    if(when_ObjectCache_l115_8) begin
      _zz_lineEnc_55 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_56 = _zz_lineEnc_57;
    if(when_ObjectCache_l115_7) begin
      _zz_lineEnc_56 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_57 = _zz_lineEnc_58;
    if(when_ObjectCache_l115_6) begin
      _zz_lineEnc_57 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_58 = _zz_lineEnc_59;
    if(when_ObjectCache_l115_5) begin
      _zz_lineEnc_58 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_59 = _zz_lineEnc_60;
    if(when_ObjectCache_l115_4) begin
      _zz_lineEnc_59 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_60 = _zz_lineEnc_61;
    if(when_ObjectCache_l115_3) begin
      _zz_lineEnc_60 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_61 = _zz_lineEnc_62;
    if(when_ObjectCache_l115_2) begin
      _zz_lineEnc_61 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_62 = _zz_lineEnc_63;
    if(when_ObjectCache_l115_1) begin
      _zz_lineEnc_62 = 1'b1;
    end
  end

  always @(*) begin
    _zz_lineEnc_63 = 1'b0;
    if(when_ObjectCache_l115) begin
      _zz_lineEnc_63 = 1'b1;
    end
  end

  assign idx = io_fieldIdx[3 : 0];
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
  assign cacheable = (io_fieldIdx[7 : 4] == 4'b0000);
  assign io_hit = ((|hitVec) && cacheable);
  assign when_ObjectCache_l115 = (_zz_when_ObjectCache_l115[0] && hitTagVec[0]);
  assign when_ObjectCache_l115_1 = (_zz_when_ObjectCache_l115_1[0] && hitTagVec[1]);
  assign when_ObjectCache_l115_2 = (_zz_when_ObjectCache_l115_2[0] && hitTagVec[2]);
  assign when_ObjectCache_l115_3 = (_zz_when_ObjectCache_l115_3[0] && hitTagVec[3]);
  assign when_ObjectCache_l115_4 = (_zz_when_ObjectCache_l115_4[0] && hitTagVec[4]);
  assign when_ObjectCache_l115_5 = (_zz_when_ObjectCache_l115_5[0] && hitTagVec[5]);
  assign when_ObjectCache_l115_6 = (_zz_when_ObjectCache_l115_6[0] && hitTagVec[6]);
  assign when_ObjectCache_l115_7 = (_zz_when_ObjectCache_l115_7[0] && hitTagVec[7]);
  assign when_ObjectCache_l115_8 = (_zz_when_ObjectCache_l115_8[0] && hitTagVec[8]);
  assign when_ObjectCache_l115_9 = (_zz_when_ObjectCache_l115_9[0] && hitTagVec[9]);
  assign when_ObjectCache_l115_10 = (_zz_when_ObjectCache_l115_10[0] && hitTagVec[10]);
  assign when_ObjectCache_l115_11 = (_zz_when_ObjectCache_l115_11[0] && hitTagVec[11]);
  assign when_ObjectCache_l115_12 = (_zz_when_ObjectCache_l115_12[0] && hitTagVec[12]);
  assign when_ObjectCache_l115_13 = (_zz_when_ObjectCache_l115_13[0] && hitTagVec[13]);
  assign when_ObjectCache_l115_14 = (_zz_when_ObjectCache_l115_14[0] && hitTagVec[14]);
  assign when_ObjectCache_l115_15 = (_zz_when_ObjectCache_l115_15[0] && hitTagVec[15]);
  always @(*) begin
    lineEnc[0] = _zz_lineEnc_48;
    lineEnc[1] = _zz_lineEnc_32;
    lineEnc[2] = _zz_lineEnc_16;
    lineEnc[3] = _zz_lineEnc;
  end

  assign when_ObjectCache_l115_16 = (_zz_when_ObjectCache_l115_16[1] && hitTagVec[0]);
  assign when_ObjectCache_l115_17 = (_zz_when_ObjectCache_l115_17[1] && hitTagVec[1]);
  assign when_ObjectCache_l115_18 = (_zz_when_ObjectCache_l115_18[1] && hitTagVec[2]);
  assign when_ObjectCache_l115_19 = (_zz_when_ObjectCache_l115_19[1] && hitTagVec[3]);
  assign when_ObjectCache_l115_20 = (_zz_when_ObjectCache_l115_20[1] && hitTagVec[4]);
  assign when_ObjectCache_l115_21 = (_zz_when_ObjectCache_l115_21[1] && hitTagVec[5]);
  assign when_ObjectCache_l115_22 = (_zz_when_ObjectCache_l115_22[1] && hitTagVec[6]);
  assign when_ObjectCache_l115_23 = (_zz_when_ObjectCache_l115_23[1] && hitTagVec[7]);
  assign when_ObjectCache_l115_24 = (_zz_when_ObjectCache_l115_24[1] && hitTagVec[8]);
  assign when_ObjectCache_l115_25 = (_zz_when_ObjectCache_l115_25[1] && hitTagVec[9]);
  assign when_ObjectCache_l115_26 = (_zz_when_ObjectCache_l115_26[1] && hitTagVec[10]);
  assign when_ObjectCache_l115_27 = (_zz_when_ObjectCache_l115_27[1] && hitTagVec[11]);
  assign when_ObjectCache_l115_28 = (_zz_when_ObjectCache_l115_28[1] && hitTagVec[12]);
  assign when_ObjectCache_l115_29 = (_zz_when_ObjectCache_l115_29[1] && hitTagVec[13]);
  assign when_ObjectCache_l115_30 = (_zz_when_ObjectCache_l115_30[1] && hitTagVec[14]);
  assign when_ObjectCache_l115_31 = (_zz_when_ObjectCache_l115_31[1] && hitTagVec[15]);
  assign when_ObjectCache_l115_32 = (_zz_when_ObjectCache_l115_32[2] && hitTagVec[0]);
  assign when_ObjectCache_l115_33 = (_zz_when_ObjectCache_l115_33[2] && hitTagVec[1]);
  assign when_ObjectCache_l115_34 = (_zz_when_ObjectCache_l115_34[2] && hitTagVec[2]);
  assign when_ObjectCache_l115_35 = (_zz_when_ObjectCache_l115_35[2] && hitTagVec[3]);
  assign when_ObjectCache_l115_36 = (_zz_when_ObjectCache_l115_36[2] && hitTagVec[4]);
  assign when_ObjectCache_l115_37 = (_zz_when_ObjectCache_l115_37[2] && hitTagVec[5]);
  assign when_ObjectCache_l115_38 = (_zz_when_ObjectCache_l115_38[2] && hitTagVec[6]);
  assign when_ObjectCache_l115_39 = (_zz_when_ObjectCache_l115_39[2] && hitTagVec[7]);
  assign when_ObjectCache_l115_40 = (_zz_when_ObjectCache_l115_40[2] && hitTagVec[8]);
  assign when_ObjectCache_l115_41 = (_zz_when_ObjectCache_l115_41[2] && hitTagVec[9]);
  assign when_ObjectCache_l115_42 = (_zz_when_ObjectCache_l115_42[2] && hitTagVec[10]);
  assign when_ObjectCache_l115_43 = (_zz_when_ObjectCache_l115_43[2] && hitTagVec[11]);
  assign when_ObjectCache_l115_44 = (_zz_when_ObjectCache_l115_44[2] && hitTagVec[12]);
  assign when_ObjectCache_l115_45 = (_zz_when_ObjectCache_l115_45[2] && hitTagVec[13]);
  assign when_ObjectCache_l115_46 = (_zz_when_ObjectCache_l115_46[2] && hitTagVec[14]);
  assign when_ObjectCache_l115_47 = (_zz_when_ObjectCache_l115_47[2] && hitTagVec[15]);
  assign when_ObjectCache_l115_48 = (_zz_when_ObjectCache_l115_48[3] && hitTagVec[0]);
  assign when_ObjectCache_l115_49 = (_zz_when_ObjectCache_l115_49[3] && hitTagVec[1]);
  assign when_ObjectCache_l115_50 = (_zz_when_ObjectCache_l115_50[3] && hitTagVec[2]);
  assign when_ObjectCache_l115_51 = (_zz_when_ObjectCache_l115_51[3] && hitTagVec[3]);
  assign when_ObjectCache_l115_52 = (_zz_when_ObjectCache_l115_52[3] && hitTagVec[4]);
  assign when_ObjectCache_l115_53 = (_zz_when_ObjectCache_l115_53[3] && hitTagVec[5]);
  assign when_ObjectCache_l115_54 = (_zz_when_ObjectCache_l115_54[3] && hitTagVec[6]);
  assign when_ObjectCache_l115_55 = (_zz_when_ObjectCache_l115_55[3] && hitTagVec[7]);
  assign when_ObjectCache_l115_56 = (_zz_when_ObjectCache_l115_56[3] && hitTagVec[8]);
  assign when_ObjectCache_l115_57 = (_zz_when_ObjectCache_l115_57[3] && hitTagVec[9]);
  assign when_ObjectCache_l115_58 = (_zz_when_ObjectCache_l115_58[3] && hitTagVec[10]);
  assign when_ObjectCache_l115_59 = (_zz_when_ObjectCache_l115_59[3] && hitTagVec[11]);
  assign when_ObjectCache_l115_60 = (_zz_when_ObjectCache_l115_60[3] && hitTagVec[12]);
  assign when_ObjectCache_l115_61 = (_zz_when_ObjectCache_l115_61[3] && hitTagVec[13]);
  assign when_ObjectCache_l115_62 = (_zz_when_ObjectCache_l115_62[3] && hitTagVec[14]);
  assign when_ObjectCache_l115_63 = (_zz_when_ObjectCache_l115_63[3] && hitTagVec[15]);
  assign when_ObjectCache_l136 = (io_chkGf || io_chkPf);
  assign when_ObjectCache_l145 = (|hitTagVec);
  assign ramRdAddr = {((|hitTagVec) ? lineEnc : nxt),idx};
  assign ramDout = dataRam_spinal_port0;
  assign io_dout = ramDoutStore;
  assign updateCache = ((io_wrGf || (io_wrPf && hitTagReg)) && cacheableReg);
  assign ramDin = (io_wrGf ? io_gfVal : io_pfVal);
  assign ramWrAddr = {lineReg,indexReg[3 : 0]};
  assign _zz_4 = ({15'd0,1'b1} <<< lineReg);
  assign _zz_5 = ({15'd0,1'b1} <<< lineReg);
  always @(*) begin
    _zz_valid_0 = _zz__zz_valid_0;
    if(incNxtReg) begin
      _zz_valid_0 = 16'h0;
    end
    _zz_valid_0[indexReg[3 : 0]] = 1'b1;
  end

  assign when_ObjectCache_l209 = ((io_wrGf && cacheableReg) && incNxtReg);
  assign snoopIdx = io_snoopFieldIdx[3 : 0];
  assign when_ObjectCache_l225 = (io_snoopValid && (tag_0 == io_snoopHandle));
  always @(*) begin
    _zz_valid_0_1 = valid_0;
    _zz_valid_0_1[snoopIdx] = 1'b0;
  end

  assign when_ObjectCache_l225_1 = (io_snoopValid && (tag_1 == io_snoopHandle));
  always @(*) begin
    _zz_valid_1 = valid_1;
    _zz_valid_1[snoopIdx] = 1'b0;
  end

  assign when_ObjectCache_l225_2 = (io_snoopValid && (tag_2 == io_snoopHandle));
  always @(*) begin
    _zz_valid_2 = valid_2;
    _zz_valid_2[snoopIdx] = 1'b0;
  end

  assign when_ObjectCache_l225_3 = (io_snoopValid && (tag_3 == io_snoopHandle));
  always @(*) begin
    _zz_valid_3 = valid_3;
    _zz_valid_3[snoopIdx] = 1'b0;
  end

  assign when_ObjectCache_l225_4 = (io_snoopValid && (tag_4 == io_snoopHandle));
  always @(*) begin
    _zz_valid_4 = valid_4;
    _zz_valid_4[snoopIdx] = 1'b0;
  end

  assign when_ObjectCache_l225_5 = (io_snoopValid && (tag_5 == io_snoopHandle));
  always @(*) begin
    _zz_valid_5 = valid_5;
    _zz_valid_5[snoopIdx] = 1'b0;
  end

  assign when_ObjectCache_l225_6 = (io_snoopValid && (tag_6 == io_snoopHandle));
  always @(*) begin
    _zz_valid_6 = valid_6;
    _zz_valid_6[snoopIdx] = 1'b0;
  end

  assign when_ObjectCache_l225_7 = (io_snoopValid && (tag_7 == io_snoopHandle));
  always @(*) begin
    _zz_valid_7 = valid_7;
    _zz_valid_7[snoopIdx] = 1'b0;
  end

  assign when_ObjectCache_l225_8 = (io_snoopValid && (tag_8 == io_snoopHandle));
  always @(*) begin
    _zz_valid_8 = valid_8;
    _zz_valid_8[snoopIdx] = 1'b0;
  end

  assign when_ObjectCache_l225_9 = (io_snoopValid && (tag_9 == io_snoopHandle));
  always @(*) begin
    _zz_valid_9 = valid_9;
    _zz_valid_9[snoopIdx] = 1'b0;
  end

  assign when_ObjectCache_l225_10 = (io_snoopValid && (tag_10 == io_snoopHandle));
  always @(*) begin
    _zz_valid_10 = valid_10;
    _zz_valid_10[snoopIdx] = 1'b0;
  end

  assign when_ObjectCache_l225_11 = (io_snoopValid && (tag_11 == io_snoopHandle));
  always @(*) begin
    _zz_valid_11 = valid_11;
    _zz_valid_11[snoopIdx] = 1'b0;
  end

  assign when_ObjectCache_l225_12 = (io_snoopValid && (tag_12 == io_snoopHandle));
  always @(*) begin
    _zz_valid_12 = valid_12;
    _zz_valid_12[snoopIdx] = 1'b0;
  end

  assign when_ObjectCache_l225_13 = (io_snoopValid && (tag_13 == io_snoopHandle));
  always @(*) begin
    _zz_valid_13 = valid_13;
    _zz_valid_13[snoopIdx] = 1'b0;
  end

  assign when_ObjectCache_l225_14 = (io_snoopValid && (tag_14 == io_snoopHandle));
  always @(*) begin
    _zz_valid_14 = valid_14;
    _zz_valid_14[snoopIdx] = 1'b0;
  end

  assign when_ObjectCache_l225_15 = (io_snoopValid && (tag_15 == io_snoopHandle));
  always @(*) begin
    _zz_valid_15 = valid_15;
    _zz_valid_15[snoopIdx] = 1'b0;
  end

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
      valid_0 <= 16'h0;
      valid_1 <= 16'h0;
      valid_2 <= 16'h0;
      valid_3 <= 16'h0;
      valid_4 <= 16'h0;
      valid_5 <= 16'h0;
      valid_6 <= 16'h0;
      valid_7 <= 16'h0;
      valid_8 <= 16'h0;
      valid_9 <= 16'h0;
      valid_10 <= 16'h0;
      valid_11 <= 16'h0;
      valid_12 <= 16'h0;
      valid_13 <= 16'h0;
      valid_14 <= 16'h0;
      valid_15 <= 16'h0;
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
      if(when_ObjectCache_l136) begin
        hitTagReg <= ((|hitTagVec) && cacheable);
        handleReg <= io_handle;
        indexReg <= io_fieldIdx;
        cacheableReg <= cacheable;
      end
      if(io_chkGf) begin
        if(when_ObjectCache_l145) begin
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
      if(when_ObjectCache_l209) begin
        nxt <= (nxt + 4'b0001);
      end
      if(io_inval) begin
        nxt <= 4'b0000;
        valid_0 <= 16'h0;
        valid_1 <= 16'h0;
        valid_2 <= 16'h0;
        valid_3 <= 16'h0;
        valid_4 <= 16'h0;
        valid_5 <= 16'h0;
        valid_6 <= 16'h0;
        valid_7 <= 16'h0;
        valid_8 <= 16'h0;
        valid_9 <= 16'h0;
        valid_10 <= 16'h0;
        valid_11 <= 16'h0;
        valid_12 <= 16'h0;
        valid_13 <= 16'h0;
        valid_14 <= 16'h0;
        valid_15 <= 16'h0;
      end
      if(when_ObjectCache_l225) begin
        valid_0 <= _zz_valid_0_1;
      end
      if(when_ObjectCache_l225_1) begin
        valid_1 <= _zz_valid_1;
      end
      if(when_ObjectCache_l225_2) begin
        valid_2 <= _zz_valid_2;
      end
      if(when_ObjectCache_l225_3) begin
        valid_3 <= _zz_valid_3;
      end
      if(when_ObjectCache_l225_4) begin
        valid_4 <= _zz_valid_4;
      end
      if(when_ObjectCache_l225_5) begin
        valid_5 <= _zz_valid_5;
      end
      if(when_ObjectCache_l225_6) begin
        valid_6 <= _zz_valid_6;
      end
      if(when_ObjectCache_l225_7) begin
        valid_7 <= _zz_valid_7;
      end
      if(when_ObjectCache_l225_8) begin
        valid_8 <= _zz_valid_8;
      end
      if(when_ObjectCache_l225_9) begin
        valid_9 <= _zz_valid_9;
      end
      if(when_ObjectCache_l225_10) begin
        valid_10 <= _zz_valid_10;
      end
      if(when_ObjectCache_l225_11) begin
        valid_11 <= _zz_valid_11;
      end
      if(when_ObjectCache_l225_12) begin
        valid_12 <= _zz_valid_12;
      end
      if(when_ObjectCache_l225_13) begin
        valid_13 <= _zz_valid_13;
      end
      if(when_ObjectCache_l225_14) begin
        valid_14 <= _zz_valid_14;
      end
      if(when_ObjectCache_l225_15) begin
        valid_15 <= _zz_valid_15;
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
  localparam State_5_IDLE = 2'd0;
  localparam State_5_S1 = 2'd1;
  localparam State_5_S2 = 2'd2;

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
  reg        [1:0]    state_6;
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
  reg                 tagValid_0;
  reg                 tagValid_1;
  reg                 tagValid_2;
  reg                 tagValid_3;
  reg                 tagValid_4;
  reg                 tagValid_5;
  reg                 tagValid_6;
  reg                 tagValid_7;
  reg                 tagValid_8;
  reg                 tagValid_9;
  reg                 tagValid_10;
  reg                 tagValid_11;
  reg                 tagValid_12;
  reg                 tagValid_13;
  reg                 tagValid_14;
  reg                 tagValid_15;
  reg        [3:0]    nxt;
  reg        [3:0]    blockAddr;
  reg                 inCache;
  wire       [3:0]    nrOfBlks;
  wire       [17:0]   useAddr;
  reg        [15:0]   clrVal;
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
  wire                when_MethodCache_l143;
  wire                when_MethodCache_l143_1;
  wire                when_MethodCache_l143_2;
  wire                when_MethodCache_l143_3;
  wire                when_MethodCache_l143_4;
  wire                when_MethodCache_l143_5;
  wire                when_MethodCache_l143_6;
  wire                when_MethodCache_l143_7;
  wire                when_MethodCache_l143_8;
  wire                when_MethodCache_l143_9;
  wire                when_MethodCache_l143_10;
  wire                when_MethodCache_l143_11;
  wire                when_MethodCache_l143_12;
  wire                when_MethodCache_l143_13;
  wire                when_MethodCache_l143_14;
  wire                when_MethodCache_l143_15;
  wire       [15:0]   _zz_1;
  wire       [15:0]   _zz_2;
  `ifndef SYNTHESIS
  reg [31:0] state_6_string;
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
    case(state_6)
      State_5_IDLE : state_6_string = "IDLE";
      State_5_S1 : state_6_string = "S1  ";
      State_5_S2 : state_6_string = "S2  ";
      default : state_6_string = "????";
    endcase
  end
  `endif

  assign nrOfBlks = _zz_nrOfBlks[3:0];
  assign useAddr = io_bcAddr;
  assign io_bcStart = {blockAddr,5'h0};
  assign io_rdy = (state_6 == State_5_IDLE);
  assign io_inCache = inCache;
  assign when_MethodCache_l132 = (tagValid_0 && (tag_0 == useAddr));
  assign when_MethodCache_l132_1 = (tagValid_1 && (tag_1 == useAddr));
  assign when_MethodCache_l132_2 = (tagValid_2 && (tag_2 == useAddr));
  assign when_MethodCache_l132_3 = (tagValid_3 && (tag_3 == useAddr));
  assign when_MethodCache_l132_4 = (tagValid_4 && (tag_4 == useAddr));
  assign when_MethodCache_l132_5 = (tagValid_5 && (tag_5 == useAddr));
  assign when_MethodCache_l132_6 = (tagValid_6 && (tag_6 == useAddr));
  assign when_MethodCache_l132_7 = (tagValid_7 && (tag_7 == useAddr));
  assign when_MethodCache_l132_8 = (tagValid_8 && (tag_8 == useAddr));
  assign when_MethodCache_l132_9 = (tagValid_9 && (tag_9 == useAddr));
  assign when_MethodCache_l132_10 = (tagValid_10 && (tag_10 == useAddr));
  assign when_MethodCache_l132_11 = (tagValid_11 && (tag_11 == useAddr));
  assign when_MethodCache_l132_12 = (tagValid_12 && (tag_12 == useAddr));
  assign when_MethodCache_l132_13 = (tagValid_13 && (tag_13 == useAddr));
  assign when_MethodCache_l132_14 = (tagValid_14 && (tag_14 == useAddr));
  assign when_MethodCache_l132_15 = (tagValid_15 && (tag_15 == useAddr));
  assign when_MethodCache_l143 = clrVal[0];
  assign when_MethodCache_l143_1 = clrVal[1];
  assign when_MethodCache_l143_2 = clrVal[2];
  assign when_MethodCache_l143_3 = clrVal[3];
  assign when_MethodCache_l143_4 = clrVal[4];
  assign when_MethodCache_l143_5 = clrVal[5];
  assign when_MethodCache_l143_6 = clrVal[6];
  assign when_MethodCache_l143_7 = clrVal[7];
  assign when_MethodCache_l143_8 = clrVal[8];
  assign when_MethodCache_l143_9 = clrVal[9];
  assign when_MethodCache_l143_10 = clrVal[10];
  assign when_MethodCache_l143_11 = clrVal[11];
  assign when_MethodCache_l143_12 = clrVal[12];
  assign when_MethodCache_l143_13 = clrVal[13];
  assign when_MethodCache_l143_14 = clrVal[14];
  assign when_MethodCache_l143_15 = clrVal[15];
  assign _zz_1 = ({15'd0,1'b1} <<< nxt);
  assign _zz_2 = ({15'd0,1'b1} <<< nxt);
  always @(posedge clk or posedge reset) begin
    if(reset) begin
      state_6 <= State_5_IDLE;
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
      tagValid_0 <= 1'b0;
      tagValid_1 <= 1'b0;
      tagValid_2 <= 1'b0;
      tagValid_3 <= 1'b0;
      tagValid_4 <= 1'b0;
      tagValid_5 <= 1'b0;
      tagValid_6 <= 1'b0;
      tagValid_7 <= 1'b0;
      tagValid_8 <= 1'b0;
      tagValid_9 <= 1'b0;
      tagValid_10 <= 1'b0;
      tagValid_11 <= 1'b0;
      tagValid_12 <= 1'b0;
      tagValid_13 <= 1'b0;
      tagValid_14 <= 1'b0;
      tagValid_15 <= 1'b0;
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
      case(state_6)
        State_5_IDLE : begin
          if(io_find) begin
            state_6 <= State_5_S1;
          end
        end
        State_5_S1 : begin
          inCache <= 1'b0;
          state_6 <= State_5_S2;
          blockAddr <= nxt;
          if(when_MethodCache_l132) begin
            blockAddr <= 4'b0000;
            inCache <= 1'b1;
            state_6 <= State_5_IDLE;
          end
          if(when_MethodCache_l132_1) begin
            blockAddr <= 4'b0001;
            inCache <= 1'b1;
            state_6 <= State_5_IDLE;
          end
          if(when_MethodCache_l132_2) begin
            blockAddr <= 4'b0010;
            inCache <= 1'b1;
            state_6 <= State_5_IDLE;
          end
          if(when_MethodCache_l132_3) begin
            blockAddr <= 4'b0011;
            inCache <= 1'b1;
            state_6 <= State_5_IDLE;
          end
          if(when_MethodCache_l132_4) begin
            blockAddr <= 4'b0100;
            inCache <= 1'b1;
            state_6 <= State_5_IDLE;
          end
          if(when_MethodCache_l132_5) begin
            blockAddr <= 4'b0101;
            inCache <= 1'b1;
            state_6 <= State_5_IDLE;
          end
          if(when_MethodCache_l132_6) begin
            blockAddr <= 4'b0110;
            inCache <= 1'b1;
            state_6 <= State_5_IDLE;
          end
          if(when_MethodCache_l132_7) begin
            blockAddr <= 4'b0111;
            inCache <= 1'b1;
            state_6 <= State_5_IDLE;
          end
          if(when_MethodCache_l132_8) begin
            blockAddr <= 4'b1000;
            inCache <= 1'b1;
            state_6 <= State_5_IDLE;
          end
          if(when_MethodCache_l132_9) begin
            blockAddr <= 4'b1001;
            inCache <= 1'b1;
            state_6 <= State_5_IDLE;
          end
          if(when_MethodCache_l132_10) begin
            blockAddr <= 4'b1010;
            inCache <= 1'b1;
            state_6 <= State_5_IDLE;
          end
          if(when_MethodCache_l132_11) begin
            blockAddr <= 4'b1011;
            inCache <= 1'b1;
            state_6 <= State_5_IDLE;
          end
          if(when_MethodCache_l132_12) begin
            blockAddr <= 4'b1100;
            inCache <= 1'b1;
            state_6 <= State_5_IDLE;
          end
          if(when_MethodCache_l132_13) begin
            blockAddr <= 4'b1101;
            inCache <= 1'b1;
            state_6 <= State_5_IDLE;
          end
          if(when_MethodCache_l132_14) begin
            blockAddr <= 4'b1110;
            inCache <= 1'b1;
            state_6 <= State_5_IDLE;
          end
          if(when_MethodCache_l132_15) begin
            blockAddr <= 4'b1111;
            inCache <= 1'b1;
            state_6 <= State_5_IDLE;
          end
        end
        default : begin
          if(when_MethodCache_l143) begin
            tag_0 <= 18'h0;
            tagValid_0 <= 1'b0;
          end
          if(when_MethodCache_l143_1) begin
            tag_1 <= 18'h0;
            tagValid_1 <= 1'b0;
          end
          if(when_MethodCache_l143_2) begin
            tag_2 <= 18'h0;
            tagValid_2 <= 1'b0;
          end
          if(when_MethodCache_l143_3) begin
            tag_3 <= 18'h0;
            tagValid_3 <= 1'b0;
          end
          if(when_MethodCache_l143_4) begin
            tag_4 <= 18'h0;
            tagValid_4 <= 1'b0;
          end
          if(when_MethodCache_l143_5) begin
            tag_5 <= 18'h0;
            tagValid_5 <= 1'b0;
          end
          if(when_MethodCache_l143_6) begin
            tag_6 <= 18'h0;
            tagValid_6 <= 1'b0;
          end
          if(when_MethodCache_l143_7) begin
            tag_7 <= 18'h0;
            tagValid_7 <= 1'b0;
          end
          if(when_MethodCache_l143_8) begin
            tag_8 <= 18'h0;
            tagValid_8 <= 1'b0;
          end
          if(when_MethodCache_l143_9) begin
            tag_9 <= 18'h0;
            tagValid_9 <= 1'b0;
          end
          if(when_MethodCache_l143_10) begin
            tag_10 <= 18'h0;
            tagValid_10 <= 1'b0;
          end
          if(when_MethodCache_l143_11) begin
            tag_11 <= 18'h0;
            tagValid_11 <= 1'b0;
          end
          if(when_MethodCache_l143_12) begin
            tag_12 <= 18'h0;
            tagValid_12 <= 1'b0;
          end
          if(when_MethodCache_l143_13) begin
            tag_13 <= 18'h0;
            tagValid_13 <= 1'b0;
          end
          if(when_MethodCache_l143_14) begin
            tag_14 <= 18'h0;
            tagValid_14 <= 1'b0;
          end
          if(when_MethodCache_l143_15) begin
            tag_15 <= 18'h0;
            tagValid_15 <= 1'b0;
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
          if(_zz_2[0]) begin
            tagValid_0 <= 1'b1;
          end
          if(_zz_2[1]) begin
            tagValid_1 <= 1'b1;
          end
          if(_zz_2[2]) begin
            tagValid_2 <= 1'b1;
          end
          if(_zz_2[3]) begin
            tagValid_3 <= 1'b1;
          end
          if(_zz_2[4]) begin
            tagValid_4 <= 1'b1;
          end
          if(_zz_2[5]) begin
            tagValid_5 <= 1'b1;
          end
          if(_zz_2[6]) begin
            tagValid_6 <= 1'b1;
          end
          if(_zz_2[7]) begin
            tagValid_7 <= 1'b1;
          end
          if(_zz_2[8]) begin
            tagValid_8 <= 1'b1;
          end
          if(_zz_2[9]) begin
            tagValid_9 <= 1'b1;
          end
          if(_zz_2[10]) begin
            tagValid_10 <= 1'b1;
          end
          if(_zz_2[11]) begin
            tagValid_11 <= 1'b1;
          end
          if(_zz_2[12]) begin
            tagValid_12 <= 1'b1;
          end
          if(_zz_2[13]) begin
            tagValid_13 <= 1'b1;
          end
          if(_zz_2[14]) begin
            tagValid_14 <= 1'b1;
          end
          if(_zz_2[15]) begin
            tagValid_15 <= 1'b1;
          end
          nxt <= (_zz_nxt + 4'b0001);
          state_6 <= State_5_IDLE;
        end
      endcase
    end
  end


endmodule

module ComputeUnitTop (
  input  wire [31:0]   io_din,
  input  wire          io_push,
  input  wire [5:0]    io_opcode,
  input  wire          io_start,
  output wire [31:0]   io_dout,
  input  wire          io_pop,
  output wire          io_busy,
  input  wire          clk,
  input  wire          reset
);

  wire       [3:0]    icu_io_op;
  wire                icu_io_start;
  wire       [3:0]    fcu_io_op;
  wire                fcu_io_start;
  wire       [3:0]    lcu_io_op;
  wire                lcu_io_start;
  wire       [3:0]    dcu_io_op;
  wire                dcu_io_start;
  wire       [31:0]   icu_io_resultLo;
  wire       [31:0]   icu_io_resultHi;
  wire       [1:0]    icu_io_resultCount;
  wire                icu_io_busy;
  wire       [31:0]   fcu_io_resultLo;
  wire       [31:0]   fcu_io_resultHi;
  wire       [1:0]    fcu_io_resultCount;
  wire                fcu_io_busy;
  wire       [31:0]   lcu_io_resultLo;
  wire       [31:0]   lcu_io_resultHi;
  wire       [1:0]    lcu_io_resultCount;
  wire                lcu_io_busy;
  wire       [31:0]   dcu_io_resultLo;
  wire       [31:0]   dcu_io_resultHi;
  wire       [1:0]    dcu_io_resultCount;
  wire                dcu_io_busy;
  wire       [1:0]    _zz__zz_1;
  reg        [31:0]   opStack_0;
  reg        [31:0]   opStack_1;
  reg        [31:0]   opStack_2;
  reg        [31:0]   opStack_3;
  reg        [2:0]    opSp;
  wire       [3:0]    _zz_1;
  wire       [1:0]    unitSel;
  reg        [1:0]    latchedUnitSel;
  reg        [0:0]    resultPtr;
  reg        [31:0]   activeResultLo;
  reg        [31:0]   activeResultHi;
  reg        [1:0]    activeResultCount;

  assign _zz__zz_1 = opSp[1:0];
  IntegerComputeUnit icu (
    .io_operands_0  (opStack_0[31:0]        ), //i
    .io_operands_1  (opStack_1[31:0]        ), //i
    .io_operands_2  (opStack_2[31:0]        ), //i
    .io_operands_3  (opStack_3[31:0]        ), //i
    .io_op          (icu_io_op[3:0]         ), //i
    .io_start       (icu_io_start           ), //i
    .io_resultLo    (icu_io_resultLo[31:0]  ), //o
    .io_resultHi    (icu_io_resultHi[31:0]  ), //o
    .io_resultCount (icu_io_resultCount[1:0]), //o
    .io_busy        (icu_io_busy            ), //o
    .clk            (clk                    ), //i
    .reset          (reset                  )  //i
  );
  FloatComputeUnit fcu (
    .io_operands_0  (opStack_0[31:0]        ), //i
    .io_operands_1  (opStack_1[31:0]        ), //i
    .io_operands_2  (opStack_2[31:0]        ), //i
    .io_operands_3  (opStack_3[31:0]        ), //i
    .io_op          (fcu_io_op[3:0]         ), //i
    .io_start       (fcu_io_start           ), //i
    .io_resultLo    (fcu_io_resultLo[31:0]  ), //o
    .io_resultHi    (fcu_io_resultHi[31:0]  ), //o
    .io_resultCount (fcu_io_resultCount[1:0]), //o
    .io_busy        (fcu_io_busy            ), //o
    .clk            (clk                    ), //i
    .reset          (reset                  )  //i
  );
  LongComputeUnit lcu (
    .io_operands_0  (opStack_0[31:0]        ), //i
    .io_operands_1  (opStack_1[31:0]        ), //i
    .io_operands_2  (opStack_2[31:0]        ), //i
    .io_operands_3  (opStack_3[31:0]        ), //i
    .io_op          (lcu_io_op[3:0]         ), //i
    .io_start       (lcu_io_start           ), //i
    .io_resultLo    (lcu_io_resultLo[31:0]  ), //o
    .io_resultHi    (lcu_io_resultHi[31:0]  ), //o
    .io_resultCount (lcu_io_resultCount[1:0]), //o
    .io_busy        (lcu_io_busy            ), //o
    .clk            (clk                    ), //i
    .reset          (reset                  )  //i
  );
  DoubleComputeUnit dcu (
    .io_operands_0  (opStack_0[31:0]        ), //i
    .io_operands_1  (opStack_1[31:0]        ), //i
    .io_operands_2  (opStack_2[31:0]        ), //i
    .io_operands_3  (opStack_3[31:0]        ), //i
    .io_op          (dcu_io_op[3:0]         ), //i
    .io_start       (dcu_io_start           ), //i
    .io_resultLo    (dcu_io_resultLo[31:0]  ), //o
    .io_resultHi    (dcu_io_resultHi[31:0]  ), //o
    .io_resultCount (dcu_io_resultCount[1:0]), //o
    .io_busy        (dcu_io_busy            ), //o
    .clk            (clk                    ), //i
    .reset          (reset                  )  //i
  );
  assign _zz_1 = ({3'd0,1'b1} <<< _zz__zz_1);
  assign unitSel = io_opcode[5 : 4];
  assign icu_io_op = io_opcode[3 : 0];
  assign fcu_io_op = io_opcode[3 : 0];
  assign lcu_io_op = io_opcode[3 : 0];
  assign dcu_io_op = io_opcode[3 : 0];
  assign icu_io_start = (io_start && (unitSel == 2'b00));
  assign fcu_io_start = (io_start && (unitSel == 2'b01));
  assign lcu_io_start = (io_start && (unitSel == 2'b10));
  assign dcu_io_start = (io_start && (unitSel == 2'b11));
  always @(*) begin
    case(latchedUnitSel)
      2'b00 : begin
        activeResultLo = icu_io_resultLo;
      end
      2'b01 : begin
        activeResultLo = fcu_io_resultLo;
      end
      2'b10 : begin
        activeResultLo = lcu_io_resultLo;
      end
      default : begin
        activeResultLo = dcu_io_resultLo;
      end
    endcase
  end

  always @(*) begin
    case(latchedUnitSel)
      2'b00 : begin
        activeResultHi = icu_io_resultHi;
      end
      2'b01 : begin
        activeResultHi = fcu_io_resultHi;
      end
      2'b10 : begin
        activeResultHi = lcu_io_resultHi;
      end
      default : begin
        activeResultHi = dcu_io_resultHi;
      end
    endcase
  end

  always @(*) begin
    case(latchedUnitSel)
      2'b00 : begin
        activeResultCount = icu_io_resultCount;
      end
      2'b01 : begin
        activeResultCount = fcu_io_resultCount;
      end
      2'b10 : begin
        activeResultCount = lcu_io_resultCount;
      end
      default : begin
        activeResultCount = dcu_io_resultCount;
      end
    endcase
  end

  assign io_dout = (((resultPtr == 1'b0) && (activeResultCount == 2'b10)) ? activeResultHi : activeResultLo);
  assign io_busy = ((((icu_io_busy || fcu_io_busy) || lcu_io_busy) || dcu_io_busy) || io_start);
  always @(posedge clk or posedge reset) begin
    if(reset) begin
      opStack_0 <= 32'h0;
      opStack_1 <= 32'h0;
      opStack_2 <= 32'h0;
      opStack_3 <= 32'h0;
      opSp <= 3'b000;
      latchedUnitSel <= 2'b00;
      resultPtr <= 1'b0;
    end else begin
      if(io_push) begin
        if(_zz_1[0]) begin
          opStack_0 <= io_din;
        end
        if(_zz_1[1]) begin
          opStack_1 <= io_din;
        end
        if(_zz_1[2]) begin
          opStack_2 <= io_din;
        end
        if(_zz_1[3]) begin
          opStack_3 <= io_din;
        end
        opSp <= (opSp + 3'b001);
      end
      if(io_start) begin
        opSp <= 3'b000;
      end
      if(io_start) begin
        latchedUnitSel <= unitSel;
      end
      if(io_start) begin
        resultPtr <= 1'b0;
      end
      if(io_pop) begin
        resultPtr <= (resultPtr + 1'b1);
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
  output wire [7:0]    io_debugVp,
  output wire [7:0]    io_debugAr,
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
  output wire          io_debugEnaA,
  output wire [2:0]    io_debugSelLmux,
  output wire          io_debugEnaB,
  output wire          io_debugSelBmux,
  output wire [31:0]   io_debugRamDoutVal,
  output wire [31:0]   io_debugLmuxVal,
  input  wire          clk,
  input  wire          reset
);

  wire       [31:0]   shifter_io_din;
  wire       [4:0]    shifter_io_off;
  reg        [31:0]   _zz_1_spinal_port1;
  wire       [31:0]   shifter_io_dout;
  wire                _zz__zz_1_port;
  wire                _zz__zz_io_debugRamData_6;
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
  wire                rotBusy;
  reg                 rotBusyDly;
  wire       [31:0]   sout;
  reg        [7:0]    smuxSignal;
  reg        [7:0]    rdaddr;
  reg        [7:0]    wraddr;
  reg        [31:0]   mmux;
  wire                when_StackStage_l321;
  reg                 wrEnaDly;
  reg        [7:0]    wrAddrDly;
  wire                when_StackStage_l336;
  wire       [31:0]   ramDout;
  reg        [7:0]    ramRdaddrReg;
  wire                when_StackStage_l354;
  wire       [7:0]    _zz_io_debugRamData;
  wire       [31:0]   _zz_io_debugRamData_1;
  wire                _zz_io_debugRamData_2;
  wire       [7:0]    _zz_io_debugRamData_3;
  reg                 _zz_io_debugRamData_4;
  reg        [31:0]   _zz_io_debugRamData_5;
  wire       [31:0]   _zz_io_debugRamData_6;
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
  wire                when_StackStage_l975;
  wire                when_StackStage_l1027;
  wire                when_StackStage_l1031;
  wire                when_StackStage_l1032;
  reg                 bDbgEnaB;
  reg                 bDbgSelBmux;
  reg        [31:0]   bDbgRamDout;
  reg        [31:0]   bDbgA;
  reg        [7:0]    bDbgRdAddr;
  wire                when_StackStage_l1057;
  wire                when_StackStage_l1065;
  wire                when_StackStage_l1071;
  wire                when_StackStage_l1079;
  wire                when_StackStage_l1084;
  reg [31:0] _zz_1 [0:255];

  assign _zz_imux_1 = opddly[7 : 0];
  assign _zz_imux = {{24{_zz_imux_1[7]}}, _zz_imux_1};
  assign _zz_imux_3 = opddly;
  assign _zz_imux_2 = {{16{_zz_imux_3[15]}}, _zz_imux_3};
  assign _zz_lmux = rmux;
  assign _zz_amux = sum[31 : 0];
  assign _zz_vpadd_1 = io_opd[6 : 0];
  assign _zz_vpadd = {1'd0, _zz_vpadd_1};
  assign _zz__zz_io_debugRamData_6 = 1'b1;
  always @(posedge clk) begin
    if(_zz_io_debugRamData_2) begin
      _zz_1[_zz_io_debugRamData] <= _zz_io_debugRamData_1;
    end
  end

  always @(posedge clk) begin
    if(_zz__zz_io_debugRamData_6) begin
      _zz_1_spinal_port1 <= _zz_1[_zz_io_debugRamData_3];
    end
  end

  Shift shifter (
    .io_din   (shifter_io_din[31:0] ), //i
    .io_off   (shifter_io_off[4:0]  ), //i
    .io_shtyp (io_selShf[1:0]       ), //i
    .io_dout  (shifter_io_dout[31:0])  //o
  );
  assign rotBusy = 1'b0;
  assign shifter_io_din = b;
  assign shifter_io_off = a[4 : 0];
  assign sout = shifter_io_dout;
  always @(*) begin
    case(io_selSmux)
      2'b00 : begin
        smuxSignal = sp;
      end
      2'b01 : begin
        smuxSignal = spm;
      end
      2'b10 : begin
        smuxSignal = spp;
      end
      default : begin
        smuxSignal = a[7 : 0];
      end
    endcase
  end

  assign when_StackStage_l321 = (io_selMmux == 1'b0);
  always @(*) begin
    if(when_StackStage_l321) begin
      mmux = a;
    end else begin
      mmux = b;
    end
  end

  assign when_StackStage_l336 = (! rotBusy);
  assign when_StackStage_l354 = (! rotBusy);
  assign _zz_io_debugRamData = (io_debugRamWrEn ? io_debugRamWrAddr : wrAddrDly);
  assign _zz_io_debugRamData_1 = (io_debugRamWrEn ? io_debugRamWrData : mmux);
  assign _zz_io_debugRamData_2 = (io_debugRamWrEn || wrEnaDly);
  assign _zz_io_debugRamData_3 = (((io_debugRamAddr != 8'h0) || io_debugRamWrEn) ? io_debugRamAddr : rdaddr);
  assign _zz_io_debugRamData_6 = (_zz_io_debugRamData_4 ? _zz_io_debugRamData_5 : _zz_1_spinal_port1);
  assign ramDout = _zz_io_debugRamData_6;
  assign io_debugRamData = _zz_io_debugRamData_6;
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
      3'b101 : begin
        lmux = {20'd0, _zz_lmux};
      end
      3'b110 : begin
        lmux = 32'h0;
      end
      default : begin
        lmux = 32'h0;
      end
    endcase
  end

  assign when_StackStage_l975 = (io_selAmux == 1'b0);
  always @(*) begin
    if(when_StackStage_l975) begin
      amux = _zz_amux;
    end else begin
      amux = lmux;
    end
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
  assign when_StackStage_l1027 = (io_enaA && (! rotBusyDly));
  assign when_StackStage_l1031 = (io_enaB && (! rotBusyDly));
  assign when_StackStage_l1032 = (io_selBmux == 1'b0);
  assign when_StackStage_l1057 = (! rotBusy);
  assign when_StackStage_l1065 = (sp == 8'hef);
  assign when_StackStage_l1071 = (io_enaVp && (! rotBusyDly));
  assign when_StackStage_l1079 = (io_enaAr && (! rotBusyDly));
  assign when_StackStage_l1084 = (! rotBusyDly);
  assign io_spOv = spOvReg;
  assign io_aout = a;
  assign io_bout = b;
  assign io_debugSp = sp;
  assign io_debugVp = vp0;
  assign io_debugAr = ar;
  assign io_debugWrAddr = wrAddrDly;
  assign io_debugWrEn = wrEnaDly;
  assign io_debugRdAddrReg = ramRdaddrReg;
  assign io_debugRamDout = ramDout;
  assign io_debugEnaA = io_enaA;
  assign io_debugSelLmux = io_selLmux;
  assign io_debugEnaB = io_enaB;
  assign io_debugSelBmux = io_selBmux;
  assign io_debugRamDoutVal = ramDout;
  assign io_debugLmuxVal = lmux;
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
      rotBusyDly <= 1'b0;
      wrEnaDly <= 1'b0;
      wrAddrDly <= 8'h0;
      ramRdaddrReg <= 8'h0;
      _zz_io_debugRamData_4 <= 1'b0;
      _zz_io_debugRamData_5 <= 32'h0;
      bDbgEnaB <= 1'b0;
      bDbgSelBmux <= 1'b0;
      bDbgRamDout <= 32'h0;
      bDbgA <= 32'h0;
      bDbgRdAddr <= 8'h0;
    end else begin
      rotBusyDly <= rotBusy;
      if(when_StackStage_l336) begin
        wrEnaDly <= io_wrEna;
        wrAddrDly <= wraddr;
      end
      if(when_StackStage_l354) begin
        ramRdaddrReg <= rdaddr;
      end
      _zz_io_debugRamData_4 <= (_zz_io_debugRamData_2 && (_zz_io_debugRamData == _zz_io_debugRamData_3));
      _zz_io_debugRamData_5 <= _zz_io_debugRamData_1;
      if(when_StackStage_l1027) begin
        a <= amux;
      end
      if(when_StackStage_l1031) begin
        if(when_StackStage_l1032) begin
          b <= a;
        end else begin
          b <= ramDout;
        end
      end
      bDbgEnaB <= (io_enaB && (! rotBusyDly));
      bDbgSelBmux <= io_selBmux;
      bDbgRamDout <= ramDout;
      bDbgA <= a;
      bDbgRdAddr <= ramRdaddrReg;
      if(when_StackStage_l1057) begin
        spp <= (smuxSignal + 8'h01);
        spm <= (smuxSignal - 8'h01);
        sp <= smuxSignal;
      end
      if(when_StackStage_l1065) begin
        spOvReg <= 1'b1;
      end
      if(when_StackStage_l1071) begin
        vp0 <= a[7 : 0];
        vp1 <= (a[7 : 0] + 8'h01);
        vp2 <= (a[7 : 0] + 8'h02);
        vp3 <= (a[7 : 0] + 8'h03);
      end
      if(when_StackStage_l1079) begin
        ar <= a[7 : 0];
      end
      if(when_StackStage_l1084) begin
        vpadd <= (vp0 + _zz_vpadd);
      end
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
  output wire [15:0]   io_memIn_bcopd,
  output wire [3:0]    io_mmuInstr,
  output reg  [7:0]    io_dirAddr,
  output wire          io_hwWr,
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
  input  wire          io_stall,
  output wire          io_cuPush,
  output wire          io_cuStart,
  output wire [5:0]    io_cuOpcode,
  output wire          io_cuPop,
  input  wire          clk,
  input  wire          reset
);

  wire                outputNode_ready;
  wire                outputNode_valid;
  reg                 isPop;
  reg                 isPush;
  wire       [3:0]    switch_DecodeStage_l304;
  wire                when_DecodeStage_l327;
  wire                when_DecodeStage_l337;
  wire       [7:0]    combinationalDecode_dirDefault;
  wire                when_DecodeStage_l354;
  wire                when_DecodeStage_l363;
  wire                when_DecodeStage_l366;
  wire                when_DecodeStage_l369;
  wire                when_DecodeStage_l378;
  wire                when_DecodeStage_l381;
  wire                when_DecodeStage_l396;
  reg                 branchDecode_brReg;
  reg                 branchDecode_jmpReg;
  wire                when_DecodeStage_l411;
  wire                when_DecodeStage_l418;
  wire                when_DecodeStage_l423;
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
  reg                 aluControlDecode_cuPushReg;
  reg                 aluControlDecode_cuStartReg;
  reg        [5:0]    aluControlDecode_cuOpcodeReg;
  reg                 aluControlDecode_cuPopReg;
  wire                when_DecodeStage_l467;
  wire                when_DecodeStage_l473;
  wire                when_DecodeStage_l620;
  wire                when_DecodeStage_l629;
  wire                when_DecodeStage_l633;
  wire                when_DecodeStage_l636;
  wire                when_DecodeStage_l640;
  wire                when_DecodeStage_l644;
  wire                when_DecodeStage_l648;
  wire                when_DecodeStage_l652;
  wire                when_DecodeStage_l658;
  wire                when_DecodeStage_l663;
  wire                when_DecodeStage_l666;
  wire                when_DecodeStage_l672;
  wire                when_DecodeStage_l679;
  wire                when_DecodeStage_l690;
  wire                when_DecodeStage_l699;
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
  reg                 mmuControlDecode_hwWrReg;
  reg                 mmuControlDecode_wrDlyReg;
  wire                when_DecodeStage_l764;
  wire                when_DecodeStage_l790;
  wire       [3:0]    switch_DecodeStage_l792;
  wire                when_DecodeStage_l819;
  wire       [3:0]    switch_DecodeStage_l821;

  assign io_mmuInstr = io_instr[3 : 0];
  always @(*) begin
    isPop = 1'b0;
    case(switch_DecodeStage_l304)
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
    case(switch_DecodeStage_l304)
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

  assign switch_DecodeStage_l304 = io_instr[9 : 6];
  always @(*) begin
    io_jbr = 1'b0;
    if(when_DecodeStage_l327) begin
      io_jbr = 1'b1;
    end
  end

  assign when_DecodeStage_l327 = (io_instr == 10'h102);
  always @(*) begin
    io_wrEna = 1'b0;
    if(when_DecodeStage_l337) begin
      io_wrEna = 1'b1;
    end
  end

  assign when_DecodeStage_l337 = ((isPush || (io_instr[9 : 5] == 5'h01)) || (io_instr[9 : 3] == 7'h02));
  assign io_selImux = io_instr[1 : 0];
  assign combinationalDecode_dirDefault = {3'b000,io_instr[4 : 0]};
  always @(*) begin
    io_dirAddr = combinationalDecode_dirDefault;
    if(when_DecodeStage_l354) begin
      io_dirAddr = {3'b001,io_instr[4 : 0]};
    end
  end

  assign when_DecodeStage_l354 = (io_instr[9 : 5] == 5'h06);
  always @(*) begin
    io_selRda = 3'b110;
    if(when_DecodeStage_l363) begin
      io_selRda = io_instr[2 : 0];
    end
    if(when_DecodeStage_l366) begin
      io_selRda = 3'b111;
    end
    if(when_DecodeStage_l369) begin
      io_selRda = 3'b111;
    end
  end

  assign when_DecodeStage_l363 = (io_instr[9 : 3] == 7'h1d);
  assign when_DecodeStage_l366 = (io_instr[9 : 5] == 5'h05);
  assign when_DecodeStage_l369 = (io_instr[9 : 5] == 5'h06);
  always @(*) begin
    io_selWra = 3'b110;
    if(when_DecodeStage_l378) begin
      io_selWra = io_instr[2 : 0];
    end
    if(when_DecodeStage_l381) begin
      io_selWra = 3'b111;
    end
  end

  assign when_DecodeStage_l378 = (io_instr[9 : 3] == 7'h02);
  assign when_DecodeStage_l381 = (io_instr[9 : 5] == 5'h01);
  always @(*) begin
    io_selSmux = 2'b00;
    if(isPop) begin
      io_selSmux = 2'b01;
    end
    if(isPush) begin
      io_selSmux = 2'b10;
    end
    if(when_DecodeStage_l396) begin
      io_selSmux = 2'b11;
    end
  end

  assign when_DecodeStage_l396 = (io_instr == 10'h01b);
  assign when_DecodeStage_l411 = (! io_stall);
  assign when_DecodeStage_l418 = (((io_instr[9 : 6] == 4'b0110) && io_zf) || ((io_instr[9 : 6] == 4'b0111) && (! io_zf)));
  assign when_DecodeStage_l423 = io_instr[9];
  assign io_br = branchDecode_brReg;
  assign io_jmp = branchDecode_jmpReg;
  assign when_DecodeStage_l467 = (! io_stall);
  assign when_DecodeStage_l473 = (io_instr[9 : 2] == 8'h0);
  assign when_DecodeStage_l620 = io_instr[9];
  assign when_DecodeStage_l629 = (io_instr[9 : 2] == 8'h07);
  assign when_DecodeStage_l633 = (io_instr[9 : 5] == 5'h05);
  assign when_DecodeStage_l636 = (io_instr[9 : 5] == 5'h06);
  assign when_DecodeStage_l640 = (io_instr[9 : 3] == 7'h1d);
  assign when_DecodeStage_l644 = (io_instr[9 : 2] == 8'h3d);
  assign when_DecodeStage_l648 = (io_instr[9 : 3] == 7'h1c);
  assign when_DecodeStage_l652 = (io_instr[9 : 2] == 8'h3c);
  assign when_DecodeStage_l658 = (io_instr == 10'h01f);
  assign when_DecodeStage_l663 = (io_instr == 10'h008);
  assign when_DecodeStage_l666 = (io_instr == 10'h009);
  assign when_DecodeStage_l672 = (io_instr[9 : 6] == 4'b0101);
  assign when_DecodeStage_l679 = (io_instr == 10'h0e1);
  assign when_DecodeStage_l690 = (! isPop);
  assign when_DecodeStage_l699 = ((! isPush) && (! isPop));
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
  assign io_cuPush = aluControlDecode_cuPushReg;
  assign io_cuStart = aluControlDecode_cuStartReg;
  assign io_cuOpcode = aluControlDecode_cuOpcodeReg;
  assign io_cuPop = aluControlDecode_cuPopReg;
  assign when_DecodeStage_l764 = (! io_stall);
  assign when_DecodeStage_l790 = (io_instr[9 : 4] == 6'h04);
  assign switch_DecodeStage_l792 = io_instr[3 : 0];
  assign when_DecodeStage_l819 = (io_instr[9 : 4] == 6'h11);
  assign switch_DecodeStage_l821 = io_instr[3 : 0];
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
  assign io_hwWr = mmuControlDecode_hwWrReg;
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
      aluControlDecode_cuPushReg <= 1'b0;
      aluControlDecode_cuStartReg <= 1'b0;
      aluControlDecode_cuOpcodeReg <= 6'h0;
      aluControlDecode_cuPopReg <= 1'b0;
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
      mmuControlDecode_hwWrReg <= 1'b0;
      mmuControlDecode_wrDlyReg <= 1'b0;
    end else begin
      if(when_DecodeStage_l411) begin
        branchDecode_brReg <= 1'b0;
        branchDecode_jmpReg <= 1'b0;
        if(when_DecodeStage_l418) begin
          branchDecode_brReg <= 1'b1;
        end
        if(when_DecodeStage_l423) begin
          branchDecode_jmpReg <= 1'b1;
        end
      end
      if(when_DecodeStage_l467) begin
        aluControlDecode_selLogReg <= 2'b00;
        if(when_DecodeStage_l473) begin
          aluControlDecode_selLogReg <= io_instr[1 : 0];
        end
        aluControlDecode_selShfReg <= io_instr[1 : 0];
        aluControlDecode_selSubReg <= 1'b1;
        aluControlDecode_selAmuxReg <= 1'b1;
        aluControlDecode_enaAReg <= 1'b1;
        aluControlDecode_enaVpReg <= 1'b0;
        aluControlDecode_enaJpcReg <= 1'b0;
        aluControlDecode_enaArReg <= 1'b0;
        aluControlDecode_cuPushReg <= 1'b0;
        aluControlDecode_cuStartReg <= 1'b0;
        aluControlDecode_cuPopReg <= 1'b0;
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
          10'h008 : begin
          end
          10'h009 : begin
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
          10'h01f : begin
            aluControlDecode_selLogReg <= 2'b00;
            aluControlDecode_cuPushReg <= 1'b1;
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
          10'h103 : begin
            aluControlDecode_enaAReg <= 1'b0;
          end
          10'h110 : begin
            aluControlDecode_enaAReg <= 1'b0;
          end
          10'h111 : begin
            aluControlDecode_enaAReg <= 1'b0;
          end
          default : begin
          end
        endcase
        if(when_DecodeStage_l620) begin
          aluControlDecode_enaAReg <= 1'b0;
        end
        aluControlDecode_selLmuxReg <= 3'b000;
        if(when_DecodeStage_l629) begin
          aluControlDecode_selLmuxReg <= 3'b001;
        end
        if(when_DecodeStage_l633) begin
          aluControlDecode_selLmuxReg <= 3'b010;
        end
        if(when_DecodeStage_l636) begin
          aluControlDecode_selLmuxReg <= 3'b010;
        end
        if(when_DecodeStage_l640) begin
          aluControlDecode_selLmuxReg <= 3'b010;
        end
        if(when_DecodeStage_l644) begin
          aluControlDecode_selLmuxReg <= 3'b011;
        end
        if(when_DecodeStage_l648) begin
          aluControlDecode_selLmuxReg <= 3'b100;
        end
        if(when_DecodeStage_l652) begin
          aluControlDecode_selLmuxReg <= 3'b101;
        end
        if(when_DecodeStage_l658) begin
          aluControlDecode_selLmuxReg <= 3'b000;
        end
        if(when_DecodeStage_l663) begin
          aluControlDecode_selLmuxReg <= 3'b110;
        end
        if(when_DecodeStage_l666) begin
          aluControlDecode_selLmuxReg <= 3'b111;
        end
        if(when_DecodeStage_l672) begin
          aluControlDecode_cuStartReg <= 1'b1;
          aluControlDecode_cuOpcodeReg <= io_instr[5 : 0];
          aluControlDecode_enaAReg <= 1'b0;
        end
        if(when_DecodeStage_l679) begin
          aluControlDecode_cuPopReg <= 1'b1;
        end
        aluControlDecode_selBmuxReg <= 1'b1;
        aluControlDecode_selMmuxReg <= 1'b0;
        if(when_DecodeStage_l690) begin
          aluControlDecode_selBmuxReg <= 1'b0;
          aluControlDecode_selMmuxReg <= 1'b1;
        end
        aluControlDecode_enaBReg <= 1'b1;
        if(when_DecodeStage_l699) begin
          aluControlDecode_enaBReg <= 1'b0;
        end
        aluControlDecode_selRmuxReg <= io_instr[1 : 0];
      end
      if(when_DecodeStage_l764) begin
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
        mmuControlDecode_hwWrReg <= 1'b0;
        mmuControlDecode_wrDlyReg <= 1'b0;
        if(when_DecodeStage_l790) begin
          mmuControlDecode_wrDlyReg <= 1'b1;
          case(switch_DecodeStage_l792)
            4'b0000 : begin
              mmuControlDecode_hwWrReg <= 1'b1;
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
        if(when_DecodeStage_l819) begin
          mmuControlDecode_wrDlyReg <= 1'b1;
          case(switch_DecodeStage_l821)
            4'b0000 : begin
              mmuControlDecode_memGetstaticReg <= 1'b1;
            end
            4'b0001 : begin
              mmuControlDecode_memCinvalReg <= 1'b1;
            end
            default : begin
            end
          endcase
        end
      end
    end
  end


endmodule

module FetchStage (
  input  wire          io_br,
  input  wire          io_jmp,
  input  wire          io_bsy,
  input  wire [11:0]   io_jpaddr,
  input  wire          io_extStall,
  output wire          io_nxt,
  output wire          io_opd,
  output wire [9:0]    io_dout,
  output wire [11:0]   io_pc_out,
  output wire [9:0]    io_ir_out,
  input  wire          reset,
  input  wire          clk
);

  wire       [11:0]   _zz_1_spinal_port0;
  wire       [11:0]   _zz_brdly;
  wire       [11:0]   _zz_brdly_1;
  wire       [11:0]   _zz_brdly_2;
  wire       [5:0]    _zz_brdly_3;
  wire       [11:0]   _zz_jpdly;
  wire       [11:0]   _zz_jpdly_1;
  wire       [11:0]   _zz_jpdly_2;
  wire       [8:0]    _zz_jpdly_3;
  wire                outputNode_ready;
  wire                outputNode_valid;
  wire       [9:0]    outputNode_INSTR_PAYLOAD;
  wire       [11:0]   outputNode_PC_PAYLOAD;
  reg        [11:0]   romAddrReg;
  reg        [11:0]   pcMux;
  wire       [11:0]   romData;
  wire                jfetch;
  wire                jopdfetch;
  wire       [9:0]    romInstr;
  reg        [11:0]   pc;
  reg        [11:0]   brdly;
  reg        [11:0]   jpdly;
  reg        [9:0]    ir;
  reg                 pcwait;
  wire       [11:0]   pcInc;
  wire                when_FetchStage_l162;
  wire                when_FetchStage_l180;
  wire                when_FetchStage_l222;
  reg [11:0] _zz_1 [0:4095];

  assign _zz_brdly = ($signed(_zz_brdly_1) + $signed(_zz_brdly_2));
  assign _zz_brdly_1 = pc;
  assign _zz_brdly_3 = ir[5 : 0];
  assign _zz_brdly_2 = {{6{_zz_brdly_3[5]}}, _zz_brdly_3};
  assign _zz_jpdly = ($signed(_zz_jpdly_1) + $signed(_zz_jpdly_2));
  assign _zz_jpdly_1 = pc;
  assign _zz_jpdly_3 = ir[8 : 0];
  assign _zz_jpdly_2 = {{3{_zz_jpdly_3[8]}}, _zz_jpdly_3};
  initial begin
    $readmemb("JopCore.v_toplevel_pipeline_fetch__zz_1.bin",_zz_1);
  end
  assign _zz_1_spinal_port0 = _zz_1[romAddrReg];
  assign romData = _zz_1_spinal_port0;
  assign jfetch = romData[11];
  assign jopdfetch = romData[10];
  assign romInstr = romData[9 : 0];
  assign pcInc = (pc + 12'h001);
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
          if(when_FetchStage_l162) begin
            pcMux = pc;
          end else begin
            pcMux = pcInc;
          end
        end
      end
    end
    if(when_FetchStage_l222) begin
      pcMux = pc;
    end
  end

  assign when_FetchStage_l162 = (pcwait && io_bsy);
  assign when_FetchStage_l180 = (romInstr == 10'h101);
  assign when_FetchStage_l222 = ((pcwait && io_bsy) || io_extStall);
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
      romAddrReg <= 12'h0;
      pc <= 12'h0;
      brdly <= 12'h0;
      jpdly <= 12'h0;
      ir <= 10'h0;
      pcwait <= 1'b0;
    end else begin
      romAddrReg <= pcMux;
      ir <= romInstr;
      pcwait <= 1'b0;
      if(when_FetchStage_l180) begin
        pcwait <= 1'b1;
      end
      if(reset) begin
        pc <= 12'h0;
        brdly <= 12'h0;
        jpdly <= 12'h0;
      end else begin
        brdly <= _zz_brdly;
        jpdly <= _zz_jpdly;
        pc <= pcMux;
      end
      if(when_FetchStage_l222) begin
        romAddrReg <= romAddrReg;
        ir <= ir;
        pcwait <= pcwait;
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
  input  wire          io_stall,
  input  wire          io_irq,
  input  wire          io_exc,
  input  wire          io_ena,
  output wire          io_ack_irq,
  output wire          io_ack_exc,
  output wire [11:0]   io_jpaddr,
  output wire [15:0]   io_opd,
  output wire [11:0]   io_jpc_out,
  output wire [7:0]    io_jinstr_out,
  input  wire          clk,
  input  wire          reset
);

  wire                jumpTable_1_io_intPend;
  reg        [31:0]   jbcRamWord_spinal_port1;
  wire       [11:0]   jumpTable_1_io_jpaddr;
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
  wire                when_BytecodeFetchStage_l149;
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
  wire                when_BytecodeFetchStage_l190;
  wire                when_BytecodeFetchStage_l197;
  wire                when_BytecodeFetchStage_l214;
  wire                when_BytecodeFetchStage_l235;
  reg        [3:0]    tp;
  wire       [11:0]   branchOffset;
  wire                when_BytecodeFetchStage_l268;
  wire                when_BytecodeFetchStage_l274;
  wire                when_BytecodeFetchStage_l277;
  wire                when_BytecodeFetchStage_l280;
  wire                when_BytecodeFetchStage_l286;
  wire                when_BytecodeFetchStage_l292;
  wire                when_BytecodeFetchStage_l295;
  wire                when_BytecodeFetchStage_l298;
  reg                 intPend;
  reg                 excPend;
  wire                doAckIrq;
  wire                doAckExc;
  wire                excPendImmediate;
  reg [31:0] jbcRamWord [0:511];

  assign _zz_jbcAddr = (jpc + 12'h001);
  assign _zz_jmp_addr = ($signed(_zz_jmp_addr_1) + $signed(branchOffset));
  assign _zz_jmp_addr_1 = jpc_br;
  assign _zz_jbcWordDataRaw = 1'b1;
  initial begin
    $readmemb("JopCore.v_toplevel_pipeline_bcfetch_jbcRamWord.bin",jbcRamWord);
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
    .io_jpaddr   (jumpTable_1_io_jpaddr[11:0]), //o
    .io_intPend  (jumpTable_1_io_intPend     ), //i
    .io_excPend  (excPendImmediate           )  //i
  );
  always @(*) begin
    if(jmp) begin
      jbcAddr = jmp_addr[10 : 0];
    end else begin
      if(when_BytecodeFetchStage_l149) begin
        jbcAddr = _zz_jbcAddr[10 : 0];
      end else begin
        jbcAddr = jpc[10 : 0];
      end
    end
  end

  assign when_BytecodeFetchStage_l149 = (io_jfetch || io_jopdfetch);
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

  assign when_BytecodeFetchStage_l190 = (! io_stall);
  assign when_BytecodeFetchStage_l197 = (io_jfetch || io_jopdfetch);
  assign io_jpc_out = jpc;
  assign io_jinstr_out = jinstr;
  assign when_BytecodeFetchStage_l214 = (! io_stall);
  assign io_opd = jopd;
  assign when_BytecodeFetchStage_l235 = ((! io_stall) && io_jfetch);
  always @(*) begin
    case(jinstr)
      8'ha5 : begin
        tp = 4'b1111;
      end
      8'ha6 : begin
        tp = 4'b0000;
      end
      8'hc6 : begin
        tp = 4'b1001;
      end
      8'hc7 : begin
        tp = 4'b1010;
      end
      default : begin
        tp = jinstr[3 : 0];
      end
    endcase
  end

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
          if(when_BytecodeFetchStage_l268) begin
            jmp = 1'b1;
          end
        end
        4'b1011 : begin
          if(io_nf) begin
            jmp = 1'b1;
          end
        end
        4'b1100 : begin
          if(when_BytecodeFetchStage_l274) begin
            jmp = 1'b1;
          end
        end
        4'b1101 : begin
          if(when_BytecodeFetchStage_l277) begin
            jmp = 1'b1;
          end
        end
        4'b1110 : begin
          if(when_BytecodeFetchStage_l280) begin
            jmp = 1'b1;
          end
        end
        4'b1111 : begin
          if(io_eq) begin
            jmp = 1'b1;
          end
        end
        4'b0000 : begin
          if(when_BytecodeFetchStage_l286) begin
            jmp = 1'b1;
          end
        end
        4'b0001 : begin
          if(io_lt) begin
            jmp = 1'b1;
          end
        end
        4'b0010 : begin
          if(when_BytecodeFetchStage_l292) begin
            jmp = 1'b1;
          end
        end
        4'b0011 : begin
          if(when_BytecodeFetchStage_l295) begin
            jmp = 1'b1;
          end
        end
        4'b0100 : begin
          if(when_BytecodeFetchStage_l298) begin
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

  assign when_BytecodeFetchStage_l268 = (! io_zf);
  assign when_BytecodeFetchStage_l274 = (! io_nf);
  assign when_BytecodeFetchStage_l277 = ((! io_zf) && (! io_nf));
  assign when_BytecodeFetchStage_l280 = (io_zf || io_nf);
  assign when_BytecodeFetchStage_l286 = (! io_eq);
  assign when_BytecodeFetchStage_l292 = (! io_lt);
  assign when_BytecodeFetchStage_l295 = ((! io_eq) && (! io_lt));
  assign when_BytecodeFetchStage_l298 = (io_eq || io_lt);
  assign excPendImmediate = (excPend || io_exc);
  assign doAckExc = (excPendImmediate && io_jfetch);
  assign doAckIrq = (((intPend && io_ena) && (! excPendImmediate)) && io_jfetch);
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
      if(when_BytecodeFetchStage_l190) begin
        if(io_jpc_wr) begin
          jpc <= io_din[11 : 0];
        end else begin
          if(jmp) begin
            jpc <= jmp_addr;
          end else begin
            if(when_BytecodeFetchStage_l197) begin
              jpc <= (jpc + 12'h001);
            end
          end
        end
      end
      if(when_BytecodeFetchStage_l214) begin
        jopd[7 : 0] <= jbcData;
        if(io_jopdfetch) begin
          jopd[15 : 8] <= jopd[7 : 0];
        end
      end
      if(when_BytecodeFetchStage_l235) begin
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

module DoubleComputeUnit (
  input  wire [31:0]   io_operands_0,
  input  wire [31:0]   io_operands_1,
  input  wire [31:0]   io_operands_2,
  input  wire [31:0]   io_operands_3,
  input  wire [3:0]    io_op,
  input  wire          io_start,
  output wire [31:0]   io_resultLo,
  output wire [31:0]   io_resultHi,
  output wire [1:0]    io_resultCount,
  output wire          io_busy,
  input  wire          clk,
  input  wire          reset
);
  localparam State_3_IDLE = 5'd0;
  localparam State_3_UNPACK = 5'd1;
  localparam State_3_ADD_ALIGN = 5'd2;
  localparam State_3_ADD_EXEC = 5'd3;
  localparam State_3_ADD_SELECT = 5'd4;
  localparam State_3_ADD_NORM = 5'd5;
  localparam State_3_MUL_STEP1 = 5'd6;
  localparam State_3_MUL_STEP2 = 5'd7;
  localparam State_3_MUL_NORM = 5'd8;
  localparam State_3_DIV_INIT = 5'd9;
  localparam State_3_DIV_ITER = 5'd10;
  localparam State_3_I2D_EXEC = 5'd11;
  localparam State_3_D2I_EXEC = 5'd12;
  localparam State_3_L2D_EXEC = 5'd13;
  localparam State_3_L2D_SHIFT = 5'd14;
  localparam State_3_D2L_EXEC = 5'd15;
  localparam State_3_F2D_EXEC = 5'd16;
  localparam State_3_D2F_EXEC = 5'd17;
  localparam State_3_DCMP_EXEC = 5'd18;
  localparam State_3_ROUND = 5'd19;
  localparam State_3_DONE = 5'd20;

  wire       [12:0]   _zz_aExp_1;
  wire       [12:0]   _zz_aExp_2;
  wire       [12:0]   _zz_bExp_1;
  wire       [12:0]   _zz_bExp_2;
  wire       [31:0]   _zz_resultReg_6;
  wire       [31:0]   _zz_resultReg_7;
  wire       [31:0]   _zz_resultReg_8;
  wire       [7:0]    _zz_resultReg_9;
  wire       [10:0]   _zz_resultReg_10;
  wire       [63:0]   CANONICAL_NAN;
  wire       [63:0]   POS_INF;
  wire       [63:0]   POS_ZERO;
  wire       [31:0]   SP_CANONICAL_NAN;
  wire       [31:0]   SP_POS_INF;
  reg        [4:0]    state_6;
  reg                 aSign;
  reg        [12:0]   aExp;
  reg        [54:0]   aMant;
  reg                 aZero;
  reg                 aInf;
  reg                 aNaN;
  reg                 bSign;
  reg        [12:0]   bExp;
  reg        [54:0]   bMant;
  reg                 bZero;
  reg                 bInf;
  reg                 bNaN;
  wire                resSign;
  wire       [12:0]   resExp;
  wire       [54:0]   resMant;
  reg                 sticky;
  reg        [63:0]   resultReg;
  reg        [3:0]    opcodeReg;
  reg        [63:0]   opaReg;
  reg        [63:0]   opbReg;
  reg                 d2fMode;
  wire       [56:0]   addMantA;
  wire       [56:0]   addMantB;
  wire                addIsSubOp;
  wire       [56:0]   addDiffAB;
  wire       [56:0]   addDiffBA;
  wire       [56:0]   addSumAB;
  wire                addAgeB;
  wire       [105:0]  mulProdHi;
  wire       [56:0]   divRemainder;
  wire       [56:0]   divDivisor;
  wire       [54:0]   divQuotient;
  wire       [5:0]    divCount;
  wire       [63:0]   l2dAbsVal;
  wire       [6:0]    l2dLz;
  wire       [10:0]   _zz_aExp;
  wire       [51:0]   _zz_aMant;
  wire                when_DoubleComputeUnit_l123;
  wire                when_DoubleComputeUnit_l126;
  wire       [10:0]   _zz_bExp;
  wire       [51:0]   _zz_bMant;
  wire                when_DoubleComputeUnit_l123_1;
  wire                when_DoubleComputeUnit_l126_1;
  wire                when_DoubleComputeUnit_l840;
  wire       [23:0]   _zz_when_DoubleComputeUnit_l848;
  reg        [24:0]   _zz_when_DoubleComputeUnit_l848_1;
  reg        [12:0]   _zz_resultReg;
  reg        [22:0]   _zz_resultReg_1;
  wire                when_DoubleComputeUnit_l848;
  wire       [12:0]   _zz_resultReg_2;
  wire                when_DoubleComputeUnit_l857;
  wire                when_DoubleComputeUnit_l859;
  wire                when_DoubleComputeUnit_l876;
  wire       [52:0]   _zz_when_DoubleComputeUnit_l884;
  reg        [53:0]   _zz_when_DoubleComputeUnit_l884_1;
  reg        [12:0]   _zz_resultReg_3;
  reg        [51:0]   _zz_resultReg_4;
  wire                when_DoubleComputeUnit_l884;
  wire       [12:0]   _zz_resultReg_5;
  wire                when_DoubleComputeUnit_l893;
  wire                when_DoubleComputeUnit_l895;
  `ifndef SYNTHESIS
  reg [79:0] state_6_string;
  `endif


  assign _zz_aExp_1 = _zz_aExp_2;
  assign _zz_aExp_2 = {2'd0, _zz_aExp};
  assign _zz_bExp_1 = _zz_bExp_2;
  assign _zz_bExp_2 = {2'd0, _zz_bExp};
  assign _zz_resultReg_6 = {{resSign,8'hff},23'h0};
  assign _zz_resultReg_7 = {resSign,31'h0};
  assign _zz_resultReg_8 = {{resSign,_zz_resultReg_9},_zz_resultReg_1};
  assign _zz_resultReg_9 = _zz_resultReg_2[7 : 0];
  assign _zz_resultReg_10 = _zz_resultReg_5[10 : 0];
  `ifndef SYNTHESIS
  always @(*) begin
    case(state_6)
      State_3_IDLE : state_6_string = "IDLE      ";
      State_3_UNPACK : state_6_string = "UNPACK    ";
      State_3_ADD_ALIGN : state_6_string = "ADD_ALIGN ";
      State_3_ADD_EXEC : state_6_string = "ADD_EXEC  ";
      State_3_ADD_SELECT : state_6_string = "ADD_SELECT";
      State_3_ADD_NORM : state_6_string = "ADD_NORM  ";
      State_3_MUL_STEP1 : state_6_string = "MUL_STEP1 ";
      State_3_MUL_STEP2 : state_6_string = "MUL_STEP2 ";
      State_3_MUL_NORM : state_6_string = "MUL_NORM  ";
      State_3_DIV_INIT : state_6_string = "DIV_INIT  ";
      State_3_DIV_ITER : state_6_string = "DIV_ITER  ";
      State_3_I2D_EXEC : state_6_string = "I2D_EXEC  ";
      State_3_D2I_EXEC : state_6_string = "D2I_EXEC  ";
      State_3_L2D_EXEC : state_6_string = "L2D_EXEC  ";
      State_3_L2D_SHIFT : state_6_string = "L2D_SHIFT ";
      State_3_D2L_EXEC : state_6_string = "D2L_EXEC  ";
      State_3_F2D_EXEC : state_6_string = "F2D_EXEC  ";
      State_3_D2F_EXEC : state_6_string = "D2F_EXEC  ";
      State_3_DCMP_EXEC : state_6_string = "DCMP_EXEC ";
      State_3_ROUND : state_6_string = "ROUND     ";
      State_3_DONE : state_6_string = "DONE      ";
      default : state_6_string = "??????????";
    endcase
  end
  `endif

  assign CANONICAL_NAN = 64'h7ff8000000000000;
  assign POS_INF = 64'h7ff0000000000000;
  assign POS_ZERO = 64'h0;
  assign SP_CANONICAL_NAN = 32'h7fc00000;
  assign SP_POS_INF = 32'h7f800000;
  assign resSign = 1'b0;
  assign resExp = 13'h0;
  assign resMant = 55'h0;
  assign io_resultLo = resultReg[31 : 0];
  assign io_resultHi = resultReg[63 : 32];
  assign io_busy = (state_6 != State_3_IDLE);
  assign io_resultCount = (((((opcodeReg == 4'b1001) || (opcodeReg == 4'b0111)) || (opcodeReg == 4'b0100)) || (opcodeReg == 4'b0101)) ? 2'b01 : 2'b10);
  assign addMantA = 57'h0;
  assign addMantB = 57'h0;
  assign addIsSubOp = 1'b0;
  assign addDiffAB = 57'h0;
  assign addDiffBA = 57'h0;
  assign addSumAB = 57'h0;
  assign addAgeB = 1'b0;
  assign mulProdHi = 106'h0;
  assign divRemainder = 57'h0;
  assign divDivisor = 57'h0;
  assign divQuotient = 55'h0;
  assign divCount = 6'h0;
  assign l2dAbsVal = 64'h0;
  assign l2dLz = 7'h0;
  assign _zz_aExp = opaReg[62 : 52];
  assign _zz_aMant = opaReg[51 : 0];
  assign when_DoubleComputeUnit_l123 = (_zz_aExp == 11'h0);
  assign when_DoubleComputeUnit_l126 = (_zz_aExp == 11'h7ff);
  assign _zz_bExp = opbReg[62 : 52];
  assign _zz_bMant = opbReg[51 : 0];
  assign when_DoubleComputeUnit_l123_1 = (_zz_bExp == 11'h0);
  assign when_DoubleComputeUnit_l126_1 = (_zz_bExp == 11'h7ff);
  assign when_DoubleComputeUnit_l840 = (resMant[30] && ((resMant[29] || sticky) || resMant[31]));
  assign _zz_when_DoubleComputeUnit_l848 = resMant[54 : 31];
  always @(*) begin
    if(when_DoubleComputeUnit_l840) begin
      _zz_when_DoubleComputeUnit_l848_1 = ({1'b0,_zz_when_DoubleComputeUnit_l848} + 25'h0000001);
    end else begin
      _zz_when_DoubleComputeUnit_l848_1 = {1'b0,_zz_when_DoubleComputeUnit_l848};
    end
  end

  assign when_DoubleComputeUnit_l848 = _zz_when_DoubleComputeUnit_l848_1[24];
  always @(*) begin
    if(when_DoubleComputeUnit_l848) begin
      _zz_resultReg = ($signed(resExp) + $signed(13'h0001));
    end else begin
      _zz_resultReg = resExp;
    end
  end

  always @(*) begin
    if(when_DoubleComputeUnit_l848) begin
      _zz_resultReg_1 = _zz_when_DoubleComputeUnit_l848_1[23 : 1];
    end else begin
      _zz_resultReg_1 = _zz_when_DoubleComputeUnit_l848_1[22 : 0];
    end
  end

  assign _zz_resultReg_2 = ($signed(_zz_resultReg) + $signed(13'h007f));
  assign when_DoubleComputeUnit_l857 = ($signed(13'h00ff) <= $signed(_zz_resultReg_2));
  assign when_DoubleComputeUnit_l859 = ($signed(_zz_resultReg_2) <= $signed(13'h0));
  assign when_DoubleComputeUnit_l876 = (resMant[1] && ((resMant[0] || sticky) || resMant[2]));
  assign _zz_when_DoubleComputeUnit_l884 = resMant[54 : 2];
  always @(*) begin
    if(when_DoubleComputeUnit_l876) begin
      _zz_when_DoubleComputeUnit_l884_1 = ({1'b0,_zz_when_DoubleComputeUnit_l884} + 54'h00000000000001);
    end else begin
      _zz_when_DoubleComputeUnit_l884_1 = {1'b0,_zz_when_DoubleComputeUnit_l884};
    end
  end

  assign when_DoubleComputeUnit_l884 = _zz_when_DoubleComputeUnit_l884_1[53];
  always @(*) begin
    if(when_DoubleComputeUnit_l884) begin
      _zz_resultReg_3 = ($signed(resExp) + $signed(13'h0001));
    end else begin
      _zz_resultReg_3 = resExp;
    end
  end

  always @(*) begin
    if(when_DoubleComputeUnit_l884) begin
      _zz_resultReg_4 = _zz_when_DoubleComputeUnit_l884_1[52 : 1];
    end else begin
      _zz_resultReg_4 = _zz_when_DoubleComputeUnit_l884_1[51 : 0];
    end
  end

  assign _zz_resultReg_5 = ($signed(_zz_resultReg_3) + $signed(13'h03ff));
  assign when_DoubleComputeUnit_l893 = ($signed(13'h07ff) <= $signed(_zz_resultReg_5));
  assign when_DoubleComputeUnit_l895 = ($signed(_zz_resultReg_5) <= $signed(13'h0));
  always @(posedge clk or posedge reset) begin
    if(reset) begin
      state_6 <= State_3_IDLE;
      aSign <= 1'b0;
      aExp <= 13'h0;
      aMant <= 55'h0;
      aZero <= 1'b0;
      aInf <= 1'b0;
      aNaN <= 1'b0;
      bSign <= 1'b0;
      bExp <= 13'h0;
      bMant <= 55'h0;
      bZero <= 1'b0;
      bInf <= 1'b0;
      bNaN <= 1'b0;
      sticky <= 1'b0;
      resultReg <= 64'h0;
      opcodeReg <= 4'b0000;
      opaReg <= 64'h0;
      opbReg <= 64'h0;
      d2fMode <= 1'b0;
    end else begin
      case(state_6)
        State_3_IDLE : begin
          if(io_start) begin
            sticky <= 1'b0;
            opcodeReg <= io_op;
            opaReg <= {io_operands_3,io_operands_2};
            opbReg <= {io_operands_1,io_operands_0};
            state_6 <= State_3_UNPACK;
          end
        end
        State_3_UNPACK : begin
          aSign <= opaReg[63];
          if(when_DoubleComputeUnit_l123) begin
            aExp <= 13'h0;
            aMant <= 55'h0;
            aZero <= 1'b1;
            aInf <= 1'b0;
            aNaN <= 1'b0;
          end else begin
            if(when_DoubleComputeUnit_l126) begin
              aExp <= 13'h07ff;
              aMant <= {{1'b1,_zz_aMant},2'b00};
              aZero <= 1'b0;
              aInf <= (_zz_aMant == 52'h0);
              aNaN <= (_zz_aMant != 52'h0);
            end else begin
              aExp <= ($signed(_zz_aExp_1) - $signed(13'h03ff));
              aMant <= {{1'b1,_zz_aMant},2'b00};
              aZero <= 1'b0;
              aInf <= 1'b0;
              aNaN <= 1'b0;
            end
          end
          bSign <= opbReg[63];
          if(when_DoubleComputeUnit_l123_1) begin
            bExp <= 13'h0;
            bMant <= 55'h0;
            bZero <= 1'b1;
            bInf <= 1'b0;
            bNaN <= 1'b0;
          end else begin
            if(when_DoubleComputeUnit_l126_1) begin
              bExp <= 13'h07ff;
              bMant <= {{1'b1,_zz_bMant},2'b00};
              bZero <= 1'b0;
              bInf <= (_zz_bMant == 52'h0);
              bNaN <= (_zz_bMant != 52'h0);
            end else begin
              bExp <= ($signed(_zz_bExp_1) - $signed(13'h03ff));
              bMant <= {{1'b1,_zz_bMant},2'b00};
              bZero <= 1'b0;
              bInf <= 1'b0;
              bNaN <= 1'b0;
            end
          end
          state_6 <= State_3_DONE;
          resultReg <= 64'h0;
        end
        State_3_ROUND : begin
          if(d2fMode) begin
            if(when_DoubleComputeUnit_l857) begin
              resultReg <= {32'd0, _zz_resultReg_6};
            end else begin
              if(when_DoubleComputeUnit_l859) begin
                resultReg <= {32'd0, _zz_resultReg_7};
              end else begin
                resultReg <= {32'd0, _zz_resultReg_8};
              end
            end
            d2fMode <= 1'b0;
            state_6 <= State_3_DONE;
          end else begin
            if(when_DoubleComputeUnit_l893) begin
              resultReg <= {{resSign,11'h7ff},52'h0};
            end else begin
              if(when_DoubleComputeUnit_l895) begin
                resultReg <= {resSign,63'h0};
              end else begin
                resultReg <= {{resSign,_zz_resultReg_10},_zz_resultReg_4};
              end
            end
            state_6 <= State_3_DONE;
          end
        end
        State_3_DONE : begin
          state_6 <= State_3_IDLE;
        end
        default : begin
        end
      endcase
    end
  end


endmodule

module LongComputeUnit (
  input  wire [31:0]   io_operands_0,
  input  wire [31:0]   io_operands_1,
  input  wire [31:0]   io_operands_2,
  input  wire [31:0]   io_operands_3,
  input  wire [3:0]    io_op,
  input  wire          io_start,
  output wire [31:0]   io_resultLo,
  output wire [31:0]   io_resultHi,
  output wire [1:0]    io_resultCount,
  output wire          io_busy,
  input  wire          clk,
  input  wire          reset
);
  localparam State_2_IDLE = 4'd0;
  localparam State_2_LADD_EXEC = 4'd1;
  localparam State_2_LCMP_EXEC = 4'd2;
  localparam State_2_MUL_EXEC = 4'd3;
  localparam State_2_DIV_SETUP = 4'd4;
  localparam State_2_DIV_EXEC = 4'd5;
  localparam State_2_DIV_DONE = 4'd6;
  localparam State_2_SHIFT_EXEC = 4'd7;
  localparam State_2_DONE = 4'd8;

  reg        [3:0]    state_6;
  reg        [63:0]   resultReg;
  reg        [3:0]    opcodeReg;
  reg        [63:0]   opaReg;
  reg        [63:0]   opbReg;
  wire       [63:0]   mulA;
  wire       [63:0]   mulB;
  wire       [63:0]   mulP;
  wire       [5:0]    mulCount;
  wire       [63:0]   divDividend;
  wire       [63:0]   divDivisor;
  wire       [64:0]   divRemainder;
  wire       [63:0]   divQuotient;
  wire                divQuotSign;
  wire                divRemSign;
  wire       [6:0]    divCount;
  wire                when_LongComputeUnit_l108;
  wire                when_LongComputeUnit_l131;
  wire                when_LongComputeUnit_l151;
  wire       [63:0]   _zz_when_LongComputeUnit_l165;
  wire       [63:0]   _zz_when_LongComputeUnit_l165_1;
  wire                when_LongComputeUnit_l165;
  wire                when_LongComputeUnit_l167;
  `ifndef SYNTHESIS
  reg [79:0] state_6_string;
  `endif


  `ifndef SYNTHESIS
  always @(*) begin
    case(state_6)
      State_2_IDLE : state_6_string = "IDLE      ";
      State_2_LADD_EXEC : state_6_string = "LADD_EXEC ";
      State_2_LCMP_EXEC : state_6_string = "LCMP_EXEC ";
      State_2_MUL_EXEC : state_6_string = "MUL_EXEC  ";
      State_2_DIV_SETUP : state_6_string = "DIV_SETUP ";
      State_2_DIV_EXEC : state_6_string = "DIV_EXEC  ";
      State_2_DIV_DONE : state_6_string = "DIV_DONE  ";
      State_2_SHIFT_EXEC : state_6_string = "SHIFT_EXEC";
      State_2_DONE : state_6_string = "DONE      ";
      default : state_6_string = "??????????";
    endcase
  end
  `endif

  assign mulA = 64'h0;
  assign mulB = 64'h0;
  assign mulP = 64'h0;
  assign mulCount = 6'h0;
  assign divDividend = 64'h0;
  assign divDivisor = 64'h0;
  assign divRemainder = 65'h0;
  assign divQuotient = 64'h0;
  assign divQuotSign = 1'b0;
  assign divRemSign = 1'b0;
  assign divCount = 7'h0;
  assign io_resultLo = resultReg[31 : 0];
  assign io_resultHi = resultReg[63 : 32];
  assign io_busy = (state_6 != State_2_IDLE);
  assign io_resultCount = ((opcodeReg == 4'b0101) ? 2'b01 : 2'b10);
  assign when_LongComputeUnit_l108 = ((io_op == 4'b0000) || (io_op == 4'b0001));
  assign when_LongComputeUnit_l131 = (io_op == 4'b0101);
  assign when_LongComputeUnit_l151 = (opcodeReg == 4'b0000);
  assign _zz_when_LongComputeUnit_l165 = opaReg;
  assign _zz_when_LongComputeUnit_l165_1 = opbReg;
  assign when_LongComputeUnit_l165 = ($signed(_zz_when_LongComputeUnit_l165_1) < $signed(_zz_when_LongComputeUnit_l165));
  assign when_LongComputeUnit_l167 = ($signed(_zz_when_LongComputeUnit_l165) < $signed(_zz_when_LongComputeUnit_l165_1));
  always @(posedge clk or posedge reset) begin
    if(reset) begin
      state_6 <= State_2_IDLE;
      resultReg <= 64'h0;
      opcodeReg <= 4'b0000;
      opaReg <= 64'h0;
      opbReg <= 64'h0;
    end else begin
      case(state_6)
        State_2_IDLE : begin
          if(io_start) begin
            opcodeReg <= io_op;
            opaReg <= {io_operands_3,io_operands_2};
            opbReg <= {io_operands_1,io_operands_0};
            if(when_LongComputeUnit_l108) begin
              state_6 <= State_2_LADD_EXEC;
            end
            if(when_LongComputeUnit_l131) begin
              state_6 <= State_2_LCMP_EXEC;
            end
          end
        end
        State_2_LADD_EXEC : begin
          if(when_LongComputeUnit_l151) begin
            resultReg <= (opaReg + opbReg);
          end else begin
            resultReg <= (opaReg - opbReg);
          end
          state_6 <= State_2_DONE;
        end
        State_2_LCMP_EXEC : begin
          if(when_LongComputeUnit_l165) begin
            resultReg <= 64'h0000000000000001;
          end else begin
            if(when_LongComputeUnit_l167) begin
              resultReg <= 64'hffffffffffffffff;
            end else begin
              resultReg <= 64'h0;
            end
          end
          state_6 <= State_2_DONE;
        end
        State_2_DONE : begin
          state_6 <= State_2_IDLE;
        end
        default : begin
        end
      endcase
    end
  end


endmodule

module FloatComputeUnit (
  input  wire [31:0]   io_operands_0,
  input  wire [31:0]   io_operands_1,
  input  wire [31:0]   io_operands_2,
  input  wire [31:0]   io_operands_3,
  input  wire [3:0]    io_op,
  input  wire          io_start,
  output wire [31:0]   io_resultLo,
  output wire [31:0]   io_resultHi,
  output wire [1:0]    io_resultCount,
  output wire          io_busy,
  input  wire          clk,
  input  wire          reset
);
  localparam State_1_IDLE = 5'd0;
  localparam State_1_UNPACK = 5'd1;
  localparam State_1_ADD_ALIGN = 5'd2;
  localparam State_1_ADD_SHIFT = 5'd3;
  localparam State_1_ADD_EXEC = 5'd4;
  localparam State_1_ADD_NORM = 5'd5;
  localparam State_1_MUL_STEP1 = 5'd6;
  localparam State_1_MUL_STEP2 = 5'd7;
  localparam State_1_MUL_NORM = 5'd8;
  localparam State_1_DIV_INIT = 5'd9;
  localparam State_1_DIV_ITER = 5'd10;
  localparam State_1_I2F_EXEC = 5'd11;
  localparam State_1_I2F_SHIFT = 5'd12;
  localparam State_1_F2I_EXEC = 5'd13;
  localparam State_1_FCMP_EXEC = 5'd14;
  localparam State_1_ROUND = 5'd15;
  localparam State_1_DONE = 5'd16;

  wire       [9:0]    _zz_aExp_1;
  wire       [9:0]    _zz_aExp_2;
  wire       [9:0]    _zz_bExp_1;
  wire       [9:0]    _zz_bExp_2;
  wire       [31:0]   _zz_resultReg_3;
  wire       [31:0]   _zz_resultReg_4;
  wire       [31:0]   _zz_resultReg_5;
  wire       [7:0]    _zz_resultReg_6;
  wire       [31:0]   CANONICAL_NAN;
  wire       [31:0]   POS_INF;
  wire       [31:0]   POS_ZERO;
  reg        [4:0]    state_6;
  reg                 aSign;
  reg        [9:0]    aExp;
  reg        [25:0]   aMant;
  reg                 aZero;
  reg                 aInf;
  reg                 aNaN;
  reg                 bSign;
  reg        [9:0]    bExp;
  reg        [25:0]   bMant;
  reg                 bZero;
  reg                 bInf;
  reg                 bNaN;
  wire                resSign;
  wire       [9:0]    resExp;
  wire       [25:0]   resMant;
  reg                 sticky;
  reg        [63:0]   resultReg;
  reg        [3:0]    opcodeReg;
  reg        [31:0]   opaReg;
  reg        [31:0]   opbReg;
  wire       [27:0]   addMantA;
  wire       [27:0]   addMantB;
  wire                addIsSubOp;
  wire       [27:0]   addShiftInput;
  wire       [4:0]    addShAmt;
  wire                addFlushShift;
  wire                addFlushSticky;
  wire       [47:0]   mulProdHi;
  wire       [27:0]   divRemainder;
  wire       [27:0]   divDivisor;
  wire       [25:0]   divQuotient;
  wire       [4:0]    divCount;
  wire       [31:0]   i2fAbsVal;
  wire       [5:0]    i2fLz;
  wire                when_FloatComputeUnit_l205;
  wire       [7:0]    _zz_aExp;
  wire       [22:0]   _zz_aMant;
  wire                when_FloatComputeUnit_l112;
  wire                when_FloatComputeUnit_l116;
  wire       [7:0]    _zz_bExp;
  wire       [22:0]   _zz_bMant;
  wire                when_FloatComputeUnit_l112_1;
  wire                when_FloatComputeUnit_l116_1;
  wire                when_FloatComputeUnit_l670;
  wire       [23:0]   _zz_when_FloatComputeUnit_l679;
  reg        [24:0]   _zz_when_FloatComputeUnit_l679_1;
  reg        [9:0]    _zz_resultReg;
  reg        [22:0]   _zz_resultReg_1;
  wire                when_FloatComputeUnit_l679;
  wire       [9:0]    _zz_resultReg_2;
  wire                when_FloatComputeUnit_l690;
  wire                when_FloatComputeUnit_l692;
  `ifndef SYNTHESIS
  reg [71:0] state_6_string;
  `endif


  assign _zz_aExp_1 = _zz_aExp_2;
  assign _zz_aExp_2 = {2'd0, _zz_aExp};
  assign _zz_bExp_1 = _zz_bExp_2;
  assign _zz_bExp_2 = {2'd0, _zz_bExp};
  assign _zz_resultReg_3 = {{resSign,8'hff},23'h0};
  assign _zz_resultReg_4 = {resSign,31'h0};
  assign _zz_resultReg_5 = {{resSign,_zz_resultReg_6},_zz_resultReg_1};
  assign _zz_resultReg_6 = _zz_resultReg_2[7 : 0];
  `ifndef SYNTHESIS
  always @(*) begin
    case(state_6)
      State_1_IDLE : state_6_string = "IDLE     ";
      State_1_UNPACK : state_6_string = "UNPACK   ";
      State_1_ADD_ALIGN : state_6_string = "ADD_ALIGN";
      State_1_ADD_SHIFT : state_6_string = "ADD_SHIFT";
      State_1_ADD_EXEC : state_6_string = "ADD_EXEC ";
      State_1_ADD_NORM : state_6_string = "ADD_NORM ";
      State_1_MUL_STEP1 : state_6_string = "MUL_STEP1";
      State_1_MUL_STEP2 : state_6_string = "MUL_STEP2";
      State_1_MUL_NORM : state_6_string = "MUL_NORM ";
      State_1_DIV_INIT : state_6_string = "DIV_INIT ";
      State_1_DIV_ITER : state_6_string = "DIV_ITER ";
      State_1_I2F_EXEC : state_6_string = "I2F_EXEC ";
      State_1_I2F_SHIFT : state_6_string = "I2F_SHIFT";
      State_1_F2I_EXEC : state_6_string = "F2I_EXEC ";
      State_1_FCMP_EXEC : state_6_string = "FCMP_EXEC";
      State_1_ROUND : state_6_string = "ROUND    ";
      State_1_DONE : state_6_string = "DONE     ";
      default : state_6_string = "?????????";
    endcase
  end
  `endif

  assign CANONICAL_NAN = 32'h7fc00000;
  assign POS_INF = 32'h7f800000;
  assign POS_ZERO = 32'h0;
  assign resSign = 1'b0;
  assign resExp = 10'h0;
  assign resMant = 26'h0;
  assign io_resultLo = resultReg[31 : 0];
  assign io_resultHi = resultReg[63 : 32];
  assign io_busy = (state_6 != State_1_IDLE);
  assign io_resultCount = 2'b01;
  assign addMantA = 28'h0;
  assign addMantB = 28'h0;
  assign addIsSubOp = 1'b0;
  assign addShiftInput = 28'h0;
  assign addShAmt = 5'h0;
  assign addFlushShift = 1'b0;
  assign addFlushSticky = 1'b0;
  assign mulProdHi = 48'h0;
  assign divRemainder = 28'h0;
  assign divDivisor = 28'h0;
  assign divQuotient = 26'h0;
  assign divCount = 5'h0;
  assign i2fAbsVal = 32'h0;
  assign i2fLz = 6'h0;
  assign when_FloatComputeUnit_l205 = ((((io_op == 4'b0001) || (io_op == 4'b0011)) || (io_op == 4'b0100)) || (io_op == 4'b0101));
  assign _zz_aExp = opaReg[30 : 23];
  assign _zz_aMant = opaReg[22 : 0];
  assign when_FloatComputeUnit_l112 = (_zz_aExp == 8'h0);
  assign when_FloatComputeUnit_l116 = (_zz_aExp == 8'hff);
  assign _zz_bExp = opbReg[30 : 23];
  assign _zz_bMant = opbReg[22 : 0];
  assign when_FloatComputeUnit_l112_1 = (_zz_bExp == 8'h0);
  assign when_FloatComputeUnit_l116_1 = (_zz_bExp == 8'hff);
  assign when_FloatComputeUnit_l670 = (resMant[1] && ((resMant[0] || sticky) || resMant[2]));
  assign _zz_when_FloatComputeUnit_l679 = resMant[25 : 2];
  always @(*) begin
    if(when_FloatComputeUnit_l670) begin
      _zz_when_FloatComputeUnit_l679_1 = ({1'b0,_zz_when_FloatComputeUnit_l679} + 25'h0000001);
    end else begin
      _zz_when_FloatComputeUnit_l679_1 = {1'b0,_zz_when_FloatComputeUnit_l679};
    end
  end

  assign when_FloatComputeUnit_l679 = _zz_when_FloatComputeUnit_l679_1[24];
  always @(*) begin
    if(when_FloatComputeUnit_l679) begin
      _zz_resultReg = ($signed(resExp) + $signed(10'h001));
    end else begin
      _zz_resultReg = resExp;
    end
  end

  always @(*) begin
    if(when_FloatComputeUnit_l679) begin
      _zz_resultReg_1 = _zz_when_FloatComputeUnit_l679_1[23 : 1];
    end else begin
      _zz_resultReg_1 = _zz_when_FloatComputeUnit_l679_1[22 : 0];
    end
  end

  assign _zz_resultReg_2 = ($signed(_zz_resultReg) + $signed(10'h07f));
  assign when_FloatComputeUnit_l690 = ($signed(10'h0ff) <= $signed(_zz_resultReg_2));
  assign when_FloatComputeUnit_l692 = ($signed(_zz_resultReg_2) <= $signed(10'h0));
  always @(posedge clk or posedge reset) begin
    if(reset) begin
      state_6 <= State_1_IDLE;
      aSign <= 1'b0;
      aExp <= 10'h0;
      aMant <= 26'h0;
      aZero <= 1'b0;
      aInf <= 1'b0;
      aNaN <= 1'b0;
      bSign <= 1'b0;
      bExp <= 10'h0;
      bMant <= 26'h0;
      bZero <= 1'b0;
      bInf <= 1'b0;
      bNaN <= 1'b0;
      sticky <= 1'b0;
      resultReg <= 64'h0;
      opcodeReg <= 4'b0000;
      opaReg <= 32'h0;
      opbReg <= 32'h0;
    end else begin
      case(state_6)
        State_1_IDLE : begin
          if(io_start) begin
            opaReg <= io_operands_0;
            opbReg <= io_operands_1;
            sticky <= 1'b0;
            if(when_FloatComputeUnit_l205) begin
              opaReg <= io_operands_1;
              opbReg <= io_operands_0;
            end
            opcodeReg <= io_op;
          end
        end
        State_1_UNPACK : begin
          aSign <= opaReg[31];
          if(when_FloatComputeUnit_l112) begin
            aExp <= 10'h0;
            aMant <= 26'h0;
            aZero <= 1'b1;
            aInf <= 1'b0;
            aNaN <= 1'b0;
          end else begin
            if(when_FloatComputeUnit_l116) begin
              aExp <= 10'h0ff;
              aMant <= {{1'b1,_zz_aMant},2'b00};
              aZero <= 1'b0;
              aInf <= (_zz_aMant == 23'h0);
              aNaN <= (_zz_aMant != 23'h0);
            end else begin
              aExp <= ($signed(_zz_aExp_1) - $signed(10'h07f));
              aMant <= {{1'b1,_zz_aMant},2'b00};
              aZero <= 1'b0;
              aInf <= 1'b0;
              aNaN <= 1'b0;
            end
          end
          bSign <= opbReg[31];
          if(when_FloatComputeUnit_l112_1) begin
            bExp <= 10'h0;
            bMant <= 26'h0;
            bZero <= 1'b1;
            bInf <= 1'b0;
            bNaN <= 1'b0;
          end else begin
            if(when_FloatComputeUnit_l116_1) begin
              bExp <= 10'h0ff;
              bMant <= {{1'b1,_zz_bMant},2'b00};
              bZero <= 1'b0;
              bInf <= (_zz_bMant == 23'h0);
              bNaN <= (_zz_bMant != 23'h0);
            end else begin
              bExp <= ($signed(_zz_bExp_1) - $signed(10'h07f));
              bMant <= {{1'b1,_zz_bMant},2'b00};
              bZero <= 1'b0;
              bInf <= 1'b0;
              bNaN <= 1'b0;
            end
          end
          state_6 <= State_1_DONE;
          resultReg <= 64'h0;
        end
        State_1_ROUND : begin
          if(when_FloatComputeUnit_l690) begin
            resultReg <= {32'd0, _zz_resultReg_3};
          end else begin
            if(when_FloatComputeUnit_l692) begin
              resultReg <= {32'd0, _zz_resultReg_4};
            end else begin
              resultReg <= {32'd0, _zz_resultReg_5};
            end
          end
          state_6 <= State_1_DONE;
        end
        State_1_DONE : begin
          state_6 <= State_1_IDLE;
        end
        default : begin
        end
      endcase
    end
  end


endmodule

module IntegerComputeUnit (
  input  wire [31:0]   io_operands_0,
  input  wire [31:0]   io_operands_1,
  input  wire [31:0]   io_operands_2,
  input  wire [31:0]   io_operands_3,
  input  wire [3:0]    io_op,
  input  wire          io_start,
  output wire [31:0]   io_resultLo,
  output wire [31:0]   io_resultHi,
  output wire [1:0]    io_resultCount,
  output wire          io_busy,
  input  wire          clk,
  input  wire          reset
);
  localparam State_IDLE = 3'd0;
  localparam State_MUL_EXEC = 3'd1;
  localparam State_DIV_SETUP = 3'd2;
  localparam State_DIV_EXEC = 3'd3;
  localparam State_DIV_DONE = 3'd4;
  localparam State_DONE = 3'd5;

  reg        [2:0]    state_6;
  wire       [63:0]   resultReg;
  reg        [1:0]    opcodeReg;
  reg        [31:0]   opaReg;
  reg        [31:0]   opbReg;
  wire       [63:0]   mulA;
  wire       [31:0]   mulB;
  wire       [63:0]   mulP;
  wire       [4:0]    mulCount;
  wire                mulWide;
  wire       [31:0]   divDividend;
  wire       [31:0]   divDivisor;
  wire       [32:0]   divRemainder;
  wire       [31:0]   divQuotient;
  wire                divQuotSign;
  wire                divRemSign;
  wire       [5:0]    divCount;
  `ifndef SYNTHESIS
  reg [71:0] state_6_string;
  `endif


  `ifndef SYNTHESIS
  always @(*) begin
    case(state_6)
      State_IDLE : state_6_string = "IDLE     ";
      State_MUL_EXEC : state_6_string = "MUL_EXEC ";
      State_DIV_SETUP : state_6_string = "DIV_SETUP";
      State_DIV_EXEC : state_6_string = "DIV_EXEC ";
      State_DIV_DONE : state_6_string = "DIV_DONE ";
      State_DONE : state_6_string = "DONE     ";
      default : state_6_string = "?????????";
    endcase
  end
  `endif

  assign resultReg = 64'h0;
  assign mulA = 64'h0;
  assign mulB = 32'h0;
  assign mulP = 64'h0;
  assign mulCount = 5'h0;
  assign mulWide = 1'b0;
  assign divDividend = 32'h0;
  assign divDivisor = 32'h0;
  assign divRemainder = 33'h0;
  assign divQuotient = 32'h0;
  assign divQuotSign = 1'b0;
  assign divRemSign = 1'b0;
  assign divCount = 6'h0;
  assign io_resultLo = resultReg[31 : 0];
  assign io_resultHi = resultReg[63 : 32];
  assign io_busy = (state_6 != State_IDLE);
  assign io_resultCount = (mulWide ? 2'b10 : 2'b01);
  always @(posedge clk or posedge reset) begin
    if(reset) begin
      state_6 <= State_IDLE;
      opcodeReg <= 2'b00;
      opaReg <= 32'h0;
      opbReg <= 32'h0;
    end else begin
      case(state_6)
        State_IDLE : begin
          if(io_start) begin
            opaReg <= io_operands_0;
            opbReg <= io_operands_1;
            case(io_op)
              4'b0000 : begin
                opcodeReg <= 2'b00;
              end
              4'b0001 : begin
                opcodeReg <= 2'b01;
              end
              4'b0010 : begin
                opcodeReg <= 2'b10;
              end
              default : begin
              end
            endcase
          end
        end
        State_DONE : begin
          state_6 <= State_IDLE;
        end
        default : begin
        end
      endcase
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
  output reg  [11:0]   io_jpaddr,
  input  wire          io_intPend,
  input  wire          io_excPend
);

  wire       [11:0]   rom_spinal_port0;
  wire       [7:0]    _zz_normalAddr;
  wire       [11:0]   normalAddr;
  reg [11:0] rom [0:255];

  initial begin
    $readmemb("JopCore.v_toplevel_pipeline_bcfetch_jumpTable_1_rom.bin",rom);
  end
  assign rom_spinal_port0 = rom[_zz_normalAddr];
  assign _zz_normalAddr = io_bytecode;
  assign normalAddr = rom_spinal_port0;
  always @(*) begin
    if(io_excPend) begin
      io_jpaddr = 12'h0a7;
    end else begin
      if(io_intPend) begin
        io_jpaddr = 12'h09f;
      end else begin
        io_jpaddr = normalAddr;
      end
    end
  end


endmodule
