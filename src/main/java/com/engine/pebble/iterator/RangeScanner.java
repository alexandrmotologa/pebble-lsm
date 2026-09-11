package com.engine.pebble.iterator;

import com.engine.pebble.common.ByteSlice;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Range scanner yielding active key-value entries in the range [fromKey, toKey).
 */
public class RangeScanner implements Iterator<StorageEntry>, AutoCloseable {

    private final InternalIterator source;
    private final ByteSlice fromKey;
    private final ByteSlice toKey;
    private StorageEntry current;

    public RangeScanner(InternalIterator source, ByteSlice fromKey, ByteSlice toKey) {
        this.source = Objects.requireNonNull(source, "source iterator must not be null");
        this.fromKey = fromKey;
        this.toKey = toKey;
        advance();
    }

    private void advance() {
        current = null;
        while (source.hasNext()) {
            StorageEntry entry = source.next();
            ByteSlice key = entry.key();

            if (fromKey != null && key.compareTo(fromKey) < 0) {
                // Before start range
                continue;
            }

            if (toKey != null && key.compareTo(toKey) >= 0) {
                // Past end range (keys are sorted, so we can stop immediately)
                break;
            }

            current = entry;
            return;
        }
    }

    @Override
    public boolean hasNext() {
        return current != null;
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

    @Override
    public void close() throws java.io.IOException {
        source.close();
    }
}
