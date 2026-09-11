package com.engine.pebble.tui;

import com.engine.pebble.engine.PebbleMetrics;
import com.engine.pebble.engine.PebbleOptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class StorageVisualizerTest {

    @Test
    void testRenderHudOutput() {
        StorageVisualizer visualizer = new StorageVisualizer();
        PebbleOptions options = PebbleOptions.builder()
                .dbPath(Path.of("./test-hud"))
                .build();

        PebbleMetrics metrics = new PebbleMetrics(
                1000L,
                500L,
                20L,
                150,
                2 * 1024 * 1024,
                3,
                12 * 1024 * 1024,
                8,
                16 * 1024 * 1024,
                2,
                40 * 1024 * 1024,
                450L,
                50L,
                0.90,
                false
        );

        String output = visualizer.render(metrics, options);
        assertThat(output).contains("PebbleLSM — Embedded Storage Engine Diagnostics HUD");
        assertThat(output).contains("Active MemTable");
        assertThat(output).contains("Level 0 (Overlapping)");
        assertThat(output).contains("Level 1 (Partitioned)");
        assertThat(output).contains("Block Cache Hits: 450");
        assertThat(output).contains("Hit Ratio: \u001B[32m90.00%");
    }
}
