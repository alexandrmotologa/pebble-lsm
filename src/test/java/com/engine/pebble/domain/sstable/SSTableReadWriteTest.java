package com.engine.pebble.domain.sstable;

import com.engine.pebble.common.ByteSlice;
import com.engine.pebble.domain.cache.BlockCache;
import com.engine.pebble.domain.memtable.MemTable;
import com.engine.pebble.domain.model.ValueEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SSTableReadWriteTest {

    @TempDir
    Path tempDir;

    @Test
    void testFlushAndReadBack() throws IOException {
        Path sstPath = tempDir.resolve("000001.sst");
        int keyCount = 10_000;

        MemTable memTable = new MemTable();
        for (int i = 0; i < keyCount; i++) {
            ByteSlice key = ByteSlice.of(String.format("key:%06d", i));
            ByteSlice val = ByteSlice.of(String.format("val:%06d", i));
            memTable.put(key, val, i + 1L);
        }

        SSTableWriter writer = new SSTableWriter(sstPath, 1L, 4096, keyCount);
        SSTableMetadata meta = writer.write(memTable.iterator());

        assertThat(meta.fileNumber()).isEqualTo(1L);
        assertThat(meta.entryCount()).isEqualTo(keyCount);
        assertThat(meta.minKey().toUtf8String()).isEqualTo("key:000000");
        assertThat(meta.maxKey().toUtf8String()).isEqualTo(String.format("key:%06d", keyCount - 1));
        assertThat(meta.fileSize()).isGreaterThan(0);

        BlockCache cache = new BlockCache(100);
        try (SSTableReader reader = SSTableReader.open(sstPath, 1L, 0, cache)) {
            assertThat(reader.blockIndex().size()).isGreaterThan(1); // Multiple 4KB data blocks

            // Read all keys
            for (int i = 0; i < keyCount; i++) {
                ByteSlice key = ByteSlice.of(String.format("key:%06d", i));
                ValueEntry entry = reader.get(key);
                assertThat(entry).isNotNull();
                assertThat(entry.value().toUtf8String()).isEqualTo(String.format("val:%06d", i));
                assertThat(entry.sequenceNumber()).isEqualTo(i + 1L);
            }

            // Test non-existent keys
            assertThat(reader.get(ByteSlice.of("key:999999"))).isNull();
            assertThat(reader.get(ByteSlice.of("aaa:below"))).isNull();

            // Verify sequential iterator
            List<String> iteratedKeys = new ArrayList<>();
            var iter = reader.iterator();
            while (iter.hasNext()) {
                iteratedKeys.add(iter.next().key().toUtf8String());
            }
            assertThat(iteratedKeys).hasSize(keyCount);
            assertThat(iteratedKeys.get(0)).isEqualTo("key:000000");
            assertThat(iteratedKeys.get(keyCount - 1)).isEqualTo(String.format("key:%06d", keyCount - 1));

            // Verify cache was used
            assertThat(cache.hits()).isGreaterThan(0);
        }
    }
}
