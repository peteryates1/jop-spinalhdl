/*
  This file is part of JOP, the Java Optimized Processor
    see <http://www.jopdesign.com/>

  Copyright (C) 2026, Peter Yates

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

import com.jopdesign.common.ClassInfo;
import com.jopdesign.common.MethodCode;
import com.jopdesign.common.MethodInfo;
import com.jopdesign.common.graphutils.ClassVisitor;
import org.apache.bcel.generic.ILOAD;
import org.apache.bcel.generic.IRETURN;
import org.apache.bcel.generic.InstructionList;
import org.apache.bcel.generic.SWAP;

/**
 * Injects SWAP bytecodes into SwapTest methods.
 *
 * javac never emits the SWAP bytecode (0x5F), so we use BCEL to inject
 * it into placeholder methods at link time. This allows testing the
 * hardware implementation of SWAP on JOP.
 *
 * doSwap(a, b): iload_0, iload_1, SWAP, pop, ireturn -> returns b
 *
 * The POP after SWAP is critical: JOP's ireturn microcode pops the
 * return value then expects the method pointer at TOS. Without POP,
 * the extra operand stack entry corrupts the frame unwinding.
 *
 * Without SWAP, TOS after POP would be local[0]=a (first loaded).
 * With SWAP, TOS after POP is local[1]=b, proving SWAP works.
 */
public class InjectSwap implements ClassVisitor {

    public InjectSwap() {
    }

    @Override
    public boolean visitClass(ClassInfo classInfo) {
        if (!classInfo.getClassName().equals("jvm.SwapTest")) {
            return true;
        }

        System.out.println("InjectSwap: processing " + classInfo.getClassName());
        for (MethodInfo method : classInfo.getMethods()) {
            if (method.isAbstract() || method.isNative()) {
                continue;
            }
            String name = method.getShortName();
            System.out.println("InjectSwap:   method " + name);
            if (name.equals("doSwap")) {
                System.out.println("InjectSwap:   injecting SWAP into doSwap");
                injectSwap(method, 0, 1);  // push a, b, swap, pop -> returns b
            }
        }
        return true;
    }

    @Override
    public void finishClass(ClassInfo classInfo) {
    }

    /**
     * Replace method body with: iload(first), iload(second), SWAP, POP, ireturn.
     * After SWAP, TOS is the value loaded by 'first'. POP removes it,
     * leaving the value loaded by 'second' (swapped down) as TOS.
     * ireturn returns TOS = value of local[second].
     */
    private void injectSwap(MethodInfo method, int first, int second) {
        MethodCode mc = method.getCode();
        InstructionList il = new InstructionList();
        il.append(new ILOAD(first));
        il.append(new ILOAD(second));
        il.append(new SWAP());
        il.append(new org.apache.bcel.generic.POP());
        il.append(new IRETURN());
        mc.setInstructionList(il);
        method.compile();
    }

    /**
     * Replace method body with just: iload(index), ireturn.
     * No SWAP - for testing if method body replacement itself causes issues.
     */
    private void injectNoSwap(MethodInfo method, int index) {
        MethodCode mc = method.getCode();
        InstructionList il = new InstructionList();
        il.append(new ILOAD(index));
        il.append(new IRETURN());
        mc.setInstructionList(il);
        method.compile();
    }

    /**
     * Replace method body with: iload(first), iload(second), POP, ireturn.
     * Same stack depth as SWAP variant but without SWAP opcode.
     * Returns second arg (like SWAP variant) because we pop the first-loaded.
     */
    private void injectPopVariant(MethodInfo method, int first, int second) {
        MethodCode mc = method.getCode();
        InstructionList il = new InstructionList();
        il.append(new ILOAD(first));
        il.append(new ILOAD(second));
        il.append(new org.apache.bcel.generic.POP());
        il.append(new IRETURN());
        mc.setInstructionList(il);
        method.compile();
    }
}
