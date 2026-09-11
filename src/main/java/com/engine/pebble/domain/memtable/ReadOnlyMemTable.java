package com.engine.pebble.domain.memtable;

import com.engine.pebble.common.ByteSlice;
import com.engine.pebble.domain.model.ValueEntry;

import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable wrapper around a frozen MemTable awaiting flush to L0 SSTable.
 */
public final class ReadOnlyMemTable implements Iterable<Map.Entry<ByteSlice, ValueEntry>> {

    private final MemTable underlying;
    private final long walFileNumber;

    public ReadOnlyMemTable(MemTable underlying, long walFileNumber) {
        this.underlying = Objects.requireNonNull(underlying, "underlying MemTable must not be null");
        this.walFileNumber = walFileNumber;
    }

    public ValueEntry get(ByteSlice key) {
        return underlying.get(key);
    }

    public long approximateBytes() {
        return underlying.approximateBytes();
    }

    public int size() {
        return underlying.size();
    }

    public boolean isEmpty() {
        return underlying.isEmpty();
    }

    public long walFileNumber() {
        return walFileNumber;
    }

    public ByteSlice minKey() {
        return underlying.minKey();
    }

    public ByteSlice maxKey() {
        return underlying.maxKey();
    }

    @Override
    public Iterator<Map.Entry<ByteSlice, ValueEntry>> iterator() {
        return underlying.iterator();
    }

    public Iterator<Map.Entry<ByteSlice, ValueEntry>> scan(ByteSlice fromKey, ByteSlice toKey) {
        return underlying.scan(fromKey, toKey);
    }
}
