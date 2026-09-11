package com.engine.pebble.iterator;

import com.engine.pebble.common.ByteSlice;
import com.engine.pebble.domain.model.ValueEntry;

import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Adapter converting MemTable key-value iterator to an InternalIterator.
 * Traverses MVCC version chains to select the latest version visible to maxSequenceNumber.
 */
public class MemTableIterator implements InternalIterator {

    private final Iterator<Map.Entry<ByteSlice, ValueEntry>> source;
    private final long maxSequenceNumber;
    private StorageEntry current;

    public MemTableIterator(Iterator<Map.Entry<ByteSlice, ValueEntry>> source, long maxSequenceNumber) {
        this.source = Objects.requireNonNull(source, "source iterator must not be null");
        this.maxSequenceNumber = maxSequenceNumber;
        advance();
    }

    public MemTableIterator(Iterator<Map.Entry<ByteSlice, ValueEntry>> source) {
        this(source, Long.MAX_VALUE);
    }

    private void advance() {
        current = null;
        while (source.hasNext()) {
            Map.Entry<ByteSlice, ValueEntry> mapEntry = source.next();
            ValueEntry entry = mapEntry.getValue();

            // Walk version chain to find the latest version <= maxSequenceNumber
            while (entry != null && entry.sequenceNumber() > maxSequenceNumber) {
                entry = entry.previousVersion();
            }

            if (entry != null) {
                current = new StorageEntry(
                        mapEntry.getKey(),
                        entry.value(),
                        entry.sequenceNumber(),
                        entry.type(),
                        entry.expiresAtTimestamp()
                );
                return;
            }
        }
    }

    @Override
    public boolean hasNext() {
        return current != null;
    }

    @Override
    public StorageEntry peek() {
        return current;
    }

    @Override
    public StorageEntry next() {
        if (current == null) {
            throw new NoSuchElementException();
        }
        StorageEntry ret = current;
        advance();
        return ret;
    }
}
