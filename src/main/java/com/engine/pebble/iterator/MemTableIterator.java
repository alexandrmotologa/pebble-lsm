package com.engine.pebble.iterator;

import com.engine.pebble.common.ByteSlice;
import com.engine.pebble.domain.model.ValueEntry;

import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Adapter converting MemTable key-value iterator to an InternalIterator.
 */
public class MemTableIterator implements InternalIterator {

    private final Iterator<Map.Entry<ByteSlice, ValueEntry>> source;
    private StorageEntry current;

    public MemTableIterator(Iterator<Map.Entry<ByteSlice, ValueEntry>> source) {
        this.source = Objects.requireNonNull(source, "source iterator must not be null");
        advance();
    }

    private void advance() {
        if (source.hasNext()) {
            Map.Entry<ByteSlice, ValueEntry> entry = source.next();
            current = new StorageEntry(
                    entry.getKey(),
                    entry.getValue().value(),
                    entry.getValue().sequenceNumber(),
                    entry.getValue().type()
            );
        } else {
            current = null;
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
