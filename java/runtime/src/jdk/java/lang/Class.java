/*
 * Minimal Class stub for JOP.
 *
 * JOP does not support reflection, so this is a skeleton that satisfies
 * compile-time references from Enum, annotation types, and other JDK
 * classes.  Runtime behaviour is limited to identity operations.
 *
 * Source: jopmin (adapted — removed native getName0, TBDException dependency)
 */
package java.lang;

import java.io.Serializable;

public class Class<T> implements Serializable {

    private static final long serialVersionUID = 6439331877174428760L;

    /**
     * Returns the name of this class.  JOP has no VM support for class
     * name retrieval, so this returns a placeholder.
     */
    public String getName() {
        return "?";  // No VM class name support on JOP
    }

    /**
     * Determines if the specified object is assignment-compatible with
     * this Class.  JOP has no runtime type information beyond instanceof
     * bytecode, so this always throws UnsupportedOperationException.
     */
    public boolean isInstance(Object obj) {
        throw new UnsupportedOperationException("Class.isInstance requires reflection");
    }
}
