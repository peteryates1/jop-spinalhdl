/*
  This file is part of JOP, the Java Optimized Processor
    see <http://www.jopdesign.com/>
*/

package com.jopdesign.build;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * What the linker needs to know about the target machine, read at RUNTIME.
 *
 * WHY THIS EXISTS. JopMethodInfo used to read Const.METHOD_MAX_SIZE directly.
 * Const is generated per configuration, and its fields are `static final int`,
 * so javac INLINES them: the limit was baked into JopMethodInfo.class and
 * jopizer.jar became a PER-CONFIGURATION artefact. Built into one shared
 * location it then went stale in a way make could not see -- the current
 * config's Const.java is older than the jar the previous config left behind, so
 * a preset switch rebuilt nothing and the linker enforced the other machine's
 * limits. Reproduced 2026-09-04; status item 140.
 *
 * Reading the same facts from a generated properties file makes the linker one
 * artefact that is TOLD which machine it is linking for.
 *
 * THERE ARE NO DEFAULTS, deliberately. A default would let JOPizer link
 * successfully against the wrong limits and say nothing, which is the exact
 * failure this replaces -- and a too-large limit produces an image whose
 * methods cannot be invoked at all, discovered on hardware rather than at link
 * time.
 */
public final class LinkerConfig {

    /** System property naming the generated properties file. */
    public static final String PROPERTY = "jop.linker.config";

    private static Properties props;

    private LinkerConfig() { }

    private static Properties load() {
        if (props != null) return props;
        String path = System.getProperty(PROPERTY);
        if (path == null || path.length() == 0) {
            throw new IllegalStateException(
                "-D" + PROPERTY + " is not set.\n"
                + "  JOPizer is built once and told which configuration it is linking for.\n"
                + "  Pass -D" + PROPERTY + "=<build/<config>/java/gen/jop-linker.properties>,\n"
                + "  which jop.generate.ConstGenerator writes beside Const.java.\n"
                + "  There is deliberately no default: linking with another machine's limits\n"
                + "  produces an image that fails on hardware, not at link time.");
        }
        Properties p = new Properties();
        FileInputStream in = null;
        try {
            in = new FileInputStream(path);
            p.load(in);
        } catch (IOException e) {
            throw new IllegalStateException(
                "cannot read " + PROPERTY + " at '" + path + "': " + e.getMessage(), e);
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) { }
        }
        props = p;
        return p;
    }

    private static int intValue(String key) {
        String v = load().getProperty(key);
        if (v == null) {
            throw new IllegalStateException(
                "'" + key + "' missing from " + System.getProperty(PROPERTY)
                + " -- regenerate it with jop.generate.ConstGeneratorMain.");
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("'" + key + "' is not an integer: '" + v + "'", e);
        }
    }

    /** Largest method this build can execute, in BYTES. */
    public static int methodMaxSize()   { return intValue("method.max.size"); }

    /** 5-bit field in the method struct's word2, so a FORMAT limit. */
    public static int methodMaxLocals() { return intValue("method.max.locals"); }

    /** 5-bit field in the method struct's word2, so a FORMAT limit. */
    public static int methodMaxArgs()   { return intValue("method.max.args"); }

    /** RTTM magic address used by ReplaceAtomicAnnotation. */
    public static int memTmMagic()      { return intValue("mem.tm.magic"); }
}
