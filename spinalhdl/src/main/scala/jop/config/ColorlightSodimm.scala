package jop.config

/**
 * Colorlight module SODIMM pin -> FPGA ball, one table per module.
 *
 * The Colorlight i5 / i9 / i9+ are DDR2-SODIMM-200 modules: power, ground, the
 * on-module Ethernet PHYs' MDI pairs and ~106 FPGA balls on the edge pins.
 * Boards that take a module (Colorlight's own i5/i9 ext board, the Atari 800
 * base board) are described in SODIMM pins ("SODIMM:84"), and the module's
 * table turns that into a ball. That is what lets any base board take any
 * module: the same SODIMM pin is a different ball on each (pin 155 is Y21 on
 * the i9+ and F2 on the i5).
 *
 * GENERATED from the wuxx/Colorlight-FPGA-Projects tables (README.md for the
 * i5 v7.0, colorlight_i9plus_v6.1.md for the i9+), signal pins only -- GND,
 * 5V, NC and the ETH pairs (which go to the on-module PHY) are omitted.
 * Checked 2026-10-10:
 *   - i9+: equal to the Atari 800 base board netlist's U5 symbol, which names
 *     each SODIMM pin by its i9+ ball (0 mismatches over 114 signal pins).
 *   - i5:  equal to the i5 column of the base board review's pinmap.csv (0
 *     mismatches over 82 pins).
 */
object ColorlightSodimm {
  /** I9Plus */
  val SodimmI9Plus: Map[Int, String] = Map(
    41 -> "R2", 42 -> "P5", 44 -> "T6", 46 -> "U7", 48 -> "U6", 49 -> "T3", 50 -> "U5", 51 -> "T4",
    52 -> "V5", 54 -> "U1", 57 -> "U2", 58 -> "H3", 59 -> "U3", 60 -> "J1", 61 -> "V2", 62 -> "K1",
    63 -> "V3", 64 -> "L1", 65 -> "W1", 66 -> "M1", 67 -> "Y1", 68 -> "J2", 69 -> "AA1", 70 -> "K2",
    71 -> "AB1", 72 -> "K3", 73 -> "W2", 74 -> "G3", 75 -> "Y2", 76 -> "J4", 77 -> "AB2", 78 -> "G4",
    79 -> "AA3", 80 -> "F4", 81 -> "AB3", 82 -> "L4", 83 -> "Y3", 84 -> "R3", 85 -> "W4", 86 -> "M3",
    87 -> "AA4", 88 -> "V4", 89 -> "Y4", 90 -> "R4", 91 -> "AB5", 92 -> "T5", 93 -> "AA5", 94 -> "J5",
    95 -> "Y6", 96 -> "J6", 97 -> "AB6", 98 -> "W5", 99 -> "AA6", 100 -> "L5", 101 -> "Y7", 102 -> "L6",
    103 -> "AB7", 104 -> "W6", 109 -> "AA8", 110 -> "V7", 111 -> "AB8", 112 -> "N13", 113 -> "Y8", 114 -> "N14",
    115 -> "W7", 116 -> "P15", 117 -> "Y9", 118 -> "P16", 119 -> "V8", 120 -> "R16", 121 -> "W9", 122 -> "N17",
    123 -> "V9", 124 -> "V17", 125 -> "R14", 126 -> "P17", 127 -> "P14", 128 -> "U17", 129 -> "W17", 130 -> "T18",
    131 -> "Y18", 132 -> "R17", 133 -> "AA18", 134 -> "U18", 135 -> "W19", 136 -> "R18", 137 -> "AB18", 138 -> "N18",
    139 -> "Y19", 140 -> "R19", 141 -> "AA19", 142 -> "N19", 143 -> "V18", 144 -> "N15", 145 -> "V19", 146 -> "M16",
    147 -> "AB20", 148 -> "M15", 149 -> "AA20", 150 -> "L15", 151 -> "AA21", 152 -> "L16", 153 -> "AB21", 154 -> "K14",
    155 -> "Y21", 156 -> "N22")

  /** I5 */
  val SodimmI5: Map[Int, String] = Map(
    41 -> "U16", 42 -> "R1", 44 -> "T1", 46 -> "U1", 48 -> "Y2", 49 -> "K18", 50 -> "W1", 51 -> "C18",
    52 -> "V1", 54 -> "M1", 57 -> "T18", 58 -> "N2", 59 -> "R18", 60 -> "N3", 61 -> "R17", 62 -> "T2",
    63 -> "P17", 64 -> "M3", 65 -> "M17", 66 -> "T3", 67 -> "T17", 68 -> "R3", 69 -> "U18", 70 -> "N4",
    71 -> "U17", 72 -> "M4", 73 -> "P18", 74 -> "L4", 75 -> "N17", 76 -> "L5", 77 -> "N18", 78 -> "P16",
    79 -> "M18", 80 -> "J16", 81 -> "L20", 82 -> "J18", 83 -> "L18", 84 -> "J17", 85 -> "K20", 86 -> "H18",
    87 -> "K19", 88 -> "H17", 89 -> "J20", 90 -> "G18", 91 -> "J19", 92 -> "H16", 93 -> "H20", 94 -> "F18",
    95 -> "G20", 96 -> "G16", 97 -> "G19", 98 -> "E18", 99 -> "F20", 100 -> "F17", 101 -> "F19", 102 -> "F16",
    103 -> "E20", 104 -> "E16", 109 -> "E19", 110 -> "E17", 111 -> "D20", 112 -> "D18", 113 -> "D19", 114 -> "D17",
    115 -> "C20", 116 -> "G5", 117 -> "B20", 118 -> "D16", 119 -> "B19", 120 -> "F5", 121 -> "B18", 122 -> "E6",
    123 -> "A19", 124 -> "E5", 125 -> "C17", 126 -> "F4", 127 -> "A18", 128 -> "E4", 129 -> "D3", 130 -> "F1",
    131 -> "C4", 132 -> "F3", 133 -> "B4", 134 -> "G3", 135 -> "C3", 136 -> "H3", 137 -> "E3", 138 -> "H4",
    139 -> "A3", 140 -> "H5", 141 -> "C2", 142 -> "J4", 143 -> "B1", 144 -> "J5", 145 -> "C1", 146 -> "K3",
    147 -> "D2", 148 -> "K4", 149 -> "D1", 150 -> "K5", 151 -> "E2", 152 -> "B3", 153 -> "E1", 154 -> "A2",
    155 -> "F2", 156 -> "B2")
}
