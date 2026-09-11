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
 * Decodes both single records and atomic WriteBatch frames.
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
            ByteBuffer headerBuf = ByteBuffer.allocate(4);

            while (channel.position() < fileSize) {
                long frameStartOffset = channel.position();

                headerBuf.clear();
                int read = channel.read(headerBuf);
                if (read < 4) {
                    truncated = true;
                    break;
                }
                headerBuf.flip();
                long expectedCrc = headerBuf.getInt() & 0xFFFFFFFFL;

                // Read type
                ByteBuffer typeBuf = ByteBuffer.allocate(1);
                read = channel.read(typeBuf);
                if (read < 1) {
                    truncated = true;
                    break;
                }
                typeBuf.flip();
                byte typeCode = typeBuf.get();

                crc32.reset();
                crc32.update(typeCode);

                if (typeCode == EntryType.BATCH.code()) {
                    // Batch frame: [SeqNum: 8B][Count: 4B]
                    ByteBuffer batchMeta = ByteBuffer.allocate(12);
                    read = channel.read(batchMeta);
                    if (read < 12) {
                        truncated = true;
                        break;
                    }
                    batchMeta.flip();
                    long startSeq = batchMeta.getLong();
                    int count = batchMeta.getInt();

                    crc32.update(batchMeta.array(), 0, 12);

                    List<WalRecord> batchRecords = new ArrayList<>(count);
                    boolean batchCorrupt = false;

                    for (int i = 0; i < count; i++) {
                        ByteBuffer opMeta = ByteBuffer.allocate(13); // OpType (1B) + ExpiresAt (8B) + KeyLen (4B)
                        read = channel.read(opMeta);
                        if (read < 13) {
                            batchCorrupt = true;
                            break;
                        }
                        opMeta.flip();
                        byte opCode = opMeta.get();
                        long expiresAt = opMeta.getLong();
                        int keyLen = opMeta.getInt();

                        crc32.update(opMeta.array(), 0, 13);

                        if (keyLen < 0 || keyLen > 10 * 1024 * 1024) {
                            batchCorrupt = true;
                            break;
                        }
                        ByteBuffer keyBuf = ByteBuffer.allocate(keyLen);
                        read = channel.read(keyBuf);
                        if (read < keyLen) {
                            batchCorrupt = true;
                            break;
                        }
                        keyBuf.flip();
                        byte[] keyBytes = keyBuf.array();
                        if (keyLen > 0) crc32.update(keyBytes);

                        ByteBuffer valLenBuf = ByteBuffer.allocate(4);
                        read = channel.read(valLenBuf);
                        if (read < 4) {
                            batchCorrupt = true;
                            break;
                        }
                        valLenBuf.flip();
                        int valLen = valLenBuf.getInt();
                        crc32.update(valLenBuf.array(), 0, 4);

                        if (valLen < 0 || valLen > 100 * 1024 * 1024) {
                            batchCorrupt = true;
                            break;
                        }
                        ByteBuffer valBuf = ByteBuffer.allocate(valLen);
                        read = channel.read(valBuf);
                        if (read < valLen) {
                            batchCorrupt = true;
                            break;
                        }
                        valBuf.flip();
                        byte[] valBytes = valBuf.array();
                        if (valLen > 0) crc32.update(valBytes);

                        long currentSeq = startSeq + i;
                        EntryType opType = EntryType.fromCode(opCode);
                        ByteSlice key = ByteSlice.of(keyBytes);
                        ByteSlice val = (opType == EntryType.DELETE) ? ByteSlice.EMPTY : ByteSlice.of(valBytes);
                        batchRecords.add(new WalRecord(opType, currentSeq, key, val, expiresAt));
                    }

                    if (batchCorrupt) {
                        truncated = true;
                        break;
                    }

                    long actualCrc = crc32.getValue();
                    if (actualCrc != expectedCrc) {
                        if (channel.position() >= fileSize) {
                            truncated = true;
                            break;
                        } else {
                            throw new CorruptedWalException("CRC32 mismatch in batch at offset " + frameStartOffset);
                        }
                    }

                    records.addAll(batchRecords);
                    if (startSeq + count - 1 > maxSeqNum) {
                        maxSeqNum = startSeq + count - 1;
                    }
                    lastValidOffset = channel.position();

                } else {
                    // Individual frame: [SeqNum: 8B][ExpiresAt: 8B][KeyLen: 4B]
                    ByteBuffer metaBuf = ByteBuffer.allocate(20);
                    read = channel.read(metaBuf);
                    if (read < 20) {
                        truncated = true;
                        break;
                    }
                    metaBuf.flip();
                    long seqNum = metaBuf.getLong();
                    long expiresAt = metaBuf.getLong();
                    int keyLen = metaBuf.getInt();

                    crc32.update(metaBuf.array(), 0, 20);

                    if (keyLen < 0 || keyLen > 10 * 1024 * 1024) {
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
                    if (keyLen > 0) crc32.update(keyBytes);

                    ByteBuffer valLenBuf = ByteBuffer.allocate(4);
                    read = channel.read(valLenBuf);
                    if (read < 4) {
                        truncated = true;
                        break;
                    }
                    valLenBuf.flip();
                    int valLen = valLenBuf.getInt();
                    crc32.update(valLenBuf.array(), 0, 4);

                    if (valLen < 0 || valLen > 100 * 1024 * 1024) {
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
                    if (valLen > 0) crc32.update(valBytes);

                    long actualCrc = crc32.getValue();
                    if (actualCrc != expectedCrc) {
                        if (channel.position() >= fileSize) {
                            truncated = true;
                            break;
                        } else {
                            throw new CorruptedWalException("CRC32 mismatch at offset " + frameStartOffset);
                        }
                    }

                    EntryType entryType = EntryType.fromCode(typeCode);
                    ByteSlice key = ByteSlice.of(keyBytes);
                    ByteSlice val = (entryType == EntryType.DELETE) ? ByteSlice.EMPTY : ByteSlice.of(valBytes);

                    records.add(new WalRecord(entryType, seqNum, key, val, expiresAt));
                    if (seqNum > maxSeqNum) {
                        maxSeqNum = seqNum;
                    }
                    lastValidOffset = channel.position();
                }
            }

            if (truncated) {
                channel.truncate(lastValidOffset);
                channel.force(false);
            }
        }

        return new WalRecoveryResult(records, maxSeqNum, lastValidOffset, truncated);
    }
}
