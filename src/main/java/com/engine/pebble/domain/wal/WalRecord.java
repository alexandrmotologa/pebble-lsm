package com.engine.pebble.domain.wal;

import com.engine.pebble.common.ByteSlice;
import com.engine.pebble.domain.model.EntryType;

/**
 * Record recovered from or written to the Write-Ahead Log.
 */
public record WalRecord(
        EntryType type,
        long sequenceNumber,
        ByteSlice key,
        ByteSlice value,
        long expiresAtTimestamp
) {
    public WalRecord(EntryType type, long sequenceNumber, ByteSlice key, ByteSlice value) {
        this(type, sequenceNumber, key, value, 0L);
    }

    public boolean isTombstone() {
        return type == EntryType.DELETE;
    }
}
