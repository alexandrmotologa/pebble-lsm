package com.engine.pebble.compaction;

import com.engine.pebble.engine.PebbleEngine;
import com.engine.pebble.engine.PebbleMetrics;
import com.engine.pebble.engine.PebbleOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LeveledCompactionTest {

    @TempDir
    Path tempDir;

    @Test
    void testL0ToL1CompactionReducesSSTableCount() throws IOException {
        Path dbPath = tempDir.resolve("compact-db");

        PebbleOptions options = PebbleOptions.builder()
                .dbPath(dbPath)
                .memTableThresholdBytes(8 * 1024) // 8KB to force rapid flushes
                .l0CompactionThreshold(3)
                .build();

        try (PebbleEngine engine = PebbleEngine.open(options)) {
            // Write multiple batches on overlapping key space
            for (int batch = 0; batch < 5; batch++) {
                for (int i = 0; i < 200; i++) {
                    engine.putString(String.format("key:%04d", i), String.format("batch%d_val%04d", batch, i));
                }
                engine.flush();
            }

            PebbleMetrics preMetrics = engine.getMetrics();
            assertThat(preMetrics.l0FileCount()).isGreaterThanOrEqualTo(3);

            // Execute synchronous compaction
            engine.compact();

            PebbleMetrics postMetrics = engine.getMetrics();
            assertThat(postMetrics.l1FileCount()).isGreaterThan(0);
            assertThat(postMetrics.l0FileCount()).isLessThan(preMetrics.l0FileCount());

            // Verify newest batch values survived
            for (int i = 0; i < 200; i++) {
                assertThat(engine.getString(String.format("key:%04d", i)))
                        .contains(String.format("batch4_val%04d", i));
            }
        }
    }
}
