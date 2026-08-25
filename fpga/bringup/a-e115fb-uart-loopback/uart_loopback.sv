`timescale 1ns/1ps

module uart_loopback(
	input wire rx,
	output wire tx
);

assign tx = rx;

endmodule
