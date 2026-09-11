package com.engine.pebble.iterator;

import java.util.Iterator;

/**
 * Peeking iterator over storage entries.
 */
public interface InternalIterator extends Iterator<StorageEntry>, AutoCloseable {

    /**
     * Inspects the next element without consuming it.
     */
    StorageEntry peek();

    @Override
    default void close() throws java.io.IOException {}
}
