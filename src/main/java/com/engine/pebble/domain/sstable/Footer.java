package com.engine.pebble.domain.sstable;

import com.engine.pebble.common.ByteSlice;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Metadata footer written at the end of every SSTable file.
 * Trailed by a fixed 12-byte header: [PayloadLen: 4B][Version: 4B][Magic: 4B]
 */
public record Footer(
        long indexOffset,
        int indexLength,
        long bloomOffset,
        int bloomLength,
        long entryCount,
        ByteSlice minKey,
        ByteSlice maxKey
) {
    public static final int MAGIC = 0x50454242; // ASCII "PEBB"
    public static final int VERSION = 1;
    public static final int FIXED_TRAILER_SIZE = 12;

    public void writeTo(FileChannel channel) throws IOException {
        int minKeyLen = (minKey != null) ? minKey.length() : 0;
        int maxKeyLen = (maxKey != null) ? maxKey.length() : 0;

        // Payload: 8 (idxOffset) + 4 (idxLen) + 8 (bloomOffset) + 4 (bloomLen) + 8 (entries) + 4 (minLen) + minLen + 4 (maxLen) + maxLen
        int payloadSize = 8 + 4 + 8 + 4 + 8 + 4 + minKeyLen + 4 + maxKeyLen;
        ByteBuffer buffer = ByteBuffer.allocate(payloadSize + FIXED_TRAILER_SIZE);

        buffer.putLong(indexOffset);
        buffer.putInt(indexLength);
        buffer.putLong(bloomOffset);
        buffer.putInt(bloomLength);
        buffer.putLong(entryCount);

        buffer.putInt(minKeyLen);
        if (minKeyLen > 0) {
            buffer.put(minKey.rawArray(), minKey.offset(), minKeyLen);
        }

        buffer.putInt(maxKeyLen);
        if (maxKeyLen > 0) {
            buffer.put(maxKey.rawArray(), maxKey.offset(), maxKeyLen);
        }

        // Fixed trailer
        buffer.putInt(payloadSize);
        buffer.putInt(VERSION);
        buffer.putInt(MAGIC);

        buffer.flip();
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    public static Footer readFrom(FileChannel channel) throws IOException {
        long fileSize = channel.size();
        if (fileSize < FIXED_TRAILER_SIZE) {
            throw new IOException("Corrupted SSTable: file size " + fileSize + " smaller than trailer size");
        }

        ByteBuffer trailer = ByteBuffer.allocate(FIXED_TRAILER_SIZE);
        channel.position(fileSize - FIXED_TRAILER_SIZE);
        channel.read(trailer);
        trailer.flip();

        int payloadLen = trailer.getInt();
        int version = trailer.getInt();
        int magic = trailer.getInt();

        if (magic != MAGIC) {
            throw new IOException("Invalid SSTable magic: expected 0x" + Integer.toHexString(MAGIC) +
                    ", got 0x" + Integer.toHexString(magic));
        }

        if (version != VERSION) {
            throw new IOException("Unsupported SSTable version: " + version);
        }

        if (fileSize < FIXED_TRAILER_SIZE + payloadLen) {
            throw new IOException("Corrupted SSTable: payload length " + payloadLen + " exceeds file size");
        }

        ByteBuffer payload = ByteBuffer.allocate(payloadLen);
        channel.position(fileSize - FIXED_TRAILER_SIZE - payloadLen);
        channel.read(payload);
        payload.flip();

        long indexOffset = payload.getLong();
        int indexLength = payload.getInt();
        long bloomOffset = payload.getLong();
        int bloomLength = payload.getInt();
        long entryCount = payload.getLong();

        int minKeyLen = payload.getInt();
        byte[] minKeyBytes = new byte[minKeyLen];
        if (minKeyLen > 0) {
            payload.get(minKeyBytes);
        }

        int maxKeyLen = payload.getInt();
        byte[] maxKeyBytes = new byte[maxKeyLen];
        if (maxKeyLen > 0) {
            payload.get(maxKeyBytes);
        }

        return new Footer(
                indexOffset,
                indexLength,
                bloomOffset,
                bloomLength,
                entryCount,
                minKeyLen > 0 ? ByteSlice.of(minKeyBytes) : null,
                maxKeyLen > 0 ? ByteSlice.of(maxKeyBytes) : null
        );
    }
}
