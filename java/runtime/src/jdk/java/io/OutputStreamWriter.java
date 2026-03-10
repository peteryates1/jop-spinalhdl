package java.io;

/**
 * An OutputStreamWriter is a bridge from character streams to byte streams.
 * Characters written to it are encoded into bytes.
 *
 * JOP adaptation: ASCII/Latin-1 only (no charset support).
 * Characters 0x00-0xFF map directly to bytes; characters > 0xFF become '?'.
 */
public class OutputStreamWriter extends Writer {

    private OutputStream out;

    public OutputStreamWriter(OutputStream out) {
        super(out);
        this.out = out;
    }

    public void write(int c) throws IOException {
        out.write(c > 0xFF ? '?' : c);
    }

    public void write(char cbuf[], int off, int len) throws IOException {
        for (int i = 0; i < len; i++) {
            char c = cbuf[off + i];
            out.write(c > 0xFF ? '?' : c);
        }
    }

    public void write(String str, int off, int len) throws IOException {
        for (int i = 0; i < len; i++) {
            char c = str.charAt(off + i);
            out.write(c > 0xFF ? '?' : c);
        }
    }

    public void flush() throws IOException {
        out.flush();
    }

    public void close() throws IOException {
        out.flush();
        out.close();
    }
}
