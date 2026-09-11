package com.engine.pebble.domain.wal;

import com.engine.pebble.common.ByteSlice;
import com.engine.pebble.domain.model.EntryType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WalCrashRecoveryTest {

    @TempDir
    Path tempDir;

    @Test
    void testCleanWriteAndRecovery() throws IOException {
        Path walFile = tempDir.resolve("000001.wal");

        try (WriteAheadLog wal = new WriteAheadLog(walFile, 1L, SyncPolicy.BUFFERED)) {
            for (int i = 0; i < 5000; i++) {
                ByteSlice key = ByteSlice.of(String.format("key:%05d", i));
                ByteSlice val = ByteSlice.of(String.format("val:%05d", i));
                wal.append(EntryType.PUT, key, val, i + 1L);
            }
            // Append a few delete tombstones
            wal.append(EntryType.DELETE, ByteSlice.of("key:00010"), ByteSlice.EMPTY, 5001L);
            wal.append(EntryType.DELETE, ByteSlice.of("key:00020"), ByteSlice.EMPTY, 5002L);
        }

        WalRecoveryResult recovery = WalRecovery.recover(walFile);
        List<WalRecord> records = recovery.records();

        assertThat(records).hasSize(5002);
        assertThat(recovery.maxSequenceNumber()).isEqualTo(5002L);
        assertThat(recovery.truncatedAtEof()).isFalse();

        WalRecord first = records.get(0);
        assertThat(first.type()).isEqualTo(EntryType.PUT);
        assertThat(first.key().toUtf8String()).isEqualTo("key:00000");
        assertThat(first.value().toUtf8String()).isEqualTo("val:00000");
        assertThat(first.sequenceNumber()).isEqualTo(1L);

        WalRecord tombstone = records.get(5000);
        assertThat(tombstone.type()).isEqualTo(EntryType.DELETE);
        assertThat(tombstone.key().toUtf8String()).isEqualTo("key:00010");
        assertThat(tombstone.sequenceNumber()).isEqualTo(5001L);
    }

    @Test
    void testCrashWithPartialTrailingWriteTruncation() throws IOException {
        Path walFile = tempDir.resolve("000002.wal");

        try (WriteAheadLog wal = new WriteAheadLog(walFile, 2L, SyncPolicy.BUFFERED)) {
            for (int i = 0; i < 1000; i++) {
                wal.append(EntryType.PUT, ByteSlice.of("k" + i), ByteSlice.of("v" + i), i + 1L);
            }
        }

        // Simulate crash mid-write: append 7 garbage bytes to the file
        try (FileChannel channel = FileChannel.open(walFile, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer garbage = ByteBuffer.wrap(new byte[]{0x12, 0x34, 0x56, 0x78, (byte) 0x9A, (byte) 0xBC, (byte) 0xDE});
            channel.write(garbage);
        }

        WalRecoveryResult recovery = WalRecovery.recover(walFile);
        assertThat(recovery.truncatedAtEof()).isTrue();
        assertThat(recovery.records()).hasSize(1000);
        assertThat(recovery.maxSequenceNumber()).isEqualTo(1000L);

        // Subsequent recovery after truncation should find no corruption and no truncation needed
        WalRecoveryResult secondRecovery = WalRecovery.recover(walFile);
        assertThat(secondRecovery.truncatedAtEof()).isFalse();
        assertThat(secondRecovery.records()).hasSize(1000);
    }
}
