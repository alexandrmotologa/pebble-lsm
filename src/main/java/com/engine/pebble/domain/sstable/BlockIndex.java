package com.engine.pebble.domain.sstable;

import com.engine.pebble.common.ByteSlice;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Sparse block index mapping block first keys to file byte offsets and sizes.
 */
public final class BlockIndex {

    public record Entry(ByteSlice firstKey, BlockHandle handle) {}

    private final List<Entry> entries;

    public BlockIndex(List<Entry> entries) {
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
    }

    public List<Entry> entries() {
        return entries;
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * Binary searches for the data block that could contain the target key.
     * Finds the largest index i such that entries[i].firstKey <= targetKey.
     */
    public BlockHandle findBlock(ByteSlice targetKey) {
        if (entries.isEmpty()) {
            return null;
        }

        int low = 0;
        int high = entries.size() - 1;

        // If targetKey is strictly smaller than the first key of the first block,
        // it cannot be in this table.
        if (targetKey.compareTo(entries.get(0).firstKey()) < 0) {
            return null;
        }

        int candidate = 0;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            int cmp = entries.get(mid).firstKey().compareTo(targetKey);

            if (cmp <= 0) {
                candidate = mid;
                low = mid + 1; // Try to find a later block with firstKey <= targetKey
            } else {
                high = mid - 1;
            }
        }

        return entries.get(candidate).handle();
    }

    public byte[] serialize() {
        int totalKeyBytes = 0;
        for (Entry e : entries) {
            totalKeyBytes += (4 + e.firstKey().length() + 8 + 4);
        }

        ByteBuffer buffer = ByteBuffer.allocate(4 + totalKeyBytes);
        buffer.putInt(entries.size());
        for (Entry e : entries) {
            buffer.putInt(e.firstKey().length());
            buffer.put(e.firstKey().rawArray(), e.firstKey().offset(), e.firstKey().length());
            buffer.putLong(e.handle().offset());
            buffer.putInt(e.handle().size());
        }
        return buffer.array();
    }

    public static BlockIndex deserialize(byte[] bytes) {
        return deserialize(ByteBuffer.wrap(bytes));
    }

    public static BlockIndex deserialize(ByteBuffer buffer) {
        int count = buffer.getInt();
        List<Entry> list = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            int keyLen = buffer.getInt();
            byte[] keyBytes = new byte[keyLen];
            buffer.get(keyBytes);
            long offset = buffer.getLong();
            int size = buffer.getInt();

            list.add(new Entry(ByteSlice.of(keyBytes), new BlockHandle(offset, size)));
        }

        return new BlockIndex(list);
    }
}
