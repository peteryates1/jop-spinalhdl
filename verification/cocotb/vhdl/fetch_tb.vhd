--
-- fetch_tb.vhd
--
-- Testbench wrapper for fetch.vhd with integrated ROM
-- This creates a self-contained module for CocoTB testing
--
-- The ROM is initialized with a test pattern that allows testing:
-- - Sequential fetch
-- - Wait instruction behavior
-- - Branch/jump offset calculations
-- - jfetch and jopdfetch signal generation
--

library ieee;
use ieee.std_logic_1164.all;
use ieee.numeric_std.all;

entity fetch_tb is
    generic (
        pc_width    : integer := 10;    -- address bits of internal instruction rom
        i_width     : integer := 10     -- instruction width
    );
    port (
        clk, reset  : in std_logic;

        nxt, opd    : out std_logic;    -- jfetch and jopdfetch from ROM

        br          : in std_logic;
        jmp         : in std_logic;
        bsy         : in std_logic;     -- direct from the memory module
        jpaddr      : in std_logic_vector(pc_width-1 downto 0);

        dout        : out std_logic_vector(i_width-1 downto 0);     -- internal instruction (rom)

        -- Debug/test outputs
        pc_out      : out std_logic_vector(pc_width-1 downto 0);    -- current PC for test observation
        ir_out      : out std_logic_vector(i_width-1 downto 0)      -- instruction register for test
    );
end fetch_tb;

architecture rtl of fetch_tb is

    signal pc_mux       : std_logic_vector(pc_width-1 downto 0);
    signal pc_inc       : std_logic_vector(pc_width-1 downto 0);
    signal pc           : std_logic_vector(pc_width-1 downto 0);
    signal brdly        : std_logic_vector(pc_width-1 downto 0);
    signal jpdly        : std_logic_vector(pc_width-1 downto 0);

    signal jfetch       : std_logic;        -- fetch next byte code as opcode
    signal jopdfetch    : std_logic;        -- fetch next byte code as operand

    signal rom_data     : std_logic_vector(i_width+1 downto 0);     -- output from ROM
    signal ir           : std_logic_vector(i_width-1 downto 0);     -- instruction register
    signal pcwait       : std_logic;

    -- ROM type for test
    type rom_type is array (0 to 2**pc_width - 1) of std_logic_vector(i_width+1 downto 0);

    -- Initialize ROM with test patterns
    -- ROM format: [jfetch(1)][jopdfetch(1)][instruction(i_width)]
    -- Total width: i_width + 2 = 12 bits for i_width=10
    --
    -- Test patterns:
    -- Address 0: NOP (will be skipped - see comment in fetch.vhd)
    -- Address 1: Regular instruction
    -- Address 2: Wait instruction (0x101 = 0b0100000001)
    -- Address 3: Instruction with branch offset +5 in bits [5:0]
    -- Address 4: Instruction with branch offset -3 in bits [5:0]
    -- Address 5: Instruction with jump offset +10 in bits [8:0]
    -- Address 6: jfetch=1 instruction (triggers jpaddr load)
    -- Address 7-15: Various test patterns
    -- Higher addresses: NOP pattern

    function init_rom return rom_type is
        variable rom : rom_type;
        variable addr : integer;
    begin
        -- Default: all locations are NOP (0x000) with jfetch=0, jopdfetch=0
        for i in 0 to 2**pc_width - 1 loop
            rom(i) := "00" & std_logic_vector(to_unsigned(0, i_width));
        end loop;

        -- Address 0: NOP (skipped during reset)
        -- jfetch=0, jopdfetch=0, instr=0x000
        rom(0) := "00" & "0000000000";

        -- Address 1: Regular NOP instruction
        -- jfetch=0, jopdfetch=0, instr=0x000
        rom(1) := "00" & "0000000000";

        -- Address 2: Wait instruction (0x101 = 0b0100000001)
        -- jfetch=0, jopdfetch=0, instr=0x101
        rom(2) := "00" & "0100000001";

        -- Address 3: Instruction with branch offset +5 in bits [5:0]
        -- jfetch=0, jopdfetch=0, instr has 0x05 in low 6 bits
        rom(3) := "00" & "0000000101";  -- offset +5

        -- Address 4: Instruction with branch offset -3 in bits [5:0]
        -- -3 in 6-bit two's complement = 0b111101 = 0x3D
        -- jfetch=0, jopdfetch=0
        rom(4) := "00" & "0000111101";  -- offset -3

        -- Address 5: Instruction with jump offset +10 in bits [8:0]
        -- jfetch=0, jopdfetch=0, instr has 0x00A in low 9 bits
        rom(5) := "00" & "0000001010";  -- offset +10

        -- Address 6: Regular NOP for sequential testing
        -- jfetch=0, jopdfetch=0, instr=0x006
        rom(6) := "00" & "0000000110";

        -- Address 7: Regular NOP for sequential testing
        -- jfetch=0, jopdfetch=0, instr=0x007
        rom(7) := "00" & "0000000111";

        -- Address 8: Regular NOP for sequential testing
        -- jfetch=0, jopdfetch=0, instr=0x008
        rom(8) := "00" & "0000001000";

        -- Address 50: jfetch=1 instruction (triggers jpaddr load)
        -- jfetch=1, jopdfetch=0, instr=0x000
        rom(50) := "10" & "0000000000";

        -- Address 51: jopdfetch=1 instruction
        -- jfetch=0, jopdfetch=1, instr=0x000
        rom(51) := "01" & "0000000000";

        -- Address 52: Both jfetch=1 and jopdfetch=1
        -- jfetch=1, jopdfetch=1, instr=0x000
        rom(52) := "11" & "0000000000";

        -- Address 9: Instruction with jump offset -5 in bits [8:0]
        -- -5 in 9-bit two's complement = 0b111111011 = 0x1FB
        rom(9) := "00" & "0111111011";  -- offset -5 (bit 9 is sign, but instruction is only 10 bits)

        -- Address 10: Regular instruction with distinct pattern
        rom(10) := "00" & "1010101010";

        -- Address 11: Wait instruction (another location)
        rom(11) := "00" & "0100000001";

        -- Address 12-15: Sequence markers
        rom(12) := "00" & "0000001100";  -- 0x00C
        rom(13) := "00" & "0000001101";  -- 0x00D
        rom(14) := "00" & "0000001110";  -- 0x00E
        rom(15) := "00" & "0000001111";  -- 0x00F

        -- Address 16+: Incrementing pattern for sequential test
        for i in 16 to 31 loop
            rom(i) := "00" & std_logic_vector(to_unsigned(i, i_width));
        end loop;

        -- Address 32: Branch offset +31 (max positive 6-bit)
        rom(32) := "00" & "0000011111";  -- +31

        -- Address 64: Branch offset -32 (max negative 6-bit)
        rom(64) := "00" & "0000100000";  -- -32

        -- Address 100: Jump offset +255 (max positive 9-bit)
        rom(100) := "00" & "0011111111";  -- +255

        -- Address 300: Jump offset -256 (max negative 9-bit)
        -- -256 in 9-bit two's complement = 0b100000000
        rom(300) := "00" & "0100000000";  -- -256 (note: this is also wait instruction pattern!)

        -- Address with max PC (for wraparound test)
        rom(1023) := "00" & "1111111111";  -- 0x3FF

        return rom;
    end function;

    signal rom : rom_type := init_rom;
    signal rom_addr_reg : std_logic_vector(pc_width-1 downto 0);

begin

    -- ROM with registered address, unregistered output
    process(clk)
    begin
        if rising_edge(clk) then
            rom_addr_reg <= pc_mux;
        end if;
    end process;

    -- Combinational ROM output
    rom_data <= rom(to_integer(unsigned(rom_addr_reg)));

    jfetch <= rom_data(i_width+1);
    jopdfetch <= rom_data(i_width);

    dout <= ir;
    nxt <= jfetch;
    opd <= jopdfetch;

    -- Debug outputs
    pc_out <= pc;
    ir_out <= ir;

    process(clk)
    begin
        if rising_edge(clk) then             -- we don't need a reset
            ir <= rom_data(i_width-1 downto 0);         -- better read (second) instruction from rom
            pcwait <= '0';
            -- decode wait instruction from unregistered rom
            if (rom_data(i_width-1 downto 0)="0100000001") then -- wait instruction
                pcwait <= '1';
            end if;
        end if;
    end process;

    process(clk, reset)
    begin
        if (reset='1') then
            pc <= (others => '0');
            brdly <= (others => '0');
            jpdly <= (others => '0');
        elsif rising_edge(clk) then
            -- 6 bits as signed branch offset
            brdly <= std_logic_vector(signed(pc) + to_signed(to_integer(signed(ir(5 downto 0))), pc_width));
            -- 9 bits as signed jump offset
            jpdly <= std_logic_vector(signed(pc) + to_signed(to_integer(signed(ir(i_width-2 downto 0))), pc_width));
            pc <= pc_mux;
        end if;
    end process;

    pc_inc <= std_logic_vector(unsigned(pc) + 1);

    process(jfetch, br, jmp, jpaddr, brdly, jpdly, pc, pc_inc, pcwait, bsy)
    begin
        if jfetch='1' then
            pc_mux <= jpaddr;
        else
            if br='1' then
                pc_mux <= brdly;
            elsif jmp='1' then
                pc_mux <= jpdly;
            else
                -- bsy is too late to register pcwait and bsy
                if (pcwait='1' and bsy='1') then
                    pc_mux <= pc;
                else
                    pc_mux <= pc_inc;
                end if;
            end if;
        end if;
    end process;

end rtl;
