/*
  This file is part of JOP, the Java Optimized Processor
    see <http://www.jopdesign.com/>

  Copyright (C) 2005-2008, Martin Schoeberl (martin@jopdesign.com)

  This program is free software: you can redistribute it and/or modify
  it under the terms of the GNU General Public License as published by
  the Free Software Foundation, either version 3 of the License, or
  (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package com.jopdesign.sys;

/**
 * JOP system constants shared between runtime and tools.
 *
 * Decompiled from the original generated Const.class and preserved as
 * source for reproducible builds.
 */
public class Const {

	// Class structure offsets
	static final int CLASS_HEADR = 5;
	public static final int CLASS_SIZE = 0;
	public static final int CLASS_SUPER = 3;
	static final int CLASS_IFTAB = 4;
	public static final int MTAB2CLINFO = -5;
	static final int MTAB2GC_INFO = -3;

	// Stack
	public static final int STACK_SIZE = 65536;
	public static final int STACK_OFF = 64;
	public static final int SCRATCHPAD_ADDRESS = 4194304;
	public static final int RAM_CP = 1;

	// Feature flags
	public static final boolean SUPPORT_DOUBLE = true;
	public static final boolean SUPPORT_FLOAT = true;
	public static final boolean USE_RTTM = false;

	// Exception codes
	public static final int EXC_SPOV = 1;
	public static final int EXC_NP = 2;
	public static final int EXC_AB = 3;
	public static final int EXC_ROLLBACK = 4;
	public static final int EXC_MON = 5;
	public static final int EXC_DIVZ = 8;

	// I/O base addresses
	public static final int IO_BASE = -128;
	public static final int IO_SYS_DEVICE = -128;
	public static final int IO_USB = -96;
	public static final int IO_DSPIO_OUT = -64;

	// System I/O registers
	public static final int IO_CNT = -128;
	public static final int IO_INT_ENA = -128;
	public static final int IO_US_CNT = -127;
	public static final int IO_TIMER = -127;
	public static final int IO_SWINT = -126;
	public static final int IO_INTNR = -126;
	public static final int IO_WD = -125;
	public static final int IO_EXCPT = -124;
	public static final int IO_LOCK = -123;
	public static final int IO_UNLOCK = -122;
	public static final int IO_CPU_ID = -122;
	public static final int IO_SIGNAL = -121;
	public static final int IO_INTMASK = -120;
	public static final int IO_INTCLEARALL = -119;
	public static final int IO_RAMCNT = -118;
	public static final int IO_DEADLINE = -118;
	public static final int IO_CPUCNT = -117;
	public static final int IO_PERFCNT = -116;
	public static final int IO_GC_HALT = -115;
	public static final int IO_MEM_SIZE = -114;
	public static final int NUM_INTERRUPTS = 3;

	// UART
	public static final int IO_STATUS = -112;
	public static final int IO_UART = -111;
	public static final int IO_UART_STATUS = -112;
	public static final int IO_UART_DATA = -111;
	public static final int MSK_UA_TDRE = 1;
	public static final int MSK_UA_RDRF = 2;

	// Peripheral bases
	public static final int LS_BASE = -64;
	public static final int EH_BASE = -80;
	public static final int IO_SD = -80;
	public static final int IO_ETH = -104;
	public static final int IO_MDIO = -96;
	public static final int IO_SD_SPI = -88;
	public static final int IO_VGA = -64;
	public static final int IO_VGA_DMA = -84;
	public static final int DM9000 = -48;
	public static final int SENS_M_BASE = -80;
	public static final int SENS_C_BASE = -48;
	public static final int I2C_A_BASE = -80;
	public static final int I2C_B_BASE = -32;

	// Keyboard/mouse
	public static final int KB_CTRL = -80;
	public static final int KB_DATA = -79;
	public static final int KB_SCANCODE = -78;
	public static final int MOUSE_STATUS = -64;
	public static final int MOUSE_FLAG = -63;
	public static final int MOUSE_X_INC = -62;
	public static final int MOUSE_Y_INC = -61;
	public static final int MSK_DTA_RDY = 1;
	public static final int MSK_BTN_LEFT = 2;
	public static final int MSK_BTN_RIGHT = 4;
	public static final int MSK_BTN_MIDDLE = 8;
	public static final int MSK_X_OVFLOW = 16;
	public static final int MSK_Y_OVFLOW = 32;

	// FPU
	public static final int IO_FPU = -16;
	public static final int FPU_A = -16;
	public static final int FPU_B = -15;
	public static final int FPU_OP = -14;
	public static final int FPU_RES = -13;
	public static final int FPU_OP_ADD = 0;
	public static final int FPU_OP_SUB = 1;
	public static final int FPU_OP_MUL = 2;
	public static final int FPU_OP_DIV = 3;

	// GPIO/ADC
	public static final int IO_IN = -64;
	public static final int IO_LED = -64;
	public static final int IO_OUT = -63;
	public static final int IO_ADC1 = -63;
	public static final int IO_ADC2 = -62;
	public static final int IO_ADC3 = -61;
	public static final int IO_CTRL = -48;
	public static final int IO_DATA = -47;

	// USB
	public static final int IO_USB_STATUS = -96;
	public static final int IO_USB_DATA = -95;

	// Wishbone
	public static final int WB_BASE = -128;
	public static final int WB_AC97 = -64;
	public static final int WB_SPI = -64;

	// Misc peripherals
	public static final int IO_LEGO = -80;
	public static final int IO_MAC = -32;
	public static final int IO_MAC_A = -32;
	public static final int IO_MAC_B = -31;
	public static final int IO_MICRO = -32;

	// Multi-UART
	public static final int IO_UART1_BASE = -112;
	public static final int IO_UART_BG_MODEM_BASE = -80;
	public static final int IO_UART_BG_GPS_BASE = -64;
	public static final int IO_STATUS2 = -80;
	public static final int IO_UART2 = -79;
	public static final int IO_STATUS3 = -64;
	public static final int IO_UART3 = -63;

	// Display/NoC/PWM
	public static final int IO_DISP = -48;
	public static final int IO_BG = -32;
	public static final int NOC_ADDR = -64;
	public static final int IO_PWM = -74;

	// Timestamping
	public static final int WB_TS0 = -80;
	public static final int WB_TS1 = -14;
	public static final int WB_TS2 = -12;
	public static final int WB_TS3 = -10;

	// Transactional memory
	public static final boolean USE_RTTM_BIGMEM = true;
	public static final int MEM_TM_MAGIC = 786432;
	public static final int TM_END_TRANSACTION = 0;
	public static final int TM_START_TRANSACTION = 1;
	public static final int TM_ABORTED = 2;
	public static final int TM_EARLY_COMMIT = 3;
	public static final int MEM_TM_RETRIES = 786432;
	public static final int MEM_TM_COMMITS = 786433;
	public static final int MEM_TM_EARLY_COMMITS = 786434;
	public static final int MEM_TM_READ_SET = 786435;
	public static final int MEM_TM_WRITE_SET = 786436;
	public static final int MEM_TM_READ_OR_WRITE_SET = 786437;
}
