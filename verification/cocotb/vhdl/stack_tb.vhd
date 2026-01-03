--
-- stack_tb.vhd
--
-- Testbench wrapper for stack.vhd (Stack/Execute stage)
-- This creates a self-contained module for CocoTB testing by:
-- 1. Embedding the shift component
-- 2. Embedding a simple dual-port RAM
-- 3. Embedding required configuration constants
-- 4. Exposing all signals for comprehensive testing
--
-- The original stack.vhd depends on:
-- - jop_config.vhd (ram_width constant)
-- - shift.vhd (barrel shifter component)
-- - ram component (technology-specific)
--
-- This wrapper embeds those dependencies to avoid complex library setup.
--

library ieee;
use ieee.std_logic_1164.all;
use ieee.numeric_std.all;

entity stack_tb is
    generic (
        width       : integer := 32;    -- data word width
        jpc_width   : integer := 10;    -- java bytecode PC width
        ram_width   : integer := 8      -- stack RAM address width (256 entries)
    );
    port (
        clk, reset  : in std_logic;

        -- Data Inputs
        din         : in std_logic_vector(width-1 downto 0);     -- External data input
        dir         : in std_logic_vector(ram_width-1 downto 0); -- Direct RAM address
        opd         : in std_logic_vector(15 downto 0);          -- Java bytecode operand
        jpc         : in std_logic_vector(jpc_width downto 0);   -- JPC read value

        -- ALU Control Inputs (from decode stage)
        sel_sub     : in std_logic;                              -- 0=add, 1=sub
        sel_amux    : in std_logic;                              -- 0=sum, 1=lmux
        ena_a       : in std_logic;                              -- Enable A register
        sel_bmux    : in std_logic;                              -- 0=A, 1=RAM
        sel_log     : in std_logic_vector(1 downto 0);           -- Logic op select
        sel_shf     : in std_logic_vector(1 downto 0);           -- Shift op select
        sel_lmux    : in std_logic_vector(2 downto 0);           -- Load mux select
        sel_imux    : in std_logic_vector(1 downto 0);           -- Immediate mux
        sel_rmux    : in std_logic_vector(1 downto 0);           -- Register mux
        sel_smux    : in std_logic_vector(1 downto 0);           -- Stack pointer mux
        sel_mmux    : in std_logic;                              -- Memory data mux
        sel_rda     : in std_logic_vector(2 downto 0);           -- Read address mux
        sel_wra     : in std_logic_vector(2 downto 0);           -- Write address mux
        wr_ena      : in std_logic;                              -- RAM write enable
        ena_b       : in std_logic;                              -- Enable B register
        ena_vp      : in std_logic;                              -- Enable VP registers
        ena_ar      : in std_logic;                              -- Enable AR register

        -- Outputs
        sp_ov       : out std_logic;                             -- Stack overflow flag
        zf          : out std_logic;                             -- Zero flag
        nf          : out std_logic;                             -- Negative flag
        eq          : out std_logic;                             -- Equal flag
        lt          : out std_logic;                             -- Less-than flag
        aout        : out std_logic_vector(width-1 downto 0);    -- A register (TOS)
        bout        : out std_logic_vector(width-1 downto 0);    -- B register (NOS)

        -- Debug outputs (for testing internal state)
        dbg_sp      : out std_logic_vector(ram_width-1 downto 0);
        dbg_vp0     : out std_logic_vector(ram_width-1 downto 0);
        dbg_ar      : out std_logic_vector(ram_width-1 downto 0)
    );
end stack_tb;

architecture rtl of stack_tb is

    -- Internal A and B registers
    signal a, b             : std_logic_vector(width-1 downto 0);
    signal ram_dout         : std_logic_vector(width-1 downto 0);

    -- Stack pointers
    signal sp, spp, spm     : std_logic_vector(ram_width-1 downto 0);

    -- Variable pointers
    signal vp0, vp1, vp2, vp3 : std_logic_vector(ram_width-1 downto 0);

    -- Address register
    signal ar               : std_logic_vector(ram_width-1 downto 0);

    -- ALU signals
    signal sum              : std_logic_vector(32 downto 0);
    signal sout             : std_logic_vector(width-1 downto 0);
    signal log              : std_logic_vector(width-1 downto 0);
    signal immval           : std_logic_vector(width-1 downto 0);
    signal opddly           : std_logic_vector(15 downto 0);

    -- Mux outputs
    signal amux             : std_logic_vector(width-1 downto 0);
    signal lmux             : std_logic_vector(width-1 downto 0);
    signal imux             : std_logic_vector(width-1 downto 0);
    signal mmux             : std_logic_vector(width-1 downto 0);
    signal rmux             : std_logic_vector(jpc_width downto 0);
    signal smux             : std_logic_vector(ram_width-1 downto 0);

    -- Address calculations
    signal vpadd            : std_logic_vector(ram_width-1 downto 0);
    signal wraddr           : std_logic_vector(ram_width-1 downto 0);
    signal rdaddr           : std_logic_vector(ram_width-1 downto 0);

    -- Embedded RAM type
    type ram_type is array (0 to 2**ram_width - 1) of std_logic_vector(width-1 downto 0);
    signal stack_ram        : ram_type := (others => (others => '0'));

    -- RAM registered signals
    signal ram_wraddr_reg   : std_logic_vector(ram_width-1 downto 0);
    signal ram_wren_reg     : std_logic;
    signal ram_din_reg      : std_logic_vector(width-1 downto 0);

begin

    --
    -- Embedded Barrel Shifter
    --
    process(b, a(4 downto 0), sel_shf)
        variable shiftin    : std_logic_vector(63 downto 0);
        variable shiftcnt   : std_logic_vector(4 downto 0);
        variable zero32     : std_logic_vector(width-1 downto 0);
    begin
        zero32 := (others => '0');
        shiftin := zero32 & b;
        shiftcnt := a(4 downto 0);

        if sel_shf = "01" then          -- SHL (shift left)
            shiftin(31 downto 0) := zero32;
            shiftin(63 downto 31) := '0' & b;
            shiftcnt := not shiftcnt;
        elsif sel_shf = "10" then       -- SHR (arithmetic shift right)
            if b(31) = '1' then
                shiftin(63 downto 32) := (others => '1');
            else
                shiftin(63 downto 32) := zero32;
            end if;
        end if;
        -- sel_shf = "00" or "11" is USHR (unsigned shift right)

        if shiftcnt(4) = '1' then
            shiftin(47 downto 0) := shiftin(63 downto 16);
        end if;
        if shiftcnt(3) = '1' then
            shiftin(39 downto 0) := shiftin(47 downto 8);
        end if;
        if shiftcnt(2) = '1' then
            shiftin(35 downto 0) := shiftin(39 downto 4);
        end if;
        if shiftcnt(1) = '1' then
            shiftin(33 downto 0) := shiftin(35 downto 2);
        end if;
        if shiftcnt(0) = '1' then
            shiftin(31 downto 0) := shiftin(32 downto 1);
        end if;

        sout <= shiftin(31 downto 0);
    end process;

    --
    -- Embedded Dual-Port RAM
    -- Registered write address, write data, and write enable
    -- Registered read address
    -- Unregistered read data (combinational read)
    --
    process(clk)
    begin
        if rising_edge(clk) then
            -- Register write signals
            ram_wraddr_reg <= wraddr;
            ram_wren_reg <= wr_ena;
            ram_din_reg <= mmux;

            -- Perform write (delayed by one cycle)
            if ram_wren_reg = '1' then
                stack_ram(to_integer(unsigned(ram_wraddr_reg))) <= ram_din_reg;
            end if;
        end if;
    end process;

    -- Combinational read (registered read address)
    process(clk)
    begin
        if rising_edge(clk) then
            ram_dout <= stack_ram(to_integer(unsigned(rdaddr)));
        end if;
    end process;

    --
    -- 33-bit ALU for correct overflow/comparison
    --
    process(a, b, sel_sub)
    begin
        if sel_sub = '1' then
            sum <= std_logic_vector(signed(b(31) & b) - signed(a(31) & a));
        else
            sum <= std_logic_vector(signed(b(31) & b) + signed(a(31) & a));
        end if;
    end process;

    lt <= sum(32);  -- Less-than from carry/borrow

    --
    -- Mux for stack register and ALU
    --
    process(ram_dout, opddly, immval, sout, din, lmux, rmux, sp, vp0, jpc, sum, log, a, b,
            sel_log, sel_shf, sel_rmux, sel_lmux, sel_imux, sel_mmux, sel_amux)
    begin
        -- Logic unit
        case sel_log is
            when "00" =>
                log <= b;           -- Pass-through (for POP)
            when "01" =>
                log <= a and b;     -- Bitwise AND
            when "10" =>
                log <= a or b;      -- Bitwise OR
            when "11" =>
                log <= a xor b;     -- Bitwise XOR
            when others =>
                log <= b;
        end case;

        -- Register mux (SP, VP, JPC)
        case sel_rmux is
            when "00" =>
                rmux <= std_logic_vector(to_signed(to_integer(unsigned(sp)), jpc_width+1));
            when "01" =>
                rmux <= std_logic_vector(to_signed(to_integer(unsigned(vp0)), jpc_width+1));
            when others =>
                rmux <= jpc;
        end case;

        -- Load mux
        case sel_lmux(2 downto 0) is
            when "000" =>
                lmux <= log;        -- Logic unit output
            when "001" =>
                lmux <= sout;       -- Shift unit output
            when "010" =>
                lmux <= ram_dout;   -- Stack RAM output
            when "011" =>
                lmux <= immval;     -- Immediate value
            when "100" =>
                lmux <= din;        -- External data input
            when others =>
                lmux <= std_logic_vector(to_signed(to_integer(unsigned(rmux)), width));
        end case;

        -- Immediate mux (operand extension)
        case sel_imux is
            when "00" =>            -- 8-bit unsigned
                imux <= "000000000000000000000000" & opddly(7 downto 0);
            when "01" =>            -- 8-bit signed
                imux <= std_logic_vector(to_signed(to_integer(signed(opddly(7 downto 0))), width));
            when "10" =>            -- 16-bit unsigned
                imux <= "0000000000000000" & opddly;
            when others =>          -- 16-bit signed
                imux <= std_logic_vector(to_signed(to_integer(signed(opddly)), width));
        end case;

        -- Memory data mux
        if sel_mmux = '0' then
            mmux <= a;
        else
            mmux <= b;
        end if;

        -- A input mux
        if sel_amux = '0' then
            amux <= sum(31 downto 0);
        else
            amux <= lmux;
        end if;

        -- Zero flag
        if (a = std_logic_vector(to_unsigned(0, width))) then
            zf <= '1';
        else
            zf <= '0';
        end if;

        -- Negative flag
        nf <= a(width-1);

        -- Equal flag
        if (a = b) then
            eq <= '1';
        else
            eq <= '0';
        end if;
    end process;

    --
    -- A and B register process
    --
    process(clk, reset)
    begin
        if (reset = '1') then
            a <= (others => '0');
            b <= (others => '0');
        elsif rising_edge(clk) then
            if ena_a = '1' then
                a <= amux;
            end if;

            if ena_b = '1' then
                if sel_bmux = '0' then
                    b <= a;
                else
                    b <= ram_dout;
                end if;
            end if;
        end if;
    end process;

    aout <= a;
    bout <= b;

    --
    -- Stack pointer mux
    --
    process(a, sp, spm, spp, sel_smux)
    begin
        case sel_smux is
            when "00" =>
                smux <= sp;         -- No change
            when "01" =>
                smux <= spm;        -- Decrement (pop)
            when "10" =>
                smux <= spp;        -- Increment (push)
            when "11" =>
                smux <= a(ram_width-1 downto 0);  -- Load from A
            when others =>
                smux <= sp;
        end case;
    end process;

    --
    -- Address mux for RAM
    --
    process(sp, spp, vp0, vp1, vp2, vp3, vpadd, ar, dir, sel_rda, sel_wra)
    begin
        -- Read address mux
        case sel_rda is
            when "000" =>
                rdaddr <= vp0;
            when "001" =>
                rdaddr <= vp1;
            when "010" =>
                rdaddr <= vp2;
            when "011" =>
                rdaddr <= vp3;
            when "100" =>
                rdaddr <= vpadd;
            when "101" =>
                rdaddr <= ar;
            when "110" =>
                rdaddr <= sp;
            when others =>
                rdaddr <= dir;
        end case;

        -- Write address mux (note: uses spp for "110", not sp)
        case sel_wra is
            when "000" =>
                wraddr <= vp0;
            when "001" =>
                wraddr <= vp1;
            when "010" =>
                wraddr <= vp2;
            when "011" =>
                wraddr <= vp3;
            when "100" =>
                wraddr <= vpadd;
            when "101" =>
                wraddr <= ar;
            when "110" =>
                wraddr <= spp;
            when others =>
                wraddr <= dir;
        end case;
    end process;

    --
    -- Stack pointer and VP registers
    --
    process(clk, reset)
    begin
        if (reset = '1') then
            -- Initial values
            sp <= std_logic_vector(to_unsigned(128, ram_width));
            spp <= std_logic_vector(to_unsigned(129, ram_width));
            spm <= std_logic_vector(to_unsigned(127, ram_width));
            sp_ov <= '0';
            vp0 <= std_logic_vector(to_unsigned(0, ram_width));
            vp1 <= std_logic_vector(to_unsigned(0, ram_width));
            vp2 <= std_logic_vector(to_unsigned(0, ram_width));
            vp3 <= std_logic_vector(to_unsigned(0, ram_width));
            ar <= (others => '0');
            vpadd <= std_logic_vector(to_unsigned(0, ram_width));
            immval <= std_logic_vector(to_unsigned(0, width));
            opddly <= std_logic_vector(to_unsigned(0, 16));
        elsif rising_edge(clk) then
            -- Update SP, SPP, SPM
            spp <= std_logic_vector(unsigned(smux) + 1);
            spm <= std_logic_vector(unsigned(smux) - 1);
            sp <= smux;

            -- Stack overflow detection
            if sp = std_logic_vector(to_unsigned(2**ram_width - 1 - 16, ram_width)) then
                sp_ov <= '1';
            end if;

            -- VP registers
            if (ena_vp = '1') then
                vp0 <= a(ram_width-1 downto 0);
                vp1 <= std_logic_vector(unsigned(a(ram_width-1 downto 0)) + 1);
                vp2 <= std_logic_vector(unsigned(a(ram_width-1 downto 0)) + 2);
                vp3 <= std_logic_vector(unsigned(a(ram_width-1 downto 0)) + 3);
            end if;

            -- AR register
            if ena_ar = '1' then
                ar <= a(ram_width-1 downto 0);
            end if;

            -- VP + offset calculation
            vpadd <= std_logic_vector(unsigned(vp0(ram_width-1 downto 0)) + unsigned(opd(6 downto 0)));

            -- Operand delay pipeline
            opddly <= opd;

            -- Immediate value registration
            immval <= imux;
        end if;
    end process;

    -- Debug outputs
    dbg_sp <= sp;
    dbg_vp0 <= vp0;
    dbg_ar <= ar;

end rtl;
