#!/usr/bin/env python3
# DISCIPLINE: docs/testing-discipline.md — "assert on content, never an exit status".
# PROVED RED 2026-11-01: written before jop_setup.py existed (status item 173,
# the setup layer's TOML form), so every test failed on the import; then each
# refusal was red against a first reader that only joined fields with '|'.
# If you change these, re-prove them.
# ---------------------------------------------------------------------------
# jop_setup.py reads this lab's setup files (setup/*.toml) and hands their
# rows to bash tools that split on '|'. What can go wrong there is silent: a
# '|' inside a field shifts every later column, and a misspelt key just drops
# the fact -- an `idcde` line would leave --assert-device with "nothing to
# check" on a board it used to check. So the reader refuses both, and these
# tests hold it to that.
#
# Run: python3 fpga/scripts/test_jop_setup.py
# ---------------------------------------------------------------------------
import io
import os
import sys
import tempfile
from contextlib import redirect_stderr, redirect_stdout

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import jop_setup  # noqa: E402


def setup_with(**files):
    """A setup directory holding the given files (name without .toml)."""
    d = tempfile.mkdtemp(prefix="jop_setup_test_")
    for name, text in files.items():
        with open(os.path.join(d, name + ".toml"), "w") as f:
            f.write(text)
    return d


def refused(fn, *words):
    try:
        fn()
    except jop_setup.SetupError as e:
        for w in words:
            assert w in str(e), f"refusal does not mention {w!r}: {e}"
        return
    raise AssertionError(f"accepted; expected a refusal mentioning {words}")


PROBES = '''
[zed]
serial = "ABCD1234"
kind = "pico"
idcode = "03631093"
device = "XC7A100T"
description = "first in the file"

[alpha]
serial = "5E7A0001"
kind = "usb-blaster"
description = """
  a description
  over two lines"""
'''

CONSOLES = '''
[board-0]
vidpid = "1209:c0ca"
serial = "ABCD1234"
interface = "01"
description = "first CDC"

[ch340]
vidpid = "1a86:7523"
product = "USB Serial"
interface = "00"
description = "no serial at all"
'''


def test_probe_rows_keep_the_files_order_and_fields():
    d = setup_with(probes=PROBES)
    rows = jop_setup.table("probes", d)
    assert rows[0] == "zed|ABCD1234|pico|first in the file", rows
    assert rows[1].startswith("alpha|5E7A0001|usb-blaster|"), rows
    assert len(rows) == 2, rows


def test_a_multiline_description_is_one_row():
    d = setup_with(probes=PROBES)
    assert jop_setup.table("probes", d)[1] == "alpha|5E7A0001|usb-blaster|a description over two lines"


def test_devices_lists_only_probes_with_an_idcode():
    d = setup_with(probes=PROBES)
    assert jop_setup.table("devices", d) == ["zed|03631093|XC7A100T"]


def test_an_idcode_is_held_in_upper_case():
    """--assert-device upper-cases what it scans and compares strings, so a
    lower-case entry would report WRONG BOARD on the right one."""
    d = setup_with(probes=PROBES.replace('"03631093"', '"0362c093"'))
    assert jop_setup.table("devices", d) == ["zed|0362C093|XC7A100T"]


def test_an_idcode_that_is_not_eight_hex_digits_is_refused():
    d = setup_with(probes=PROBES.replace('"03631093"', '"3631093"'))
    refused(lambda: jop_setup.table("devices", d), "zed", "idcode")


def test_a_misspelt_key_is_refused():
    """The defect this reader exists to stop: the fact simply vanishes."""
    d = setup_with(probes=PROBES.replace("idcode =", "idcde ="))
    refused(lambda: jop_setup.table("probes", d), "zed", "idcde")


def test_an_unknown_probe_kind_is_refused():
    """The kind picks the scanning backend and, in check-probe-selection.sh,
    the tool. An unknown one falls through to 'none' and 'openfpgaloader'."""
    d = setup_with(probes=PROBES.replace('kind = "pico"', 'kind = "pico2"'))
    refused(lambda: jop_setup.table("probes", d), "zed", "pico2")


def test_a_probe_without_a_serial_is_refused():
    d = setup_with(probes=PROBES.replace('serial = "ABCD1234"\n', ""))
    refused(lambda: jop_setup.table("probes", d), "zed", "serial")


def test_a_field_holding_the_separator_is_refused():
    """bash reads these rows with IFS='|': a '|' in the product string would
    move the serial into the interface column."""
    d = setup_with(consoles=CONSOLES.replace('"USB Serial"', '"USB|Serial"'))
    refused(lambda: jop_setup.table("consoles", d), "ch340", "product")


def test_console_fields_left_out_mean_do_not_care():
    d = setup_with(consoles=CONSOLES)
    assert jop_setup.table("consoles", d) == [
        "board-0|1209:c0ca||ABCD1234|01|first CDC",
        "ch340|1a86:7523|USB Serial||00|no serial at all",
    ]


def test_a_console_vidpid_must_be_vid_colon_pid():
    d = setup_with(consoles=CONSOLES.replace('"1a86:7523"', '"1a867523"'))
    refused(lambda: jop_setup.table("consoles", d), "ch340", "vidpid")


def test_JOP_SETUP_names_the_setup_directory():
    d = setup_with(probes=PROBES)
    old = os.environ.get("JOP_SETUP")
    os.environ["JOP_SETUP"] = d
    try:
        assert jop_setup.table("probes")[0].startswith("zed|"), "JOP_SETUP was not read"
    finally:
        if old is None:
            del os.environ["JOP_SETUP"]
        else:
            os.environ["JOP_SETUP"] = old


def test_a_missing_setup_says_where_it_looked_and_how_to_point_elsewhere():
    d = os.path.join(tempfile.mkdtemp(prefix="jop_setup_test_"), "nowhere")
    refused(lambda: jop_setup.table("probes", d), d, "JOP_SETUP")


def test_the_cli_prints_one_row_per_line():
    d = setup_with(probes=PROBES)
    out = io.StringIO()
    with redirect_stdout(out):
        rc = jop_setup.main(["--setup", d, "table", "devices"])
    assert rc == 0 and out.getvalue() == "zed|03631093|XC7A100T\n", (rc, out.getvalue())


def test_the_cli_refuses_an_unknown_table():
    d = setup_with(probes=PROBES)
    err = io.StringIO()
    with redirect_stderr(err), redirect_stdout(io.StringIO()):
        rc = jop_setup.main(["--setup", d, "table", "probez"])
    assert rc != 0 and "probez" in err.getvalue(), (rc, err.getvalue())


UNITS = '''
[jop-x-1]
assemblies = ["asm-1", "asm-2"]
macs = ["02:12:34:56:78:01"]
probe = "zed"
console = "board-0"
loader-cable = "dirtyJtag"
presets = ["p1", "p2"]
not-fitted = ["LED"]

[jop-y-1]
assemblies = ["asm-3"]
probe = "alpha"
console = "ch340"
consoles = { UART_B = "board-0" }
'''


def units_setup(units=UNITS):
    return setup_with(probes=PROBES, consoles=CONSOLES, units=units)


def test_unit_rows_join_lists_with_commas_and_keep_the_files_order():
    assert jop_setup.table("units", units_setup()) == [
        "jop-x-1|asm-1,asm-2|02:12:34:56:78:01|zed|board-0|dirtyJtag||p1,p2|LED||",
        "jop-y-1|asm-3||alpha|ch340||||||UART_B=board-0",
    ]


def test_a_misspelt_loader_key_is_refused():
    """The CYC5000 class (items 156/157): a unit naming no loader silently
    becomes a quartus unit, and the failure reads as dead hardware."""
    d = units_setup(UNITS.replace("loader-cable", "loader_cable"))
    refused(lambda: jop_setup.table("units", d), "jop-x-1", "loader_cable")


def test_a_unit_naming_a_probe_the_setup_lacks_is_refused():
    d = units_setup(UNITS.replace('probe = "zed"', 'probe = "zedd"'))
    refused(lambda: jop_setup.table("units", d), "jop-x-1", "zedd")


def test_a_unit_naming_a_console_the_setup_lacks_is_refused():
    d = units_setup(UNITS.replace('console = "ch340"', 'console = "ch341"'))
    refused(lambda: jop_setup.table("units", d), "jop-y-1", "ch341")


def test_a_console_route_to_a_console_the_setup_lacks_is_refused():
    """`consoles` maps a UART part to the console wired to it (the Wukong's
    Pico CDCs); a route to nothing would read as a silent board."""
    d = units_setup(UNITS.replace('UART_B = "board-0"', 'UART_B = "board-9"'))
    refused(lambda: jop_setup.table("units", d), "jop-y-1", "board-9")


def test_console_routes_written_as_a_list_are_refused():
    d = units_setup(UNITS.replace('consoles = { UART_B = "board-0" }', 'consoles = ["board-0"]'))
    refused(lambda: jop_setup.table("units", d), "jop-y-1", "consoles")


def test_a_unit_with_no_assembly_is_refused():
    d = units_setup(UNITS.replace('assemblies = ["asm-3"]\n', ""))
    refused(lambda: jop_setup.table("units", d), "jop-y-1", "assemblies")


def test_a_list_written_as_a_string_is_refused():
    d = units_setup(UNITS.replace('assemblies = ["asm-3"]', 'assemblies = "asm-3"'))
    refused(lambda: jop_setup.table("units", d), "jop-y-1", "assemblies")


def test_a_malformed_mac_is_refused():
    d = units_setup(UNITS.replace('"02:12:34:56:78:01"', '"02:12:34:56:78"'))
    refused(lambda: jop_setup.table("units", d), "jop-x-1", "02:12:34:56:78")


def test_two_units_sharing_a_mac_is_refused():
    """Two live boards with one MAC steal each other's frames (item 174)."""
    d = units_setup(UNITS.replace('probe = "alpha"', 'macs = ["02:12:34:56:78:01"]\nprobe = "alpha"'))
    refused(lambda: jop_setup.table("units", d), "02:12:34:56:78:01", "jop-x-1", "jop-y-1")


def test_the_cli_answers_one_fact_of_one_unit():
    d = units_setup()
    out = io.StringIO()
    with redirect_stdout(out):
        rc = jop_setup.main(["--setup", d, "unit", "jop-x-1", "assemblies"])
    assert rc == 0 and out.getvalue() == "asm-1\nasm-2\n", (rc, out.getvalue())
    out = io.StringIO()
    with redirect_stdout(out):
        rc = jop_setup.main(["--setup", d, "unit", "jop-y-1", "loader-cable"])
    assert rc == 0 and out.getvalue() == "", "an unset fact prints nothing"


def test_the_cli_refuses_an_unknown_unit_or_fact():
    d = units_setup()
    for argv, word in ((["unit", "jop-z-1", "macs"], "jop-z-1"), (["unit", "jop-x-1", "mac"], "mac")):
        err = io.StringIO()
        with redirect_stderr(err), redirect_stdout(io.StringIO()):
            rc = jop_setup.main(["--setup", d] + argv)
        assert rc != 0 and word in err.getvalue(), (argv, rc, err.getvalue())


HOST = '''
[quartus]
"25.1" = "/tools/altera/25.1/quartus"
"18.1" = "/tools/altera/18.1/quartus"

[jdk]
"8" = "/tools/jdk8"
'''


def test_a_tool_is_found_by_name_and_version():
    d = setup_with(host=HOST)
    assert jop_setup.host("quartus", "18.1", d) == "/tools/altera/18.1/quartus"
    assert jop_setup.host("jdk", "8", d) == "/tools/jdk8"


def test_a_tool_the_host_lacks_says_what_to_add():
    d = setup_with(host=HOST)
    refused(lambda: jop_setup.host("vivado", "2025.2", d), "vivado", "2025.2", "host.toml")
    refused(lambda: jop_setup.host("quartus", "24.1", d), "quartus", "24.1", "host.toml")


def test_a_relative_tool_path_is_refused():
    """A flow runs tools from many directories; a relative path is right in one."""
    d = setup_with(host=HOST.replace('"/tools/jdk8"', '"tools/jdk8"'))
    refused(lambda: jop_setup.host("jdk", "8", d), "jdk", "tools/jdk8")


def test_a_version_given_as_a_number_is_refused():
    d = setup_with(host=HOST.replace('"8" = "/tools/jdk8"', '8 = 8'))
    refused(lambda: jop_setup.host("jdk", "8", d), "jdk")


def test_host_mk_gives_make_one_variable_per_tool_version():
    d = setup_with(host=HOST)
    mk = jop_setup.host_mk(d)
    for line in ("JOP_HOST_quartus_25.1 := /tools/altera/25.1/quartus",
                 "JOP_HOST_quartus_18.1 := /tools/altera/18.1/quartus",
                 "JOP_HOST_jdk_8 := /tools/jdk8"):
        assert line in mk.splitlines(), f"no `{line}` in\n{mk}"


def test_host_mk_without_a_host_file_defines_nothing():
    """A lab with no host.toml can still run make; a tool it then needs
    stops make where it is used, naming the variable to set."""
    d = setup_with(probes=PROBES)
    assert not [l for l in jop_setup.host_mk(d).splitlines() if not l.startswith("#")]


def test_the_cli_prints_a_tools_directory():
    d = setup_with(host=HOST)
    out = io.StringIO()
    with redirect_stdout(out):
        rc = jop_setup.main(["--setup", d, "host", "quartus", "25.1"])
    assert rc == 0 and out.getvalue() == "/tools/altera/25.1/quartus\n", (rc, out.getvalue())


def test_this_labs_setup_loads():
    for name in ("probes", "devices", "consoles", "units", "host"):
        assert jop_setup.table(name), f"the lab setup has no {name}"


if __name__ == "__main__":
    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_")]
    bad = 0
    for t in tests:
        try:
            t()
            print(f"  ok   {t.__name__}")
        except Exception as e:  # a crash is a failure too, not an abort
            print(f"  FAIL {t.__name__}: {type(e).__name__}: {e}")
            bad += 1
    print(f"  {len(tests) - bad}/{len(tests)} jop_setup tests passed")
    sys.exit(1 if bad else 0)
