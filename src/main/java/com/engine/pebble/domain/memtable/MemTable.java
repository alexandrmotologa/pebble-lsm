package com.engine.pebble.domain.memtable;

import com.engine.pebble.common.ByteSlice;
import com.engine.pebble.domain.model.EntryType;
import com.engine.pebble.domain.model.ValueEntry;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory sorted buffer backed by a ConcurrentSkipListMap.
 * Supports MVCC version chains per key to guarantee snapshot isolation across concurrent writes.
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
        put(key, value, sequenceNumber, 0L);
    }

    public void put(ByteSlice key, ByteSlice value, long sequenceNumber, long expiresAtTimestamp) {
        map.compute(key, (k, prev) -> {
            ValueEntry entry = new ValueEntry(value, sequenceNumber, EntryType.PUT, expiresAtTimestamp, prev);
            long newEntryBytes = entry.estimatedBytes(key.length());
            if (prev == null) {
                approximateBytes.addAndGet(newEntryBytes);
                entryCount.incrementAndGet();
            } else {
                long prevBytes = prev.estimatedBytes(key.length());
                approximateBytes.addAndGet(newEntryBytes - prevBytes);
            }
            return entry;
        });
    }

    public void delete(ByteSlice key, long sequenceNumber) {
        map.compute(key, (k, prev) -> {
            ValueEntry entry = new ValueEntry(ByteSlice.EMPTY, sequenceNumber, EntryType.DELETE, 0L, prev);
            long newEntryBytes = entry.estimatedBytes(key.length());
            if (prev == null) {
                approximateBytes.addAndGet(newEntryBytes);
                entryCount.incrementAndGet();
            } else {
                long prevBytes = prev.estimatedBytes(key.length());
                approximateBytes.addAndGet(newEntryBytes - prevBytes);
            }
            return entry;
        });
    }

    public ValueEntry get(ByteSlice key) {
        return get(key, Long.MAX_VALUE);
    }

    public ValueEntry get(ByteSlice key, long maxSequenceNumber) {
        ValueEntry curr = map.get(key);
        while (curr != null && curr.sequenceNumber() > maxSequenceNumber) {
            curr = curr.previousVersion();
        }
        return curr;
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
