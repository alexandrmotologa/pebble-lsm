package com.engine.pebble.engine;

import com.engine.pebble.domain.sstable.CompressionType;
import com.engine.pebble.domain.wal.SyncPolicy;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Configuration options for PebbleEngine.
 */
public record PebbleOptions(
        Path dbPath,
        long memTableThresholdBytes,
        int l0CompactionThreshold,
        int l0SlowdownWritesThreshold,
        int l0StopWritesThreshold,
        long l1MaxSizeBytes,
        long l2MaxSizeBytes,
        int sstableTargetBlockSize,
        int blockCacheCapacity,
        CompressionType compressionType,
        SyncPolicy syncPolicy
) {
    public static final long DEFAULT_MEMTABLE_THRESHOLD = 32L * 1024 * 1024; // 32MB
    public static final int DEFAULT_L0_THRESHOLD = 4;
    public static final int DEFAULT_L0_SLOWDOWN_THRESHOLD = 8;
    public static final int DEFAULT_L0_STOP_THRESHOLD = 12;
    public static final long DEFAULT_L1_MAX_SIZE = 10L * 1024 * 1024; // 10MB
    public static final long DEFAULT_L2_MAX_SIZE = 100L * 1024 * 1024; // 100MB
    public static final int DEFAULT_BLOCK_SIZE = 4096; // 4KB
    public static final int DEFAULT_CACHE_CAPACITY = 4096; // ~16MB at 4KB/block

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Path dbPath = Path.of("./pebble-data");
        private long memTableThresholdBytes = DEFAULT_MEMTABLE_THRESHOLD;
        private int l0CompactionThreshold = DEFAULT_L0_THRESHOLD;
        private int l0SlowdownWritesThreshold = DEFAULT_L0_SLOWDOWN_THRESHOLD;
        private int l0StopWritesThreshold = DEFAULT_L0_STOP_THRESHOLD;
        private long l1MaxSizeBytes = DEFAULT_L1_MAX_SIZE;
        private long l2MaxSizeBytes = DEFAULT_L2_MAX_SIZE;
        private int sstableTargetBlockSize = DEFAULT_BLOCK_SIZE;
        private int blockCacheCapacity = DEFAULT_CACHE_CAPACITY;
        private CompressionType compressionType = CompressionType.LZ4;
        private SyncPolicy syncPolicy = SyncPolicy.BUFFERED;

        public Builder dbPath(Path dbPath) {
            this.dbPath = Objects.requireNonNull(dbPath, "dbPath must not be null");
            return this;
        }

        public Builder memTableThresholdBytes(long bytes) {
            this.memTableThresholdBytes = Math.max(1024, bytes);
            return this;
        }

        public Builder l0CompactionThreshold(int threshold) {
            this.l0CompactionThreshold = Math.max(2, threshold);
            return this;
        }

        public Builder l0SlowdownWritesThreshold(int threshold) {
            this.l0SlowdownWritesThreshold = Math.max(3, threshold);
            return this;
        }

        public Builder l0StopWritesThreshold(int threshold) {
            this.l0StopWritesThreshold = Math.max(4, threshold);
            return this;
        }

        public Builder l1MaxSizeBytes(long bytes) {
            this.l1MaxSizeBytes = Math.max(1024, bytes);
            return this;
        }

        public Builder l2MaxSizeBytes(long bytes) {
            this.l2MaxSizeBytes = Math.max(1024, bytes);
            return this;
        }

        public Builder sstableTargetBlockSize(int blockSize) {
            this.sstableTargetBlockSize = Math.max(512, blockSize);
            return this;
        }

        public Builder blockCacheCapacity(int capacity) {
            this.blockCacheCapacity = Math.max(16, capacity);
            return this;
        }

        public Builder compressionType(CompressionType compressionType) {
            this.compressionType = Objects.requireNonNull(compressionType, "compressionType must not be null");
            return this;
        }

        public Builder syncPolicy(SyncPolicy syncPolicy) {
            this.syncPolicy = Objects.requireNonNull(syncPolicy, "syncPolicy must not be null");
            return this;
        }

        public PebbleOptions build() {
            return new PebbleOptions(
                    dbPath,
                    memTableThresholdBytes,
                    l0CompactionThreshold,
                    l0SlowdownWritesThreshold,
                    l0StopWritesThreshold,
                    l1MaxSizeBytes,
                    l2MaxSizeBytes,
                    sstableTargetBlockSize,
                    blockCacheCapacity,
                    compressionType,
                    syncPolicy
            );
        }
    }
}
