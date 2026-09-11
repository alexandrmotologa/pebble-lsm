package com.engine.pebble.domain.sstable;

import com.engine.pebble.common.ByteSlice;
import com.engine.pebble.domain.filter.BloomFilter;
import com.engine.pebble.domain.model.ValueEntry;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Writes sorted key-value pairs into an immutable SSTable file.
 * Automatically slices records into 4KB data blocks, builds a sparse index and Bloom filter.
 */
public class SSTableWriter {

    public static final int DEFAULT_BLOCK_SIZE = 4096; // 4KB

    private final Path path;
    private final long fileNumber;
    private final int targetBlockSize;
    private final int expectedElements;

    public SSTableWriter(Path path, long fileNumber, int targetBlockSize, int expectedElements) {
        this.path = path;
        this.fileNumber = fileNumber;
        this.targetBlockSize = Math.max(512, targetBlockSize);
        this.expectedElements = Math.max(1, expectedElements);
    }

    public SSTableMetadata write(Iterator<Map.Entry<ByteSlice, ValueEntry>> iterator) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }

        BloomFilter bloomFilter = new BloomFilter(expectedElements, 0.01);
        List<BlockIndex.Entry> indexEntries = new ArrayList<>();

        ByteSlice minKey = null;
        ByteSlice maxKey = null;
        long entryCount = 0;

        try (FileChannel channel = FileChannel.open(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {

            List<DataBlock.Record> currentBlockRecords = new ArrayList<>();
            int currentBlockBytes = 4; // 4 bytes for count

            while (iterator.hasNext()) {
                Map.Entry<ByteSlice, ValueEntry> entry = iterator.next();
                ByteSlice key = entry.getKey();
                ValueEntry val = entry.getValue();

                if (minKey == null) {
                    minKey = key;
                }
                maxKey = key;
                entryCount++;

                bloomFilter.add(key);

                DataBlock.Record record = new DataBlock.Record(
                        key,
                        val.value(),
                        val.sequenceNumber(),
                        val.type()
                );
                currentBlockRecords.add(record);

                int recordBytes = 4 + key.length() + 4 + (val.value() != null ? val.value().length() : 0) + 8 + 1;
                currentBlockBytes += recordBytes;

                if (currentBlockBytes >= targetBlockSize) {
                    flushBlock(channel, currentBlockRecords, indexEntries);
                    currentBlockRecords.clear();
                    currentBlockBytes = 4;
                }
            }

            // Flush trailing block
            if (!currentBlockRecords.isEmpty()) {
                flushBlock(channel, currentBlockRecords, indexEntries);
                currentBlockRecords.clear();
            }

            // Write Sparse Block Index
            BlockIndex blockIndex = new BlockIndex(indexEntries);
            byte[] indexBytes = blockIndex.serialize();
            long indexOffset = channel.position();
            int indexLength = indexBytes.length;
            writeFully(channel, indexBytes);

            // Write Bloom Filter
            byte[] bloomBytes = bloomFilter.serialize();
            long bloomOffset = channel.position();
            int bloomLength = bloomBytes.length;
            writeFully(channel, bloomBytes);

            // Write Magic Footer
            Footer footer = new Footer(
                    indexOffset,
                    indexLength,
                    bloomOffset,
                    bloomLength,
                    entryCount,
                    minKey,
                    maxKey
            );
            footer.writeTo(channel);

            channel.force(false);
            long fileSize = channel.size();

            return new SSTableMetadata(
                    fileNumber,
                    path,
                    0, // Default level 0 upon initial write
                    entryCount,
                    minKey,
                    maxKey,
                    fileSize
            );
        }
    }

    private void flushBlock(
            FileChannel channel,
            List<DataBlock.Record> records,
            List<BlockIndex.Entry> indexEntries) throws IOException {

        DataBlock block = new DataBlock(records);
        byte[] blockBytes = block.serialize();
        long offset = channel.position();
        int size = blockBytes.length;

        writeFully(channel, blockBytes);

        ByteSlice firstKey = records.get(0).key();
        indexEntries.add(new BlockIndex.Entry(firstKey, new BlockHandle(offset, size)));
    }

    private void writeFully(FileChannel channel, byte[] bytes) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        while (buf.hasRemaining()) {
            channel.write(buf);
        }
    }
}
