//////////////////////////////////////////////////////////////////////////////////
// Module Name:    UDP communication module
//////////////////////////////////////////////////////////////////////////////////

module udp(
			input wire           reset_n,

			input	 wire           e_rxc,
			input  wire	[7:0]	    e_rxd,
			input	 wire           e_rxdv,
			output wire	          e_txen,
			output wire	[7:0]     e_txd,
			output wire		       e_txer,

			output wire 	       data_o_valid,                        // Receive data valid signal
			output wire [31:0]    ram_wr_data,                         // 32-bit received IP packet data
			output wire [15:0]    rx_total_length,                     // RX IP packet total length

			output wire [3:0]     rx_state,                            // UDP receive state machine
			output wire [15:0]    rx_data_length,		                 // RX IP data length
		   output wire [8:0]     ram_wr_addr,                         // RAM write address

			input  wire [31:0]    ram_rd_data,                         // Data read from RAM
		   output      [3:0]     tx_state,                            // UDP transmit state machine

			input  wire [15:0]    tx_data_length,                      // TX IP data length
			input  wire [15:0]    tx_total_length,                     // TX IP packet total length
		   output wire [8:0]     ram_rd_addr,                         // RAM read address
			output wire           data_receive
);


wire	[31:0] crcnext;
wire	[31:0] crc32;
wire	crcreset;
wire	crcen;


// IP frame transmit
ipsend ipsend_inst(
	.clk(e_rxc),
	.txen(e_txen),
	.txer(e_txer),
	.dataout(e_txd),
	.crc(crc32),
	.datain(ram_rd_data),
	.crcen(crcen),
	.crcre(crcreset),
	.tx_state(tx_state),
	.tx_data_length(tx_data_length),
	.tx_total_length(tx_total_length),
	.ram_rd_addr(ram_rd_addr)
	);

// CRC32 checksum
crc	crc_inst(
	.Clk(e_rxc),
	.Reset(crcreset),
	.Enable(crcen),
	.Data_in(e_txd),
	.Crc(crc32),
	.CrcNext(crcnext));

// IP frame receive
iprecieve iprecieve_inst(
	.clk(e_rxc),
	.datain(e_rxd),
	.e_rxdv(e_rxdv),
	.clr(reset_n),
	.board_mac(),
	.pc_mac(),
	.IP_Prtcl(),
	.IP_layer(),
	.pc_IP(),
	.board_IP(),
	.UDP_layer(),
	.data_o(ram_wr_data),
	.valid_ip_P(),
	.rx_total_length(rx_total_length),
	.data_o_valid(data_o_valid),
	.rx_state(rx_state),
	.rx_data_length(rx_data_length),
	.ram_wr_addr(ram_wr_addr),
	.data_receive(data_receive)


	);

endmodule
