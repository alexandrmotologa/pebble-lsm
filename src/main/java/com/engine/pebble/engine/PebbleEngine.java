package com.engine.pebble.engine;

import com.engine.pebble.common.ByteSlice;
import com.engine.pebble.compaction.CompactionManager;
import com.engine.pebble.domain.batch.BatchOperation;
import com.engine.pebble.domain.batch.WriteBatch;
import com.engine.pebble.domain.cache.BlockCache;
import com.engine.pebble.domain.memtable.MemTable;
import com.engine.pebble.domain.memtable.ReadOnlyMemTable;
import com.engine.pebble.domain.model.EntryType;
import com.engine.pebble.domain.model.Snapshot;
import com.engine.pebble.domain.model.ValueEntry;
import com.engine.pebble.domain.sstable.SSTableReader;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Primary interface for the PebbleLSM embedded key-value storage engine.
 * Supports single operations, atomic multi-key WriteBatches, MVCC point-in-time Snapshots,
 * key expiration (TTL), transparent LZ4 block compression, and adaptive write-stall backpressure.
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

        // Replay existing WAL files
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
                    memTable.put(rec.key(), rec.value(), rec.sequenceNumber(), rec.expiresAtTimestamp());
                } else {
                    memTable.delete(rec.key(), rec.sequenceNumber());
                }
            }
            if (result.maxSequenceNumber() > maxSeq) {
                maxSeq = result.maxSequenceNumber();
            }
        }

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
        put(ByteSlice.of(key), ByteSlice.of(value), 0L);
    }

    public void put(byte[] key, byte[] value, Duration ttl) throws IOException {
        long expiresAt = (ttl != null && !ttl.isZero() && !ttl.isNegative())
                ? System.currentTimeMillis() + ttl.toMillis()
                : 0L;
        put(ByteSlice.of(key), ByteSlice.of(value), expiresAt);
    }

    public void put(ByteSlice key, ByteSlice value) throws IOException {
        put(key, value, 0L);
    }

    public void put(ByteSlice key, ByteSlice value, long expiresAtTimestamp) throws IOException {
        ensureOpen();
        applyBackpressureIfNeeded();
        writeLock.lock();
        try {
            maybeFreezeAndFlush();
            long seq = sequenceGenerator.incrementAndGet();
            activeWal.append(EntryType.PUT, key, value, seq, expiresAtTimestamp);
            activeMemTable.put(key, value, seq, expiresAtTimestamp);
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
        applyBackpressureIfNeeded();
        writeLock.lock();
        try {
            maybeFreezeAndFlush();
            long seq = sequenceGenerator.incrementAndGet();
            activeWal.append(EntryType.DELETE, key, ByteSlice.EMPTY, seq, 0L);
            activeMemTable.delete(key, seq);
            deletesCount.incrementAndGet();
            manifestManager.updateLastSequenceNumber(seq);
        } finally {
            writeLock.unlock();
        }
    }

    public void write(WriteBatch batch) throws IOException {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        ensureOpen();
        applyBackpressureIfNeeded();
        writeLock.lock();
        try {
            maybeFreezeAndFlush();
            int batchSize = batch.size();
            long startSeq = sequenceGenerator.addAndGet(batchSize) - batchSize + 1;

            activeWal.appendBatch(batch, startSeq);

            for (int i = 0; i < batchSize; i++) {
                BatchOperation op = batch.operations().get(i);
                long currentSeq = startSeq + i;
                if (op.type() == EntryType.PUT) {
                    activeMemTable.put(op.key(), op.value(), currentSeq, op.expiresAtTimestamp());
                    writesCount.incrementAndGet();
                } else {
                    activeMemTable.delete(op.key(), currentSeq);
                    deletesCount.incrementAndGet();
                }
            }

            manifestManager.updateLastSequenceNumber(startSeq + batchSize - 1);
        } finally {
            writeLock.unlock();
        }
    }

    public Snapshot getSnapshot() {
        ensureOpen();
        return new Snapshot(sequenceGenerator.get());
    }

    public Optional<byte[]> get(byte[] key) throws IOException {
        return get(ByteSlice.of(key));
    }

    public Optional<byte[]> get(ByteSlice key) throws IOException {
        return get(key, Long.MAX_VALUE);
    }

    public Optional<byte[]> get(byte[] key, Snapshot snapshot) throws IOException {
        return get(ByteSlice.of(key), snapshot != null ? snapshot.sequenceNumber() : Long.MAX_VALUE);
    }

    public Optional<byte[]> get(ByteSlice key, Snapshot snapshot) throws IOException {
        return get(key, snapshot != null ? snapshot.sequenceNumber() : Long.MAX_VALUE);
    }

    private Optional<byte[]> get(ByteSlice key, long maxSeq) throws IOException {
        ValueEntry entry = getEntry(key, maxSeq);
        if (entry == null || entry.isTombstone() || entry.isExpired()) {
            return Optional.empty();
        }
        return Optional.of(entry.value().toByteArray());
    }

    public Optional<String> getString(String key) throws IOException {
        return get(key.getBytes(StandardCharsets.UTF_8)).map(b -> new String(b, StandardCharsets.UTF_8));
    }

    public Optional<String> getString(String key, Snapshot snapshot) throws IOException {
        return get(key.getBytes(StandardCharsets.UTF_8), snapshot).map(b -> new String(b, StandardCharsets.UTF_8));
    }

    public void putString(String key, String value) throws IOException {
        put(key.getBytes(StandardCharsets.UTF_8), value.getBytes(StandardCharsets.UTF_8));
    }

    public void putString(String key, String value, Duration ttl) throws IOException {
        put(key.getBytes(StandardCharsets.UTF_8), value.getBytes(StandardCharsets.UTF_8), ttl);
    }

    public ValueEntry getEntry(ByteSlice key) throws IOException {
        return getEntry(key, Long.MAX_VALUE);
    }

    public ValueEntry getEntry(ByteSlice key, long maxSeq) throws IOException {
        ensureOpen();
        readsCount.incrementAndGet();

        // 1. Check Active MemTable
        ValueEntry entry = activeMemTable.get(key, maxSeq);
        if (entry != null) {
            return entry;
        }

        // 2. Check Frozen MemTable
        ReadOnlyMemTable frozen = frozenMemTable;
        if (frozen != null) {
            entry = frozen.get(key, maxSeq);
            if (entry != null) {
                return entry;
            }
        }

        // 3. Check Level 0 SSTables (newest to oldest)
        List<SSTableReader> l0Snapshot = manifestManager.getL0TablesSnapshot();
        for (SSTableReader reader : l0Snapshot) {
            entry = reader.get(key, maxSeq);
            if (entry != null) {
                return entry;
            }
        }

        // 4. Check Level 1 SSTables (non-overlapping)
        List<SSTableReader> l1Snapshot = manifestManager.getL1TablesSnapshot();
        for (SSTableReader reader : l1Snapshot) {
            if (reader.footer().minKey().compareTo(key) <= 0 && reader.footer().maxKey().compareTo(key) >= 0) {
                entry = reader.get(key, maxSeq);
                if (entry != null) {
                    return entry;
                }
                break;
            }
        }

        // 5. Check Level 2 SSTables (non-overlapping)
        List<SSTableReader> l2Snapshot = manifestManager.getL2TablesSnapshot();
        for (SSTableReader reader : l2Snapshot) {
            if (reader.footer().minKey().compareTo(key) <= 0 && reader.footer().maxKey().compareTo(key) >= 0) {
                entry = reader.get(key, maxSeq);
                if (entry != null) {
                    return entry;
                }
                break;
            }
        }

        return null;
    }

    public RangeScanner scan(byte[] fromKey, byte[] toKey) {
        return scan(
                (fromKey != null) ? ByteSlice.of(fromKey) : null,
                (toKey != null) ? ByteSlice.of(toKey) : null,
                null
        );
    }

    public RangeScanner scan(byte[] fromKey, byte[] toKey, Snapshot snapshot) {
        return scan(
                (fromKey != null) ? ByteSlice.of(fromKey) : null,
                (toKey != null) ? ByteSlice.of(toKey) : null,
                snapshot
        );
    }

    public RangeScanner scan(ByteSlice fromKey, ByteSlice toKey) {
        return scan(fromKey, toKey, null);
    }

    public RangeScanner scan(ByteSlice fromKey, ByteSlice toKey, Snapshot snapshot) {
        ensureOpen();
        long maxSeq = (snapshot != null) ? snapshot.sequenceNumber() : Long.MAX_VALUE;

        List<InternalIterator> iterators = new ArrayList<>();

        // 1. Active MemTable
        iterators.add(new MemTableIterator(activeMemTable.scan(fromKey, toKey), maxSeq));

        // 2. Frozen MemTable
        ReadOnlyMemTable frozen = frozenMemTable;
        if (frozen != null) {
            iterators.add(new MemTableIterator(frozen.scan(fromKey, toKey), maxSeq));
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

        PriorityQueueMergeIterator merger = new PriorityQueueMergeIterator(iterators, true, maxSeq);
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

    private void applyBackpressureIfNeeded() {
        int l0Count = manifestManager.getL0Count();
        if (l0Count >= options.l0StopWritesThreshold()) {
            compactionManager.maybeTriggerCompactionAsync();
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } else if (l0Count >= options.l0SlowdownWritesThreshold()) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void maybeFreezeAndFlush() throws IOException {
        if (activeMemTable.approximateBytes() >= options.memTableThresholdBytes()) {
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
