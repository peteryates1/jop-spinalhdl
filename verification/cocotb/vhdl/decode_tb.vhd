--
-- decode_tb.vhd
--
-- Testbench wrapper for decode.vhd
-- This creates a self-contained module for CocoTB testing by:
-- 1. Defining required configuration constants locally
-- 2. Exposing the mem_in record fields as individual ports
-- 3. Providing all control signals for comprehensive testing
--
-- The original decode.vhd depends on:
-- - jop_config.vhd (ram_width constant)
-- - jop_types.vhd (mem_in_type record, MMU constants)
--
-- This wrapper embeds those dependencies to avoid complex library setup.
--

library ieee;
use ieee.std_logic_1164.all;
use ieee.numeric_std.all;

entity decode_tb is
    generic (
        i_width     : integer := 10;    -- instruction width
        ram_width   : integer := 8      -- stack RAM address width
    );
    port (
        clk, reset  : in std_logic;

        -- Inputs
        instr       : in std_logic_vector(i_width-1 downto 0);
        zf, nf      : in std_logic;     -- flags from ALU
        eq, lt      : in std_logic;     -- comparison flags (unused in original)
        bcopd       : in std_logic_vector(15 downto 0);  -- bytecode operand

        -- Branch/Jump Control Outputs
        br          : out std_logic;
        jmp         : out std_logic;
        jbr         : out std_logic;

        -- Memory/MMU Control Outputs (mem_in record fields exposed individually)
        mem_in_bcopd     : out std_logic_vector(15 downto 0);
        mem_in_rd        : out std_logic;
        mem_in_wr        : out std_logic;
        mem_in_addr_wr   : out std_logic;
        mem_in_bc_rd     : out std_logic;
        mem_in_iaload    : out std_logic;
        mem_in_iastore   : out std_logic;
        mem_in_stidx     : out std_logic;
        mem_in_getfield  : out std_logic;
        mem_in_putfield  : out std_logic;
        mem_in_getstatic : out std_logic;
        mem_in_putstatic : out std_logic;
        mem_in_putref    : out std_logic;
        mem_in_rdc       : out std_logic;
        mem_in_rdf       : out std_logic;
        mem_in_wrf       : out std_logic;
        mem_in_copy      : out std_logic;
        mem_in_cinval    : out std_logic;
        mem_in_atmstart  : out std_logic;
        mem_in_atmend    : out std_logic;

        -- MMU instruction select
        mmu_instr   : out std_logic_vector(3 downto 0);  -- MMU_WIDTH = 4
        mul_wr      : out std_logic;
        wr_dly      : out std_logic;

        -- Direct RAM address
        dir         : out std_logic_vector(ram_width-1 downto 0);

        -- ALU Control Signals
        sel_sub     : out std_logic;    -- 0=add, 1=sub
        sel_amux    : out std_logic;    -- 0=sum, 1=lmux
        ena_a       : out std_logic;    -- enable A register
        sel_bmux    : out std_logic;    -- 0=a, 1=mem
        sel_log     : out std_logic_vector(1 downto 0);  -- logic op select
        sel_shf     : out std_logic_vector(1 downto 0);  -- shift op select
        sel_lmux    : out std_logic_vector(2 downto 0);  -- load mux select
        sel_imux    : out std_logic_vector(1 downto 0);  -- immediate mux
        sel_rmux    : out std_logic_vector(1 downto 0);  -- register mux
        sel_smux    : out std_logic_vector(1 downto 0);  -- stack pointer mux
        sel_mmux    : out std_logic;    -- memory mux
        sel_rda     : out std_logic_vector(2 downto 0);  -- read address mux
        sel_wra     : out std_logic_vector(2 downto 0);  -- write address mux
        wr_ena      : out std_logic;    -- RAM write enable
        ena_b       : out std_logic;    -- enable B register
        ena_vp      : out std_logic;    -- enable VP register
        ena_jpc     : out std_logic;    -- enable JPC register
        ena_ar      : out std_logic     -- enable AR register
    );
end decode_tb;

architecture rtl of decode_tb is

    -- MMU width constant
    constant MMU_WIDTH : integer := 4;

    -- MMU instruction constants (from jop_types.vhd)
    constant STMUL  : std_logic_vector(MMU_WIDTH-1 downto 0) := "0000";
    constant STMWA  : std_logic_vector(MMU_WIDTH-1 downto 0) := "0001";
    constant STMRA  : std_logic_vector(MMU_WIDTH-1 downto 0) := "0010";
    constant STMWD  : std_logic_vector(MMU_WIDTH-1 downto 0) := "0011";
    constant STALD  : std_logic_vector(MMU_WIDTH-1 downto 0) := "0100";
    constant STAST  : std_logic_vector(MMU_WIDTH-1 downto 0) := "0101";
    constant STGF   : std_logic_vector(MMU_WIDTH-1 downto 0) := "0110";
    constant STPF   : std_logic_vector(MMU_WIDTH-1 downto 0) := "0111";
    constant STPFR  : std_logic_vector(MMU_WIDTH-1 downto 0) := "1111";
    constant STCP   : std_logic_vector(MMU_WIDTH-1 downto 0) := "1000";
    constant STBCR  : std_logic_vector(MMU_WIDTH-1 downto 0) := "1001";
    constant STIDX  : std_logic_vector(MMU_WIDTH-1 downto 0) := "1010";
    constant STPS   : std_logic_vector(MMU_WIDTH-1 downto 0) := "1011";
    constant STMRAC : std_logic_vector(MMU_WIDTH-1 downto 0) := "1100";
    constant STMRAF : std_logic_vector(MMU_WIDTH-1 downto 0) := "1101";
    constant STMWDF : std_logic_vector(MMU_WIDTH-1 downto 0) := "1110";

    -- no stack change MMU instructions
    constant STGS     : std_logic_vector(MMU_WIDTH-1 downto 0) := "0000";
    constant CINVAL   : std_logic_vector(MMU_WIDTH-1 downto 0) := "0001";
    constant ATMSTART : std_logic_vector(MMU_WIDTH-1 downto 0) := "0010";
    constant ATMEND   : std_logic_vector(MMU_WIDTH-1 downto 0) := "0011";

    -- Internal signals
    signal ir       : std_logic_vector(9 downto 0);
    signal is_push  : std_logic;
    signal is_pop   : std_logic;

    -- Internal mem_in signals (since we can't use record in testbench ports easily)
    signal mem_rd        : std_logic;
    signal mem_wr        : std_logic;
    signal mem_addr_wr   : std_logic;
    signal mem_bc_rd     : std_logic;
    signal mem_stidx     : std_logic;
    signal mem_iaload    : std_logic;
    signal mem_iastore   : std_logic;
    signal mem_getfield  : std_logic;
    signal mem_putfield  : std_logic;
    signal mem_putref    : std_logic;
    signal mem_getstatic : std_logic;
    signal mem_putstatic : std_logic;
    signal mem_rdc       : std_logic;
    signal mem_rdf       : std_logic;
    signal mem_wrf       : std_logic;
    signal mem_copy      : std_logic;
    signal mem_cinval    : std_logic;
    signal mem_atmstart  : std_logic;
    signal mem_atmend    : std_logic;

begin

    ir <= instr;  -- registered in fetch

    mmu_instr <= ir(MMU_WIDTH-1 downto 0);  -- address for extension select

    -- Route mem_in signals to output ports
    mem_in_bcopd     <= bcopd;
    mem_in_rd        <= mem_rd;
    mem_in_wr        <= mem_wr;
    mem_in_addr_wr   <= mem_addr_wr;
    mem_in_bc_rd     <= mem_bc_rd;
    mem_in_iaload    <= mem_iaload;
    mem_in_iastore   <= mem_iastore;
    mem_in_stidx     <= mem_stidx;
    mem_in_getfield  <= mem_getfield;
    mem_in_putfield  <= mem_putfield;
    mem_in_getstatic <= mem_getstatic;
    mem_in_putstatic <= mem_putstatic;
    mem_in_putref    <= mem_putref;
    mem_in_rdc       <= mem_rdc;
    mem_in_rdf       <= mem_rdf;
    mem_in_wrf       <= mem_wrf;
    mem_in_copy      <= mem_copy;
    mem_in_cinval    <= mem_cinval;
    mem_in_atmstart  <= mem_atmstart;
    mem_in_atmend    <= mem_atmend;

    --
    -- Branch/jbranch decode (registered for br/jmp, combinational for jbr)
    --
    process(clk, reset)
    begin
        if (reset='1') then
            br <= '0';
            jmp <= '0';
        elsif rising_edge(clk) then
            br <= '0';
            jmp <= '0';
            if((ir(9 downto 6)="0110" and zf='1') or      -- bz
               (ir(9 downto 6)="0111" and zf='0')) then   -- bnz
                br <= '1';
            end if;
            if (ir(9)='1') then                           -- jmp
                jmp <= '1';
            end if;
        end if;
    end process;

    -- jbr is combinational
    process(ir)
    begin
        jbr <= '0';
        if ir="0100000010" then     -- jbr: goto and if_xxx
            jbr <= '1';
        end if;
    end process;

    --
    -- Address/read stage decode (combinational)
    --
    process(ir, is_pop, is_push)
    begin
        is_pop <= '0';
        is_push <= '0';

        case ir(9 downto 6) is
            when "0000" =>          -- POP
                is_pop <= '1';
            when "0001" =>          -- POP
                is_pop <= '1';
            when "0010" =>          -- PUSH
                is_push <= '1';
            when "0011" =>          -- PUSH
                is_push <= '1';
            when "0100" =>          -- NOP
            when "0101" =>          -- null
            when "0110" =>          -- POP (branch)
                is_pop <= '1';
            when "0111" =>          -- POP (branch)
                is_pop <= '1';
            when others =>
                null;
        end case;

        -- RAM write enable
        wr_ena <= '0';
        if (is_push='1' or                      -- push instructions
            ir(9 downto 5)="00001" or           -- stm
            ir(9 downto 3)="0000010") then      -- st, stn, stmi
            wr_ena <= '1';
        end if;

        sel_imux <= ir(1 downto 0);             -- ld opd_x

        -- select for rd/wr address muxes
        dir <= std_logic_vector(to_unsigned(0, ram_width-5)) & ir(4 downto 0);

        sel_rda <= "110";                       -- sp
        if (ir(9 downto 3)="0011101") then      -- ld, ldn, ldmi
            sel_rda <= ir(2 downto 0);
        end if;
        if (ir(9 downto 5)="00101") then        -- ldm
            sel_rda <= "111";
        end if;
        if (ir(9 downto 5)="00110") then        -- ldi
            sel_rda <= "111";
            dir <= std_logic_vector(to_unsigned(1, ram_width-5)) &
                   ir(4 downto 0);              -- addr > 31 constants
        end if;

        sel_wra <= "110";                       -- spp
        if ir(9 downto 3)="0000010" then        -- st, stn, stmi
            sel_wra <= ir(2 downto 0);
        end if;
        if ir(9 downto 5)="00001" then          -- stm
            sel_wra <= "111";
        end if;

        -- select for sp update
        sel_smux <= "00";                       -- sp = sp
        if is_pop='1' then                      -- 'pop' instruction
            sel_smux <= "01";                   -- --sp
        end if;
        if is_push='1' then                     -- 'push' instruction
            sel_smux <= "10";                   -- ++sp
        end if;
        if ir="0000011011" then                 -- st sp
            sel_smux <= "11";                   -- sp = a
        end if;
    end process;

    --
    -- Execute stage ALU control (registered)
    --
    process(clk, reset)
    begin
        if (reset='1') then
            sel_sub <= '0';
            sel_amux <= '0';
            ena_a <= '0';
            sel_bmux <= '0';
            sel_log <= "00";
            sel_shf <= "00";
            sel_lmux <= "000";
            sel_rmux <= "00";
            sel_mmux <= '0';
            ena_b <= '0';
            ena_vp <= '0';
            ena_jpc <= '0';
            ena_ar <= '0';

        elsif rising_edge(clk) then

            sel_log <= "00";                    -- default is pop path
            if (ir(9 downto 2)="00000000") then -- pop, and, or, xor
                sel_log <= ir(1 downto 0);
            end if;

            sel_shf <= ir(1 downto 0);

            sel_sub <= '1';                     -- default is subtract for lt-flag
            sel_amux <= '1';                    -- default is lmux
            ena_a <= '1';                       -- default is enable
            ena_vp <= '0';
            ena_jpc <= '0';
            ena_ar <= '0';

            case ir is
                when "0000000000" =>            -- pop
                when "0000000001" =>            -- and
                when "0000000010" =>            -- or
                when "0000000011" =>            -- xor
                when "0000000100" =>            -- add
                    sel_sub <= '0';
                    sel_amux <= '0';
                when "0000000101" =>            -- sub
                    sel_amux <= '0';
                when "0000010000" =>            -- st0
                when "0000010001" =>            -- st1
                when "0000010010" =>            -- st2
                when "0000010011" =>            -- st3
                when "0000010100" =>            -- st
                when "0000010101" =>            -- stmi
                when "0000011000" =>            -- stvp
                    ena_vp <= '1';
                when "0000011001" =>            -- stjpc
                    ena_jpc <= '1';
                when "0000011010" =>            -- star
                    ena_ar <= '1';
                when "0000011011" =>            -- stsp
                when "0000011100" =>            -- ushr
                when "0000011101" =>            -- shl
                when "0000011110" =>            -- shr
                when "0001000000" =>            -- stmul
                when "0001000001" =>            -- stmwa
                when "0001000010" =>            -- stmra
                when "0001000011" =>            -- stmwd
                when "0001000100" =>            -- stald
                when "0001000101" =>            -- stast
                when "0001000110" =>            -- stgf
                when "0001000111" =>            -- stpf
                when "0001001111" =>            -- stpfr/stpsr/stastr
                when "0001001000" =>            -- stcp
                when "0001001001" =>            -- stbcrd
                when "0001001010" =>            -- stidx
                when "0001001011" =>            -- stps
                when "0001001100" =>            -- stmrac
                when "0001001101" =>            -- stmraf
                when "0001001110" =>            -- stmwdf
                when "0011100000" =>            -- ldmrd
                when "0011100001" =>            -- ldmul
                when "0011100010" =>            -- ldbcstart
                when "0011101000" =>            -- ld0
                when "0011101001" =>            -- ld1
                when "0011101010" =>            -- ld2
                when "0011101011" =>            -- ld3
                when "0011101100" =>            -- ld
                when "0011101101" =>            -- ldmi
                when "0011110000" =>            -- ldsp
                when "0011110001" =>            -- ldvp
                when "0011110010" =>            -- ldjpc
                when "0011110100" =>            -- ld_opd_8u
                when "0011110101" =>            -- ld_opd_8s
                when "0011110110" =>            -- ld_opd_16u
                when "0011110111" =>            -- ld_opd_16s
                when "0011111000" =>            -- dup
                    ena_a <= '0';
                when "0100000000" =>            -- nop
                    ena_a <= '0';
                when "0100000001" =>            -- wait
                    ena_a <= '0';
                when "0100000010" =>            -- jbr
                    ena_a <= '0';
                when "0100010000" =>            -- stgs
                    ena_a <= '0';
                when "0100010001" =>            -- cinval
                    ena_a <= '0';
                when "0100010010" =>            -- atmstart
                    ena_a <= '0';
                when "0100010011" =>            -- atmend
                    ena_a <= '0';
                when others =>
                    null;
            end case;

            if ir(9)='1' then                   -- jmp
                ena_a <= '0';
            end if;

            sel_lmux <= "000";                  -- log

            if ir(9 downto 2)="00000111" then   -- ushr, shl, shr
                sel_lmux <= "001";
            end if;

            if ir(9 downto 5)="00101" then      -- ldm
                sel_lmux <= "010";
            end if;
            if ir(9 downto 5)="00110" then      -- ldi
                sel_lmux <= "010";
            end if;

            if ir(9 downto 3)="0011101" then    -- ld, ldn, ldmi
                sel_lmux <= "010";
            end if;

            if ir(9 downto 2)="00111101" then   -- ld_opd_x
                sel_lmux <= "011";
            end if;

            if ir(9 downto 3)="0011100" then    -- ld from mmu/mul
                sel_lmux <= "100";
            end if;

            if ir(9 downto 2)="00111100" then   -- ldsp, ldvp, ldjpc
                sel_lmux <= "101";
            end if;

            -- default 'pop'
            sel_bmux <= '1';                    -- mem
            sel_mmux <= '0';                    -- a
            if is_pop='0' then                  -- 'push' or 'no stack change'
                sel_bmux <= '0';                -- a
                sel_mmux <= '1';                -- b
            end if;

            ena_b <= '1';
            if is_push='0' and is_pop='0' then  -- 'no stack change' (nop, wait, jbr)
                ena_b <= '0';
            end if;

            sel_rmux <= ir(1 downto 0);         -- ldsp, ldvp, ldjpc

        end if;
    end process;

    --
    -- Execute stage MMU/mul control (registered)
    --
    process(clk, reset)
    begin
        if (reset='1') then
            mem_rd <= '0';
            mem_wr <= '0';
            mem_addr_wr <= '0';
            mem_bc_rd <= '0';
            mem_stidx <= '0';
            mem_iaload <= '0';
            mem_iastore <= '0';
            mem_getfield <= '0';
            mem_putfield <= '0';
            mem_putref <= '0';
            mem_getstatic <= '0';
            mem_putstatic <= '0';
            mem_rdc <= '0';
            mem_rdf <= '0';
            mem_wrf <= '0';
            mem_copy <= '0';
            mem_cinval <= '0';
            mem_atmstart <= '0';
            mem_atmend <= '0';
            mul_wr <= '0';
            wr_dly <= '0';

        elsif rising_edge(clk) then
            mem_rd <= '0';
            mem_wr <= '0';
            mem_addr_wr <= '0';
            mem_bc_rd <= '0';
            mem_stidx <= '0';
            mem_iaload <= '0';
            mem_iastore <= '0';
            mem_getfield <= '0';
            mem_putfield <= '0';
            mem_putref <= '0';
            mem_getstatic <= '0';
            mem_putstatic <= '0';
            mem_rdc <= '0';
            mem_rdf <= '0';
            mem_wrf <= '0';
            mem_copy <= '0';
            mem_cinval <= '0';
            mem_atmstart <= '0';
            mem_atmend <= '0';
            mul_wr <= '0';
            wr_dly <= '0';

            if ir(9 downto 4)="000100" then     -- a MMU or mul instruction
                wr_dly <= '1';
                case ir(MMU_WIDTH-1 downto 0) is
                    when STMUL =>
                        mul_wr <= '1';          -- start multiplier
                    when STMWA =>
                        mem_addr_wr <= '1';     -- store write address
                    when STMRA =>
                        mem_rd <= '1';          -- start memory or io read
                    when STMWD =>
                        mem_wr <= '1';          -- start memory or io write
                    when STALD =>
                        mem_iaload <= '1';      -- start array load
                    when STAST =>
                        mem_iastore <= '1';     -- start array store
                    when STGF =>
                        mem_getfield <= '1';    -- start getfield
                    when STPF =>
                        mem_putfield <= '1';    -- start putfield
                    when STPFR =>
                        mem_putfield <= '1';    -- start putfield reference
                        mem_putref <= '1';
                    when STCP =>
                        mem_copy <= '1';        -- start copy
                    when STBCR =>
                        mem_bc_rd <= '1';       -- start bytecode read
                    when STIDX =>
                        mem_stidx <= '1';       -- store index
                    when STPS =>
                        mem_putstatic <= '1';   -- start putstatic
                    when STMRAC =>
                        mem_rdc <= '1';         -- start memory constant read
                    when STMRAF =>
                        mem_rdf <= '1';         -- start memory read through full assoc. cache
                    when STMWDF =>
                        mem_wrf <= '1';         -- start memory write through full assoc. cache
                    when others =>
                        null;
                end case;
            end if;

            if ir(9 downto 4)="010001" then     -- a MMU instruction, no SP change
                wr_dly <= '1';
                case ir(MMU_WIDTH-1 downto 0) is
                    when STGS =>
                        mem_getstatic <= '1';   -- start getstatic
                    when CINVAL =>
                        mem_cinval <= '1';      -- invalidate data cache
                    when ATMSTART =>
                        mem_atmstart <= '1';    -- start atomic arbiter operation
                    when ATMEND =>
                        mem_atmend <= '1';      -- end atomic arbiter operation
                    when others =>
                        null;
                end case;
            end if;

        end if;
    end process;

end rtl;
