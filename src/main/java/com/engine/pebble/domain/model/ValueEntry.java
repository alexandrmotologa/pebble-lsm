package com.engine.pebble.domain.model;

import com.engine.pebble.common.ByteSlice;

import java.util.Objects;

/**
 * Value entry recorded in the MemTable and iterators.
 * Supports Time-To-Live (TTL) expiration timestamps and MVCC version chains.
 */
public final class ValueEntry {

    private final ByteSlice value;
    private final long sequenceNumber;
    private final EntryType type;
    private final long expiresAtTimestamp; // Epoch millis (0 = never expires)
    private final ValueEntry previousVersion; // Older version of the same key in MemTable

    public ValueEntry(
            ByteSlice value,
            long sequenceNumber,
            EntryType type,
            long expiresAtTimestamp,
            ValueEntry previousVersion) {

        this.value = (type == EntryType.DELETE) ? ByteSlice.EMPTY : Objects.requireNonNull(value, "value must not be null for PUT");
        this.sequenceNumber = sequenceNumber;
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.expiresAtTimestamp = Math.max(0L, expiresAtTimestamp);
        this.previousVersion = previousVersion;
    }

    public ValueEntry(ByteSlice value, long sequenceNumber, EntryType type, long expiresAtTimestamp) {
        this(value, sequenceNumber, type, expiresAtTimestamp, null);
    }

    public ValueEntry(ByteSlice value, long sequenceNumber, EntryType type) {
        this(value, sequenceNumber, type, 0L, null);
    }

    public static ValueEntry put(ByteSlice value, long sequenceNumber) {
        return new ValueEntry(value, sequenceNumber, EntryType.PUT, 0L, null);
    }

    public static ValueEntry put(ByteSlice value, long sequenceNumber, long expiresAtTimestamp) {
        return new ValueEntry(value, sequenceNumber, EntryType.PUT, expiresAtTimestamp, null);
    }

    public static ValueEntry delete(long sequenceNumber) {
        return new ValueEntry(ByteSlice.EMPTY, sequenceNumber, EntryType.DELETE, 0L, null);
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

    public long expiresAtTimestamp() {
        return expiresAtTimestamp;
    }

    public ValueEntry previousVersion() {
        return previousVersion;
    }

    public boolean isTombstone() {
        return type == EntryType.DELETE;
    }

    public boolean isExpired() {
        return isExpired(System.currentTimeMillis());
    }

    public boolean isExpired(long now) {
        return expiresAtTimestamp > 0 && now >= expiresAtTimestamp;
    }

    public long estimatedBytes(int keyLength) {
        return 72L + keyLength + (value != null ? value.length() : 0);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ValueEntry that)) return false;
        return sequenceNumber == that.sequenceNumber &&
                type == that.type &&
                expiresAtTimestamp == that.expiresAtTimestamp &&
                Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value, sequenceNumber, type, expiresAtTimestamp);
    }

    @Override
    public String toString() {
        return "ValueEntry{" +
                "type=" + type +
                ", seq=" + sequenceNumber +
                ", valLength=" + (value != null ? value.length() : 0) +
                ", expiresAt=" + expiresAtTimestamp +
                '}';
    }
}
