package com.engine.pebble.engine;

import com.engine.pebble.compaction.CompactionManager;
import com.engine.pebble.domain.cache.BlockCache;
import com.engine.pebble.domain.memtable.ReadOnlyMemTable;
import com.engine.pebble.domain.sstable.SSTableReader;
import com.engine.pebble.domain.sstable.SSTableWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Background worker that flushes frozen ReadOnlyMemTables to Level 0 SSTables on disk.
 */
public class FlushWorker implements AutoCloseable {

    private final PebbleOptions options;
    private final ManifestManager manifestManager;
    private final BlockCache blockCache;
    private final CompactionManager compactionManager;
    private final ExecutorService flushExecutor;

    public FlushWorker(
            PebbleOptions options,
            ManifestManager manifestManager,
            BlockCache blockCache,
            CompactionManager compactionManager) {

        this.options = options;
        this.manifestManager = manifestManager;
        this.blockCache = blockCache;
        this.compactionManager = compactionManager;
        this.flushExecutor = Executors.newSingleThreadExecutor(
                Thread.ofVirtual().name("pebble-flush-", 0).factory()
        );
    }

    public CompletableFuture<Void> flushAsync(ReadOnlyMemTable frozen, Path oldWalPath) {
        return CompletableFuture.runAsync(() -> {
            try {
                flushSync(frozen, oldWalPath);
            } catch (IOException e) {
                throw new RuntimeException("Failed to flush MemTable to L0 SSTable", e);
            }
        }, flushExecutor);
    }

    public void flushSync(ReadOnlyMemTable frozen, Path oldWalPath) throws IOException {
        if (frozen == null || frozen.isEmpty()) {
            if (oldWalPath != null) {
                Files.deleteIfExists(oldWalPath);
            }
            return;
        }

        long fileNumber = manifestManager.getNextFileNumber();
        Path sstPath = options.dbPath().resolve(ManifestManager.formatSSTableName(fileNumber));

        SSTableWriter writer = new SSTableWriter(
                sstPath,
                fileNumber,
                options.sstableTargetBlockSize(),
                frozen.size()
        );

        writer.write(frozen.iterator());

        SSTableReader reader = SSTableReader.open(sstPath, fileNumber, 0, blockCache);
        manifestManager.addSSTable(0, reader);

        // Safely remove obsolete WAL log
        if (oldWalPath != null) {
            Files.deleteIfExists(oldWalPath);
        }

        // Trigger compaction check if threshold exceeded
        compactionManager.maybeTriggerCompactionAsync();
    }

    @Override
    public void close() {
        flushExecutor.shutdown();
        try {
            if (!flushExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                flushExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            flushExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
