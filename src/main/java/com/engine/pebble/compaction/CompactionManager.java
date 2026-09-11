package com.engine.pebble.compaction;

import com.engine.pebble.common.ByteSlice;
import com.engine.pebble.domain.cache.BlockCache;
import com.engine.pebble.domain.model.ValueEntry;
import com.engine.pebble.domain.sstable.SSTableMetadata;
import com.engine.pebble.domain.sstable.SSTableReader;
import com.engine.pebble.domain.sstable.SSTableWriter;
import com.engine.pebble.engine.ManifestManager;
import com.engine.pebble.engine.PebbleOptions;
import com.engine.pebble.iterator.InternalIterator;
import com.engine.pebble.iterator.PriorityQueueMergeIterator;
import com.engine.pebble.iterator.SSTableIterator;
import com.engine.pebble.iterator.StorageEntry;

import java.io.IOException;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Executes leveled background compaction across L0, L1, and L2.
 * Eliminates redundant keys, purges tombstones at bottom level, and keeps read amplification low.
 */
public class CompactionManager implements AutoCloseable {

    private final PebbleOptions options;
    private final ManifestManager manifestManager;
    private final BlockCache blockCache;
    private final ExecutorService compactionExecutor;
    private final ReentrantLock compactionLock = new ReentrantLock();
    private final AtomicBoolean isCompacting = new AtomicBoolean(false);

    public CompactionManager(PebbleOptions options, ManifestManager manifestManager, BlockCache blockCache) {
        this.options = options;
        this.manifestManager = manifestManager;
        this.blockCache = blockCache;
        this.compactionExecutor = Executors.newSingleThreadExecutor(
                Thread.ofVirtual().name("pebble-compaction-", 0).factory()
        );
    }

    public void maybeTriggerCompactionAsync() {
        if (shouldCompact()) {
            compactionExecutor.submit(() -> {
                try {
                    compact();
                } catch (Exception ignored) {}
            });
        }
    }

    public boolean shouldCompact() {
        if (manifestManager.getL0Count() >= options.l0CompactionThreshold()) {
            return true;
        }
        if (manifestManager.getLevelBytes(1) >= options.l1MaxSizeBytes()) {
            return true;
        }
        return false;
    }

    public void compact() throws IOException {
        if (!compactionLock.tryLock()) {
            // Already running a compaction cycle
            return;
        }
        isCompacting.set(true);
        try {
            // Check L0 -> L1 compaction
            if (manifestManager.getL0Count() >= options.l0CompactionThreshold()) {
                compactL0ToL1();
            }

            // Check L1 -> L2 compaction
            if (manifestManager.getLevelBytes(1) >= options.l1MaxSizeBytes()) {
                compactL1ToL2();
            }
        } finally {
            isCompacting.set(false);
            compactionLock.unlock();
        }
    }

    private void compactL0ToL1() throws IOException {
        List<SSTableReader> l0Snapshot = manifestManager.getL0TablesSnapshot();
        if (l0Snapshot.isEmpty()) {
            return;
        }

        // Determine min and max key across selected L0 files
        ByteSlice minKey = null;
        ByteSlice maxKey = null;
        for (SSTableReader r : l0Snapshot) {
            ByteSlice rMin = r.footer().minKey();
            ByteSlice rMax = r.footer().maxKey();
            if (minKey == null || rMin.compareTo(minKey) < 0) minKey = rMin;
            if (maxKey == null || rMax.compareTo(maxKey) > 0) maxKey = rMax;
        }

        // Find intersecting L1 files
        List<SSTableReader> l1Snapshot = manifestManager.getL1TablesSnapshot();
        List<SSTableReader> overlappingL1 = new ArrayList<>();
        for (SSTableReader r : l1Snapshot) {
            if (r.footer().minKey().compareTo(maxKey) <= 0 && r.footer().maxKey().compareTo(minKey) >= 0) {
                overlappingL1.add(r);
            }
        }

        List<SSTableReader> inputTables = new ArrayList<>(l0Snapshot);
        inputTables.addAll(overlappingL1);

        // Perform multi-way merge
        List<InternalIterator> iterators = new ArrayList<>();
        for (SSTableReader r : inputTables) {
            iterators.add(new SSTableIterator(r.iterator()));
        }

        // Tombstones cannot be dropped at L1 if L2 might contain older versions
        boolean dropTombstones = manifestManager.getL2TablesSnapshot().isEmpty();
        try (PriorityQueueMergeIterator merger = new PriorityQueueMergeIterator(iterators, dropTombstones)) {
            List<SSTableReader> newL1Tables = writeCompactedTables(merger, 1);
            manifestManager.applyCompactionEdit(0, inputTables, 1, newL1Tables);
        }
    }

    private void compactL1ToL2() throws IOException {
        List<SSTableReader> l1Snapshot = manifestManager.getL1TablesSnapshot();
        if (l1Snapshot.isEmpty()) {
            return;
        }

        // Pick the first L1 file
        SSTableReader sourceL1 = l1Snapshot.get(0);
        ByteSlice minKey = sourceL1.footer().minKey();
        ByteSlice maxKey = sourceL1.footer().maxKey();

        // Find intersecting L2 files
        List<SSTableReader> l2Snapshot = manifestManager.getL2TablesSnapshot();
        List<SSTableReader> overlappingL2 = new ArrayList<>();
        for (SSTableReader r : l2Snapshot) {
            if (r.footer().minKey().compareTo(maxKey) <= 0 && r.footer().maxKey().compareTo(minKey) >= 0) {
                overlappingL2.add(r);
            }
        }

        List<SSTableReader> inputTables = new ArrayList<>();
        inputTables.add(sourceL1);
        inputTables.addAll(overlappingL2);

        List<InternalIterator> iterators = new ArrayList<>();
        for (SSTableReader r : inputTables) {
            iterators.add(new SSTableIterator(r.iterator()));
        }

        // Deepest level L2 safely drops tombstones
        try (PriorityQueueMergeIterator merger = new PriorityQueueMergeIterator(iterators, true)) {
            List<SSTableReader> newL2Tables = writeCompactedTables(merger, 2);
            manifestManager.applyCompactionEdit(1, inputTables, 2, newL2Tables);
        }
    }

    private List<SSTableReader> writeCompactedTables(InternalIterator iterator, int targetLevel) throws IOException {
        List<SSTableReader> result = new ArrayList<>();
        long targetFileSize = 2L * 1024 * 1024; // 2MB per file

        while (iterator.hasNext()) {
            long fileNum = manifestManager.getNextFileNumber();
            Path path = options.dbPath().resolve(ManifestManager.formatSSTableName(fileNum));

            List<Map.Entry<ByteSlice, ValueEntry>> fileEntries = new ArrayList<>();
            long currentBytes = 0;

            while (iterator.hasNext() && currentBytes < targetFileSize) {
                StorageEntry entry = iterator.next();
                fileEntries.add(new AbstractMap.SimpleEntry<>(entry.key(), entry.toValueEntry()));
                currentBytes += (entry.key().length() + entry.value().length() + 32);
            }

            if (!fileEntries.isEmpty()) {
                SSTableWriter writer = new SSTableWriter(
                        path,
                        fileNum,
                        options.sstableTargetBlockSize(),
                        fileEntries.size(),
                        options.compressionType()
                );
                writer.write(fileEntries.iterator());
                SSTableReader reader = SSTableReader.open(path, fileNum, targetLevel, blockCache);
                result.add(reader);
            }
        }

        return result;
    }

    public boolean isCompacting() {
        return isCompacting.get();
    }

    @Override
    public void close() {
        compactionExecutor.shutdown();
        try {
            if (!compactionExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                compactionExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            compactionExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
