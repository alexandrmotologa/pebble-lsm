package com.engine.pebble.domain.model;

import com.engine.pebble.common.ByteSlice;

import java.util.Objects;

/**
 * Value entry recorded in the MemTable and iterators.
 */
public final class ValueEntry {

    private final ByteSlice value;
    private final long sequenceNumber;
    private final EntryType type;

    public ValueEntry(ByteSlice value, long sequenceNumber, EntryType type) {
        this.value = (type == EntryType.DELETE) ? ByteSlice.EMPTY : Objects.requireNonNull(value, "value must not be null for PUT");
        this.sequenceNumber = sequenceNumber;
        this.type = Objects.requireNonNull(type, "type must not be null");
    }

    public static ValueEntry put(ByteSlice value, long sequenceNumber) {
        return new ValueEntry(value, sequenceNumber, EntryType.PUT);
    }

    public static ValueEntry delete(long sequenceNumber) {
        return new ValueEntry(ByteSlice.EMPTY, sequenceNumber, EntryType.DELETE);
    }

    public ByteSlice value() {
        return value;
    }

    public long sequenceNumber() {
        return sequenceNumber;
    }

    public EntryType type() {
        return type;
    }

    public boolean isTombstone() {
        return type == EntryType.DELETE;
    }

    /**
     * Estimates in-memory byte size including skip list node overhead.
     */
    public long estimatedBytes(int keyLength) {
        // 64 bytes base overhead for skip list node and object headers
        return 64L + keyLength + (value != null ? value.length() : 0);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ValueEntry that)) return false;
        return sequenceNumber == that.sequenceNumber &&
                type == that.type &&
                Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value, sequenceNumber, type);
    }

    @Override
    public String toString() {
        return "ValueEntry{" +
                "type=" + type +
                ", seq=" + sequenceNumber +
                ", valLength=" + (value != null ? value.length() : 0) +
                '}';
    }
}
