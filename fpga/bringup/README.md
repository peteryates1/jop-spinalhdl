# Bring-up jigs

Small designs used to prove a board, a cable or a connector is alive. **They are
deliberately independent of the JOP config and build system, and must stay that
way.**

## Why they are not converted

A jig that shares machinery with the thing under test is compromised as a
diagnostic. If `JopTop` will not elaborate, or the constraint generators emit
the wrong pins, or a preset is missing — you still need a way to prove the board
and the cable work. That answer must not depend on any of the machinery in
question.

`WukongUartLoopback.v` already said as much in its own header: *"a test fixture,
not part of any design, and three assigns do not warrant a generator entry."*

So the risk is not that these are hand-written. It is that they LOOK like
ordinary flows, and someone converts them on a tidy-up — which removes their
value silently, because a converted jig still passes right up until the moment
you need it to be independent.

Hence: one directory per jig, all under here, each self-contained.

## What is here

| jig | board | what it proves |
|-----|-------|----------------|
| `a-e115fb-uart-loopback` | A-E115FB (EP4CE115) | UART wiring: RX looped back to TX |
| `wukong-uart-loopback` | QMTECH Wukong (XC7A100T) | the same, three assigns of Verilog |
| `ep4cgx150-eth-ref` | EP4CGX150 + DB_FPGA V4 | a THIRD-PARTY Ethernet reference design on the RTL8211EG PHY |

`ep4cgx150-eth-ref` is worth knowing about for a second reason. It is an
independent implementation against the same PHY as our own MAC, so when the JOP
Ethernet links but passes no packets (status item 68), it separates "the board,
PHY and cable are fine" from "our MAC is at fault" — which nothing else here can
do.

## Rules

- No dependency on `jop.config`, `build/<config>/`, or the generators.
- Hand-written constraints are correct here. Do not replace them with generated
  ones.
- If a jig stops building, fix it or retire it — do not convert it.
