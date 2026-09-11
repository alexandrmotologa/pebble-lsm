package com.engine.pebble.tui;

import com.engine.pebble.engine.PebbleMetrics;
import com.engine.pebble.engine.PebbleOptions;

/**
 * Terminal UI dashboard visualizing the LSM storage hierarchy and real-time metrics.
 */
public class StorageVisualizer {

    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";
    private static final String CYAN = "\u001B[36m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED = "\u001B[31m";
    private static final String BLUE = "\u001B[34m";
    private static final String GRAY = "\u001B[90m";

    public String render(PebbleMetrics metrics, PebbleOptions options) {
        StringBuilder sb = new StringBuilder();

        sb.append(CYAN).append(BOLD)
                .append("================================================================================\n")
                .append("  PebbleLSM — Embedded Storage Engine Diagnostics HUD\n")
                .append("================================================================================\n")
                .append(RESET);

        // Memory & MemTable Section
        long memBytes = metrics.activeMemTableBytes();
        long memLimit = options.memTableThresholdBytes();
        double memPct = Math.min(100.0, (double) memBytes / memLimit * 100.0);

        sb.append(BOLD).append("\n[ In-Memory Tier ]\n").append(RESET);
        sb.append(String.format("  Active MemTable : %s%,d entries%s (%s%.2f MB%s / %.2f MB) [%s]\n",
                GREEN, metrics.activeMemTableSize(), RESET,
                YELLOW, memBytes / (1024.0 * 1024.0), RESET,
                memLimit / (1024.0 * 1024.0),
                renderBar(memPct, 24)));

        // On-Disk LSM Levels Section
        sb.append(BOLD).append("\n[ On-Disk LSM Hierarchy ]\n").append(RESET);

        // Level 0
        int l0Count = metrics.l0FileCount();
        int l0Threshold = options.l0CompactionThreshold();
        String l0Color = (l0Count >= l0Threshold) ? RED : GREEN;
        sb.append(String.format("  Level 0 (Overlapping) : %s%d files%s (threshold: %d) | Size: %.2f MB\n",
                l0Color, l0Count, RESET, l0Threshold, metrics.l0SizeBytes() / (1024.0 * 1024.0)));

        // Level 1
        int l1Count = metrics.l1FileCount();
        double l1Pct = (double) metrics.l1SizeBytes() / options.l1MaxSizeBytes() * 100.0;
        sb.append(String.format("  Level 1 (Partitioned) : %s%d files%s | Size: %.2f MB / %.2f MB [%s]\n",
                BLUE, l1Count, RESET,
                metrics.l1SizeBytes() / (1024.0 * 1024.0),
                options.l1MaxSizeBytes() / (1024.0 * 1024.0),
                renderBar(l1Pct, 20)));

        // Level 2
        int l2Count = metrics.l2FileCount();
        double l2Pct = (double) metrics.l2SizeBytes() / options.l2MaxSizeBytes() * 100.0;
        sb.append(String.format("  Level 2 (Compacted)   : %s%d files%s | Size: %.2f MB / %.2f MB [%s]\n",
                BLUE, l2Count, RESET,
                metrics.l2SizeBytes() / (1024.0 * 1024.0),
                options.l2MaxSizeBytes() / (1024.0 * 1024.0),
                renderBar(l2Pct, 20)));

        // I/O & Block Cache Metrics
        sb.append(BOLD).append("\n[ Performance & Cache ]\n").append(RESET);
        sb.append(String.format("  Writes: %,d  |  Reads: %,d  |  Deletes: %,d\n",
                metrics.writesCount(), metrics.readsCount(), metrics.deletesCount()));
        sb.append(String.format("  Block Cache Hits: %,d  |  Misses: %,d  |  Hit Ratio: %s%.2f%%%s\n",
                metrics.cacheHits(), metrics.cacheMisses(),
                GREEN, metrics.cacheHitRatio() * 100.0, RESET));

        String compStatus = metrics.isCompacting()
                ? (YELLOW + BOLD + "ACTIVE (Compacting background levels)" + RESET)
                : (GRAY + "IDLE" + RESET);
        sb.append(String.format("  Compaction Worker: %s\n", compStatus));

        sb.append(CYAN)
                .append("--------------------------------------------------------------------------------\n")
                .append(RESET);

        return sb.toString();
    }

    private String renderBar(double percentage, int totalChars) {
        int filled = (int) Math.round(Math.min(100.0, Math.max(0.0, percentage)) / 100.0 * totalChars);
        StringBuilder bar = new StringBuilder();
        bar.append(CYAN);
        for (int i = 0; i < filled; i++) {
            bar.append("=");
        }
        bar.append(GRAY);
        for (int i = filled; i < totalChars; i++) {
            bar.append("-");
        }
        bar.append(RESET);
        return bar.toString();
    }
}
