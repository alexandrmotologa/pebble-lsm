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
        ByteSlice value
) {
    public boolean isTombstone() {
        return type == EntryType.DELETE;
    }
}
