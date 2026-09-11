package com.engine.pebble.iterator;

import com.engine.pebble.common.ByteSlice;
import com.engine.pebble.domain.memtable.MemTable;
import com.engine.pebble.domain.model.EntryType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MergeIteratorTest {

    @Test
    void testMergeDeduplicationAndTombstoneDrop() throws Exception {
        // Stream 1 (MemTable: newest)
        MemTable mem1 = new MemTable();
        mem1.put(ByteSlice.of("b"), ByteSlice.of("b_newest"), 10L);
        mem1.put(ByteSlice.of("d"), ByteSlice.of("d_val"), 11L);

        // Stream 2 (Middle SSTable)
        MemTable mem2 = new MemTable();
        mem2.put(ByteSlice.of("a"), ByteSlice.of("a_val"), 4L);
        mem2.put(ByteSlice.of("b"), ByteSlice.of("b_middle"), 5L);
        mem2.delete(ByteSlice.of("c"), 6L); // Tombstone for c

        // Stream 3 (Oldest SSTable)
        MemTable mem3 = new MemTable();
        mem3.put(ByteSlice.of("b"), ByteSlice.of("b_ancient"), 1L);
        mem3.put(ByteSlice.of("c"), ByteSlice.of("c_ancient"), 2L);

        List<InternalIterator> iterators = List.of(
                new MemTableIterator(mem1.iterator()),
                new MemTableIterator(mem2.iterator()),
                new MemTableIterator(mem3.iterator())
        );

        try (PriorityQueueMergeIterator merger = new PriorityQueueMergeIterator(iterators, true)) {
            List<StorageEntry> results = new ArrayList<>();
            while (merger.hasNext()) {
                results.add(merger.next());
            }

            assertThat(results).hasSize(3);

            assertThat(results.get(0).key().toUtf8String()).isEqualTo("a");
            assertThat(results.get(0).value().toUtf8String()).isEqualTo("a_val");
            assertThat(results.get(0).sequenceNumber()).isEqualTo(4L);

            assertThat(results.get(1).key().toUtf8String()).isEqualTo("b");
            assertThat(results.get(1).value().toUtf8String()).isEqualTo("b_newest");
            assertThat(results.get(1).sequenceNumber()).isEqualTo(10L);

            assertThat(results.get(2).key().toUtf8String()).isEqualTo("d");
            assertThat(results.get(2).value().toUtf8String()).isEqualTo("d_val");
            assertThat(results.get(2).sequenceNumber()).isEqualTo(11L);

            // 'c' should be eliminated by tombstone
            assertThat(results).noneMatch(e -> e.key().toUtf8String().equals("c"));
        }
    }

    @Test
    void testRangeScannerBounds() throws Exception {
        MemTable mem = new MemTable();
        for (int i = 0; i < 10; i++) {
            mem.put(ByteSlice.of(String.format("k%02d", i)), ByteSlice.of("v" + i), i + 1L);
        }

        InternalIterator baseIter = new MemTableIterator(mem.iterator());
        try (RangeScanner scanner = new RangeScanner(baseIter, ByteSlice.of("k03"), ByteSlice.of("k07"))) {
            List<String> scannedKeys = new ArrayList<>();
            while (scanner.hasNext()) {
                scannedKeys.add(scanner.next().key().toUtf8String());
            }
            assertThat(scannedKeys).containsExactly("k03", "k04", "k05", "k06");
        }
    }
}
