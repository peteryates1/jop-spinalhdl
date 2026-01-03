-- Generator : SpinalHDL v1.12.2    git head : f25edbcee624ef41548345cfb91c42060e33313f
-- Component : StackStageTb
-- Git hash  : c880911767b4e23b25c8f29500172ddeb2c0a384

library IEEE;
use IEEE.STD_LOGIC_1164.ALL;
use IEEE.NUMERIC_STD.all;

package pkg_enum is

end pkg_enum;

library IEEE;
use ieee.std_logic_1164.all;
use ieee.numeric_std.all;
use ieee.math_real.all;

package pkg_scala2hdl is
  function pkg_extract (that : std_logic_vector; bitId : integer) return std_logic;
  function pkg_extract (that : std_logic_vector; base : unsigned; size : integer) return std_logic_vector;
  function pkg_cat (a : std_logic_vector; b : std_logic_vector) return std_logic_vector;
  function pkg_not (value : std_logic_vector) return std_logic_vector;
  function pkg_extract (that : unsigned; bitId : integer) return std_logic;
  function pkg_extract (that : unsigned; base : unsigned; size : integer) return unsigned;
  function pkg_cat (a : unsigned; b : unsigned) return unsigned;
  function pkg_not (value : unsigned) return unsigned;
  function pkg_extract (that : signed; bitId : integer) return std_logic;
  function pkg_extract (that : signed; base : unsigned; size : integer) return signed;
  function pkg_cat (a : signed; b : signed) return signed;
  function pkg_not (value : signed) return signed;

  function pkg_mux (sel : std_logic; one : std_logic; zero : std_logic) return std_logic;
  function pkg_mux (sel : std_logic; one : std_logic_vector; zero : std_logic_vector) return std_logic_vector;
  function pkg_mux (sel : std_logic; one : unsigned; zero : unsigned) return unsigned;
  function pkg_mux (sel : std_logic; one : signed; zero : signed) return signed;

  function pkg_toStdLogic (value : boolean) return std_logic;
  function pkg_toStdLogicVector (value : std_logic) return std_logic_vector;
  function pkg_toUnsigned (value : std_logic) return unsigned;
  function pkg_toSigned (value : std_logic) return signed;
  function pkg_stdLogicVector (lit : std_logic_vector) return std_logic_vector;
  function pkg_unsigned (lit : unsigned) return unsigned;
  function pkg_signed (lit : signed) return signed;

  function pkg_resize (that : std_logic_vector; width : integer) return std_logic_vector;
  function pkg_resize (that : unsigned; width : integer) return unsigned;
  function pkg_resize (that : signed; width : integer) return signed;

  function pkg_extract (that : std_logic_vector; high : integer; low : integer) return std_logic_vector;
  function pkg_extract (that : unsigned; high : integer; low : integer) return unsigned;
  function pkg_extract (that : signed; high : integer; low : integer) return signed;

  function pkg_shiftRight (that : std_logic_vector; size : natural) return std_logic_vector;
  function pkg_shiftRight (that : std_logic_vector; size : unsigned) return std_logic_vector;
  function pkg_shiftLeft (that : std_logic_vector; size : natural) return std_logic_vector;
  function pkg_shiftLeft (that : std_logic_vector; size : unsigned) return std_logic_vector;

  function pkg_shiftRight (that : unsigned; size : natural) return unsigned;
  function pkg_shiftRight (that : unsigned; size : unsigned) return unsigned;
  function pkg_shiftLeft (that : unsigned; size : natural) return unsigned;
  function pkg_shiftLeft (that : unsigned; size : unsigned) return unsigned;

  function pkg_shiftRight (that : signed; size : natural) return signed;
  function pkg_shiftRight (that : signed; size : unsigned) return signed;
  function pkg_shiftLeft (that : signed; size : natural) return signed;
  function pkg_shiftLeft (that : signed; size : unsigned; w : integer) return signed;

  function pkg_rotateLeft (that : std_logic_vector; size : unsigned) return std_logic_vector;

  function pkg_toString (that : std_logic_vector) return string;
  function pkg_toString (that : unsigned) return string;
  function pkg_toString (that : signed) return string;
end  pkg_scala2hdl;

package body pkg_scala2hdl is
  function pkg_extract (that : std_logic_vector; bitId : integer) return std_logic is
    alias temp : std_logic_vector(that'length-1 downto 0) is that;
  begin
    if bitId >= temp'length then
      return 'U';
    end if;
    return temp(bitId);
  end pkg_extract;

  function pkg_extract (that : std_logic_vector; base : unsigned; size : integer) return std_logic_vector is
    alias temp : std_logic_vector(that'length-1 downto 0) is that;    constant elementCount : integer := temp'length - size + 1;
    type tableType is array (0 to elementCount-1) of std_logic_vector(size-1 downto 0);
    variable table : tableType;
  begin
    for i in 0 to elementCount-1 loop
      table(i) := temp(i + size - 1 downto i);
    end loop;
    if base + size >= elementCount then
      return (size-1 downto 0 => 'U');
    end if;
    return table(to_integer(base));
  end pkg_extract;

  function pkg_cat (a : std_logic_vector; b : std_logic_vector) return std_logic_vector is
    variable cat : std_logic_vector(a'length + b'length-1 downto 0);
  begin
    cat := a & b;
    return cat;
  end pkg_cat;

  function pkg_not (value : std_logic_vector) return std_logic_vector is
    variable ret : std_logic_vector(value'length-1 downto 0);
  begin
    ret := not value;
    return ret;
  end pkg_not;

  function pkg_extract (that : unsigned; bitId : integer) return std_logic is
    alias temp : unsigned(that'length-1 downto 0) is that;
  begin
    if bitId >= temp'length then
      return 'U';
    end if;
    return temp(bitId);
  end pkg_extract;

  function pkg_extract (that : unsigned; base : unsigned; size : integer) return unsigned is
    alias temp : unsigned(that'length-1 downto 0) is that;    constant elementCount : integer := temp'length - size + 1;
    type tableType is array (0 to elementCount-1) of unsigned(size-1 downto 0);
    variable table : tableType;
  begin
    for i in 0 to elementCount-1 loop
      table(i) := temp(i + size - 1 downto i);
    end loop;
    if base + size >= elementCount then
      return (size-1 downto 0 => 'U');
    end if;
    return table(to_integer(base));
  end pkg_extract;

  function pkg_cat (a : unsigned; b : unsigned) return unsigned is
    variable cat : unsigned(a'length + b'length-1 downto 0);
  begin
    cat := a & b;
    return cat;
  end pkg_cat;

  function pkg_not (value : unsigned) return unsigned is
    variable ret : unsigned(value'length-1 downto 0);
  begin
    ret := not value;
    return ret;
  end pkg_not;

  function pkg_extract (that : signed; bitId : integer) return std_logic is
    alias temp : signed(that'length-1 downto 0) is that;
  begin
    if bitId >= temp'length then
      return 'U';
    end if;
    return temp(bitId);
  end pkg_extract;

  function pkg_extract (that : signed; base : unsigned; size : integer) return signed is
    alias temp : signed(that'length-1 downto 0) is that;    constant elementCount : integer := temp'length - size + 1;
    type tableType is array (0 to elementCount-1) of signed(size-1 downto 0);
    variable table : tableType;
  begin
    for i in 0 to elementCount-1 loop
      table(i) := temp(i + size - 1 downto i);
    end loop;
    if base + size >= elementCount then
      return (size-1 downto 0 => 'U');
    end if;
    return table(to_integer(base));
  end pkg_extract;

  function pkg_cat (a : signed; b : signed) return signed is
    variable cat : signed(a'length + b'length-1 downto 0);
  begin
    cat := a & b;
    return cat;
  end pkg_cat;

  function pkg_not (value : signed) return signed is
    variable ret : signed(value'length-1 downto 0);
  begin
    ret := not value;
    return ret;
  end pkg_not;


  -- unsigned shifts
  function pkg_shiftRight (that : unsigned; size : natural) return unsigned is
    variable ret : unsigned(that'length-1 downto 0);
  begin
    if size >= that'length then
      return "";
    else
      ret := shift_right(that,size);
      return ret(that'length-1-size downto 0);
    end if;
  end pkg_shiftRight;

  function pkg_shiftRight (that : unsigned; size : unsigned) return unsigned is
    variable ret : unsigned(that'length-1 downto 0);
  begin
    ret := shift_right(that,to_integer(size));
    return ret;
  end pkg_shiftRight;

  function pkg_shiftLeft (that : unsigned; size : natural) return unsigned is
  begin
    return shift_left(resize(that,that'length + size),size);
  end pkg_shiftLeft;

  function pkg_shiftLeft (that : unsigned; size : unsigned) return unsigned is
  begin
    return shift_left(resize(that,that'length + 2**size'length - 1),to_integer(size));
  end pkg_shiftLeft;

  -- std_logic_vector shifts
  function pkg_shiftRight (that : std_logic_vector; size : natural) return std_logic_vector is
  begin
    return std_logic_vector(pkg_shiftRight(unsigned(that),size));
  end pkg_shiftRight;

  function pkg_shiftRight (that : std_logic_vector; size : unsigned) return std_logic_vector is
  begin
    return std_logic_vector(pkg_shiftRight(unsigned(that),size));
  end pkg_shiftRight;

  function pkg_shiftLeft (that : std_logic_vector; size : natural) return std_logic_vector is
  begin
    return std_logic_vector(pkg_shiftLeft(unsigned(that),size));
  end pkg_shiftLeft;

  function pkg_shiftLeft (that : std_logic_vector; size : unsigned) return std_logic_vector is
  begin
    return std_logic_vector(pkg_shiftLeft(unsigned(that),size));
  end pkg_shiftLeft;

  -- signed shifts
  function pkg_shiftRight (that : signed; size : natural) return signed is
  begin
    return signed(pkg_shiftRight(unsigned(that),size));
  end pkg_shiftRight;

  function pkg_shiftRight (that : signed; size : unsigned) return signed is
  begin
    return shift_right(that,to_integer(size));
  end pkg_shiftRight;

  function pkg_shiftLeft (that : signed; size : natural) return signed is
  begin
    return signed(pkg_shiftLeft(unsigned(that),size));
  end pkg_shiftLeft;

  function pkg_shiftLeft (that : signed; size : unsigned; w : integer) return signed is
  begin
    return shift_left(resize(that,w),to_integer(size));
  end pkg_shiftLeft;

  function pkg_rotateLeft (that : std_logic_vector; size : unsigned) return std_logic_vector is
  begin
    return std_logic_vector(rotate_left(unsigned(that),to_integer(size)));
  end pkg_rotateLeft;

  function pkg_extract (that : std_logic_vector; high : integer; low : integer) return std_logic_vector is
    alias temp : std_logic_vector(that'length-1 downto 0) is that;
  begin
    return temp(high downto low);
  end pkg_extract;

  function pkg_extract (that : unsigned; high : integer; low : integer) return unsigned is
    alias temp : unsigned(that'length-1 downto 0) is that;
  begin
    return temp(high downto low);
  end pkg_extract;

  function pkg_extract (that : signed; high : integer; low : integer) return signed is
    alias temp : signed(that'length-1 downto 0) is that;
  begin
    return temp(high downto low);
  end pkg_extract;

  function pkg_mux (sel : std_logic; one : std_logic; zero : std_logic) return std_logic is
  begin
    if sel = '1' then
      return one;
    else
      return zero;
    end if;
  end pkg_mux;

  function pkg_mux (sel : std_logic; one : std_logic_vector; zero : std_logic_vector) return std_logic_vector is
    variable ret : std_logic_vector(zero'range);
  begin
    if sel = '1' then
      ret := one;
    else
      ret := zero;
    end if;
    return ret;
  end pkg_mux;

  function pkg_mux (sel : std_logic; one : unsigned; zero : unsigned) return unsigned is
    variable ret : unsigned(zero'range);
  begin
    if sel = '1' then
      ret := one;
    else
      ret := zero;
    end if;
    return ret;
  end pkg_mux;

  function pkg_mux (sel : std_logic; one : signed; zero : signed) return signed is
    variable ret : signed(zero'range);
  begin
    if sel = '1' then
      ret := one;
    else
      ret := zero;
    end if;
    return ret;
  end pkg_mux;

  function pkg_toStdLogic (value : boolean) return std_logic is
  begin
    if value = true then
      return '1';
    else
      return '0';
    end if;
  end pkg_toStdLogic;

  function pkg_toStdLogicVector (value : std_logic) return std_logic_vector is
    variable ret : std_logic_vector(0 downto 0);
  begin
    ret(0) := value;
    return ret;
  end pkg_toStdLogicVector;

  function pkg_toUnsigned (value : std_logic) return unsigned is
    variable ret : unsigned(0 downto 0);
  begin
    ret(0) := value;
    return ret;
  end pkg_toUnsigned;

  function pkg_toSigned (value : std_logic) return signed is
    variable ret : signed(0 downto 0);
  begin
    ret(0) := value;
    return ret;
  end pkg_toSigned;

  function pkg_stdLogicVector (lit : std_logic_vector) return std_logic_vector is
    alias ret : std_logic_vector(lit'length-1 downto 0) is lit;
  begin
    return std_logic_vector(ret);
  end pkg_stdLogicVector;

  function pkg_unsigned (lit : unsigned) return unsigned is
    alias ret : unsigned(lit'length-1 downto 0) is lit;
  begin
    return unsigned(ret);
  end pkg_unsigned;

  function pkg_signed (lit : signed) return signed is
    alias ret : signed(lit'length-1 downto 0) is lit;
  begin
    return signed(ret);
  end pkg_signed;

  function pkg_resize (that : std_logic_vector; width : integer) return std_logic_vector is
  begin
    return std_logic_vector(resize(unsigned(that),width));
  end pkg_resize;

  function pkg_resize (that : unsigned; width : integer) return unsigned is
    variable ret : unsigned(width-1 downto 0);
  begin
    if that'length = 0 then
       ret := (others => '0');
    else
       ret := resize(that,width);
    end if;
    return ret;
  end pkg_resize;
  function pkg_resize (that : signed; width : integer) return signed is
    alias temp : signed(that'length-1 downto 0) is that;
    variable ret : signed(width-1 downto 0);
  begin
    if temp'length = 0 then
       ret := (others => '0');
    elsif temp'length >= width then
       ret := temp(width-1 downto 0);
    else
       ret := resize(temp,width);
    end if;
    return ret;
  end pkg_resize;

  function pkg_toString (that : std_logic_vector) return string is
    variable ret : string((that'length-1)/4 downto 0);
    constant chars : string := "0123456789abcdef";
    variable left : natural;
  begin
    for i in ret'range loop
      left := i*4+3;
      if left > that'left then
        left := that'left;
      end if;
      ret(i) := chars(to_integer(unsigned(that(left downto i*4)))+1);
    end loop;
    return "x" & '"' & ret & '"';
  end pkg_toString;
  function pkg_toString (that : unsigned) return string is
  begin
    if that > 0 then
      return pkg_toString(that / 10) & integer'image(to_integer(that mod 10));
    else
      return "";
    end if;
  end pkg_toString;
  function pkg_toString (that : signed) return string is
  begin
    if that < 0 then
      return "-" & pkg_toString(0 - pkg_resize(that, that'length + 1));
    elsif that > 0 then
      return pkg_toString(that / 10) & integer'image(to_integer(that mod 10));
    else
      return "";
    end if;
  end pkg_toString;
end pkg_scala2hdl;


library ieee;
use ieee.std_logic_1164.all;
use ieee.numeric_std.all;

library work;
use work.pkg_scala2hdl.all;
use work.all;
use work.pkg_enum.all;


entity StackStageTb is
  port(
    clk : in std_logic;
    reset : in std_logic;
    din : in std_logic_vector(31 downto 0);
    dir : in std_logic_vector(7 downto 0);
    opd : in std_logic_vector(15 downto 0);
    jpc : in std_logic_vector(10 downto 0);
    sel_sub : in std_logic;
    sel_amux : in std_logic;
    ena_a : in std_logic;
    sel_bmux : in std_logic;
    sel_log : in std_logic_vector(1 downto 0);
    sel_shf : in std_logic_vector(1 downto 0);
    sel_lmux : in std_logic_vector(2 downto 0);
    sel_imux : in std_logic_vector(1 downto 0);
    sel_rmux : in std_logic_vector(1 downto 0);
    sel_smux : in std_logic_vector(1 downto 0);
    sel_mmux : in std_logic;
    sel_rda : in std_logic_vector(2 downto 0);
    sel_wra : in std_logic_vector(2 downto 0);
    wr_ena : in std_logic;
    ena_b : in std_logic;
    ena_vp : in std_logic;
    ena_ar : in std_logic;
    sp_ov : out std_logic;
    zf : out std_logic;
    nf : out std_logic;
    eq : out std_logic;
    lt : out std_logic;
    aout : out std_logic_vector(31 downto 0);
    bout : out std_logic_vector(31 downto 0);
    dbg_sp : out std_logic_vector(7 downto 0);
    dbg_vp0 : out std_logic_vector(7 downto 0);
    dbg_ar : out std_logic_vector(7 downto 0)
  );

end StackStageTb;

architecture arch of StackStageTb is
  signal stackArea_stackRam_spinal_port1 : std_logic_vector(31 downto 0);
  signal zz_stackArea_stackRam_port : std_logic;
  signal zz_stackArea_ramDout : std_logic;
  attribute ramstyle : string;

  signal stackArea_a : std_logic_vector(31 downto 0);
  signal stackArea_b : std_logic_vector(31 downto 0);
  signal stackArea_sp : unsigned(7 downto 0);
  signal stackArea_spp : unsigned(7 downto 0);
  signal stackArea_spm : unsigned(7 downto 0);
  signal stackArea_vp0 : unsigned(7 downto 0);
  signal stackArea_vp1 : unsigned(7 downto 0);
  signal stackArea_vp2 : unsigned(7 downto 0);
  signal stackArea_vp3 : unsigned(7 downto 0);
  signal stackArea_ar : unsigned(7 downto 0);
  signal stackArea_vpadd : unsigned(7 downto 0);
  signal stackArea_opddly : std_logic_vector(15 downto 0);
  signal stackArea_immval : std_logic_vector(31 downto 0);
  signal stackArea_spOvReg : std_logic;
  signal stackArea_sout : std_logic_vector(31 downto 0);
  signal stackArea_shifterLogic_shiftin : std_logic_vector(63 downto 0);
  signal stackArea_shifterLogic_shiftcnt : unsigned(4 downto 0);
  signal stackArea_shifterLogic_zero32 : std_logic_vector(31 downto 0);
  signal when_StackStage_l569 : std_logic;
  signal when_StackStage_l575 : std_logic;
  signal when_StackStage_l573 : std_logic;
  signal stackArea_shifterLogic_s0 : std_logic_vector(63 downto 0);
  signal when_StackStage_l585 : std_logic;
  signal stackArea_shifterLogic_s1 : std_logic_vector(63 downto 0);
  signal when_StackStage_l592 : std_logic;
  signal stackArea_shifterLogic_s2 : std_logic_vector(63 downto 0);
  signal when_StackStage_l599 : std_logic;
  signal stackArea_shifterLogic_s3 : std_logic_vector(63 downto 0);
  signal when_StackStage_l606 : std_logic;
  signal stackArea_shifterLogic_s4 : std_logic_vector(63 downto 0);
  signal when_StackStage_l613 : std_logic;
  signal stackArea_rdaddr : unsigned(7 downto 0);
  signal stackArea_wraddr : unsigned(7 downto 0);
  signal stackArea_mmux : std_logic_vector(31 downto 0);
  signal when_StackStage_l632 : std_logic;
  signal stackArea_ramWraddrReg : unsigned(7 downto 0);
  signal stackArea_ramWrenReg : std_logic;
  signal stackArea_ramDinReg : std_logic_vector(31 downto 0);
  signal stackArea_ramDout : std_logic_vector(31 downto 0);
  signal stackArea_sum : std_logic_vector(32 downto 0);
  signal stackArea_aSigned : signed(32 downto 0);
  signal stackArea_bSigned : signed(32 downto 0);
  signal stackArea_log : std_logic_vector(31 downto 0);
  signal stackArea_rmux : std_logic_vector(10 downto 0);
  signal stackArea_lmux : std_logic_vector(31 downto 0);
  signal stackArea_imux : std_logic_vector(31 downto 0);
  signal stackArea_amux : std_logic_vector(31 downto 0);
  signal when_StackStage_l751 : std_logic;
  signal stackArea_smux : unsigned(7 downto 0);
  signal when_StackStage_l813 : std_logic;
  signal when_StackStage_l828 : std_logic;
  type stackArea_stackRam_type is array (0 to 255) of std_logic_vector(31 downto 0);
  signal stackArea_stackRam : stackArea_stackRam_type;
  attribute ramstyle of stackArea_stackRam : signal is "no_rw_check";
begin
  zz_stackArea_ramDout <= pkg_toStdLogic(true);
  process(clk)
  begin
    if rising_edge(clk) then
      if stackArea_ramWrenReg = '1' then
      stackArea_stackRam(to_integer(stackArea_ramWraddrReg)) <= stackArea_ramDinReg;
      end if;
    end if;
  end process;

  process(clk)
  begin
    if rising_edge(clk) then
      if zz_stackArea_ramDout = '1' then
        stackArea_stackRam_spinal_port1 <= stackArea_stackRam(to_integer(stackArea_rdaddr));
      end if;
    end if;
  end process;

  stackArea_shifterLogic_zero32 <= pkg_stdLogicVector("00000000000000000000000000000000");
  process(stackArea_shifterLogic_zero32,stackArea_b,when_StackStage_l569,when_StackStage_l573,when_StackStage_l575)
  begin
    stackArea_shifterLogic_shiftin <= pkg_cat(stackArea_shifterLogic_zero32,stackArea_b);
    if when_StackStage_l569 = '1' then
      stackArea_shifterLogic_shiftin <= pkg_cat(pkg_cat(pkg_stdLogicVector("0"),stackArea_b),pkg_stdLogicVector("0000000000000000000000000000000"));
    else
      if when_StackStage_l573 = '1' then
        if when_StackStage_l575 = '1' then
          stackArea_shifterLogic_shiftin <= pkg_cat(pkg_stdLogicVector("11111111111111111111111111111111"),stackArea_b);
        else
          stackArea_shifterLogic_shiftin <= pkg_cat(stackArea_shifterLogic_zero32,stackArea_b);
        end if;
      end if;
    end if;
  end process;

  process(stackArea_a,when_StackStage_l569)
  begin
    stackArea_shifterLogic_shiftcnt <= unsigned(pkg_extract(stackArea_a,4,0));
    if when_StackStage_l569 = '1' then
      stackArea_shifterLogic_shiftcnt <= pkg_not(unsigned(pkg_extract(stackArea_a,4,0)));
    end if;
  end process;

  when_StackStage_l569 <= pkg_toStdLogic(sel_shf = pkg_stdLogicVector("01"));
  when_StackStage_l575 <= pkg_extract(stackArea_b,31);
  when_StackStage_l573 <= pkg_toStdLogic(sel_shf = pkg_stdLogicVector("10"));
  when_StackStage_l585 <= pkg_extract(stackArea_shifterLogic_shiftcnt,4);
  process(when_StackStage_l585,stackArea_shifterLogic_shiftin)
  begin
    if when_StackStage_l585 = '1' then
      stackArea_shifterLogic_s0 <= pkg_cat(pkg_stdLogicVector("0000000000000000"),pkg_extract(stackArea_shifterLogic_shiftin,63,16));
    else
      stackArea_shifterLogic_s0 <= stackArea_shifterLogic_shiftin;
    end if;
  end process;

  when_StackStage_l592 <= pkg_extract(stackArea_shifterLogic_shiftcnt,3);
  process(when_StackStage_l592,stackArea_shifterLogic_s0)
  begin
    if when_StackStage_l592 = '1' then
      stackArea_shifterLogic_s1 <= pkg_cat(pkg_stdLogicVector("00000000"),pkg_extract(stackArea_shifterLogic_s0,63,8));
    else
      stackArea_shifterLogic_s1 <= stackArea_shifterLogic_s0;
    end if;
  end process;

  when_StackStage_l599 <= pkg_extract(stackArea_shifterLogic_shiftcnt,2);
  process(when_StackStage_l599,stackArea_shifterLogic_s1)
  begin
    if when_StackStage_l599 = '1' then
      stackArea_shifterLogic_s2 <= pkg_cat(pkg_stdLogicVector("0000"),pkg_extract(stackArea_shifterLogic_s1,63,4));
    else
      stackArea_shifterLogic_s2 <= stackArea_shifterLogic_s1;
    end if;
  end process;

  when_StackStage_l606 <= pkg_extract(stackArea_shifterLogic_shiftcnt,1);
  process(when_StackStage_l606,stackArea_shifterLogic_s2)
  begin
    if when_StackStage_l606 = '1' then
      stackArea_shifterLogic_s3 <= pkg_cat(pkg_stdLogicVector("00"),pkg_extract(stackArea_shifterLogic_s2,63,2));
    else
      stackArea_shifterLogic_s3 <= stackArea_shifterLogic_s2;
    end if;
  end process;

  when_StackStage_l613 <= pkg_extract(stackArea_shifterLogic_shiftcnt,0);
  process(when_StackStage_l613,stackArea_shifterLogic_s3)
  begin
    if when_StackStage_l613 = '1' then
      stackArea_shifterLogic_s4 <= pkg_cat(pkg_stdLogicVector("0"),pkg_extract(stackArea_shifterLogic_s3,63,1));
    else
      stackArea_shifterLogic_s4 <= stackArea_shifterLogic_s3;
    end if;
  end process;

  stackArea_sout <= pkg_extract(stackArea_shifterLogic_s4,31,0);
  when_StackStage_l632 <= pkg_toStdLogic(sel_mmux = pkg_toStdLogic(false));
  process(when_StackStage_l632,stackArea_a,stackArea_b)
  begin
    if when_StackStage_l632 = '1' then
      stackArea_mmux <= stackArea_a;
    else
      stackArea_mmux <= stackArea_b;
    end if;
  end process;

  stackArea_ramDout <= stackArea_stackRam_spinal_port1;
  stackArea_aSigned <= signed(pkg_cat(pkg_toStdLogicVector(pkg_extract(stackArea_a,31)),stackArea_a));
  stackArea_bSigned <= signed(pkg_cat(pkg_toStdLogicVector(pkg_extract(stackArea_b,31)),stackArea_b));
  process(sel_sub,stackArea_bSigned,stackArea_aSigned)
  begin
    if sel_sub = '1' then
      stackArea_sum <= std_logic_vector((stackArea_bSigned - stackArea_aSigned));
    else
      stackArea_sum <= std_logic_vector((stackArea_bSigned + stackArea_aSigned));
    end if;
  end process;

  lt <= pkg_extract(stackArea_sum,32);
  process(sel_log,stackArea_b,stackArea_a)
  begin
    case sel_log is
      when "00" =>
        stackArea_log <= stackArea_b;
      when "01" =>
        stackArea_log <= (stackArea_a and stackArea_b);
      when "10" =>
        stackArea_log <= (stackArea_a or stackArea_b);
      when others =>
        stackArea_log <= (stackArea_a xor stackArea_b);
    end case;
  end process;

  process(sel_rmux,stackArea_sp,stackArea_vp0,jpc)
  begin
    case sel_rmux is
      when "00" =>
        stackArea_rmux <= std_logic_vector(pkg_resize(stackArea_sp,11));
      when "01" =>
        stackArea_rmux <= std_logic_vector(pkg_resize(stackArea_vp0,11));
      when others =>
        stackArea_rmux <= jpc;
    end case;
  end process;

  process(sel_lmux,stackArea_log,stackArea_sout,stackArea_ramDout,stackArea_immval,din,stackArea_rmux)
  begin
    case sel_lmux is
      when "000" =>
        stackArea_lmux <= stackArea_log;
      when "001" =>
        stackArea_lmux <= stackArea_sout;
      when "010" =>
        stackArea_lmux <= stackArea_ramDout;
      when "011" =>
        stackArea_lmux <= stackArea_immval;
      when "100" =>
        stackArea_lmux <= din;
      when others =>
        stackArea_lmux <= pkg_resize(stackArea_rmux,32);
    end case;
  end process;

  process(sel_imux,stackArea_opddly)
  begin
    case sel_imux is
      when "00" =>
        stackArea_imux <= pkg_cat(pkg_stdLogicVector("000000000000000000000000"),pkg_extract(stackArea_opddly,7,0));
      when "01" =>
        stackArea_imux <= std_logic_vector(pkg_resize(signed(pkg_extract(stackArea_opddly,7,0)),32));
      when "10" =>
        stackArea_imux <= pkg_cat(pkg_stdLogicVector("0000000000000000"),stackArea_opddly);
      when others =>
        stackArea_imux <= std_logic_vector(pkg_resize(signed(stackArea_opddly),32));
    end case;
  end process;

  when_StackStage_l751 <= pkg_toStdLogic(sel_amux = pkg_toStdLogic(false));
  process(when_StackStage_l751,stackArea_sum,stackArea_lmux)
  begin
    if when_StackStage_l751 = '1' then
      stackArea_amux <= pkg_extract(stackArea_sum,31,0);
    else
      stackArea_amux <= stackArea_lmux;
    end if;
  end process;

  zf <= pkg_toStdLogic(stackArea_a = pkg_stdLogicVector("00000000000000000000000000000000"));
  nf <= pkg_extract(stackArea_a,31);
  eq <= pkg_toStdLogic(stackArea_a = stackArea_b);
  process(sel_smux,stackArea_sp,stackArea_spm,stackArea_spp,stackArea_a)
  begin
    case sel_smux is
      when "00" =>
        stackArea_smux <= stackArea_sp;
      when "01" =>
        stackArea_smux <= stackArea_spm;
      when "10" =>
        stackArea_smux <= stackArea_spp;
      when others =>
        stackArea_smux <= unsigned(pkg_extract(stackArea_a,7,0));
    end case;
  end process;

  process(sel_rda,stackArea_vp0,stackArea_vp1,stackArea_vp2,stackArea_vp3,stackArea_vpadd,stackArea_ar,stackArea_sp,dir)
  begin
    case sel_rda is
      when "000" =>
        stackArea_rdaddr <= stackArea_vp0;
      when "001" =>
        stackArea_rdaddr <= stackArea_vp1;
      when "010" =>
        stackArea_rdaddr <= stackArea_vp2;
      when "011" =>
        stackArea_rdaddr <= stackArea_vp3;
      when "100" =>
        stackArea_rdaddr <= stackArea_vpadd;
      when "101" =>
        stackArea_rdaddr <= stackArea_ar;
      when "110" =>
        stackArea_rdaddr <= stackArea_sp;
      when others =>
        stackArea_rdaddr <= unsigned(dir);
    end case;
  end process;

  process(sel_wra,stackArea_vp0,stackArea_vp1,stackArea_vp2,stackArea_vp3,stackArea_vpadd,stackArea_ar,stackArea_spp,dir)
  begin
    case sel_wra is
      when "000" =>
        stackArea_wraddr <= stackArea_vp0;
      when "001" =>
        stackArea_wraddr <= stackArea_vp1;
      when "010" =>
        stackArea_wraddr <= stackArea_vp2;
      when "011" =>
        stackArea_wraddr <= stackArea_vp3;
      when "100" =>
        stackArea_wraddr <= stackArea_vpadd;
      when "101" =>
        stackArea_wraddr <= stackArea_ar;
      when "110" =>
        stackArea_wraddr <= stackArea_spp;
      when others =>
        stackArea_wraddr <= unsigned(dir);
    end case;
  end process;

  when_StackStage_l813 <= pkg_toStdLogic(sel_bmux = pkg_toStdLogic(false));
  when_StackStage_l828 <= pkg_toStdLogic(stackArea_sp = pkg_unsigned("11101111"));
  sp_ov <= stackArea_spOvReg;
  aout <= stackArea_a;
  bout <= stackArea_b;
  dbg_sp <= std_logic_vector(stackArea_sp);
  dbg_vp0 <= std_logic_vector(stackArea_vp0);
  dbg_ar <= std_logic_vector(stackArea_ar);
  process(clk, reset)
  begin
    if reset = '1' then
      stackArea_a <= pkg_stdLogicVector("00000000000000000000000000000000");
      stackArea_b <= pkg_stdLogicVector("00000000000000000000000000000000");
      stackArea_sp <= pkg_unsigned("10000000");
      stackArea_spp <= pkg_unsigned("10000001");
      stackArea_spm <= pkg_unsigned("01111111");
      stackArea_vp0 <= pkg_unsigned("00000000");
      stackArea_vp1 <= pkg_unsigned("00000000");
      stackArea_vp2 <= pkg_unsigned("00000000");
      stackArea_vp3 <= pkg_unsigned("00000000");
      stackArea_ar <= pkg_unsigned("00000000");
      stackArea_vpadd <= pkg_unsigned("00000000");
      stackArea_opddly <= pkg_stdLogicVector("0000000000000000");
      stackArea_immval <= pkg_stdLogicVector("00000000000000000000000000000000");
      stackArea_spOvReg <= pkg_toStdLogic(false);
      stackArea_ramWraddrReg <= pkg_unsigned("00000000");
      stackArea_ramWrenReg <= pkg_toStdLogic(false);
      stackArea_ramDinReg <= pkg_stdLogicVector("00000000000000000000000000000000");
    elsif rising_edge(clk) then
      stackArea_ramWraddrReg <= stackArea_wraddr;
      stackArea_ramWrenReg <= wr_ena;
      stackArea_ramDinReg <= stackArea_mmux;
      if ena_a = '1' then
        stackArea_a <= stackArea_amux;
      end if;
      if ena_b = '1' then
        if when_StackStage_l813 = '1' then
          stackArea_b <= stackArea_a;
        else
          stackArea_b <= stackArea_ramDout;
        end if;
      end if;
      stackArea_spp <= (stackArea_smux + pkg_unsigned("00000001"));
      stackArea_spm <= (stackArea_smux - pkg_unsigned("00000001"));
      stackArea_sp <= stackArea_smux;
      if when_StackStage_l828 = '1' then
        stackArea_spOvReg <= pkg_toStdLogic(true);
      end if;
      if ena_vp = '1' then
        stackArea_vp0 <= unsigned(pkg_extract(stackArea_a,7,0));
        stackArea_vp1 <= (unsigned(pkg_extract(stackArea_a,7,0)) + pkg_unsigned("00000001"));
        stackArea_vp2 <= (unsigned(pkg_extract(stackArea_a,7,0)) + pkg_unsigned("00000010"));
        stackArea_vp3 <= (unsigned(pkg_extract(stackArea_a,7,0)) + pkg_unsigned("00000011"));
      end if;
      if ena_ar = '1' then
        stackArea_ar <= unsigned(pkg_extract(stackArea_a,7,0));
      end if;
      stackArea_vpadd <= (stackArea_vp0 + pkg_resize(unsigned(pkg_extract(opd,6,0)),8));
      stackArea_opddly <= opd;
      stackArea_immval <= stackArea_imux;
    end if;
  end process;

end arch;

