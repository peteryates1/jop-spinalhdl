/*
  This file is part of JOP, the Java Optimized Processor
    see <http://www.jopdesign.com/>

  This program is free software: you can redistribute it and/or modify
  it under the terms of the GNU General Public License as published by
  the Free Software Foundation, either version 3 of the License, or
  (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package com.jopdesign.build;

import java.io.PrintWriter;
import java.util.Arrays;

import org.apache.bcel.Constants;
import org.apache.bcel.classfile.Code;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.InstructionHandle;
import org.apache.bcel.generic.InstructionList;

/**
 * Emit a static profile of the linked application: how often each opcode
 * appears, and how long the methods are.
 *
 * WHY. Two hardware decisions are currently made by picking a preset and
 * hoping. Which compute units to build is one -- a DCU is ~4,961 LUTs PER CORE
 * on an XC7A100T, so at four cores it is ~19,800, and an application that never
 * executes a double bytecode pays all of it. The method cache geometry is the
 * other: only a method's FIRST block carries a tag, so a method spanning k
 * blocks consumes k tag slots to use one, and the right block size is a
 * property of the method-length distribution. JOPizer already walks every
 * method of the linked application, so it already holds both answers.
 *
 * WHAT THIS DELIBERATELY DOES NOT DO. It reports RAW COUNTS and draws no
 * conclusion -- it does not say "you do not need a DCU". Deciding that needs
 * the registry of which bytecodes are configurable and what may legally replace
 * them, which lives in Scala (`jop.config.BytecodeConfig`, with an
 * `ImpConstraint` per entry). Reproducing that table here would create a second
 * copy to drift out of sync, which is the defect item 52 already tracks. Raw
 * data here, interpretation where the registry is.
 *
 * IT IS ALSO STATIC, NOT DYNAMIC. A count of 1 means the opcode appears once in
 * the code, not that it executes once: an error path and an inner loop look
 * identical. Absence is the trustworthy signal (0 sites cannot execute);
 * presence needs a profile before it justifies hardware. Nor is it a
 * reachability analysis -- these are the methods JOPizer linked, which includes
 * library and runtime code the application never calls.
 */
public class OpcodeStats extends AppVisitor {

	private final long[] counts = new long[256];
	private int[] lengths = new int[1024];
	private int methodCount = 0;
	private long totalCodeBytes = 0;

	/** Block sizes to report slot consumption for, in bytes. */
	private static final int[] BLOCK_SIZES = { 128, 256, 512, 1024 };

	public OpcodeStats(OldAppInfo ai) {
		super(ai);
	}

	public void visitMethod(Method method) {
		Code code = method.getCode();
		if (code == null) {
			return;              // abstract or native: no bytecode to count
		}
		byte[] bytes = code.getCode();
		if (bytes == null || bytes.length == 0) {
			return;
		}

		if (methodCount == lengths.length) {
			lengths = Arrays.copyOf(lengths, lengths.length * 2);
		}
		lengths[methodCount++] = bytes.length;
		totalCodeBytes += bytes.length;

		// BCEL parses the variable-length encoding for us. A method whose code
		// it cannot parse is counted for LENGTH but not for opcodes, rather
		// than aborting the link over a report.
		try {
			InstructionList il = new InstructionList(bytes);
			for (InstructionHandle ih = il.getStart(); ih != null; ih = ih.getNext()) {
				int op = ih.getInstruction().getOpcode() & 0xff;
				counts[op]++;
			}
		} catch (RuntimeException e) {
			System.err.println("OpcodeStats: cannot decode "
					+ clazz.getClassName() + ":" + method.getName()
					+ " (" + e + ") -- counted for length only");
		}
	}

	/** nth percentile of a SORTED prefix, nearest-rank. */
	private static int percentile(int[] sorted, int n, int pct) {
		if (n == 0) {
			return 0;
		}
		int rank = (int) Math.ceil((pct / 100.0) * n) - 1;
		if (rank < 0) {
			rank = 0;
		}
		if (rank >= n) {
			rank = n - 1;
		}
		return sorted[rank];
	}

	public void dump(PrintWriter out) {
		int[] sorted = Arrays.copyOf(lengths, methodCount);
		Arrays.sort(sorted);

		out.println("=== JOP application static profile ===");
		out.println();
		out.println("Static counts over the methods JOPizer linked, including library and");
		out.println("runtime code. A count is APPEARANCES IN CODE, not executions -- an error");
		out.println("path and an inner loop look the same here. Zero is the trustworthy");
		out.println("signal; a non-zero count needs a profile before it justifies hardware.");
		out.println();

		out.println("--- Method length distribution ---");
		out.println("methods        " + methodCount);
		out.println("code bytes     " + totalCodeBytes);
		if (methodCount > 0) {
			out.println("min            " + sorted[0]);
			out.println("median         " + percentile(sorted, methodCount, 50));
			out.println("p90            " + percentile(sorted, methodCount, 90));
			out.println("p99            " + percentile(sorted, methodCount, 99));
			out.println("max            " + sorted[methodCount - 1]);
		}
		out.println();

		// Method cache sizing. Only the first block of a method carries a tag,
		// so a method needing k blocks costs k slots and uses one; the share of
		// methods fitting in a single block is what block size should be chosen
		// for.
		out.println("--- Blocks consumed per method, by block size ---");
		out.println("(only the FIRST block of a method carries a tag, so k blocks = k slots for 1 method)");
		out.println("block   1blk   2blk   3+blk   worst");
		for (int b = 0; b < BLOCK_SIZES.length; b++) {
			int size = BLOCK_SIZES[b];
			int one = 0, two = 0, more = 0, worst = 0;
			for (int i = 0; i < methodCount; i++) {
				int blocks = (sorted[i] + size - 1) / size;
				if (blocks <= 1) {
					one++;
				} else if (blocks == 2) {
					two++;
				} else {
					more++;
				}
				if (blocks > worst) {
					worst = blocks;
				}
			}
			out.println(pad(size + "B", 8) + pad(Integer.toString(one), 7)
					+ pad(Integer.toString(two), 7) + pad(Integer.toString(more), 8)
					+ worst);
		}
		out.println();

		out.println("--- Opcode histogram (present opcodes only) ---");
		out.println("opcode  name                 sites");
		for (int op = 0; op < 256; op++) {
			if (counts[op] == 0) {
				continue;
			}
			String name = (op < Constants.OPCODE_NAMES.length)
					? Constants.OPCODE_NAMES[op] : "(unknown)";
			out.println(pad(hex(op), 8) + pad(name, 21) + counts[op]);
		}
		out.flush();
	}

	private static String hex(int op) {
		String h = Integer.toHexString(op);
		return "0x" + (h.length() < 2 ? "0" + h : h);
	}

	private static String pad(String s, int width) {
		StringBuffer sb = new StringBuffer(s);
		while (sb.length() < width) {
			sb.append(' ');
		}
		return sb.toString();
	}
}
