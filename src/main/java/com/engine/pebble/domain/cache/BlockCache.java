package com.engine.pebble.domain.cache;

import com.engine.pebble.domain.sstable.DataBlock;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe Least-Recently-Used (LRU) cache for uncompressed 4KB SSTable data blocks.
 */
public class BlockCache {

    public record Key(long fileNumber, long blockOffset) {}

    private final int maxCapacity;
    private final LinkedHashMap<Key, DataBlock> map;
    private final ReentrantLock lock = new ReentrantLock();

    private long hits;
    private long misses;

    public BlockCache(int maxCapacity) {
        this.maxCapacity = Math.max(16, maxCapacity);
        this.map = new LinkedHashMap<>(maxCapacity, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, DataBlock> eldest) {
                return size() > BlockCache.this.maxCapacity;
            }
        };
    }

    public DataBlock get(long fileNumber, long blockOffset) {
        Key key = new Key(fileNumber, blockOffset);
        lock.lock();
        try {
            DataBlock block = map.get(key);
            if (block != null) {
                hits++;
                return block;
            } else {
                misses++;
                return null;
            }
        } finally {
            lock.unlock();
        }
    }

    public void put(long fileNumber, long blockOffset, DataBlock block) {
        Key key = new Key(fileNumber, blockOffset);
        lock.lock();
        try {
            map.put(key, block);
        } finally {
            lock.unlock();
        }
    }

    public void invalidateFile(long fileNumber) {
        lock.lock();
        try {
            map.keySet().removeIf(k -> k.fileNumber() == fileNumber);
        } finally {
            lock.unlock();
        }
    }

    public void clear() {
        lock.lock();
        try {
            map.clear();
        } finally {
            lock.unlock();
        }
    }

    public double hitRatio() {
        lock.lock();
        try {
            long total = hits + misses;
            return (total == 0) ? 0.0 : (double) hits / total;
        } finally {
            lock.unlock();
        }
    }

    public long hits() {
        lock.lock();
        try {
            return hits;
        } finally {
            lock.unlock();
        }
    }

    public long misses() {
        lock.lock();
        try {
            return misses;
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try {
            return map.size();
        } finally {
            lock.unlock();
        }
    }
}
