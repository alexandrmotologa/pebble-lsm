package com.engine.pebble.domain.sstable;

import com.engine.pebble.common.ByteSlice;
import com.engine.pebble.domain.memtable.MemTable;
import com.engine.pebble.domain.model.ValueEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class Lz4CompressionTest {

    @TempDir
    Path tempDir;

    @Test
    void testLz4CompressionReducesFileSizeAndDecompressesAccurately() throws IOException {
        Path uncompressedPath = tempDir.resolve("uncompressed.sst");
        Path compressedPath = tempDir.resolve("compressed.sst");
        int keyCount = 5000;

        // Create highly compressible data (repeating pattern values)
        MemTable memTable = new MemTable();
        for (int i = 0; i < keyCount; i++) {
            ByteSlice key = ByteSlice.of(String.format("key:%06d", i));
            ByteSlice val = ByteSlice.of("repetitive-payload-data-padding-string-for-compression-" + (i % 5));
            memTable.put(key, val, i + 1L);
        }

        // 1. Write uncompressed
        SSTableWriter uncompressedWriter = new SSTableWriter(
                uncompressedPath, 1L, 4096, keyCount, CompressionType.NONE);
        uncompressedWriter.write(memTable.iterator());

        // 2. Write compressed with LZ4
        SSTableWriter compressedWriter = new SSTableWriter(
                compressedPath, 2L, 4096, keyCount, CompressionType.LZ4);
        compressedWriter.write(memTable.iterator());

        long uncompressedSize = Files.size(uncompressedPath);
        long compressedSize = Files.size(compressedPath);

        // Compressed SSTable should be significantly smaller
        assertThat(compressedSize).isLessThan(uncompressedSize);

        // 3. Read back all keys from compressed table
        try (SSTableReader reader = SSTableReader.open(compressedPath, 2L, 0, null)) {
            for (int i = 0; i < keyCount; i++) {
                ByteSlice key = ByteSlice.of(String.format("key:%06d", i));
                ValueEntry entry = reader.get(key);
                assertThat(entry).isNotNull();
                assertThat(entry.value().toUtf8String())
                        .isEqualTo("repetitive-payload-data-padding-string-for-compression-" + (i % 5));
            }
        }
    }
}
