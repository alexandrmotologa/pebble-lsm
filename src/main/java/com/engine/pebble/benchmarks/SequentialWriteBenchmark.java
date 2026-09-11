package com.engine.pebble.benchmarks;

import com.engine.pebble.engine.PebbleEngine;
import com.engine.pebble.engine.PebbleOptions;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * Benchmark measuring sequential write throughput and disk bandwidth.
 */
public class SequentialWriteBenchmark {

    public static void main(String[] args) throws Exception {
        Path benchDir = Path.of("./target/bench-seq-write");
        cleanup(benchDir);

        int totalOps = 200_000;
        byte[] valuePayload = new byte[128]; // 128 bytes payload
        for (int i = 0; i < valuePayload.length; i++) {
            valuePayload[i] = (byte) ('a' + (i % 26));
        }

        PebbleOptions options = PebbleOptions.builder()
                .dbPath(benchDir)
                .memTableThresholdBytes(32 * 1024 * 1024) // 32MB
                .l0CompactionThreshold(4)
                .build();

        System.out.println("=================================================");
        System.out.printf(" Starting Sequential Write Benchmark (%,d ops)...\n", totalOps);
        System.out.println("=================================================");

        long startNs = System.nanoTime();
        try (PebbleEngine engine = PebbleEngine.open(options)) {
            for (int i = 0; i < totalOps; i++) {
                byte[] key = String.format("key:%08d", i).getBytes();
                engine.put(key, valuePayload);

                if ((i + 1) % 50_000 == 0) {
                    System.out.printf("  Completed %,d writes...\n", i + 1);
                }
            }
        }
        long durationNs = System.nanoTime() - startNs;
        double durationSec = durationNs / 1_000_000_000.0;
        double opsPerSec = totalOps / durationSec;
        double mbWritten = (double) (totalOps * (16 + 128)) / (1024 * 1024);
        double mbPerSec = mbWritten / durationSec;

        System.out.println("\n----------------- Benchmark Results -----------------");
        System.out.printf(" Total Duration : %.2f seconds\n", durationSec);
        System.out.printf(" Throughput     : %,.0f ops/second\n", opsPerSec);
        System.out.printf(" Disk Bandwidth : %.2f MB/second\n", mbPerSec);
        System.out.println("-----------------------------------------------------");

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
