# Board instances

**Status: all four steps done (2026-10-28 to 10-31).** Tracked as status item
[174](../current-status.md#item-174). The layering it starts is item
[173](../current-status.md#item-173).

## Why

More i9+ modules are coming, each on its own Atari-800-derived base board,
and they must all be usable on the LAN at once. Two things stop that today.

**One network identity for every board.** `NetConfig` gives every JOP image
the MAC 02:00:00:00:00:01 and the IP 192.168.0.123. A second live JOP board
pulls that MAC to its own switch port, and traffic for the first goes there
instead; that confounded item 170's measurements.

**Unit facts on board designs.** `Board` describes a PCB design but also
carries one physical unit's probe alias, console alias and loader cable. So
the second Atari-800 base board had to become a copied board definition,
`ColorlightAtari800BaseV1Unit2`, plus a copied assembly. Which parts are
fitted on it is recorded only in that copy's comment.

The MAC must not depend on the FPGA. The Xilinx DNA would work on the i9+,
but the ECP5 has no fabric-readable ID and the Cyclone IV has none at all.

## Three layers (item 173)

| layer | what | examples |
|---|---|---|
| generic | basic JOP, every board and environment | core, microcode, runtime, net stack, tools, generators, flows |
| hardware support | boards, modules and parts, usable by anyone with the same hardware | `Parts.scala`, board designs, assemblies, presets, per-board Makefiles, board docs |
| setup | one lab's physical units | instances: which module on which base board, fitted parts, probe and console serials, MACs, hostnames; the host's toolchain paths |

Instances are the first piece of the setup layer, so they start outside
`Board.scala`.

## What an instance is

One entry per physical unit:

- **name**: unique and stable. It is also the DHCP hostname, e.g. `jop-i9plus-1`.
- **assembly**: which module on which base board, by design.
- **fitted**: for each device of its boards, fitted, not fitted, or fitted but
  broken. A preset built for the instance may use only fitted devices;
  anything else fails at elaboration.
- **probe** and **console**: the serials that `jtag_probe_map` and
  `usb_serial_map` hold by hand today.
- **FPGA grade**: it belongs to the physical chip, so `withGrade` belongs here
  (one i9+ may be -1, another -2). The default comes from the module design.
- **DNA** (optional, Xilinx only): an extra check that a probe reaches the
  expected chip. Not used for identity.
- **MAC**: `02:4A:4F:50:00:nn` ("JOP"), locally administered unicast, assigned
  explicitly per instance.
- **presets**: which presets it builds.

The two Atari-800 base boards, as instances:

| instance | module | fitted |
|---|---|---|
| i5 unit | Colorlight i5 | RP2040-STAMP, reset button, Ethernet jack |
| i9+ unit | Colorlight i9+ | all connectors; HDMI fitted but broken |

## How a build uses it

- **The bitstream stays per preset**, shared by every unit of that assembly:
  no Vivado run per unit.
- **The Java image becomes per (preset, instance)**, about a minute's build.
  Its generated `Const.java` carries the instance's MAC and hostname next to
  today's I/O map, and `NetConfig` takes its defaults from there.
- **That generated layer is the board support package.** It holds the
  instance's hardware constants and the libraries its fitted parts allow,
  e.g. the net stack only where Ethernet is fitted. An app that needs a part
  the instance lacks fails to build. The JDK is NOT per board: JOPizer's
  BCEL 5.2 needs Java 6 class files everywhere.

## Network

- **DHCP and DNS from the router, to be verified.** Each board sends its
  instance name as its hostname (DHCP option 12). Whether the router's DNS
  then resolves it is measured once the board sends one. If it does not, the
  fallback is a reservation per MAC on the router plus names elsewhere, for
  example on the Pi 4.
- **Two DHCP client fixes for simultaneous boots.** Every board starts with
  the same transaction ID (`0x4A4F5001`), and replies are matched on it alone.
  Two boards booting together could take each other's offers. The fixes: seed
  the transaction ID from the MAC, and check the reply's hardware address.
- **NetTest moves from static .123 to DHCP**, and the test scripts address
  boards by name.

## Steps

Each step is test-first.

1. **The instance registry**, in a `jop.setup` package outside the board
   designs, with the two Atari-800 units migrated and
   `ColorlightAtari800BaseV1Unit2` removed. Tests: every instance's MAC is
   unique, unicast and locally administered; every preset an instance builds
   uses only fitted devices.

   **Done 2026-10-28.** `jop.setup.Instance` and `Instances` hold `jop-i9plus-1`
   (02:4a:4f:50:00:01) and `jop-i5-1` (02:4a:4f:50:00:02; LEDs not fitted).
   The base board is one design again. How a unit is reached is a
   `UnitAccess`: `HwVerifyDescriptor` takes one, and only its entry point reads
   the registry, so the generic layer still never imports `jop.setup`.
   `check-probe-selection.sh` reads the instances too: without that the move
   would have dropped them from it silently, 9 units checked becoming 7. It was
   proved red on a mistyped instance alias, a console with no probe, and the
   no-parse tripwire. `InstanceTest` failed first on the base board design
   still naming jop-i9plus-1's probe.
2. **MAC and hostname into the generated constants**, with `NetConfig`'s
   defaults taken from them.

   **Done 2026-10-28.** `ConstGenerator` writes `ETH_MAC_HI`, `ETH_MAC_LO` and
   `ETH_HOSTNAME` from a `NetIdentity`, a generic type. Its entry point passes
   the instance's, and `NetConfig` defaults from them. `java/Makefile` lists
   `jop/setup/` among `Const.java`'s inputs, so a changed MAC regenerates it;
   `check-generated-deps.sh` asserts that edge and went red first. On the i5
   unit, NetTest prints `MAC=02:4A:4F:50:00:02`. 123 frames went out from that
   MAC and none from the shared one, the host's neighbour entry took it, and
   every network check passed. Boards no instance covers keep
   02:00:00:00:00:01 and an empty hostname. Note that only `make -C java`
   regenerates `Const.java`; an app build alone compiles against the old one.
3. **The DHCP client**: option 12, a transaction ID seeded from the MAC, and
   the hardware-address check. Then measure whether the router resolves the
   names.

   **Done 2026-10-28.** DISCOVER and REQUEST carry option 12, which is
   `NetConfig.hostname`, the instance name. The transaction ID is seeded once
   from a hash of the MAC (Knuth's multiplicative constant), so two units' IDs
   differ even when their MACs differ by one. A reply must match our hardware
   address as well as the ID.

   Tested on the wire. `dhcp_check.py` (scratchpad) failed on the old client
   -- no hostname, and the shared ID 0x4a4f5002 -- and passed after. Then both
   Atari-800 units booted DhcpTest at the same moment: `jop-i9plus-1` used xid
   0x0c8229b1 and got lease .238; `jop-i5-1` used 0xaabba364 and got .239.
   No automated test covers the DHCP client, since no simulation drives the
   net stack; the check is that script, on hardware.

   **The router as DNS, measured.** It does register each unit's hostname: a
   reverse lookup at 192.168.0.1 gives .238 -> `jop-i9plus-1` and .239 ->
   `jop-i5-1`. But forward lookups fail:
   - a bare local name gets no reply, not even the router's own name or this
     VM's;
   - `.lan`, `.home`, `.local`, `.localdomain` and `.home.arpa` give NXDOMAIN;
   - its DHCP hands clients the ISP's DNS (194.168.4.100) rather than itself.

   So name -> address needs something else (open question below).

   **Answered 2026-10-29 by Pi-hole.** The user moved DHCP and DNS to Pi-hole
   in Docker on the Pi 4 (192.168.0.2), with the router's DHCP off. Both units
   booted DhcpTest at the same moment again:
   - Pi-hole served both and handed out itself as DNS, with the router as the
     gateway.
   - It offered **the same address, .227, to both** units. `jop-i9plus-1` took it;
     `jop-i5-1`'s REQUEST got a NAK, so it restarted with a new transaction ID
     and bound .228. That is the race the per-unit IDs and the hardware-address
     check exist for.
   - Forward lookups at Pi-hole now work: `jop-i9plus-1` -> .227 and `jop-i5-1` ->
     .228. Reverse gives `jop-i9plus-1.local` and `jop-i5-1.local`, since Pi-hole's
     domain is `local`; `.lan` names do not exist.
   - The units' own DNS client resolves through it too (DhcpTest's lookup of
     example.com).

   A host on an old router lease keeps the ISP's DNS until it renews.
4. **The tools by instance**: the alias tables generated from, or checked
   against, the instances, and `hw_verify` and download taking an instance
   name. This changes the flow scripts, so it needs a separate go-ahead.

   **Done 2026-10-31** (option 2a: a Java tree per unit, the bitstream per
   preset):
   - `Instances.select` picks the unit. A named one must exist, be on the
     preset's assembly and build that preset; unnamed, it is the assembly's
     only unit, and several units of one assembly are an error that lists
     them.
   - `--instance NAME` on `HwVerifyDescriptorMain` and `ConstGeneratorMain`.
     The descriptor gains `JAVA_DIR`, `INSTANCE` and `HOSTNAME`.
   - `JOP_INSTANCE=NAME` in `java/config.mk` moves the whole Java tree --
     constants, tools, runtime, apps -- to `build/<config>/java/<name>/` and
     generates for that unit.
   - `hw_verify.py --instance NAME` downloads from that tree, and its log line
     records `@NAME`.
   - `make download INSTANCE=NAME` (console.mk) uses that unit's console and
     image, and stops if the unit is not on the Makefile's preset.

   Test first: `InstanceTest` (the selection rules, with a fake second i5
   unit for the ambiguity, and the descriptor through its real main) and
   `check-generated-deps.sh` (the `JOP_INSTANCE` edge, through `make -p`)
   both failed before the change. A download naming the wrong unit used to
   go silently to the Makefile's own console; now it stops. On hardware, by
   name: `jop-i5-1` and `jop-i9plus-1` both passed DoAll 68/68 from their own
   trees.

   Not covered: no second unit of one assembly exists yet, so the ambiguity
   error is tested only in Scala. Network checks still address a unit by IP.

## Open questions

- **Name -> address**, since the router does not resolve local names. Settled
  by Pi-hole (2026-10-29, step 3 above). The alternatives stay on record:
  - **The boards answer for their own names on the link.** The host's
    systemd-resolved already multicasts an LLMNR query (224.0.0.252:5355) for
    a bare name such as `jop-i5-1`, and nothing answers it. A small LLMNR
    responder in the net stack would make the name resolve here with no
    server and no host change. mDNS (`jop-i5-1.local`) is the more common
    standard, but this host would need avahi or resolved's mDNS turned on.
  - **The Pi 4 as the LAN's DNS** (dnsmasq): conventional, but it is not
    configured for this network, and it would need the names (static entries
    plus reservations, or the Pi serving DHCP too).
  - **The test tools read the lease from the console**: DhcpTest already
    prints it. That works today, but only for scripts.

- **The setup layer's form** (item 173): a Scala package, or a data file that
  Scala and the Python tools both read. TOML would need no new package in
  Python 3.13 but one small library in Scala. Step 1 uses a package so the
  choice stays open.
- **Where the per-instance Java images go** in the build tree.
- **Scope of step 1:** only the two Atari-800 units first, or every board's
  unit facts at once.
