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
 * Supports MVCC snapshot visibility filtering and TTL expiration dropping.
 */
public class PriorityQueueMergeIterator implements InternalIterator {

    private final List<InternalIterator> allIterators;
    private final PriorityQueue<InternalIterator> pq;
    private final boolean dropTombstones;
    private final long maxSequenceNumber;
    private StorageEntry nextEntry;

    public PriorityQueueMergeIterator(List<InternalIterator> iterators, boolean dropTombstones, long maxSequenceNumber) {
        this.allIterators = new ArrayList<>(iterators);
        this.dropTombstones = dropTombstones;
        this.maxSequenceNumber = maxSequenceNumber;

        Comparator<InternalIterator> comparator = (a, b) -> a.peek().compareTo(b.peek());
        this.pq = new PriorityQueue<>(Math.max(1, iterators.size()), comparator);

        for (InternalIterator it : iterators) {
            if (it.hasNext()) {
                pq.offer(it);
            }
        }

        advance();
    }

    public PriorityQueueMergeIterator(List<InternalIterator> iterators, boolean dropTombstones) {
        this(iterators, dropTombstones, Long.MAX_VALUE);
    }

    private void advance() {
        nextEntry = null;
        long now = System.currentTimeMillis();

        while (!pq.isEmpty()) {
            InternalIterator top = pq.poll();
            StorageEntry chosen = top.next();
            if (top.hasNext()) {
                pq.offer(top);
            }

            // If entry sequence number exceeds the snapshot limit, find an older version for this key
            ByteSlice currentKey = chosen.key();
            boolean visible = chosen.sequenceNumber() <= maxSequenceNumber;

            // Consume duplicate older versions from other iterators
            while (!pq.isEmpty() && pq.peek().peek().key().equals(currentKey)) {
                InternalIterator older = pq.poll();
                StorageEntry olderEntry = older.next();
                if (!visible && olderEntry.sequenceNumber() <= maxSequenceNumber) {
                    chosen = olderEntry;
                    visible = true;
                }
                if (older.hasNext()) {
                    pq.offer(older);
                }
            }

            if (!visible) {
                // Key had no version visible to this snapshot
                continue;
            }

            if (chosen.isExpired(now)) {
                // Key has expired via TTL
                continue;
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
