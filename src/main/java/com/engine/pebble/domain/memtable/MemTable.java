package com.engine.pebble.domain.memtable;

import com.engine.pebble.common.ByteSlice;
import com.engine.pebble.domain.model.ValueEntry;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory sorted buffer backed by a ConcurrentSkipListMap.
 * Tracks memory consumption and provides O(log N) operations.
 */
public class MemTable implements Iterable<Map.Entry<ByteSlice, ValueEntry>> {

    private final ConcurrentSkipListMap<ByteSlice, ValueEntry> map;
    private final AtomicLong approximateBytes;
    private final AtomicInteger entryCount;

    public MemTable() {
        this.map = new ConcurrentSkipListMap<>();
        this.approximateBytes = new AtomicLong(0);
        this.entryCount = new AtomicInteger(0);
    }

    public void put(ByteSlice key, ByteSlice value, long sequenceNumber) {
        ValueEntry entry = ValueEntry.put(value, sequenceNumber);
        ValueEntry previous = map.put(key, entry);

        long newEntryBytes = entry.estimatedBytes(key.length());
        if (previous == null) {
            approximateBytes.addAndGet(newEntryBytes);
            entryCount.incrementAndGet();
        } else {
            long prevBytes = previous.estimatedBytes(key.length());
            approximateBytes.addAndGet(newEntryBytes - prevBytes);
        }
    }

    public void delete(ByteSlice key, long sequenceNumber) {
        ValueEntry entry = ValueEntry.delete(sequenceNumber);
        ValueEntry previous = map.put(key, entry);

        long newEntryBytes = entry.estimatedBytes(key.length());
        if (previous == null) {
            approximateBytes.addAndGet(newEntryBytes);
            entryCount.incrementAndGet();
        } else {
            long prevBytes = previous.estimatedBytes(key.length());
            approximateBytes.addAndGet(newEntryBytes - prevBytes);
        }
    }

    public ValueEntry get(ByteSlice key) {
        return map.get(key);
    }

    public long approximateBytes() {
        return approximateBytes.get();
    }

    public int size() {
        return entryCount.get();
    }

    public boolean isEmpty() {
        return map.isEmpty();
    }

    public ByteSlice minKey() {
        return map.isEmpty() ? null : map.firstKey();
    }

    public ByteSlice maxKey() {
        return map.isEmpty() ? null : map.lastKey();
    }

    @Override
    public Iterator<Map.Entry<ByteSlice, ValueEntry>> iterator() {
        return map.entrySet().iterator();
    }

    /**
     * Returns an iterator over keys in range [fromKey, toKey).
     */
    public Iterator<Map.Entry<ByteSlice, ValueEntry>> scan(ByteSlice fromKey, ByteSlice toKey) {
        if (fromKey == null && toKey == null) {
            return iterator();
        }
        if (fromKey == null) {
            return map.headMap(toKey, false).entrySet().iterator();
        }
        if (toKey == null) {
            return map.tailMap(fromKey, true).entrySet().iterator();
        }
        return map.subMap(fromKey, true, toKey, false).entrySet().iterator();
    }
}
