package com.engine.pebble.domain.memtable;

import com.engine.pebble.common.ByteSlice;
import com.engine.pebble.domain.model.EntryType;
import com.engine.pebble.domain.model.ValueEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MemTableTest {

    private MemTable memTable;

    @BeforeEach
    void setUp() {
        memTable = new MemTable();
    }

    @Test
    void testPutAndGet() {
        ByteSlice key = ByteSlice.of("user:101");
        ByteSlice val = ByteSlice.of("Alex");

        memTable.put(key, val, 1L);

        ValueEntry entry = memTable.get(key);
        assertThat(entry).isNotNull();
        assertThat(entry.type()).isEqualTo(EntryType.PUT);
        assertThat(entry.value().toUtf8String()).isEqualTo("Alex");
        assertThat(entry.sequenceNumber()).isEqualTo(1L);
        assertThat(memTable.size()).isEqualTo(1);
        assertThat(memTable.approximateBytes()).isGreaterThan(0);
    }

    @Test
    void testDeleteTombstone() {
        ByteSlice key = ByteSlice.of("user:102");
        ByteSlice val = ByteSlice.of("Bob");

        memTable.put(key, val, 1L);
        memTable.delete(key, 2L);

        ValueEntry entry = memTable.get(key);
        assertThat(entry).isNotNull();
        assertThat(entry.isTombstone()).isTrue();
        assertThat(entry.sequenceNumber()).isEqualTo(2L);
    }

    @Test
    void testOverwriteUpdatesBytes() {
        ByteSlice key = ByteSlice.of("key1");
        ByteSlice val1 = ByteSlice.of("short");
        ByteSlice val2 = ByteSlice.of("a much longer value string to test byte accounting");

        memTable.put(key, val1, 1L);
        long bytes1 = memTable.approximateBytes();

        memTable.put(key, val2, 2L);
        long bytes2 = memTable.approximateBytes();

        assertThat(bytes2).isGreaterThan(bytes1);
        assertThat(memTable.size()).isEqualTo(1);
    }

    @Test
    void testRangeScan() {
        for (int i = 0; i < 10; i++) {
            ByteSlice key = ByteSlice.of(String.format("key:%02d", i));
            ByteSlice val = ByteSlice.of(String.format("val:%02d", i));
            memTable.put(key, val, i + 1);
        }

        var iter = memTable.scan(ByteSlice.of("key:03"), ByteSlice.of("key:07"));
        List<String> keys = new ArrayList<>();
        while (iter.hasNext()) {
            Map.Entry<ByteSlice, ValueEntry> entry = iter.next();
            keys.add(entry.getKey().toUtf8String());
        }

        assertThat(keys).containsExactly("key:03", "key:04", "key:05", "key:06");
    }

    @Test
    void testMinMaxKeys() {
        assertThat(memTable.minKey()).isNull();
        assertThat(memTable.maxKey()).isNull();

        memTable.put(ByteSlice.of("c"), ByteSlice.of("3"), 1L);
        memTable.put(ByteSlice.of("a"), ByteSlice.of("1"), 2L);
        memTable.put(ByteSlice.of("b"), ByteSlice.of("2"), 3L);

        assertThat(memTable.minKey().toUtf8String()).isEqualTo("a");
        assertThat(memTable.maxKey().toUtf8String()).isEqualTo("c");
    }
}
