package com.engine.pebble.domain.sstable;

import com.engine.pebble.common.ByteSlice;

import java.nio.file.Path;

/**
 * Metadata descriptor for an immutable SSTable file.
 */
public record SSTableMetadata(
        long fileNumber,
        Path path,
        int level,
        long entryCount,
        ByteSlice minKey,
        ByteSlice maxKey,
        long fileSize
) {
    public boolean overlaps(ByteSlice startKey, ByteSlice endKey) {
        if (minKey == null || maxKey == null) {
            return false;
        }
        if (startKey != null && maxKey.compareTo(startKey) < 0) {
            return false;
        }
        if (endKey != null && minKey.compareTo(endKey) > 0) {
            return false;
        }
        return true;
    }

    public boolean overlaps(SSTableMetadata other) {
        return overlaps(other.minKey(), other.maxKey());
    }
}
