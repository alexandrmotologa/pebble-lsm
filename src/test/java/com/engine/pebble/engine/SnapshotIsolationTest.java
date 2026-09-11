package com.engine.pebble.engine;

import com.engine.pebble.domain.model.Snapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotIsolationTest {

    @TempDir
    Path tempDir;

    @Test
    void testSnapshotVisibilityIsolation() throws IOException {
        Path dbPath = tempDir.resolve("snapshot-db");
        PebbleOptions options = PebbleOptions.builder().dbPath(dbPath).build();

        try (PebbleEngine engine = PebbleEngine.open(options)) {
            engine.putString("key:1", "initial_v1");
            engine.putString("key:2", "initial_v1");

            // Capture snapshot at sequence number of initial writes
            try (Snapshot snapshot = engine.getSnapshot()) {
                // Perform subsequent writes and deletes
                engine.putString("key:1", "updated_v2");
                engine.delete("key:2".getBytes());
                engine.putString("key:3", "new_key_v1");

                // Live view sees the updates
                assertThat(engine.getString("key:1")).contains("updated_v2");
                assertThat(engine.getString("key:2")).isEmpty();
                assertThat(engine.getString("key:3")).contains("new_key_v1");

                // Snapshot view sees the exact point-in-time state
                assertThat(engine.getString("key:1", snapshot)).contains("initial_v1");
                assertThat(engine.getString("key:2", snapshot)).contains("initial_v1");
                assertThat(engine.getString("key:3", snapshot)).isEmpty();

                // Snapshot range scan
                List<String> snapshotKeys = new ArrayList<>();
                try (var scanner = engine.scan("key:0".getBytes(), "key:9".getBytes(), snapshot)) {
                    while (scanner.hasNext()) {
                        snapshotKeys.add(scanner.next().key().toUtf8String());
                    }
                }
                assertThat(snapshotKeys).containsExactly("key:1", "key:2");
            }
        }
    }
}
