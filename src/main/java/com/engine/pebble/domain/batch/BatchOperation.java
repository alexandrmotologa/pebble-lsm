package com.engine.pebble.domain.batch;

import com.engine.pebble.common.ByteSlice;
import com.engine.pebble.domain.model.EntryType;

/**
 * Individual mutation operation within an atomic WriteBatch.
 */
public record BatchOperation(
        EntryType type,
        ByteSlice key,
        ByteSlice value,
        long expiresAtTimestamp
) {
    public boolean isTombstone() {
        return type == EntryType.DELETE;
    }
}
