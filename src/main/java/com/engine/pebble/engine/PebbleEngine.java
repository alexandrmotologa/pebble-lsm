package com.engine.pebble.engine;

import com.engine.pebble.common.ByteSlice;
import com.engine.pebble.compaction.CompactionManager;
import com.engine.pebble.domain.cache.BlockCache;
import com.engine.pebble.domain.memtable.MemTable;
import com.engine.pebble.domain.memtable.ReadOnlyMemTable;
import com.engine.pebble.domain.model.EntryType;
import com.engine.pebble.domain.model.ValueEntry;
import com.engine.pebble.domain.sstable.SSTableReader;
import com.engine.pebble.domain.wal.SyncPolicy;
import com.engine.pebble.domain.wal.WalRecord;
import com.engine.pebble.domain.wal.WalRecovery;
import com.engine.pebble.domain.wal.WalRecoveryResult;
import com.engine.pebble.domain.wal.WriteAheadLog;
import com.engine.pebble.iterator.InternalIterator;
import com.engine.pebble.iterator.MemTableIterator;
import com.engine.pebble.iterator.PriorityQueueMergeIterator;
import com.engine.pebble.iterator.RangeScanner;
import com.engine.pebble.iterator.SSTableIterator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Primary interface for the PebbleLSM embedded key-value storage engine.
 */
public class PebbleEngine implements AutoCloseable {

    private final PebbleOptions options;
    private final BlockCache blockCache;
    private final ManifestManager manifestManager;
    private final CompactionManager compactionManager;
    private final FlushWorker flushWorker;

    private final ReentrantLock writeLock = new ReentrantLock();
    private final AtomicLong sequenceGenerator = new AtomicLong(0L);

    private MemTable activeMemTable;
    private WriteAheadLog activeWal;
    private volatile ReadOnlyMemTable frozenMemTable;
    private CompletableFuture<Void> currentFlushFuture;

    private final AtomicLong writesCount = new AtomicLong(0);
    private final AtomicLong readsCount = new AtomicLong(0);
    private final AtomicLong deletesCount = new AtomicLong(0);

    private volatile boolean closed = false;

    private PebbleEngine(
            PebbleOptions options,
            BlockCache blockCache,
            ManifestManager manifestManager,
            CompactionManager compactionManager,
            FlushWorker flushWorker,
            MemTable initialMemTable,
            WriteAheadLog initialWal,
            long initialSeqNum) {

        this.options = options;
        this.blockCache = blockCache;
        this.manifestManager = manifestManager;
        this.compactionManager = compactionManager;
        this.flushWorker = flushWorker;
        this.activeMemTable = initialMemTable;
        this.activeWal = initialWal;
        this.sequenceGenerator.set(initialSeqNum);
    }

    public static PebbleEngine open(PebbleOptions options) throws IOException {
        Files.createDirectories(options.dbPath());

        BlockCache cache = new BlockCache(options.blockCacheCapacity());
        ManifestManager manifest = ManifestManager.open(options.dbPath(), cache);
        CompactionManager compaction = new CompactionManager(options, manifest, cache);
        FlushWorker flusher = new FlushWorker(options, manifest, cache, compaction);

        MemTable memTable = new MemTable();
        long maxSeq = manifest.getLastSequenceNumber();

        // Scan for existing WAL files to replay
        List<Path> existingWals = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(options.dbPath(), "*.wal")) {
            for (Path p : stream) {
                existingWals.add(p);
            }
        }
        existingWals.sort(Path::compareTo);

        for (Path walPath : existingWals) {
            WalRecoveryResult result = WalRecovery.recover(walPath);
            for (WalRecord rec : result.records()) {
                if (rec.type() == EntryType.PUT) {
                    memTable.put(rec.key(), rec.value(), rec.sequenceNumber());
                } else {
                    memTable.delete(rec.key(), rec.sequenceNumber());
                }
            }
            if (result.maxSequenceNumber() > maxSeq) {
                maxSeq = result.maxSequenceNumber();
            }
        }

        // If recovered memTable has data, flush it to L0 to start with a clean WAL
        if (!memTable.isEmpty()) {
            ReadOnlyMemTable frozen = new ReadOnlyMemTable(memTable, 0L);
            flusher.flushSync(frozen, null);
            for (Path walPath : existingWals) {
                Files.deleteIfExists(walPath);
            }
            memTable = new MemTable();
        }

        long walFileNum = manifest.getNextFileNumber();
        Path walPath = options.dbPath().resolve(ManifestManager.formatWalName(walFileNum));
        WriteAheadLog wal = new WriteAheadLog(walPath, walFileNum, options.syncPolicy());

        return new PebbleEngine(options, cache, manifest, compaction, flusher, memTable, wal, maxSeq);
    }

    public void put(byte[] key, byte[] value) throws IOException {
        put(ByteSlice.of(key), ByteSlice.of(value));
    }

    public void put(ByteSlice key, ByteSlice value) throws IOException {
        ensureOpen();
        writeLock.lock();
        try {
            maybeFreezeAndFlush();
            long seq = sequenceGenerator.incrementAndGet();
            activeWal.append(EntryType.PUT, key, value, seq);
            activeMemTable.put(key, value, seq);
            writesCount.incrementAndGet();
            manifestManager.updateLastSequenceNumber(seq);
        } finally {
            writeLock.unlock();
        }
    }

    public void delete(byte[] key) throws IOException {
        delete(ByteSlice.of(key));
    }

    public void delete(ByteSlice key) throws IOException {
        ensureOpen();
        writeLock.lock();
        try {
            maybeFreezeAndFlush();
            long seq = sequenceGenerator.incrementAndGet();
            activeWal.append(EntryType.DELETE, key, ByteSlice.EMPTY, seq);
            activeMemTable.delete(key, seq);
            deletesCount.incrementAndGet();
            manifestManager.updateLastSequenceNumber(seq);
        } finally {
            writeLock.unlock();
        }
    }

    public Optional<byte[]> get(byte[] key) throws IOException {
        ValueEntry entry = getEntry(ByteSlice.of(key));
        if (entry == null || entry.isTombstone()) {
            return Optional.empty();
        }
        return Optional.of(entry.value().toByteArray());
    }

    public Optional<String> getString(String key) throws IOException {
        return get(key.getBytes(StandardCharsets.UTF_8)).map(b -> new String(b, StandardCharsets.UTF_8));
    }

    public void putString(String key, String value) throws IOException {
        put(key.getBytes(StandardCharsets.UTF_8), value.getBytes(StandardCharsets.UTF_8));
    }

    public ValueEntry getEntry(ByteSlice key) throws IOException {
        ensureOpen();
        readsCount.incrementAndGet();

        // 1. Check Active MemTable
        ValueEntry entry = activeMemTable.get(key);
        if (entry != null) {
            return entry;
        }

        // 2. Check Frozen MemTable
        ReadOnlyMemTable frozen = frozenMemTable;
        if (frozen != null) {
            entry = frozen.get(key);
            if (entry != null) {
                return entry;
            }
        }

        // 3. Check Level 0 SSTables (newest to oldest)
        List<SSTableReader> l0Snapshot = manifestManager.getL0TablesSnapshot();
        for (SSTableReader reader : l0Snapshot) {
            entry = reader.get(key);
            if (entry != null) {
                return entry;
            }
        }

        // 4. Check Level 1 SSTables (non-overlapping key ranges)
        List<SSTableReader> l1Snapshot = manifestManager.getL1TablesSnapshot();
        for (SSTableReader reader : l1Snapshot) {
            if (reader.footer().minKey().compareTo(key) <= 0 && reader.footer().maxKey().compareTo(key) >= 0) {
                entry = reader.get(key);
                if (entry != null) {
                    return entry;
                }
                break; // Non-overlapping: at most one table can contain the key
            }
        }

        // 5. Check Level 2 SSTables (non-overlapping key ranges)
        List<SSTableReader> l2Snapshot = manifestManager.getL2TablesSnapshot();
        for (SSTableReader reader : l2Snapshot) {
            if (reader.footer().minKey().compareTo(key) <= 0 && reader.footer().maxKey().compareTo(key) >= 0) {
                entry = reader.get(key);
                if (entry != null) {
                    return entry;
                }
                break;
            }
        }

        return null;
    }

    public RangeScanner scan(byte[] fromKey, byte[] toKey) {
        ByteSlice from = (fromKey != null) ? ByteSlice.of(fromKey) : null;
        ByteSlice to = (toKey != null) ? ByteSlice.of(toKey) : null;
        return scan(from, to);
    }

    public RangeScanner scan(ByteSlice fromKey, ByteSlice toKey) {
        ensureOpen();

        List<InternalIterator> iterators = new ArrayList<>();

        // 1. Active MemTable
        iterators.add(new MemTableIterator(activeMemTable.scan(fromKey, toKey)));

        // 2. Frozen MemTable
        ReadOnlyMemTable frozen = frozenMemTable;
        if (frozen != null) {
            iterators.add(new MemTableIterator(frozen.scan(fromKey, toKey)));
        }

        // 3. Level 0 SSTables
        for (SSTableReader r : manifestManager.getL0TablesSnapshot()) {
            iterators.add(new SSTableIterator(r.iterator()));
        }

        // 4. Level 1 SSTables
        for (SSTableReader r : manifestManager.getL1TablesSnapshot()) {
            if (r.metadata().overlaps(fromKey, toKey)) {
                iterators.add(new SSTableIterator(r.iterator()));
            }
        }

        // 5. Level 2 SSTables
        for (SSTableReader r : manifestManager.getL2TablesSnapshot()) {
            if (r.metadata().overlaps(fromKey, toKey)) {
                iterators.add(new SSTableIterator(r.iterator()));
            }
        }

        PriorityQueueMergeIterator merger = new PriorityQueueMergeIterator(iterators, true);
        return new RangeScanner(merger, fromKey, toKey);
    }

    public void flush() throws IOException {
        ensureOpen();
        writeLock.lock();
        try {
            if (activeMemTable.isEmpty()) {
                return;
            }
            ReadOnlyMemTable toFlush = new ReadOnlyMemTable(activeMemTable, activeWal.getFileNumber());
            Path oldWalPath = activeWal.getPath();
            activeWal.close();

            // Create new WAL and new MemTable
            activeMemTable = new MemTable();
            long nextWalNum = manifestManager.getNextFileNumber();
            Path nextWalPath = options.dbPath().resolve(ManifestManager.formatWalName(nextWalNum));
            activeWal = new WriteAheadLog(nextWalPath, nextWalNum, options.syncPolicy());

            flushWorker.flushSync(toFlush, oldWalPath);
        } finally {
            writeLock.unlock();
        }
    }

    public void compact() throws IOException {
        ensureOpen();
        compactionManager.compact();
    }

    private void maybeFreezeAndFlush() throws IOException {
        if (activeMemTable.approximateBytes() >= options.memTableThresholdBytes()) {
            // Wait for previous background flush to complete if still in progress
            if (currentFlushFuture != null && !currentFlushFuture.isDone()) {
                currentFlushFuture.join();
            }

            frozenMemTable = new ReadOnlyMemTable(activeMemTable, activeWal.getFileNumber());
            Path oldWal = activeWal.getPath();
            activeWal.close();

            activeMemTable = new MemTable();
            long nextWalNum = manifestManager.getNextFileNumber();
            Path nextWalPath = options.dbPath().resolve(ManifestManager.formatWalName(nextWalNum));
            activeWal = new WriteAheadLog(nextWalPath, nextWalNum, options.syncPolicy());

            ReadOnlyMemTable toFlush = frozenMemTable;
            currentFlushFuture = flushWorker.flushAsync(toFlush, oldWal).thenRun(() -> {
                if (frozenMemTable == toFlush) {
                    frozenMemTable = null;
                }
            });
        }
    }

    public PebbleMetrics getMetrics() {
        return new PebbleMetrics(
                writesCount.get(),
                readsCount.get(),
                deletesCount.get(),
                activeMemTable.size(),
                activeMemTable.approximateBytes(),
                manifestManager.getL0Count(),
                manifestManager.getLevelBytes(0),
                manifestManager.getL1TablesSnapshot().size(),
                manifestManager.getLevelBytes(1),
                manifestManager.getL2TablesSnapshot().size(),
                manifestManager.getLevelBytes(2),
                blockCache.hits(),
                blockCache.misses(),
                blockCache.hitRatio(),
                compactionManager.isCompacting()
        );
    }

    public PebbleOptions options() {
        return options;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("PebbleEngine is closed");
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        writeLock.lock();
        try {
            closed = true;
            if (currentFlushFuture != null) {
                currentFlushFuture.join();
            }

            // Flush remaining data in active MemTable
            if (!activeMemTable.isEmpty()) {
                ReadOnlyMemTable frozen = new ReadOnlyMemTable(activeMemTable, activeWal.getFileNumber());
                Path oldWal = activeWal.getPath();
                flushWorker.flushSync(frozen, oldWal);
            }

            activeWal.close();
            flushWorker.close();
            compactionManager.close();
            manifestManager.close();
        } finally {
            writeLock.unlock();
        }
    }
}
