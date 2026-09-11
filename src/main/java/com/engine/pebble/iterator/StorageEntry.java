package com.engine.pebble.iterator;

import com.engine.pebble.common.ByteSlice;
import com.engine.pebble.domain.model.EntryType;
import com.engine.pebble.domain.model.ValueEntry;

import java.util.Objects;

/**
 * Standardized storage entry yielded by iterators and range scanners.
 */
public record StorageEntry(
        ByteSlice key,
        ByteSlice value,
        long sequenceNumber,
        EntryType type
) implements Comparable<StorageEntry> {

    public StorageEntry {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(type, "type must not be null");
        if (value == null) {
            value = ByteSlice.EMPTY;
        }
    }

    public boolean isTombstone() {
        return type == EntryType.DELETE;
    }

    public ValueEntry toValueEntry() {
        return new ValueEntry(value, sequenceNumber, type);
    }

    @Override
    public int compareTo(StorageEntry other) {
        int keyCmp = this.key.compareTo(other.key);
        if (keyCmp != 0) {
            return keyCmp;
        }
        // Newer sequence number comes first (descending order)
        return Long.compare(other.sequenceNumber, this.sequenceNumber);
    }
}
