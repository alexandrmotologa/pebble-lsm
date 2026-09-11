package com.engine.pebble.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PebbleEngineIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void testBasicCrudAndScan() throws IOException {
        PebbleOptions options = PebbleOptions.builder()
                .dbPath(tempDir.resolve("crud-db"))
                .memTableThresholdBytes(64 * 1024) // 64KB
                .build();

        try (PebbleEngine engine = PebbleEngine.open(options)) {
            engine.putString("user:1", "Alice");
            engine.putString("user:2", "Bob");
            engine.putString("user:3", "Charlie");

            assertThat(engine.getString("user:1")).contains("Alice");
            assertThat(engine.getString("user:2")).contains("Bob");
            assertThat(engine.getString("user:3")).contains("Charlie");
            assertThat(engine.getString("user:4")).isEmpty();

            // Delete
            engine.delete("user:2".getBytes());
            assertThat(engine.getString("user:2")).isEmpty();

            // Range scan
            List<String> found = new ArrayList<>();
            try (var scanner = engine.scan("user:0".getBytes(), "user:9".getBytes())) {
                while (scanner.hasNext()) {
                    found.add(scanner.next().key().toUtf8String());
                }
            }
            assertThat(found).containsExactly("user:1", "user:3");
        }
    }

    @Test
    void testFlushesAndReopenPersistence() throws IOException {
        Path dbPath = tempDir.resolve("reopen-db");
        int totalKeys = 10_000;

        PebbleOptions options = PebbleOptions.builder()
                .dbPath(dbPath)
                .memTableThresholdBytes(16 * 1024) // 16KB threshold to force multiple flushes
                .build();

        // Step 1: Write keys across multiple flushes
        try (PebbleEngine engine = PebbleEngine.open(options)) {
            for (int i = 0; i < totalKeys; i++) {
                engine.putString(String.format("key:%06d", i), String.format("val:%06d", i));
            }
            // Some updates and deletes
            engine.putString("key:000500", "updated_val");
            engine.delete("key:000501".getBytes());

            PebbleMetrics metrics = engine.getMetrics();
            assertThat(metrics.totalSSTables()).isGreaterThan(0);
        }

        // Step 2: Reopen engine from disk and verify all keys are intact
        try (PebbleEngine engine = PebbleEngine.open(options)) {
            for (int i = 0; i < totalKeys; i++) {
                if (i == 500) {
                    assertThat(engine.getString(String.format("key:%06d", i))).contains("updated_val");
                } else if (i == 501) {
                    assertThat(engine.getString(String.format("key:%06d", i))).isEmpty();
                } else {
                    assertThat(engine.getString(String.format("key:%06d", i))).contains(String.format("val:%06d", i));
                }
            }
        }
    }
}
