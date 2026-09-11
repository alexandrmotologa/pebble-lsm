package com.engine.pebble.common;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable reference to a byte array segment.
 * Implements unsigned lexicographical ordering suitable for storage keys.
 */
public final class ByteSlice implements Comparable<ByteSlice> {

    public static final ByteSlice EMPTY = new ByteSlice(new byte[0], 0, 0);

    private final byte[] data;
    private final int offset;
    private final int length;
    private int hash; // Lazily computed hash code

    private ByteSlice(byte[] data, int offset, int length) {
        this.data = Objects.requireNonNull(data, "data must not be null");
        if (offset < 0 || length < 0 || offset + length > data.length) {
            throw new IndexOutOfBoundsException(
                    "Invalid slice bounds: offset=" + offset + ", length=" + length + ", arrayLength=" + data.length);
        }
        this.offset = offset;
        this.length = length;
    }

    public static ByteSlice of(byte[] data) {
        if (data == null || data.length == 0) {
            return EMPTY;
        }
        byte[] copy = Arrays.copyOf(data, data.length);
        return new ByteSlice(copy, 0, copy.length);
    }

    public static ByteSlice of(String text) {
        if (text == null || text.isEmpty()) {
            return EMPTY;
        }
        return of(text.getBytes(StandardCharsets.UTF_8));
    }

    public static ByteSlice wrap(byte[] data, int offset, int length) {
        return new ByteSlice(data, offset, length);
    }

    public int length() {
        return length;
    }

    public boolean isEmpty() {
        return length == 0;
    }

    public byte get(int index) {
        if (index < 0 || index >= length) {
            throw new IndexOutOfBoundsException("Index " + index + " out of bounds for length " + length);
        }
        return data[offset + index];
    }

    public byte[] toByteArray() {
        return Arrays.copyOfRange(data, offset, offset + length);
    }

    public String toUtf8String() {
        return new String(data, offset, length, StandardCharsets.UTF_8);
    }

    public byte[] rawArray() {
        return data;
    }

    public int offset() {
        return offset;
    }

    @Override
    public int compareTo(ByteSlice other) {
        return Arrays.compareUnsigned(
                this.data, this.offset, this.offset + this.length,
                other.data, other.offset, other.offset + other.length);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof ByteSlice other)) return false;
        if (this.length != other.length) return false;
        return Arrays.equals(
                this.data, this.offset, this.offset + this.length,
                other.data, other.offset, other.offset + other.length);
    }

    @Override
    public int hashCode() {
        int h = hash;
        if (h == 0 && length > 0) {
            h = 1;
            for (int i = offset; i < offset + length; i++) {
                h = 31 * h + data[i];
            }
            hash = h;
        }
        return h;
    }

    @Override
    public String toString() {
        return "ByteSlice[" + toUtf8String() + " (" + length + " bytes)]";
    }
}
