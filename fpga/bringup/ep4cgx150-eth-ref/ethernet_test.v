`timescale 1ns / 1ps
//////////////////////////////////////////////////////////////////////////////////
// Module Name:    ethernet_test
// Ported from QMTech EP4CE15 Project09_GMII_Ethernet to EP4CGX150
// Uses PLL to generate 125MHz from 50MHz board oscillator
//////////////////////////////////////////////////////////////////////////////////
module ethernet_test(
               input  fpga_gclk,                   // 50MHz board oscillator
               output e_reset,
               output e_mdc,
               inout  e_mdio,

               input  e_rxc,                       // 125MHz ethernet GMII RX clock
               input  e_rxdv,
               input  e_rxer,
               input  [7:0] e_rxd,

               input  e_txc,                       // 25MHz ethernet MII TX clock (unused)
               output e_gtxc,                      // 125MHz ethernet GMII TX clock
               output e_txen,
               output e_txer,
               output [7:0] e_txd
    );

// PLL: 50MHz -> 125MHz
wire clk_125;
wire pll_locked;

pll_125 pll_inst (
    .inclk0(fpga_gclk),
    .c0(clk_125),
    .locked(pll_locked)
);

// Use PLL locked as reset (active low)
wire reset_n = pll_locked;

wire [31:0] ram_wr_data;
wire [31:0] ram_rd_data;
wire [8:0] ram_wr_addr;
wire [8:0] ram_rd_addr;

assign e_gtxc=clk_125;                 // GTXC from PLL (clean 125MHz)
assign e_reset = 1'b1;

wire [31:0] datain_reg;

wire [3:0] tx_state;
wire [3:0] rx_state;
wire [15:0] rx_total_length;          // RX IP packet length
wire [15:0] tx_total_length;          // TX IP packet length
wire [15:0] rx_data_length;           // RX UDP data length
wire [15:0] tx_data_length;           // TX UDP data length

wire data_receive;
reg ram_wr_finish;

reg [31:0] udp_data [4:0];           // Transmit character storage
reg ram_wren_i;
reg [8:0] ram_addr_i;
reg [31:0] ram_data_i;
reg [4:0] i;

wire data_o_valid;
wire wea;
wire [8:0] addra;
wire [31:0] dina;

assign wea=ram_wr_finish?data_o_valid:ram_wren_i;
assign addra=ram_wr_finish?ram_wr_addr:ram_addr_i;
assign dina=ram_wr_finish?ram_wr_data:ram_data_i;


assign tx_data_length=data_receive?rx_data_length:16'd28;
assign tx_total_length=data_receive?rx_total_length:16'd48;

//////// UDP send and receive ///////////////////
udp u1(
	.reset_n(reset_n),
	.e_rxc(clk_125),                                // Use PLL clock
	.e_rxd(e_rxd),
   .e_rxdv(e_rxdv),
	.e_txen(e_txen),
	.e_txd(e_txd),
	.e_txer(e_txer),

	.data_o_valid(data_o_valid),                    // Data receive valid, write to RAM
	.ram_wr_data(ram_wr_data),                      // 32-bit received data written to RAM
	.rx_total_length(rx_total_length),              // RX IP packet total length
	.rx_state(rx_state),                            // for chipscope test
	.rx_data_length(rx_data_length),                // RX IP data length
	.ram_wr_addr(ram_wr_addr),                      // RAM write address
	.data_receive(data_receive),                    // Ethernet packet received flag

	.ram_rd_data(ram_rd_data),                      // 32-bit data read from RAM
	.tx_state(tx_state),                            // for chipscope test

	.tx_data_length(tx_data_length),                // TX IP data length
	.tx_total_length(tx_total_length),              // TX IP packet total length
	.ram_rd_addr(ram_rd_addr)                       // RAM read address

	);


////////// RAM stores received Ethernet data or test data ///////////////////
ram ram_inst (
  .wrclock(clk_125),          // Use PLL clock
  .wren(wea),                // input [0 : 0] ram write enable
  .wraddress(addra),         // input [8 : 0] ram write address
  .data(dina),               // input [31 : 0] ram write data
  .rdclock(clk_125),         // Use PLL clock
  .rdaddress(ram_rd_addr),   // input [8 : 0] ram read address
  .q(ram_rd_data)            // output [31 : 0] ram read data
);


/********************************************/
// Store characters to transmit
/********************************************/
always @(*)
begin
	 udp_data[0]<={8'd72,8'd69,8'd76,8'd76};   // 'H' 'E' 'L' 'L'
	 udp_data[1]<={8'd79,8'd32,8'd81,8'd77};   // 'O' ' ' 'Q' 'M'
    udp_data[2]<={8'd84,8'd69,8'd67,8'd72};   // 'T' 'E' 'C' 'H'
	 udp_data[3]<={8'd32,8'd66,8'd79,8'd65};   // ' ' 'B' 'O' 'A'
	 udp_data[4]<={8'd82,8'd68,8'd10,8'd13};   // 'R' 'D' LF CR
end


////////// Write default transmit data into RAM //////////////////
always@(negedge clk_125)                        // Use PLL clock
begin
  if(reset_n==1'b0) begin
     ram_wr_finish<=1'b0;
	  ram_addr_i<=0;
	  ram_data_i<=0;
	  i<=0;
  end
  else begin
     if(i==5) begin
        ram_wr_finish<=1'b1;
        ram_wren_i<=1'b0;
     end
     else begin
        ram_wren_i<=1'b1;
		  ram_addr_i<=ram_addr_i+1'b1;
		  ram_data_i<=udp_data[i];
		  i<=i+1'b1;
	  end
  end
end


endmodule
