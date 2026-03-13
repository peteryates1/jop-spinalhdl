package java.security;

import java.util.Random;

/**
 * Minimal SecureRandom stub for JOP. Delegates to java.util.Random.
 * JOP has no cryptographic random source, so this is NOT secure.
 * Only exists to satisfy BigInteger's prime generation dependency.
 */
public class SecureRandom extends Random {
    public SecureRandom() {
        super();
    }

    public SecureRandom(byte[] seed) {
        super(bytesToLong(seed));
    }

    private static long bytesToLong(byte[] seed) {
        long result = 0;
        int len = seed.length < 8 ? seed.length : 8;
        for (int i = 0; i < len; i++) {
            result = (result << 8) | (seed[i] & 0xFF);
        }
        return result;
    }
}
