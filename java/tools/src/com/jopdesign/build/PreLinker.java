/*
 * This file is part of JOP, the Java Optimized Processor
 *   see <http://www.jopdesign.com/>
 *
 * Copyright (C) 2008, Martin Schoeberl (martin@jopdesign.com)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.jopdesign.build;

import com.jopdesign.common.AppInfo;
import com.jopdesign.common.AppSetup;

/**
 * Pre-link transformations on class files before JOPizer.
 *
 * Replaces IINC bytecodes and inserts monitorenter/monitorexit
 * for synchronized methods. These transforms must be applied
 * consistently by both JOPizer and any analysis tools (e.g. WCA).
 *
 * Renamed from com.jopdesign.wcet.WCETPreprocess per the FIXME there:
 * "should really be moved to build, and not called WCETPreprocess"
 *
 * @author Martin Schoeberl
 * @author Stefan Hepp
 */
public class PreLinker {

    public PreLinker() {
    }

    public static void preprocess(AppInfo ai) {
        ai.iterate(new ReplaceIinc());
        ai.iterate(new InsertSynchronized());
        ai.iterate(new InjectSwap());
    }

    public static void main(String[] args) {

        AppSetup setup = new AppSetup();
        AppInfo ai = setup.initAndLoad(args, false, false, true);

        preprocess(ai);

        // write the class files
        setup.writeClasses();
    }

}
