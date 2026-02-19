-- CYC5000 PLL for JOP
-- Cyclone V altera_pll: 12 MHz -> 80 MHz (c0) + 80 MHz/-2.5ns (c1)
--
-- Generated parameters for Quartus altera_pll megafunction.
-- 12 MHz * 20 / 3 = 80 MHz

library ieee;
use ieee.std_logic_1164.all;

library altera_mf;
use altera_mf.altera_mf_components.all;

entity cyc5000_pll is
    port (
        refclk   : in  std_logic := '0';
        rst      : in  std_logic := '0';
        outclk_0 : out std_logic;
        outclk_1 : out std_logic;
        locked   : out std_logic
    );
end cyc5000_pll;

architecture syn of cyc5000_pll is

    component altpll
    generic (
        bandwidth_type          : string;
        clk0_divide_by          : natural;
        clk0_duty_cycle         : natural;
        clk0_multiply_by        : natural;
        clk0_phase_shift        : string;
        clk1_divide_by          : natural;
        clk1_duty_cycle         : natural;
        clk1_multiply_by        : natural;
        clk1_phase_shift        : string;
        compensate_clock        : string;
        inclk0_input_frequency  : natural;
        intended_device_family  : string;
        lpm_hint                : string;
        lpm_type                : string;
        operation_mode          : string;
        port_activeclock        : string;
        port_areset             : string;
        port_clkbad0            : string;
        port_clkbad1            : string;
        port_clkloss            : string;
        port_clkswitch          : string;
        port_configupdate       : string;
        port_fbin               : string;
        port_inclk0             : string;
        port_inclk1             : string;
        port_locked             : string;
        port_pfdena             : string;
        port_phasecounterselect : string;
        port_phasedone          : string;
        port_phasestep          : string;
        port_phaseupdown        : string;
        port_pllena             : string;
        port_scanaclr           : string;
        port_scanclk            : string;
        port_scanclkena         : string;
        port_scandata           : string;
        port_scandataout        : string;
        port_scandone           : string;
        port_scanread           : string;
        port_scanwrite          : string;
        port_clk0               : string;
        port_clk1               : string;
        port_clk2               : string;
        port_clk3               : string;
        port_clk4               : string;
        port_clk5               : string;
        port_clkena0            : string;
        port_clkena1            : string;
        port_clkena2            : string;
        port_clkena3            : string;
        port_clkena4            : string;
        port_clkena5            : string;
        port_extclk0            : string;
        port_extclk1            : string;
        port_extclk2            : string;
        port_extclk3            : string;
        valid_lock_multiplier   : natural
    );
    port (
        areset : in  std_logic;
        clk    : out std_logic_vector(5 downto 0);
        inclk  : in  std_logic_vector(1 downto 0);
        locked : out std_logic
    );
    end component;

    signal sub_wire0  : std_logic_vector(5 downto 0);
    signal sub_wire6  : std_logic_vector(1 downto 0);

begin
    outclk_0 <= sub_wire0(0);
    outclk_1 <= sub_wire0(1);
    sub_wire6 <= "0" & refclk;

    altpll_component : altpll
    generic map (
        bandwidth_type          => "AUTO",
        clk0_divide_by          => 3,
        clk0_duty_cycle         => 50,
        clk0_multiply_by        => 20,
        clk0_phase_shift        => "0",
        clk1_divide_by          => 3,
        clk1_duty_cycle         => 50,
        clk1_multiply_by        => 20,
        clk1_phase_shift        => "-2500",
        compensate_clock        => "CLK0",
        inclk0_input_frequency  => 83333,    -- 12 MHz = 83333 ps
        intended_device_family  => "Cyclone V",
        lpm_hint                => "CBX_MODULE_PREFIX=cyc5000_pll",
        lpm_type                => "altpll",
        operation_mode          => "NORMAL",
        port_activeclock        => "PORT_UNUSED",
        port_areset             => "PORT_USED",
        port_clkbad0            => "PORT_UNUSED",
        port_clkbad1            => "PORT_UNUSED",
        port_clkloss            => "PORT_UNUSED",
        port_clkswitch          => "PORT_UNUSED",
        port_configupdate       => "PORT_UNUSED",
        port_fbin               => "PORT_UNUSED",
        port_inclk0             => "PORT_USED",
        port_inclk1             => "PORT_UNUSED",
        port_locked             => "PORT_USED",
        port_pfdena             => "PORT_UNUSED",
        port_phasecounterselect => "PORT_UNUSED",
        port_phasedone          => "PORT_UNUSED",
        port_phasestep          => "PORT_UNUSED",
        port_phaseupdown        => "PORT_UNUSED",
        port_pllena             => "PORT_UNUSED",
        port_scanaclr           => "PORT_UNUSED",
        port_scanclk            => "PORT_UNUSED",
        port_scanclkena         => "PORT_UNUSED",
        port_scandata           => "PORT_UNUSED",
        port_scandataout        => "PORT_UNUSED",
        port_scandone           => "PORT_UNUSED",
        port_scanread           => "PORT_UNUSED",
        port_scanwrite          => "PORT_UNUSED",
        port_clk0               => "PORT_USED",
        port_clk1               => "PORT_USED",
        port_clk2               => "PORT_UNUSED",
        port_clk3               => "PORT_UNUSED",
        port_clk4               => "PORT_UNUSED",
        port_clk5               => "PORT_UNUSED",
        port_clkena0            => "PORT_UNUSED",
        port_clkena1            => "PORT_UNUSED",
        port_clkena2            => "PORT_UNUSED",
        port_clkena3            => "PORT_UNUSED",
        port_clkena4            => "PORT_UNUSED",
        port_clkena5            => "PORT_UNUSED",
        port_extclk0            => "PORT_UNUSED",
        port_extclk1            => "PORT_UNUSED",
        port_extclk2            => "PORT_UNUSED",
        port_extclk3            => "PORT_UNUSED",
        valid_lock_multiplier   => 1
    )
    port map (
        areset => rst,
        inclk  => sub_wire6,
        clk    => sub_wire0,
        locked => locked
    );

end syn;
