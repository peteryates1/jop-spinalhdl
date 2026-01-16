--
-- bcfetch_ref_tb.vhd
--
-- Testbench wrapper for the ORIGINAL bcfetch.vhd from reference repository
-- This creates a self-contained module for CocoTB testing by:
-- 1. Embedding required package definitions (jop_config_global, jop_types)
-- 2. Embedding the jbc component (bytecode RAM)
-- 3. Including the jtbl component (jump table from asm/generated)
-- 4. Exposing irq record fields as individual ports for CocoTB
--
-- This testbench validates the REFERENCE VHDL implementation to establish
-- golden reference behavior for the SpinalHDL port.
--

library ieee;
use ieee.std_logic_1164.all;
use ieee.numeric_std.all;

-- ============================================================================
-- Embedded jop_config_global package (simplified for testbench)
-- ============================================================================
package jop_config_global_tb is
    constant SC_ADDR_SIZE : integer := 23;
    constant STACK_SIZE_GLOBAL : integer := 8;
    constant USE_OCACHE : std_logic := '0';
    constant OCACHE_ADDR_BITS : integer := 23;
    constant OCACHE_WAY_BITS : integer := 4;
    constant OCACHE_MAX_INDEX_BITS : integer := 8;
    constant OCACHE_INDEX_BITS : integer := 3;
    constant USE_ACACHE : std_logic := '0';
    constant ACACHE_ADDR_BITS : integer := 23;
    constant ACACHE_MAX_INDEX_BITS : integer := 23;
    constant ACACHE_WAY_BITS : integer := 4;
    constant ACACHE_FIELD_BITS : integer := 2;
end package jop_config_global_tb;

-- ============================================================================
-- Embedded jop_types package (simplified for bcfetch testbench)
-- ============================================================================
library ieee;
use ieee.std_logic_1164.all;

package jop_types_tb is
    -- interrupt and exception request to bcfetch
    type irq_bcf_type is record
        irq     : std_logic;    -- interrupt request, single cycle
        exc     : std_logic;    -- exception request, single cycle
        ena     : std_logic;    -- global enable
    end record;

    -- interrupt and exception ack when jfetch the interrupt bytecode
    type irq_ack_type is record
        ack_irq : std_logic;    -- interrupt ack from bcfetch, single cycle
        ack_exc : std_logic;    -- exception ack from bcfetch
    end record;
end package jop_types_tb;

-- ============================================================================
-- JBC component (bytecode RAM) - same as simulation version
-- ============================================================================
library ieee;
use ieee.std_logic_1164.all;
use ieee.numeric_std.all;

entity jbc is
    generic (jpc_width : integer := 10);
    port (
        clk         : in std_logic;
        data        : in std_logic_vector(31 downto 0);
        rd_addr     : in std_logic_vector(jpc_width-1 downto 0);
        wr_addr     : in std_logic_vector(jpc_width-3 downto 0);
        wr_en       : in std_logic;
        q           : out std_logic_vector(7 downto 0)
    );
end entity jbc;

architecture rtl of jbc is
    constant nwords : integer := 2**(jpc_width-2);
    type mem_type is array(0 to nwords-1) of std_logic_vector(31 downto 0);
    signal ram_block : mem_type := (others => (others => '0'));
    signal rda_reg : std_logic_vector(jpc_width-1 downto 0);
    signal d : std_logic_vector(31 downto 0);
begin
    process(clk)
    begin
        if rising_edge(clk) then
            if wr_en = '1' then
                ram_block(to_integer(unsigned(wr_addr))) <= data;
            end if;
            rda_reg <= rd_addr;
        end if;
    end process;

    d <= ram_block(to_integer(unsigned(rda_reg(jpc_width-1 downto 2))));

    process(rda_reg, d)
    begin
        case rda_reg(1 downto 0) is
            when "11" => q <= d(31 downto 24);
            when "10" => q <= d(23 downto 16);
            when "01" => q <= d(15 downto 8);
            when "00" => q <= d(7 downto 0);
            when others => q <= (others => '0');
        end case;
    end process;
end architecture rtl;

-- ============================================================================
-- Main testbench entity
-- ============================================================================
library ieee;
use ieee.std_logic_1164.all;
use ieee.numeric_std.all;
use work.jop_types_tb.all;

entity bcfetch_ref_tb is
    generic (
        jpc_width   : integer := 11;    -- address bits of java byte code pc
        pc_width    : integer := 11     -- address bits of internal instruction rom
    );
    port (
        clk, reset  : in std_logic;

        -- JPC interface
        jpc_out     : out std_logic_vector(jpc_width downto 0);
        din         : in std_logic_vector(31 downto 0);
        jpc_wr      : in std_logic;

        -- JBC write interface
        bc_wr_addr  : in std_logic_vector(jpc_width-3 downto 0);
        bc_wr_data  : in std_logic_vector(31 downto 0);
        bc_wr_ena   : in std_logic;

        -- Fetch control
        jfetch      : in std_logic;
        jopdfetch   : in std_logic;

        -- Condition flags
        zf, nf      : in std_logic;
        eq, lt      : in std_logic;

        -- Branch control
        jbr         : in std_logic;

        -- Interrupt interface (record fields exposed)
        irq_in_irq  : in std_logic;
        irq_in_exc  : in std_logic;
        irq_in_ena  : in std_logic;

        -- Interrupt acknowledge
        irq_out_ack_irq : out std_logic;
        irq_out_ack_exc : out std_logic;

        -- Outputs
        jpaddr      : out std_logic_vector(pc_width-1 downto 0);
        opd         : out std_logic_vector(15 downto 0);

        -- Debug outputs
        dbg_jbc_data    : out std_logic_vector(7 downto 0);
        dbg_jinstr      : out std_logic_vector(7 downto 0);
        dbg_jpc_br      : out std_logic_vector(jpc_width downto 0);
        dbg_jmp         : out std_logic;
        dbg_int_pend    : out std_logic;
        dbg_exc_pend    : out std_logic
    );
end entity bcfetch_ref_tb;

architecture rtl of bcfetch_ref_tb is

    -- Internal signals for bcfetch
    signal jbc_addr     : std_logic_vector(jpc_width-1 downto 0);
    signal jbc_data     : std_logic_vector(7 downto 0);
    signal jbc_mux      : std_logic_vector(jpc_width downto 0);
    signal jbc_q        : std_logic_vector(7 downto 0);

    signal jpc          : std_logic_vector(jpc_width downto 0);
    signal jpc_br       : std_logic_vector(jpc_width downto 0);
    signal jmp_addr     : std_logic_vector(jpc_width downto 0);

    signal jinstr       : std_logic_vector(7 downto 0);
    signal tp           : std_logic_vector(3 downto 0);
    signal jmp          : std_logic;

    signal jopd         : std_logic_vector(15 downto 0);

    -- Interrupt handling
    signal int_pend     : std_logic;
    signal int_req      : std_logic;
    signal int_taken    : std_logic;
    signal exc_pend     : std_logic;
    signal exc_taken    : std_logic;

    signal bytecode     : std_logic_vector(7 downto 0);
    signal jtbl_addr    : std_logic_vector(pc_width-1 downto 0);

begin

    -- ========================================================================
    -- JBC RAM instance
    -- ========================================================================
    jbc_inst: entity work.jbc
        generic map (jpc_width => jpc_width)
        port map (
            clk     => clk,
            data    => bc_wr_data,
            rd_addr => jbc_addr,
            wr_addr => bc_wr_addr,
            wr_en   => bc_wr_ena,
            q       => jbc_data
        );

    -- ========================================================================
    -- Jump table instance (from asm/generated/jtbl.vhd)
    -- ========================================================================
    jtbl_inst: entity work.jtbl
        port map (
            bcode    => bytecode,
            int_pend => int_req,
            exc_pend => exc_pend,
            q        => jtbl_addr
        );

    -- ========================================================================
    -- Interrupt processing (from bcfetch.vhd)
    -- ========================================================================
    process(clk, reset)
    begin
        if (reset = '1') then
            int_pend <= '0';
            exc_pend <= '0';
        elsif rising_edge(clk) then
            if irq_in_irq = '1' then
                int_pend <= '1';
            elsif int_taken = '1' then
                int_pend <= '0';
            end if;

            if irq_in_exc = '1' then
                exc_pend <= '1';
            elsif exc_taken = '1' then
                exc_pend <= '0';
            end if;
        end if;
    end process;

    int_req <= int_pend and irq_in_ena;
    int_taken <= int_req and jfetch;
    exc_taken <= exc_pend and jfetch;

    irq_out_ack_irq <= int_taken;
    irq_out_ack_exc <= exc_taken;

    -- ========================================================================
    -- Bytecode to jump table
    -- ========================================================================
    bytecode <= jbc_q;
    jpaddr <= jtbl_addr;

    jbc_addr <= jbc_mux(jpc_width-1 downto 0);
    jbc_q <= jbc_data;

    -- ========================================================================
    -- Branch type decode (from bcfetch.vhd)
    -- ========================================================================
    process(clk)
    begin
        if rising_edge(clk) then
            case jinstr is
                when "10100101" => tp <= "1111";    -- if_acmpeq
                when "10100110" => tp <= "0000";    -- if_acmpne
                when "11000110" => tp <= "1001";    -- ifnull
                when "11000111" => tp <= "1010";    -- ifnonnull
                when others => tp <= jinstr(3 downto 0);
            end case;
        end if;
    end process;

    -- ========================================================================
    -- Branch condition evaluation (from bcfetch.vhd)
    -- ========================================================================
    process(tp, jbr, zf, nf, eq, lt)
    begin
        jmp <= '0';
        if (jbr = '1') then
            case tp is
                when "1001" =>          -- ifeq, ifnull
                    if (zf = '1') then jmp <= '1'; end if;
                when "1010" =>          -- ifne, ifnonnull
                    if (zf = '0') then jmp <= '1'; end if;
                when "1011" =>          -- iflt
                    if (nf = '1') then jmp <= '1'; end if;
                when "1100" =>          -- ifge
                    if (nf = '0') then jmp <= '1'; end if;
                when "1101" =>          -- ifgt
                    if (zf = '0' and nf = '0') then jmp <= '1'; end if;
                when "1110" =>          -- ifle
                    if (zf = '1' or nf = '1') then jmp <= '1'; end if;
                when "1111" =>          -- if_icmpeq, if_acmpeq
                    if (eq = '1') then jmp <= '1'; end if;
                when "0000" =>          -- if_icmpne, if_acmpne
                    if (eq = '0') then jmp <= '1'; end if;
                when "0001" =>          -- if_icmplt
                    if (lt = '1') then jmp <= '1'; end if;
                when "0010" =>          -- if_icmpge
                    if (lt = '0') then jmp <= '1'; end if;
                when "0011" =>          -- if_icmpgt
                    if (eq = '0' and lt = '0') then jmp <= '1'; end if;
                when "0100" =>          -- if_icmple
                    if (eq = '1' or lt = '1') then jmp <= '1'; end if;
                when "0111" =>          -- goto
                    jmp <= '1';
                when others =>
                    null;
            end case;
        end if;
    end process;

    -- ========================================================================
    -- JBC read address mux (from bcfetch.vhd)
    -- ========================================================================
    process(din, jpc, jmp_addr, jopd, jfetch, jopdfetch, jmp)
    begin
        if (jmp = '1') then
            jbc_mux <= jmp_addr;
        elsif (jfetch = '1' or jopdfetch = '1') then
            jbc_mux <= std_logic_vector(unsigned(jpc) + 1);
        else
            jbc_mux <= jpc;
        end if;
    end process;

    -- ========================================================================
    -- JPC update logic (from bcfetch.vhd)
    -- ========================================================================
    process(clk, reset)
    begin
        if (reset = '1') then
            jpc <= std_logic_vector(to_unsigned(0, jpc_width+1));
        elsif rising_edge(clk) then
            if (jpc_wr = '1') then
                jpc <= din(jpc_width downto 0);
            elsif (jmp = '1') then
                jpc <= jmp_addr;
            elsif (jfetch = '1' or jopdfetch = '1') then
                jpc <= std_logic_vector(unsigned(jpc) + 1);
            end if;
        end if;
    end process;

    jpc_out <= jpc;

    -- ========================================================================
    -- Branch address calculation (from bcfetch.vhd)
    -- ========================================================================
    process(clk)
        variable branch_offset : std_logic_vector(jpc_width downto 0);
    begin
        if rising_edge(clk) then
            branch_offset := jopd(jpc_width-8 downto 0) & jbc_q;
            jmp_addr <= std_logic_vector(unsigned(jpc_br) + unsigned(branch_offset));

            if (jfetch = '1') then
                jpc_br <= jpc;
                jinstr <= jbc_q;
            end if;
        end if;
    end process;

    -- ========================================================================
    -- Operand accumulation (from bcfetch.vhd)
    -- ========================================================================
    process(clk, reset)
    begin
        if (reset = '1') then
            jopd <= (others => '0');
        elsif rising_edge(clk) then
            jopd(7 downto 0) <= jbc_q;
            if (jopdfetch = '1') then
                jopd(15 downto 8) <= jopd(7 downto 0);
            end if;
        end if;
    end process;

    opd <= jopd;

    -- ========================================================================
    -- Debug outputs
    -- ========================================================================
    dbg_jbc_data <= jbc_q;
    dbg_jinstr <= jinstr;
    dbg_jpc_br <= jpc_br;
    dbg_jmp <= jmp;
    dbg_int_pend <= int_pend;
    dbg_exc_pend <= exc_pend;

end architecture rtl;
