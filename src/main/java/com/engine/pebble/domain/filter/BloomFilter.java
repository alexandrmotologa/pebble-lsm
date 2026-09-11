package com.engine.pebble.domain.filter;

import com.engine.pebble.common.ByteSlice;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Probabilistic Bloom filter using MurmurHash3 dual-hashing.
 * Prevents unnecessary disk reads by testing key presence with low false-positive rate.
 */
public final class BloomFilter {

    private final int numBits;
    private final int k; // Number of hash functions
    private final long[] words;

    public BloomFilter(int expectedElements, double fpp) {
        if (expectedElements <= 0) {
            expectedElements = 1;
        }
        if (fpp <= 0.0 || fpp >= 1.0) {
            fpp = 0.01;
        }

        // m = - (n * ln(p)) / (ln(2)^2)
        long calculatedBits = (long) (-expectedElements * Math.log(fpp) / (Math.log(2) * Math.log(2)));
        this.numBits = (int) Math.max(64, Math.min(Integer.MAX_VALUE - 64, calculatedBits));

        // k = (m / n) * ln(2)
        int calculatedK = (int) Math.round(((double) numBits / expectedElements) * Math.log(2));
        this.k = Math.max(1, Math.min(30, calculatedK));

        int wordCount = (numBits + 63) / 64;
        this.words = new long[wordCount];
    }

    private BloomFilter(int numBits, int k, long[] words) {
        this.numBits = numBits;
        this.k = k;
        this.words = words;
    }

    public void add(ByteSlice key) {
        byte[] raw = key.rawArray();
        int offset = key.offset();
        int length = key.length();

        int h1 = MurmurHash3.hash32(raw, offset, length, 0);
        int h2 = MurmurHash3.hash32(raw, offset, length, h1);

        for (int i = 0; i < k; i++) {
            int combinedHash = h1 + (i * h2);
            int bitIndex = Math.floorMod(combinedHash, numBits);
            words[bitIndex >>> 6] |= (1L << (bitIndex & 63));
        }
    }

    public boolean mightContain(ByteSlice key) {
        byte[] raw = key.rawArray();
        int offset = key.offset();
        int length = key.length();

        int h1 = MurmurHash3.hash32(raw, offset, length, 0);
        int h2 = MurmurHash3.hash32(raw, offset, length, h1);

        for (int i = 0; i < k; i++) {
            int combinedHash = h1 + (i * h2);
            int bitIndex = Math.floorMod(combinedHash, numBits);
            if ((words[bitIndex >>> 6] & (1L << (bitIndex & 63))) == 0) {
                return false;
            }
        }
        return true;
    }

    public int numBits() {
        return numBits;
    }

    public int k() {
        return k;
    }

    public byte[] serialize() {
        int byteSize = 4 + 4 + (words.length * 8);
        ByteBuffer buffer = ByteBuffer.allocate(byteSize);
        buffer.putInt(numBits);
        buffer.putInt(k);
        for (long word : words) {
            buffer.putLong(word);
        }
        return buffer.array();
    }

    public static BloomFilter deserialize(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        int numBits = buffer.getInt();
        int k = buffer.getInt();
        int wordCount = (numBits + 63) / 64;
        long[] words = new long[wordCount];
        for (int i = 0; i < wordCount; i++) {
            words[i] = buffer.getLong();
        }
        return new BloomFilter(numBits, k, words);
    }

    public static BloomFilter deserialize(ByteBuffer buffer) {
        int numBits = buffer.getInt();
        int k = buffer.getInt();
        int wordCount = (numBits + 63) / 64;
        long[] words = new long[wordCount];
        for (int i = 0; i < wordCount; i++) {
            words[i] = buffer.getLong();
        }
        return new BloomFilter(numBits, k, words);
    }
}
