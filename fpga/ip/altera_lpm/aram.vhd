--
--  Stack RAM for JOP — Altera LPM version
--
--  Wraps lpm_ram_dp with .mif initialization.
--  All LPM controls are REGISTERED (required for MAX10 M9K synchronous RAM).
--  Internal wraddr_dly/wren_dly delay write address and enable by one cycle
--  to match the LPM-registered write data timing.
--  Inverted write clock avoids read-during-write hazards on Cyclone/MAX10.
--  Caller feeds combinational (unregistered) signals; LPM does all registration.
--  Based on jopmin aram.vhd.
--

Library IEEE;
use IEEE.std_logic_1164.all;
use IEEE.std_logic_arith.all;
use IEEE.std_logic_unsigned.all;

entity ram is
generic (width : integer := 32; addr_width : integer := 8);
port (
	reset		: in std_logic;
	data		: in std_logic_vector(width-1 downto 0);
	wraddress	: in std_logic_vector(addr_width-1 downto 0);
	rdaddress	: in std_logic_vector(addr_width-1 downto 0);
	wren		: in std_logic;
	clock		: in std_logic;
	q		: out std_logic_vector(width-1 downto 0)
);
end ram;

--
--  registered and delayed wraddress, wren
--  registered din
--  registered rdaddress
--  unregistered dout
--
--  Inverted clock on write port avoids read-during-write issues on Cyclone/MAX10.
--
architecture rtl of ram is

	COMPONENT lpm_ram_dp
	GENERIC (LPM_WIDTH: POSITIVE;
		LPM_WIDTHAD: POSITIVE;
		LPM_NUMWORDS: NATURAL := 0;
		LPM_TYPE: STRING := "LPM_RAM_DP";
		LPM_INDATA: STRING := "REGISTERED";
		LPM_OUTDATA: STRING := "REGISTERED";
		LPM_RDADDRESS_CONTROL: STRING := "REGISTERED";
		LPM_WRADDRESS_CONTROL: STRING := "REGISTERED";
		LPM_FILE: STRING := "UNUSED";
		LPM_HINT: STRING := "UNUSED"
		);
	PORT (rdaddress, wraddress: IN STD_LOGIC_VECTOR(LPM_WIDTHAD-1 DOWNTO 0);
		rdclock, wrclock: IN STD_LOGIC := '1';
		rden, rdclken, wrclken: IN STD_LOGIC := '1';
		wren: IN STD_LOGIC;
		data: IN STD_LOGIC_VECTOR(LPM_WIDTH-1 DOWNTO 0);
		q: OUT STD_LOGIC_VECTOR(LPM_WIDTH-1 DOWNTO 0));
	END COMPONENT;

	signal wraddr_dly	: std_logic_vector(addr_width-1 downto 0);
	signal wren_dly		: std_logic;

	signal nclk		: std_logic;

begin

	nclk <= not clock;

--
--  delay wr addr and ena because of registered indata
--
process(clock) begin

	if rising_edge(clock) then
		wraddr_dly <= wraddress;
		wren_dly <= wren;
	end if;
end process;

	cmp_ram: lpm_ram_dp
		generic map (
			LPM_WIDTH => width,
			LPM_WIDTHAD => addr_width,
			LPM_NUMWORDS => 2**addr_width,
			LPM_TYPE => "LPM_RAM_DP",
			LPM_INDATA => "REGISTERED",
			LPM_OUTDATA => "UNREGISTERED",
			LPM_RDADDRESS_CONTROL => "REGISTERED",
			LPM_WRADDRESS_CONTROL => "REGISTERED",
			LPM_FILE => "../../asm/generated/serial/ram.mif",
			LPM_HINT => "USE_EAB=ON")
		port map (
			rdaddress => rdaddress,
			wraddress => wraddr_dly,
			data => data,
			rdclock => clock,
			wrclock => nclk,
			wren => wren_dly,
			q => q
		);

end rtl;
