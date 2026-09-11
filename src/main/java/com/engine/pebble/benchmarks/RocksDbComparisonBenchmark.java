package com.engine.pebble.benchmarks;

import com.engine.pebble.engine.PebbleEngine;
import com.engine.pebble.engine.PebbleOptions;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Random;

/**
 * Head-to-head comparison benchmark between PebbleLSM and RocksDB Java on identical workloads.
 */
public class RocksDbComparisonBenchmark {

    public static void main(String[] args) throws Exception {
        RocksDB.loadLibrary();

        Path pebbleDir = Path.of("./target/bench-pebble");
        Path rocksDir = Path.of("./target/bench-rocks");
        cleanup(pebbleDir);
        cleanup(rocksDir);

        int totalWrites = 100_000;
        int totalReads = 50_000;

        System.out.println("=================================================================");
        System.out.println("   Head-to-Head Benchmark: PebbleLSM vs RocksDB Java");
        System.out.println("=================================================================");
        System.out.printf(" Workload: %,d sequential writes + %,d random reads\n\n", totalWrites, totalReads);

        // ----------------- PebbleLSM Benchmark -----------------
        System.out.println("Running PebbleLSM Benchmark...");
        PebbleOptions pebbleOptions = PebbleOptions.builder()
                .dbPath(pebbleDir)
                .memTableThresholdBytes(32 * 1024 * 1024)
                .build();

        long pebbleWriteStart = System.nanoTime();
        try (PebbleEngine pebble = PebbleEngine.open(pebbleOptions)) {
            for (int i = 0; i < totalWrites; i++) {
                pebble.putString(String.format("key:%07d", i), "payload-" + i);
            }
        }
        long pebbleWriteDurationNs = System.nanoTime() - pebbleWriteStart;
        double pebbleWriteSec = pebbleWriteDurationNs / 1_000_000_000.0;
        double pebbleWriteOpsSec = totalWrites / pebbleWriteSec;

        long pebbleReadStart = System.nanoTime();
        try (PebbleEngine pebble = PebbleEngine.open(pebbleOptions)) {
            Random rnd = new Random(1234);
            for (int i = 0; i < totalReads; i++) {
                int keyIdx = rnd.nextInt(totalWrites);
                pebble.getString(String.format("key:%07d", keyIdx));
            }
        }
        long pebbleReadDurationNs = System.nanoTime() - pebbleReadStart;
        double pebbleReadSec = pebbleReadDurationNs / 1_000_000_000.0;
        double pebbleReadOpsSec = totalReads / pebbleReadSec;

        // ----------------- RocksDB Benchmark -----------------
        System.out.println("Running RocksDB Benchmark...");
        Files.createDirectories(rocksDir);
        Options rocksOptions = new Options().setCreateIfMissing(true);

        long rocksWriteStart = System.nanoTime();
        try (RocksDB rocks = RocksDB.open(rocksOptions, rocksDir.toString())) {
            for (int i = 0; i < totalWrites; i++) {
                byte[] k = String.format("key:%07d", i).getBytes();
                byte[] v = ("payload-" + i).getBytes();
                rocks.put(k, v);
            }
        }
        long rocksWriteDurationNs = System.nanoTime() - rocksWriteStart;
        double rocksWriteSec = rocksWriteDurationNs / 1_000_000_000.0;
        double rocksWriteOpsSec = totalWrites / rocksWriteSec;

        long rocksReadStart = System.nanoTime();
        try (RocksDB rocks = RocksDB.open(rocksOptions, rocksDir.toString())) {
            Random rnd = new Random(1234);
            for (int i = 0; i < totalReads; i++) {
                int keyIdx = rnd.nextInt(totalWrites);
                byte[] k = String.format("key:%07d", keyIdx).getBytes();
                rocks.get(k);
            }
        }
        long rocksReadDurationNs = System.nanoTime() - rocksReadStart;
        double rocksReadSec = rocksReadDurationNs / 1_000_000_000.0;
        double rocksReadOpsSec = totalReads / rocksReadSec;

        // ----------------- Summary Table -----------------
        System.out.println("\n=================================================================");
        System.out.printf(" %-20s | %-18s | %-18s\n", "Metric", "PebbleLSM", "RocksDB");
        System.out.println("----------------------+--------------------+-------------------");
        System.out.printf(" %-20s | %,12.0f ops/s | %,12.0f ops/s\n", "Write Throughput", pebbleWriteOpsSec, rocksWriteOpsSec);
        System.out.printf(" %-20s | %12.2f ms    | %12.2f ms   \n", "Write Duration", pebbleWriteSec * 1000.0, rocksWriteSec * 1000.0);
        System.out.printf(" %-20s | %,12.0f ops/s | %,12.0f ops/s\n", "Read Throughput", pebbleReadOpsSec, rocksReadOpsSec);
        System.out.printf(" %-20s | %12.2f ms    | %12.2f ms   \n", "Read Duration", pebbleReadSec * 1000.0, rocksReadSec * 1000.0);
        System.out.println("=================================================================\n");

        cleanup(pebbleDir);
        cleanup(rocksDir);
    }

    private static void cleanup(Path dir) {
        if (Files.exists(dir)) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            } catch (IOException ignored) {}
        }
    }
}
