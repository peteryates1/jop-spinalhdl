/*
  Symbol table loader for JOP debug simulator.
  Parses .link.txt files produced by JOPizer to provide
  Java-level names for method addresses and static fields.
*/

package com.jopdesign.tools;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

public class JopSymbols {

	// static field address -> "class.field" name
	private Map<Integer, String> staticFields = new HashMap<Integer, String>();

	// bytecode address -> "class.method(sig)" (sorted for floor lookups)
	private TreeMap<Integer, String> methods = new TreeMap<Integer, String>();

	public void load(String linkFile) {
		try {
			BufferedReader br = new BufferedReader(new FileReader(linkFile));
			String line;
			while ((line = br.readLine()) != null) {
				line = line.trim();
				if (line.startsWith("static ")) {
					parseStatic(line);
				} else if (line.startsWith("bytecode ")) {
					parseBytecode(line);
				} else if (line.startsWith("-mtab ")) {
					parseMtab(line);
				}
			}
			br.close();
			System.out.println("JopSymbols: loaded " + staticFields.size() +
				" static fields, " + methods.size() + " methods from " + linkFile);
		} catch (IOException e) {
			System.out.println("JopSymbols: failed to load " + linkFile + ": " + e.getMessage());
		}
	}

	// Format: "static com.jopdesign.sys.GC.mem_startI 29"
	// The field name includes type descriptor suffix (I, Z, J, L..., [...)
	private void parseStatic(String line) {
		// "static <qualified.field><type> <addr>"
		String rest = line.substring(7); // skip "static "
		int lastSpace = rest.lastIndexOf(' ');
		if (lastSpace < 0) return;
		String qualifiedField = rest.substring(0, lastSpace);
		int addr;
		try {
			addr = Integer.parseInt(rest.substring(lastSpace + 1).trim());
		} catch (NumberFormatException e) {
			return;
		}
		// Strip type descriptor suffix for readability
		// Type descriptors: I, Z, B, C, S, J, D, F, L...;, [...
		String name = stripTypeDescriptor(qualifiedField);
		staticFields.put(addr, name);
	}

	// Format: "bytecode com.jopdesign.sys.GC.push(I)V 1110"
	private void parseBytecode(String line) {
		String rest = line.substring(9); // skip "bytecode "
		int lastSpace = rest.lastIndexOf(' ');
		if (lastSpace < 0) return;
		String qualifiedMethod = rest.substring(0, lastSpace);
		int addr;
		try {
			addr = Integer.parseInt(rest.substring(lastSpace + 1).trim());
		} catch (NumberFormatException e) {
			return;
		}
		methods.put(addr, qualifiedMethod);
	}

	// Format: " -mtab java.io.InputStream.skip(J)J 6923"
	// mtab entries are method table slots — mp points here for virtual calls
	private void parseMtab(String line) {
		String rest = line.substring(6); // skip "-mtab "
		int lastSpace = rest.lastIndexOf(' ');
		if (lastSpace < 0) return;
		String qualifiedMethod = rest.substring(0, lastSpace);
		int addr;
		try {
			addr = Integer.parseInt(rest.substring(lastSpace + 1).trim());
		} catch (NumberFormatException e) {
			return;
		}
		// Only add if not already present (bytecode entries take priority)
		if (!methods.containsKey(addr)) {
			methods.put(addr, qualifiedMethod);
		}
	}

	// Strip JVM type descriptor from field name
	// e.g. "com.jopdesign.sys.GC.mem_startI" -> "com.jopdesign.sys.GC.mem_start"
	// e.g. "com.jopdesign.sys.GC.mutexLjava/lang/Object;" -> "com.jopdesign.sys.GC.mutex"
	// e.g. "com.jopdesign.sys.GC.roots[I" -> "com.jopdesign.sys.GC.roots"
	private String stripTypeDescriptor(String qualifiedField) {
		// Find the last dot to split class from field
		int lastDot = qualifiedField.lastIndexOf('.');
		if (lastDot < 0) return qualifiedField;
		String className = qualifiedField.substring(0, lastDot);
		String fieldWithType = qualifiedField.substring(lastDot + 1);

		// Strip type: L...;, [..., or single char (I,Z,B,C,S,J,D,F)
		String field = fieldWithType;
		int lIdx = fieldWithType.indexOf('L');
		int arrIdx = fieldWithType.indexOf('[');
		if (lIdx >= 0 && (arrIdx < 0 || lIdx < arrIdx)) {
			field = fieldWithType.substring(0, lIdx);
		} else if (arrIdx >= 0) {
			field = fieldWithType.substring(0, arrIdx);
		} else if (fieldWithType.length() > 0) {
			// Single-char type descriptor at end
			char last = fieldWithType.charAt(fieldWithType.length() - 1);
			if ("IZBCSJDF".indexOf(last) >= 0) {
				field = fieldWithType.substring(0, fieldWithType.length() - 1);
			}
		}
		return className + "." + field;
	}

	/**
	 * Find the method containing the given method struct address.
	 * Uses floor entry in TreeMap — the method whose bytecode address
	 * is <= the given address.
	 */
	public String methodContaining(int mpAddr) {
		// mp points to method struct — may be at bytecode addr or mtab addr
		String name = methods.get(mpAddr);
		if (name != null) return formatMethod(name);
		return null;
	}

	/**
	 * Look up static field name by address.
	 */
	public String staticFieldName(int addr) {
		return staticFields.get(addr);
	}

	/**
	 * Format a method signature for display.
	 * Simplifies "com.jopdesign.sys.GC.push(I)V" to "GC.push(I)V"
	 */
	private String formatMethod(String fullName) {
		// Find the second-to-last dot before '(' to get ClassName.method
		int parenIdx = fullName.indexOf('(');
		String prefix = (parenIdx >= 0) ? fullName.substring(0, parenIdx) : fullName;
		String suffix = (parenIdx >= 0) ? fullName.substring(parenIdx) : "";

		int lastDot = prefix.lastIndexOf('.');
		if (lastDot < 0) return fullName;

		int prevDot = prefix.lastIndexOf('.', lastDot - 1);
		if (prevDot >= 0) {
			return prefix.substring(prevDot + 1) + suffix;
		}
		return fullName;
	}
}
