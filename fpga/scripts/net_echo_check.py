#!/usr/bin/env python3
"""TCP echo checks against NetTest (port 7) -- status item 171.

Each check ends with a fresh echo round trip, so a check fails if the board
stops serving after it, not only if the check's own connection misbehaves.

  echo     one clean 10 KB round trip
  reset    N connections the client aborts with an RST mid-transfer
  idle     a connection held open and silent while another client echoes
  dead     every slot held by silent clients; served again after the
           board's idle timeout (slow: --dead-wait seconds)

Usage:
  net_echo_check.py <host> [--checks echo,reset,idle] [--dead-wait 40]
Exit 0 when every check passes.
"""
import argparse
import os
import socket
import struct
import sys
import time

PORT = 7


def echo(host, size=10240, timeout=10.0):
    """One round trip of `size` random bytes; returns bytes echoed correctly."""
    data = os.urandom(size)
    with socket.create_connection((host, PORT), timeout=timeout) as s:
        s.settimeout(timeout)
        got = bytearray()
        sent = 0
        while sent < len(data):
            sent += s.send(data[sent:sent + 1024])
            try:
                s.settimeout(0.05)
                while True:
                    chunk = s.recv(65536)
                    if not chunk:
                        break
                    got += chunk
            except (socket.timeout, BlockingIOError):
                pass
            finally:
                s.settimeout(timeout)
        deadline = time.time() + timeout
        while len(got) < len(data) and time.time() < deadline:
            try:
                chunk = s.recv(65536)
            except socket.timeout:
                break
            if not chunk:
                break
            got += chunk
    n = 0
    while n < min(len(got), len(data)) and got[n] == data[n]:
        n += 1
    return n


def check_echo(host, args):
    n = echo(host)
    return n == 10240, "echo 10240 -> %d" % n


def rst_close(s):
    s.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))
    s.close()


def check_reset(host, args):
    for i in range(args.resets):
        try:
            s = socket.create_connection((host, PORT), timeout=5)
        except OSError as e:
            return False, "reset: connect %d of %d: %s" % (i + 1, args.resets, e)
        s.sendall(os.urandom(2048))
        time.sleep(0.3)
        rst_close(s)
        time.sleep(0.3)
    try:
        n = echo(host)
    except OSError as e:
        return False, "reset: echo after %d RSTs: %s" % (args.resets, e)
    return n == 10240, "reset: echo after %d RSTs 10240 -> %d" % (args.resets, n)


def check_idle(host, args):
    held = socket.create_connection((host, PORT), timeout=5)
    try:
        time.sleep(0.5)
        try:
            n = echo(host)
        except OSError as e:
            return False, "idle: echo beside a held connection: %s" % e
        return n == 10240, "idle: echo beside a held connection 10240 -> %d" % n
    finally:
        held.close()


def check_dead(host, args):
    # Earlier checks leave connections closing (TIME_WAIT is 2 s on the board).
    time.sleep(5)
    held = []
    try:
        for _ in range(args.slots):
            try:
                held.append(socket.create_connection((host, PORT), timeout=5))
            except OSError:
                break
        if len(held) < args.slots:
            # Not every slot is taken, so the echo below would pass without
            # the board ever dropping a client: the check proves nothing.
            return False, "dead: only %d of %d silent clients connected" % (
                len(held), args.slots)
        time.sleep(args.dead_wait)
        # The board has given up on them; drop them without telling it, as a
        # dead peer would: an RST from us would make the check pass trivially.
        try:
            n = echo(host)
        except OSError as e:
            return False, "dead: echo %d s after %d silent clients: %s" % (
                args.dead_wait, len(held), e)
        return n == 10240, "dead: echo %d s after %d silent clients 10240 -> %d" % (
            args.dead_wait, len(held), n)
    finally:
        for s in held:
            s.close()


CHECKS = {"echo": check_echo, "reset": check_reset, "idle": check_idle,
          "dead": check_dead}


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("host")
    ap.add_argument("--checks", default="echo,reset,idle")
    ap.add_argument("--resets", type=int, default=5)
    ap.add_argument("--slots", type=int, default=4,
                    help="silent clients for 'dead' (NetConfig.MAX_TCP_CONN)")
    ap.add_argument("--dead-wait", type=int, default=40)
    args = ap.parse_args()
    ok = True
    for name in args.checks.split(","):
        try:
            passed, msg = CHECKS[name](args.host, args)
        except OSError as e:
            passed, msg = False, "%s: %s" % (name, e)
        print("%s %s" % ("PASS" if passed else "FAIL", msg), flush=True)
        ok &= passed
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
