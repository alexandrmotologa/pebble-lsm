package com.engine.pebble.iterator;

import com.engine.pebble.domain.sstable.DataBlock;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Adapter converting an SSTable record iterator to an InternalIterator.
 */
public class SSTableIterator implements InternalIterator {

    private final Iterator<DataBlock.Record> source;
    private final AutoCloseable closeable;
    private StorageEntry current;

    public SSTableIterator(Iterator<DataBlock.Record> source, AutoCloseable closeable) {
        this.source = Objects.requireNonNull(source, "source iterator must not be null");
        this.closeable = closeable;
        advance();
    }

    public SSTableIterator(Iterator<DataBlock.Record> source) {
        this(source, null);
    }

    private void advance() {
        if (source.hasNext()) {
            DataBlock.Record record = source.next();
            current = new StorageEntry(
                    record.key(),
                    record.value(),
                    record.sequenceNumber(),
                    record.type(),
                    record.expiresAtTimestamp()
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

    @Override
    public void close() throws java.io.IOException {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception e) {
                if (e instanceof java.io.IOException ioe) throw ioe;
                throw new java.io.IOException("Error closing resource", e);
            }
        }
    }
}
