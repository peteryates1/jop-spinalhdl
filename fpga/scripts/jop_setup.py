#!/usr/bin/env python3
"""
Read this lab's SETUP -- status item 173's third layer.

JOP has three layers: generic (the core, runtime, tools and flows), hardware
support (boards, assemblies, presets, anyone with the same boards can use
them), and a setup: one lab's physical units, the probes and consoles that
reach them, and the host's tools. The setup is data, kept in TOML files in one
directory, so that the generic and hardware layers never name a serial number:

    setup/probes.toml     JTAG probes, by alias      (jtag_probe_map)
    setup/consoles.toml   serial consoles, by alias  (usb_serial_map)
    setup/units.toml      the physical units: assemblies, MACs, probe, console
                          (the generators read it too, through tomlj)

The directory is `setup/` at the repository root unless JOP_SETUP names
another, or --setup is given. It can therefore live in a repository of its own.

Every reader goes through this file. Python imports it; bash and Make run it
and read one row per line, fields separated by '|':

    jop_setup.py table probes     alias|serial|kind|description
    jop_setup.py table devices    alias|idcode|device   (probes with an idcode)
    jop_setup.py table consoles   alias|vid:pid|product|serial|interface|description
    jop_setup.py table units      name|assemblies|macs|probe|console|loader-cable|
                                  loader-board|presets|not-fitted|broken
    jop_setup.py unit NAME FACT   one fact of one unit, one line per item

An empty field means the entry leaves it out ("do not care" for a console's
product, serial and interface). Descriptions are joined onto one line; a
unit's lists are joined with ','.

It REFUSES what bash would read wrong without a sound: a '|' inside a field
(every later column shifts), a key it does not know (a misspelt `idcode`
would leave --assert-device with nothing to check), a probe kind no tool maps,
an IDCODE that is not eight hex digits, a unit naming a probe or console the
setup lacks, and a MAC two units share. Tests: test_jop_setup.py.
"""
import os
import re
import sys
import tomllib

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))


class SetupError(Exception):
    pass


# The probe kinds jtag_probe_map and check-probe-selection.sh map to a
# scanning backend and a programming tool. A new kind needs both.
PROBE_KINDS = ("usb-blaster", "arrow-usb-blaster", "pico", "cmsis-dap", "ftdi-xilinx")

# key -> (required, type). The table's column order is fixed in table().
PROBE_KEYS = {"serial": (True, str), "kind": (True, str), "idcode": (False, str),
              "device": (False, str), "description": (False, str)}
CONSOLE_KEYS = {"vidpid": (True, str), "product": (False, str), "serial": (False, str),
                "interface": (False, str), "description": (False, str)}
UNIT_KEYS = {"assemblies": (True, list), "macs": (False, list), "probe": (False, str),
             "console": (False, str), "loader-cable": (False, str), "loader-board": (False, str),
             "presets": (False, list), "not-fitted": (False, list), "broken": (False, list)}
MAC = re.compile(r"[0-9a-f]{2}(:[0-9a-f]{2}){5}")


def setup_dir(d=None):
    """The setup directory: `d`, else $JOP_SETUP, else <repo>/setup."""
    d = d or os.environ.get("JOP_SETUP") or os.path.join(ROOT, "setup")
    if not os.path.isdir(d):
        raise SetupError(f"no setup directory at {d} -- set JOP_SETUP to this lab's setup "
                         f"(see docs/architecture/board-instances.md)")
    return d


def _load(name, d):
    path = os.path.join(setup_dir(d), name + ".toml")
    try:
        with open(path, "rb") as f:
            data = tomllib.load(f)
    except FileNotFoundError:
        raise SetupError(f"{path} does not exist")
    except tomllib.TOMLDecodeError as e:
        raise SetupError(f"{path}: {e}")
    for alias, entry in data.items():
        if not isinstance(entry, dict):
            raise SetupError(f"{path}: '{alias}' is not a table ([{alias}])")
    return path, data


def _checked(path, alias, entry, keys):
    unknown = sorted(set(entry) - set(keys))
    if unknown:
        raise SetupError(f"{path}: [{alias}] has unknown key(s) {', '.join(unknown)} "
                         f"(known: {', '.join(keys)})")
    for k, (required, _) in keys.items():
        if required and not entry.get(k):
            raise SetupError(f"{path}: [{alias}] has no {k}")
    if "|" in alias or "," in alias:
        raise SetupError(f"{path}: [{alias}] the name holds a field separator")
    out = {}
    for k, (_, kind) in keys.items():
        v = entry.get(k, kind())
        if kind is list:
            if not isinstance(v, list) or not all(isinstance(x, str) for x in v):
                raise SetupError(f"{path}: [{alias}] {k} must be a list of strings, not {v!r}")
            v = [" ".join(x.split()) for x in v]
            if any(sep in x for x in v for sep in "|,"):
                raise SetupError(f"{path}: [{alias}] {k} holds '|' or ',', a field separator")
        else:
            if not isinstance(v, str):
                raise SetupError(f"{path}: [{alias}] {k} must be a string, not {v!r}")
            v = " ".join(v.split())  # one line, single spaces
            if "|" in v:
                raise SetupError(f"{path}: [{alias}] {k} holds '|', the field separator")
        out[k] = v
    return out


def probes(d=None):
    """alias -> checked probe entry, in file order."""
    path, data = _load("probes", d)
    out = {}
    for alias, entry in data.items():
        p = _checked(path, alias, entry, PROBE_KEYS)
        if p["kind"] not in PROBE_KINDS:
            raise SetupError(f"{path}: [{alias}] kind '{p['kind']}' is not one of "
                             f"{', '.join(PROBE_KINDS)}")
        if p["idcode"]:
            if not re.fullmatch(r"[0-9A-Fa-f]{8}", p["idcode"]):
                raise SetupError(f"{path}: [{alias}] idcode '{p['idcode']}' is not eight hex "
                                 f"digits, as the scanning tool prints it")
            p["idcode"] = p["idcode"].upper()
        out[alias] = p
    return out


def consoles(d=None):
    """alias -> checked console entry, in file order."""
    path, data = _load("consoles", d)
    out = {}
    for alias, entry in data.items():
        c = _checked(path, alias, entry, CONSOLE_KEYS)
        if not re.fullmatch(r"[0-9a-f]{4}:[0-9a-f]{4}", c["vidpid"]):
            raise SetupError(f"{path}: [{alias}] vidpid '{c['vidpid']}' is not vid:pid "
                             f"in lower-case hex")
        out[alias] = c
    return out


def units(d=None):
    """name -> checked unit entry, in file order. A unit's probe and console
    must be in the setup's registries, and no two units may share a MAC."""
    path, data = _load("units", d)
    known_probes, known_consoles = probes(d), consoles(d)
    out, owner = {}, {}
    for name, entry in data.items():
        u = _checked(path, name, entry, UNIT_KEYS)
        if u["probe"] and u["probe"] not in known_probes:
            raise SetupError(f"{path}: [{name}] probe '{u['probe']}' is not in probes.toml")
        if u["console"] and u["console"] not in known_consoles:
            raise SetupError(f"{path}: [{name}] console '{u['console']}' is not in consoles.toml")
        u["macs"] = [m.lower() for m in u["macs"]]
        for m in u["macs"]:
            if not MAC.fullmatch(m):
                raise SetupError(f"{path}: [{name}] '{m}' is not a MAC address")
            if m in owner:
                raise SetupError(f"{path}: {m} is both {owner[m]}'s and {name}'s")
            owner[m] = name
        out[name] = u
    return out


def table(name, d=None):
    """The rows of one table, as the bash tools read them."""
    if name == "probes":
        return [f"{a}|{p['serial']}|{p['kind']}|{p['description']}" for a, p in probes(d).items()]
    if name == "devices":
        return [f"{a}|{p['idcode']}|{p['device']}" for a, p in probes(d).items() if p["idcode"]]
    if name == "consoles":
        return [f"{a}|{c['vidpid']}|{c['product']}|{c['serial']}|{c['interface']}|{c['description']}"
                for a, c in consoles(d).items()]
    if name == "units":
        cols = ("assemblies", "macs", "probe", "console", "loader-cable", "loader-board",
                "presets", "not-fitted", "broken")
        return ["|".join([n] + [",".join(u[c]) if isinstance(u[c], list) else u[c] for c in cols])
                for n, u in units(d).items()]
    raise SetupError(f"unknown table '{name}' (tables: probes, devices, consoles, units)")


def unit_fact(name, key, d=None):
    """One fact of one unit, as lines: a list one item per line, a string as
    one line, and nothing for a fact the unit leaves out."""
    us = units(d)
    if name not in us:
        raise SetupError(f"no unit named '{name}' (units: {', '.join(us)})")
    if key not in UNIT_KEYS:
        raise SetupError(f"a unit has no fact '{key}' (facts: {', '.join(UNIT_KEYS)})")
    v = us[name][key]
    return v if isinstance(v, list) else [v] if v else []


USAGE = """usage: jop_setup.py [--setup DIR] table probes|devices|consoles|units
       jop_setup.py [--setup DIR] unit NAME FACT     e.g. unit jop-x-1 macs
       jop_setup.py [--setup DIR] where"""


def main(argv):
    d = None
    if argv[:1] == ["--setup"] and len(argv) >= 2:
        d, argv = argv[1], argv[2:]
    try:
        if argv[:1] == ["table"] and len(argv) == 2:
            for row in table(argv[1], d):
                print(row)
            return 0
        if argv[:1] == ["unit"] and len(argv) == 3:
            for line in unit_fact(argv[1], argv[2], d):
                print(line)
            return 0
        if argv == ["where"]:
            print(setup_dir(d))
            return 0
    except SetupError as e:
        print(f"jop_setup: {e}", file=sys.stderr)
        return 2
    print(USAGE, file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
