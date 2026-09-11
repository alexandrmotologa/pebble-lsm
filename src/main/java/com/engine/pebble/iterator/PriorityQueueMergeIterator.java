package com.engine.pebble.iterator;

import com.engine.pebble.common.ByteSlice;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.PriorityQueue;

/**
 * Multi-way merge iterator across multiple sorted entry streams.
 * Automatically deduplicates duplicate keys by retaining the version with the highest sequence number.
 * Can optionally purge delete tombstones (used when scanning or compacting into the base level).
 */
public class PriorityQueueMergeIterator implements InternalIterator {

    private final List<InternalIterator> allIterators;
    private final PriorityQueue<InternalIterator> pq;
    private final boolean dropTombstones;
    private StorageEntry nextEntry;

    public PriorityQueueMergeIterator(List<InternalIterator> iterators, boolean dropTombstones) {
        this.allIterators = new ArrayList<>(iterators);
        this.dropTombstones = dropTombstones;

        Comparator<InternalIterator> comparator = (a, b) -> a.peek().compareTo(b.peek());
        this.pq = new PriorityQueue<>(Math.max(1, iterators.size()), comparator);

        for (InternalIterator it : iterators) {
            if (it.hasNext()) {
                pq.offer(it);
            }
        }

        advance();
    }

    private void advance() {
        nextEntry = null;

        while (!pq.isEmpty()) {
            InternalIterator top = pq.poll();
            StorageEntry chosen = top.next();
            if (top.hasNext()) {
                pq.offer(top);
            }

            ByteSlice currentKey = chosen.key();

            // Discard duplicate older versions from other iterators
            while (!pq.isEmpty() && pq.peek().peek().key().equals(currentKey)) {
                InternalIterator older = pq.poll();
                older.next(); // Discard older version
                if (older.hasNext()) {
                    pq.offer(older);
                }
            }

            if (dropTombstones && chosen.isTombstone()) {
                // Skip deleted key
                continue;
            }

            nextEntry = chosen;
            return;
        }
    }

    @Override
    public boolean hasNext() {
        return nextEntry != null;
    }

    @Override
    public StorageEntry peek() {
        return nextEntry;
    }

    @Override
    public StorageEntry next() {
        if (nextEntry == null) {
            throw new NoSuchElementException();
        }
        StorageEntry ret = nextEntry;
        advance();
        return ret;
    }

    @Override
    public void close() throws java.io.IOException {
        for (InternalIterator it : allIterators) {
            try {
                it.close();
            } catch (Exception ignored) {}
        }
    }
}
