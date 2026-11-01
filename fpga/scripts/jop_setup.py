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

The directory is `setup/` at the repository root unless JOP_SETUP names
another, or --setup is given. It can therefore live in a repository of its own.

Every reader goes through this file. Python imports it; bash and Make run it
and read one row per line, fields separated by '|':

    jop_setup.py table probes     alias|serial|kind|description
    jop_setup.py table devices    alias|idcode|device   (probes with an idcode)
    jop_setup.py table consoles   alias|vid:pid|product|serial|interface|description

An empty field means the entry leaves it out ("do not care" for a console's
product, serial and interface). Descriptions are joined onto one line.

It REFUSES what bash would read wrong without a sound: a '|' inside a field
(every later column shifts), a key it does not know (a misspelt `idcode`
would leave --assert-device with nothing to check), a probe kind no tool maps,
and an IDCODE that is not eight hex digits. Tests: test_jop_setup.py.
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

# key -> required. Order is not significant; the table's column order is fixed below.
PROBE_KEYS = {"serial": True, "kind": True, "idcode": False, "device": False, "description": False}
CONSOLE_KEYS = {"vidpid": True, "product": False, "serial": False, "interface": False,
                "description": False}


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
    for k, required in keys.items():
        if required and not entry.get(k):
            raise SetupError(f"{path}: [{alias}] has no {k}")
    out = {}
    for k in keys:
        v = entry.get(k, "")
        if not isinstance(v, str):
            raise SetupError(f"{path}: [{alias}] {k} must be a string, not {v!r}")
        v = " ".join(v.split())  # one line, single spaces
        if "|" in v or "|" in alias:
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


def table(name, d=None):
    """The rows of one table, as the bash tools read them."""
    if name == "probes":
        return [f"{a}|{p['serial']}|{p['kind']}|{p['description']}" for a, p in probes(d).items()]
    if name == "devices":
        return [f"{a}|{p['idcode']}|{p['device']}" for a, p in probes(d).items() if p["idcode"]]
    if name == "consoles":
        return [f"{a}|{c['vidpid']}|{c['product']}|{c['serial']}|{c['interface']}|{c['description']}"
                for a, c in consoles(d).items()]
    raise SetupError(f"unknown table '{name}' (tables: probes, devices, consoles)")


USAGE = """usage: jop_setup.py [--setup DIR] table probes|devices|consoles
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
