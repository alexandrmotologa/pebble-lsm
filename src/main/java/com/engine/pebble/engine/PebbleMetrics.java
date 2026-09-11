package com.engine.pebble.engine;

/**
 * Real-time storage engine metrics snapshot.
 */
public record PebbleMetrics(
        long writesCount,
        long readsCount,
        long deletesCount,
        int activeMemTableSize,
        long activeMemTableBytes,
        int l0FileCount,
        long l0SizeBytes,
        int l1FileCount,
        long l1SizeBytes,
        int l2FileCount,
        long l2SizeBytes,
        long cacheHits,
        long cacheMisses,
        double cacheHitRatio,
        boolean isCompacting
) {
    public long totalSSTables() {
        return l0FileCount + l1FileCount + l2FileCount;
    }

    public long totalDiskBytes() {
        return l0SizeBytes + l1SizeBytes + l2SizeBytes;
    }
}
