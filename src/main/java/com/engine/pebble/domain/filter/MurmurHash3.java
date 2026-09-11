package com.engine.pebble.domain.filter;

/**
 * Fast 32-bit MurmurHash3 algorithm implementation for byte arrays.
 */
public final class MurmurHash3 {

    private static final int C1 = 0xcc9e2d51;
    private static final int C2 = 0x1b873593;

    private MurmurHash3() {}

    public static int hash32(byte[] data, int offset, int length, int seed) {
        int h1 = seed;
        int roundedEnd = offset + (length & 0xfffffffc); // Round down to multiple of 4

        for (int i = offset; i < roundedEnd; i += 4) {
            int k1 = (data[i] & 0xff)
                    | ((data[i + 1] & 0xff) << 8)
                    | ((data[i + 2] & 0xff) << 16)
                    | ((data[i + 3] & 0xff) << 24);

            k1 *= C1;
            k1 = Integer.rotateLeft(k1, 15);
            k1 *= C2;

            h1 ^= k1;
            h1 = Integer.rotateLeft(h1, 13);
            h1 = h1 * 5 + 0xe6546b64;
        }

        int k1 = 0;
        int tail = length & 0x03;
        if (tail == 3) {
            k1 ^= (data[roundedEnd + 2] & 0xff) << 16;
        }
        if (tail >= 2) {
            k1 ^= (data[roundedEnd + 1] & 0xff) << 8;
        }
        if (tail >= 1) {
            k1 ^= (data[roundedEnd] & 0xff);
            k1 *= C1;
            k1 = Integer.rotateLeft(k1, 15);
            k1 *= C2;
            h1 ^= k1;
        }

        h1 ^= length;
        h1 ^= h1 >>> 16;
        h1 *= 0x85ebca6b;
        h1 ^= h1 >>> 13;
        h1 *= 0xc2b2ae35;
        h1 ^= h1 >>> 16;

        return h1;
    }
}
