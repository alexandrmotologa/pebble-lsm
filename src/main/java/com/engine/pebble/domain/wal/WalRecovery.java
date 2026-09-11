package com.engine.pebble.domain.wal;

import com.engine.pebble.common.ByteSlice;
import com.engine.pebble.domain.model.EntryType;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Scans a WAL file, validates checksums, and replays valid records into memory.
 * Gracefully handles incomplete frames at EOF resulting from system crashes.
 */
public final class WalRecovery {

    private WalRecovery() {}

    public static WalRecoveryResult recover(Path walPath) throws IOException {
        if (!Files.exists(walPath) || Files.size(walPath) == 0) {
            return new WalRecoveryResult(List.of(), 0L, 0L, false);
        }

        List<WalRecord> records = new ArrayList<>();
        long maxSeqNum = 0L;
        long lastValidOffset = 0L;
        boolean truncated = false;
        CRC32 crc32 = new CRC32();

        try (FileChannel channel = FileChannel.open(walPath, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            long fileSize = channel.size();
            ByteBuffer headerBuf = ByteBuffer.allocate(4); // CRC32 header
            ByteBuffer metaBuf = ByteBuffer.allocate(13);  // Type (1B) + SeqNum (8B) + KeyLen (4B)

            while (channel.position() < fileSize) {
                long frameStartOffset = channel.position();

                headerBuf.clear();
                int read = channel.read(headerBuf);
                if (read < 4) {
                    // Truncated header at EOF
                    truncated = true;
                    break;
                }
                headerBuf.flip();
                long expectedCrc = headerBuf.getInt() & 0xFFFFFFFFL;

                metaBuf.clear();
                read = channel.read(metaBuf);
                if (read < 13) {
                    // Truncated metadata at EOF
                    truncated = true;
                    break;
                }
                metaBuf.flip();
                byte typeCode = metaBuf.get();
                long seqNum = metaBuf.getLong();
                int keyLen = metaBuf.getInt();

                if (keyLen < 0 || keyLen > 10 * 1024 * 1024) { // Sanity check: max 10MB key
                    truncated = true;
                    break;
                }

                ByteBuffer keyBuf = ByteBuffer.allocate(keyLen);
                read = channel.read(keyBuf);
                if (read < keyLen) {
                    truncated = true;
                    break;
                }
                keyBuf.flip();
                byte[] keyBytes = keyBuf.array();

                ByteBuffer valLenBuf = ByteBuffer.allocate(4);
                read = channel.read(valLenBuf);
                if (read < 4) {
                    truncated = true;
                    break;
                }
                valLenBuf.flip();
                int valLen = valLenBuf.getInt();
                if (valLen < 0 || valLen > 100 * 1024 * 1024) { // Sanity check: max 100MB value
                    truncated = true;
                    break;
                }

                ByteBuffer valBuf = ByteBuffer.allocate(valLen);
                read = channel.read(valBuf);
                if (read < valLen) {
                    truncated = true;
                    break;
                }
                valBuf.flip();
                byte[] valBytes = valBuf.array();

                // Compute CRC32 over payload: [Type][SeqNum][KeyLen][KeyBytes][ValLen][ValBytes]
                crc32.reset();
                crc32.update(typeCode);

                ByteBuffer seqBuffer = ByteBuffer.allocate(8);
                seqBuffer.putLong(seqNum).flip();
                crc32.update(seqBuffer.array());

                ByteBuffer keyLenBuffer = ByteBuffer.allocate(4);
                keyLenBuffer.putInt(keyLen).flip();
                crc32.update(keyLenBuffer.array());

                if (keyLen > 0) {
                    crc32.update(keyBytes);
                }

                ByteBuffer valLenBuffer = ByteBuffer.allocate(4);
                valLenBuffer.putInt(valLen).flip();
                crc32.update(valLenBuffer.array());

                if (valLen > 0) {
                    crc32.update(valBytes);
                }

                long actualCrc = crc32.getValue();
                if (actualCrc != expectedCrc) {
                    // Checksum mismatch
                    if (channel.position() >= fileSize) {
                        // At the very end of file, treat as crash truncation
                        truncated = true;
                        break;
                    } else {
                        throw new CorruptedWalException("CRC32 mismatch at offset " + frameStartOffset +
                                ": expected " + expectedCrc + ", computed " + actualCrc);
                    }
                }

                EntryType entryType = EntryType.fromCode(typeCode);
                ByteSlice keySlice = ByteSlice.of(keyBytes);
                ByteSlice valSlice = (entryType == EntryType.DELETE) ? ByteSlice.EMPTY : ByteSlice.of(valBytes);

                records.add(new WalRecord(entryType, seqNum, keySlice, valSlice));
                if (seqNum > maxSeqNum) {
                    maxSeqNum = seqNum;
                }
                lastValidOffset = channel.position();
            }

            if (truncated) {
                // Truncate file at the last cleanly read record offset
                channel.truncate(lastValidOffset);
                channel.force(false);
            }
        }

        return new WalRecoveryResult(records, maxSeqNum, lastValidOffset, truncated);
    }
}
