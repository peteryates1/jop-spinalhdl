package java.io;

/**
 * An InputStreamReader is a bridge from byte streams to character streams.
 * It reads bytes and decodes them into characters.
 *
 * JOP adaptation: ASCII/Latin-1 only (no charset support).
 * Each byte maps directly to the corresponding Unicode code point (0x00-0xFF).
 */
public class InputStreamReader extends Reader {

    private InputStream in;

    public InputStreamReader(InputStream in) {
        super(in);
        this.in = in;
    }

    public int read() throws IOException {
        return in.read();
    }

    public int read(char cbuf[], int offset, int length) throws IOException {
        if (length == 0) return 0;
        int count = 0;
        for (int i = 0; i < length; i++) {
            int b = in.read();
            if (b == -1) {
                return count == 0 ? -1 : count;
            }
            cbuf[offset + i] = (char) (b & 0xFF);
            count++;
        }
        return count;
    }

    public boolean ready() throws IOException {
        return in.available() > 0;
    }

    public void close() throws IOException {
        in.close();
    }
}
