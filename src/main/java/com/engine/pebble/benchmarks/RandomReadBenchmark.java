package com.engine.pebble.benchmarks;

import com.engine.pebble.engine.PebbleEngine;
import com.engine.pebble.engine.PebbleOptions;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Random;

/**
 * Benchmark measuring point lookup latency for hits versus misses (Bloom filter evaluation).
 */
public class RandomReadBenchmark {

    public static void main(String[] args) throws Exception {
        Path benchDir = Path.of("./target/bench-rand-read");
        cleanup(benchDir);

        int keyCount = 50_000;
        int readOps = 50_000;

        PebbleOptions options = PebbleOptions.builder()
                .dbPath(benchDir)
                .memTableThresholdBytes(8 * 1024) // Force multiple SSTables on disk
                .build();

        try (PebbleEngine engine = PebbleEngine.open(options)) {
            System.out.printf("Populating %,d keys...\n", keyCount);
            for (int i = 0; i < keyCount; i++) {
                engine.putString(String.format("key:%06d", i), "payload-content-" + i);
            }
            engine.flush();

            Random rnd = new Random(42);

            // 1. Read Hits
            System.out.printf("Measuring %,d random READ HITS...\n", readOps);
            long startHits = System.nanoTime();
            int hitFound = 0;
            for (int i = 0; i < readOps; i++) {
                int target = rnd.nextInt(keyCount);
                if (engine.getString(String.format("key:%06d", target)).isPresent()) {
                    hitFound++;
                }
            }
            long durationHitsNs = System.nanoTime() - startHits;
            double hitOpsPerSec = readOps / (durationHitsNs / 1_000_000_000.0);
            double avgHitMicros = (durationHitsNs / (double) readOps) / 1_000.0;

            // 2. Read Misses (testing Bloom filter rejection)
            System.out.printf("Measuring %,d random READ MISSES (Bloom filter path)...\n", readOps);
            long startMisses = System.nanoTime();
            int missFound = 0;
            for (int i = 0; i < readOps; i++) {
                int target = keyCount + rnd.nextInt(keyCount);
                if (engine.getString(String.format("key:%06d", target)).isPresent()) {
                    missFound++;
                }
            }
            long durationMissesNs = System.nanoTime() - startMisses;
            double missOpsPerSec = readOps / (durationMissesNs / 1_000_000_000.0);
            double avgMissMicros = (durationMissesNs / (double) readOps) / 1_000.0;

            System.out.println("\n----------------- Read Benchmark Results -----------------");
            System.out.printf(" Read Hits   : %,.0f ops/sec (avg latency: %.2f µs/op) [found: %,d]\n",
                    hitOpsPerSec, avgHitMicros, hitFound);
            System.out.printf(" Read Misses : %,.0f ops/sec (avg latency: %.2f µs/op) [found: %,d]\n",
                    missOpsPerSec, avgMissMicros, missFound);
            System.out.printf(" Cache hit ratio: %.2f%%\n", engine.getMetrics().cacheHitRatio() * 100.0);
            System.out.println("----------------------------------------------------------");
        }

        cleanup(benchDir);
    }

    private static void cleanup(Path dir) {
        if (Files.exists(dir)) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            } catch (IOException ignored) {}
        }
    }
}
