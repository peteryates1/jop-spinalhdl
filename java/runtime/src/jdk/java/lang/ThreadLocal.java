package java.lang;

/**
 * Minimal ThreadLocal stub for JOP. JOP is single-threaded per core,
 * so this simply wraps a single value with lazy initialization.
 */
public class ThreadLocal<T> {

    private T value;
    private boolean initialized;

    public ThreadLocal() {
    }

    public T get() {
        if (!initialized) {
            value = initialValue();
            initialized = true;
        }
        return value;
    }

    public void set(T value) {
        this.value = value;
        initialized = true;
    }

    public void remove() {
        value = null;
        initialized = false;
    }

    protected T initialValue() {
        return null;
    }
}
