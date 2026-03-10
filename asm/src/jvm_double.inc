//
//  jvm_double.inc — Double-precision hardware compute unit handlers
//
//  These handlers dispatch to the DoubleComputeUnit (DCU, unit=3) via
//  the stop/sthw/wait/ldop microcode pattern.
//
//  DCU unit selector = 3 (sthw bits[5:4] = 11)
//  DCU opcodes: dadd=0, dsub=1, dmul=2, ddiv=3, dcmpl=4, dcmpg=5,
//               f2d=6, d2f=7, i2d=8, d2i=9, l2d=10, d2l=11
//
//  Operand mapping (DCU IDLE state):
//    4-operand binary: value1=opStack[3]:opStack[2], value2=opStack[1]:opStack[0]
//    2-operand (d2i/d2f/d2l): opStack[1]:opStack[0] = double value
//    2-operand (l2d): opStack[1]:opStack[0] = long value
//    1-operand (i2d/f2d): opStack[0] = int/float value
//

// ====================================================================
// Binary double ops: dadd, dsub, dmul, ddiv
// Stack: ..., value1_hi, value1_lo, value2_hi, value2_lo (TOS=value2_lo)
// Result: 2 words (hi, lo)
// ====================================================================

dadd:
dadd_hw:
			stop				// pop value2_lo -> opStack[0]
			stop				// pop value2_hi -> opStack[1]
			stop				// pop value1_lo -> opStack[2]
			stop				// pop value1_hi -> opStack[3]
			sthw 48				// start DCU dadd (unit=3, op=0)
			wait
			wait
			ldop				// push result_hi
			ldop nxt			// push result_lo

dsub:
dsub_hw:
			stop
			stop
			stop
			stop
			sthw 49				// start DCU dsub (unit=3, op=1)
			wait
			wait
			ldop
			ldop nxt

dmul:
dmul_hw:
			stop
			stop
			stop
			stop
			sthw 50				// start DCU dmul (unit=3, op=2)
			wait
			wait
			ldop
			ldop nxt

ddiv:
ddiv_hw:
			stop
			stop
			stop
			stop
			sthw 51				// start DCU ddiv (unit=3, op=3)
			wait
			wait
			ldop
			ldop nxt

// ====================================================================
// Double compare: dcmpl, dcmpg
// Stack: ..., value1_hi, value1_lo, value2_hi, value2_lo (TOS=value2_lo)
// Result: 1 word (-1, 0, or 1)
// ====================================================================

dcmpl:
dcmpl_hw:
			stop
			stop
			stop
			stop
			sthw 52				// start DCU dcmpl (unit=3, op=4)
			wait
			wait
			ldop nxt			// push single result

dcmpg:
dcmpg_hw:
			stop
			stop
			stop
			stop
			sthw 53				// start DCU dcmpg (unit=3, op=5)
			wait
			wait
			ldop nxt

// ====================================================================
// Conversions: 1 operand → 2-word result
// ====================================================================

// f2d: float (1 word) → double (2 words)
// Stack: ..., float_value (TOS=float)
f2d:
f2d_hw:
			stop				// pop float -> opStack[0]
			sthw 54				// start DCU f2d (unit=3, op=6)
			wait
			wait
			ldop				// push result_hi
			ldop nxt			// push result_lo

// i2d: int (1 word) → double (2 words)
// Stack: ..., int_value (TOS=int)
i2d:
i2d_hw:
			stop				// pop int -> opStack[0]
			sthw 56				// start DCU i2d (unit=3, op=8)
			wait
			wait
			ldop
			ldop nxt

// ====================================================================
// Conversions: 2 operands → 2-word result
// ====================================================================

// l2d: long (2 words) → double (2 words)
// Stack: ..., long_hi, long_lo (TOS=long_lo)
l2d:
l2d_hw:
			stop				// pop long_lo -> opStack[0]
			stop				// pop long_hi -> opStack[1]
			sthw 58				// start DCU l2d (unit=3, op=10)
			wait
			wait
			ldop
			ldop nxt

// ====================================================================
// Conversions: 2 operands → 1-word result
// ====================================================================

// d2i: double (2 words) → int (1 word)
// Stack: ..., double_hi, double_lo (TOS=double_lo)
d2i:
d2i_hw:
			stop				// pop double_lo -> opStack[0]
			stop				// pop double_hi -> opStack[1]
			sthw 57				// start DCU d2i (unit=3, op=9)
			wait
			wait
			ldop nxt			// push single int result

// d2f: double (2 words) → float (1 word)
// Stack: ..., double_hi, double_lo (TOS=double_lo)
d2f:
d2f_hw:
			stop
			stop
			sthw 55				// start DCU d2f (unit=3, op=7)
			wait
			wait
			ldop nxt			// push single float result

// ====================================================================
// Conversions: 2 operands → 2-word result
// ====================================================================

// d2l: double (2 words) → long (2 words)
// Stack: ..., double_hi, double_lo (TOS=double_lo)
d2l:
d2l_hw:
			stop
			stop
			sthw 59				// start DCU d2l (unit=3, op=11)
			wait
			wait
			ldop
			ldop nxt
