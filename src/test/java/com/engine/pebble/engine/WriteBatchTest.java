package com.engine.pebble.engine;

import com.engine.pebble.domain.batch.WriteBatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WriteBatchTest {

    @TempDir
    Path tempDir;

    @Test
    void testAtomicBatchWritesAndRecovery() throws IOException {
        Path dbPath = tempDir.resolve("batch-db");
        PebbleOptions options = PebbleOptions.builder().dbPath(dbPath).build();

        try (PebbleEngine engine = PebbleEngine.open(options)) {
            WriteBatch batch = new WriteBatch();
            batch.putString("account:1", "1000");
            batch.putString("account:2", "2000");
            batch.putString("account:3", "3000");
            batch.deleteString("account:old");

            engine.write(batch);

            assertThat(engine.getString("account:1")).contains("1000");
            assertThat(engine.getString("account:2")).contains("2000");
            assertThat(engine.getString("account:3")).contains("3000");
            assertThat(engine.getString("account:old")).isEmpty();
        }

        // Reopen database and verify WAL replay of batch
        try (PebbleEngine engine = PebbleEngine.open(options)) {
            assertThat(engine.getString("account:1")).contains("1000");
            assertThat(engine.getString("account:2")).contains("2000");
            assertThat(engine.getString("account:3")).contains("3000");
        }
    }
}
