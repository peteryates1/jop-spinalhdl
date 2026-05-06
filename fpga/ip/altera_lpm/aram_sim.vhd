--
--  Stack RAM for JOP — Altera LPM version (simulation/preloaded boot)
--
--  Same as aram.vhd but loads simulation stack init instead of serial boot.
--  Use this for BootMode.Simulation builds where stack must be pre-initialized.
--

Library IEEE;
use IEEE.std_logic_1164.all;
use IEEE.std_logic_arith.all;
use IEEE.std_logic_unsigned.all;

entity ram is
generic (width : integer; addr_width : integer);
port (
	reset	: in std_logic;
	clock	: in std_logic;
	data	: in std_logic_vector(width-1 downto 0);
	wraddress	: in std_logic_vector(addr_width-1 downto 0);
	rdaddress	: in std_logic_vector(addr_width-1 downto 0);
	wren	: in std_logic;
	q		: out std_logic_vector(width-1 downto 0)
);
end ram;

architecture rtl of ram is

	COMPONENT lpm_ram_dp
	GENERIC (LPM_WIDTH: POSITIVE;
		LPM_TYPE: STRING := "LPM_RAM_DP";
		LPM_WIDTHAD: POSITIVE;
		LPM_NUMWORDS: NATURAL := 0;
		LPM_FILE: STRING := "UNUSED";
		LPM_INDATA: STRING := "REGISTERED";
		LPM_WRADDRESS_CONTROL: STRING := "REGISTERED";
		LPM_RDADDRESS_CONTROL: STRING := "REGISTERED";
		LPM_OUTDATA: STRING := "REGISTERED";
		LPM_HINT: STRING := "UNUSED");
	PORT (rdclock: IN STD_LOGIC := '0';
		wrclock: IN STD_LOGIC := '0';
		data: IN STD_LOGIC_VECTOR(LPM_WIDTH-1 DOWNTO 0);
		rdaddress: IN STD_LOGIC_VECTOR(LPM_WIDTHAD-1 DOWNTO 0);
		wraddress: IN STD_LOGIC_VECTOR(LPM_WIDTHAD-1 DOWNTO 0);
		wren: IN STD_LOGIC := '0';
		q: OUT STD_LOGIC_VECTOR(LPM_WIDTH-1 DOWNTO 0));
	END COMPONENT;

	signal inv_clock : std_logic;

begin

	inv_clock <= not clock;

	cmp_ram: lpm_ram_dp
		generic map (
			LPM_WIDTH => width,
			LPM_TYPE => "LPM_RAM_DP",
			LPM_WIDTHAD => addr_width,
			LPM_NUMWORDS => 2**addr_width,
			LPM_FILE => "../../asm/generated/simulation/ram.mif",
			LPM_INDATA => "REGISTERED",
			LPM_WRADDRESS_CONTROL => "REGISTERED",
			LPM_RDADDRESS_CONTROL => "REGISTERED",
			LPM_OUTDATA => "UNREGISTERED",
			LPM_HINT => "USE_EAB=ON")
		port map (
			rdclock => clock,
			wrclock => inv_clock,
			data => data,
			rdaddress => rdaddress,
			wraddress => wraddress,
			wren => wren,
			q => q
		);

end rtl;
