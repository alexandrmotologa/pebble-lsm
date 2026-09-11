package com.engine.pebble.domain.sstable;

import com.engine.pebble.common.ByteSlice;
import com.engine.pebble.domain.cache.BlockCache;
import com.engine.pebble.domain.filter.BloomFilter;
import com.engine.pebble.domain.model.ValueEntry;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Reader for immutable SSTable files.
 * Provides logarithmic disk lookups via bounds checking, Bloom filter tests,
 * sparse block index binary search, and optional LRU block caching.
 */
public class SSTableReader implements AutoCloseable {

    private final Path path;
    private final long fileNumber;
    private final int level;
    private final long fileSize;
    private final FileChannel channel;
    private final Footer footer;
    private final BlockIndex blockIndex;
    private final BloomFilter bloomFilter;
    private final BlockCache blockCache;

    private SSTableReader(
            Path path,
            long fileNumber,
            int level,
            long fileSize,
            FileChannel channel,
            Footer footer,
            BlockIndex blockIndex,
            BloomFilter bloomFilter,
            BlockCache blockCache) {

        this.path = path;
        this.fileNumber = fileNumber;
        this.level = level;
        this.fileSize = fileSize;
        this.channel = channel;
        this.footer = footer;
        this.blockIndex = blockIndex;
        this.bloomFilter = bloomFilter;
        this.blockCache = blockCache;
    }

    public static SSTableReader open(
            Path path,
            long fileNumber,
            int level,
            BlockCache blockCache) throws IOException {

        FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
        try {
            long fileSize = channel.size();
            Footer footer = Footer.readFrom(channel);

            // Read sparse index
            ByteBuffer indexBuf = ByteBuffer.allocate(footer.indexLength());
            channel.position(footer.indexOffset());
            channel.read(indexBuf);
            indexBuf.flip();
            BlockIndex index = BlockIndex.deserialize(indexBuf);

            // Read bloom filter
            ByteBuffer bloomBuf = ByteBuffer.allocate(footer.bloomLength());
            channel.position(footer.bloomOffset());
            channel.read(bloomBuf);
            bloomBuf.flip();
            BloomFilter bloom = BloomFilter.deserialize(bloomBuf);

            return new SSTableReader(path, fileNumber, level, fileSize, channel, footer, index, bloom, blockCache);
        } catch (Exception e) {
            channel.close();
            throw e;
        }
    }

    public ValueEntry get(ByteSlice key) throws IOException {
        // 1. Min/Max bounds test
        if (footer.minKey() == null || footer.maxKey() == null) {
            return null;
        }
        if (key.compareTo(footer.minKey()) < 0 || key.compareTo(footer.maxKey()) > 0) {
            return null;
        }

        // 2. Probabilistic Bloom filter test
        if (!bloomFilter.mightContain(key)) {
            return null;
        }

        // 3. Sparse index lookup
        BlockHandle handle = blockIndex.findBlock(key);
        if (handle == null) {
            return null;
        }

        // 4. Data block fetch (cache or disk)
        DataBlock block = readBlock(handle);

        // 5. Binary search inside the block
        return block.search(key);
    }

    public DataBlock readBlock(BlockHandle handle) throws IOException {
        if (blockCache != null) {
            DataBlock cached = blockCache.get(fileNumber, handle.offset());
            if (cached != null) {
                return cached;
            }
        }

        ByteBuffer buffer = ByteBuffer.allocate(handle.size());
        synchronized (channel) {
            channel.position(handle.offset());
            while (buffer.hasRemaining()) {
                channel.read(buffer);
            }
        }
        buffer.flip();

        DataBlock block = DataBlock.deserialize(buffer);
        if (blockCache != null) {
            blockCache.put(fileNumber, handle.offset(), block);
        }

        return block;
    }

    public Iterator<DataBlock.Record> iterator() {
        return new SSTableFileIterator();
    }

    public SSTableMetadata metadata() {
        return new SSTableMetadata(
                fileNumber,
                path,
                level,
                footer.entryCount(),
                footer.minKey(),
                footer.maxKey(),
                fileSize
        );
    }

    public Footer footer() {
        return footer;
    }

    public BlockIndex blockIndex() {
        return blockIndex;
    }

    public BloomFilter bloomFilter() {
        return bloomFilter;
    }

    public long fileNumber() {
        return fileNumber;
    }

    public int level() {
        return level;
    }

    public Path path() {
        return path;
    }

    @Override
    public void close() throws IOException {
        if (blockCache != null) {
            blockCache.invalidateFile(fileNumber);
        }
        if (channel.isOpen()) {
            channel.close();
        }
    }

    private class SSTableFileIterator implements Iterator<DataBlock.Record> {
        private int blockIdx = 0;
        private Iterator<DataBlock.Record> currentBlockIterator = null;

        @Override
        public boolean hasNext() {
            while (currentBlockIterator == null || !currentBlockIterator.hasNext()) {
                if (blockIdx >= blockIndex.size()) {
                    return false;
                }
                BlockHandle handle = blockIndex.entries().get(blockIdx++).handle();
                try {
                    DataBlock block = readBlock(handle);
                    currentBlockIterator = block.iterator();
                } catch (IOException e) {
                    throw new RuntimeException("Failed to read SSTable block at offset " + handle.offset(), e);
                }
            }
            return true;
        }

        @Override
        public DataBlock.Record next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            return currentBlockIterator.next();
        }
    }
}
