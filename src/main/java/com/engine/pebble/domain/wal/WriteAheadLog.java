package com.engine.pebble.domain.wal;

import com.engine.pebble.common.ByteSlice;
import com.engine.pebble.domain.model.EntryType;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32;

/**
 * Write-Ahead Log (WAL) providing sequential disk append with CRC32 frame checksums.
 * Binary format: [CRC32: 4B][Type: 1B][SeqNum: 8B][KeyLen: 4B][KeyBytes][ValLen: 4B][ValBytes]
 */
public class WriteAheadLog implements AutoCloseable {

    private final Path path;
    private final long fileNumber;
    private final SyncPolicy syncPolicy;
    private final FileChannel channel;
    private final CRC32 crc32 = new CRC32();
    private final Object writeLock = new Object();

    public WriteAheadLog(Path path, long fileNumber, SyncPolicy syncPolicy) throws IOException {
        this.path = path;
        this.fileNumber = fileNumber;
        this.syncPolicy = syncPolicy;

        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }

        this.channel = FileChannel.open(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND
        );
    }

    public void append(EntryType type, ByteSlice key, ByteSlice value, long sequenceNumber) throws IOException {
        int keyLen = (key != null) ? key.length() : 0;
        int valLen = (value != null) ? value.length() : 0;

        // Payload size: 1 (type) + 8 (seq) + 4 (keyLen) + keyLen + 4 (valLen) + valLen
        int payloadSize = 1 + 8 + 4 + keyLen + 4 + valLen;
        ByteBuffer buffer = ByteBuffer.allocate(4 + payloadSize);

        // Position at byte 4 to write payload first
        buffer.position(4);
        buffer.put(type.code());
        buffer.putLong(sequenceNumber);
        buffer.putInt(keyLen);
        if (keyLen > 0) {
            buffer.put(key.rawArray(), key.offset(), keyLen);
        }
        buffer.putInt(valLen);
        if (valLen > 0) {
            buffer.put(value.rawArray(), value.offset(), valLen);
        }

        // Compute CRC32 on payload
        buffer.position(4);
        byte[] payloadBytes = new byte[payloadSize];
        buffer.get(payloadBytes);

        synchronized (writeLock) {
            crc32.reset();
            crc32.update(payloadBytes);
            long checksum = crc32.getValue();

            // Write CRC32 at beginning
            buffer.position(0);
            buffer.putInt((int) (checksum & 0xFFFFFFFFL));
            buffer.position(0);

            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }

            if (syncPolicy == SyncPolicy.ALWAYS) {
                channel.force(false);
            }
        }
    }

    public void sync() throws IOException {
        synchronized (writeLock) {
            channel.force(false);
        }
    }

    public Path getPath() {
        return path;
    }

    public long getFileNumber() {
        return fileNumber;
    }

    public long size() throws IOException {
        synchronized (writeLock) {
            return channel.size();
        }
    }

    @Override
    public void close() throws IOException {
        synchronized (writeLock) {
            if (channel.isOpen()) {
                channel.force(false);
                channel.close();
            }
        }
    }
}
