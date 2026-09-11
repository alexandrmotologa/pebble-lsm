package com.engine.pebble.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class TtlExpirationTest {

    @TempDir
    Path tempDir;

    @Test
    void testKeyExpiresAfterTtlDuration() throws IOException, InterruptedException {
        Path dbPath = tempDir.resolve("ttl-db");
        PebbleOptions options = PebbleOptions.builder().dbPath(dbPath).build();

        try (PebbleEngine engine = PebbleEngine.open(options)) {
            // Write key with 80ms TTL
            engine.putString("session:user_temp", "active_session", Duration.ofMillis(80));
            // Write permanent key
            engine.putString("session:user_perm", "permanent_session");

            // Immediately accessible
            assertThat(engine.getString("session:user_temp")).contains("active_session");
            assertThat(engine.getString("session:user_perm")).contains("permanent_session");

            // Wait for expiration
            Thread.sleep(120);

            // Expired key returns empty
            assertThat(engine.getString("session:user_temp")).isEmpty();
            // Permanent key is still present
            assertThat(engine.getString("session:user_perm")).contains("permanent_session");
        }
    }
}
