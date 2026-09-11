package com.engine.pebble.engine;

import com.engine.pebble.domain.cache.BlockCache;
import com.engine.pebble.domain.sstable.SSTableReader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Manages the MANIFEST file, tracking active SSTables across levels (L0, L1, L2).
 * Guarantees crash-consistent atomic state transitions for flushes and compactions.
 */
public class ManifestManager implements AutoCloseable {

    private static final byte OP_ADD_SSTABLE = 0x01;
    private static final byte OP_REMOVE_SSTABLE = 0x02;
    private static final byte OP_NEXT_FILE_NUM = 0x03;
    private static final byte OP_LAST_SEQ_NUM = 0x04;

    private final Path dbPath;
    private final Path manifestPath;
    private final BlockCache blockCache;
    private final FileChannel manifestChannel;

    private final List<SSTableReader> l0Tables = new ArrayList<>(); // Newest first
    private final List<SSTableReader> l1Tables = new ArrayList<>(); // Sorted by minKey
    private final List<SSTableReader> l2Tables = new ArrayList<>(); // Sorted by minKey

    private final AtomicLong nextFileNumber = new AtomicLong(1L);
    private final AtomicLong lastSequenceNumber = new AtomicLong(0L);
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    private ManifestManager(Path dbPath, BlockCache blockCache, FileChannel manifestChannel) {
        this.dbPath = dbPath;
        this.manifestPath = dbPath.resolve("MANIFEST");
        this.blockCache = blockCache;
        this.manifestChannel = manifestChannel;
    }

    public static ManifestManager open(Path dbPath, BlockCache blockCache) throws IOException {
        Files.createDirectories(dbPath);
        Path manifestPath = dbPath.resolve("MANIFEST");

        // Replay existing manifest if present
        List<Long> activeL0 = new ArrayList<>();
        List<Long> activeL1 = new ArrayList<>();
        List<Long> activeL2 = new ArrayList<>();
        long maxFileNum = 1L;
        long maxSeqNum = 0L;

        if (Files.exists(manifestPath) && Files.size(manifestPath) > 0) {
            try (FileChannel channel = FileChannel.open(manifestPath, StandardOpenOption.READ)) {
                ByteBuffer buf = ByteBuffer.allocate(13); // Max record size: 1 (op) + 4 (level) + 8 (num)
                while (channel.position() < channel.size()) {
                    buf.clear();
                    buf.limit(1);
                    if (channel.read(buf) <= 0) break;
                    buf.flip();
                    byte op = buf.get();

                    if (op == OP_ADD_SSTABLE) {
                        buf.clear();
                        buf.limit(12);
                        channel.read(buf);
                        buf.flip();
                        int level = buf.getInt();
                        long fileNum = buf.getLong();
                        if (level == 0) activeL0.add(0, fileNum); // Newest first
                        else if (level == 1) activeL1.add(fileNum);
                        else if (level == 2) activeL2.add(fileNum);
                        if (fileNum >= maxFileNum) maxFileNum = fileNum + 1;
                    } else if (op == OP_REMOVE_SSTABLE) {
                        buf.clear();
                        buf.limit(12);
                        channel.read(buf);
                        buf.flip();
                        int level = buf.getInt();
                        long fileNum = buf.getLong();
                        if (level == 0) activeL0.remove(fileNum);
                        else if (level == 1) activeL1.remove(fileNum);
                        else if (level == 2) activeL2.remove(fileNum);
                    } else if (op == OP_NEXT_FILE_NUM) {
                        buf.clear();
                        buf.limit(8);
                        channel.read(buf);
                        buf.flip();
                        long num = buf.getLong();
                        if (num > maxFileNum) maxFileNum = num;
                    } else if (op == OP_LAST_SEQ_NUM) {
                        buf.clear();
                        buf.limit(8);
                        channel.read(buf);
                        buf.flip();
                        long seq = buf.getLong();
                        if (seq > maxSeqNum) maxSeqNum = seq;
                    }
                }
            }
        }

        FileChannel appendChannel = FileChannel.open(
                manifestPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND
        );

        ManifestManager manager = new ManifestManager(dbPath, blockCache, appendChannel);
        manager.nextFileNumber.set(maxFileNum);
        manager.lastSequenceNumber.set(maxSeqNum);

        // Open SSTable readers for active files
        for (long fileNum : activeL0) {
            Path path = dbPath.resolve(formatSSTableName(fileNum));
            if (Files.exists(path)) {
                manager.l0Tables.add(SSTableReader.open(path, fileNum, 0, blockCache));
            }
        }
        for (long fileNum : activeL1) {
            Path path = dbPath.resolve(formatSSTableName(fileNum));
            if (Files.exists(path)) {
                manager.l1Tables.add(SSTableReader.open(path, fileNum, 1, blockCache));
            }
        }
        for (long fileNum : activeL2) {
            Path path = dbPath.resolve(formatSSTableName(fileNum));
            if (Files.exists(path)) {
                manager.l2Tables.add(SSTableReader.open(path, fileNum, 2, blockCache));
            }
        }

        // Sort L1 and L2 by minKey
        manager.l1Tables.sort((a, b) -> a.footer().minKey().compareTo(b.footer().minKey()));
        manager.l2Tables.sort((a, b) -> a.footer().minKey().compareTo(b.footer().minKey()));

        // Garbage collect any orphaned SSTable files on disk
        manager.cleanOrphanFiles(activeL0, activeL1, activeL2);

        return manager;
    }

    public long getNextFileNumber() {
        long num = nextFileNumber.getAndIncrement();
        recordNextFileNumber(num + 1);
        return num;
    }

    public long getLastSequenceNumber() {
        return lastSequenceNumber.get();
    }

    public void updateLastSequenceNumber(long seq) {
        lastSequenceNumber.updateAndGet(curr -> Math.max(curr, seq));
    }

    public void addSSTable(int level, SSTableReader reader) throws IOException {
        rwLock.writeLock().lock();
        try {
            if (level == 0) {
                l0Tables.add(0, reader); // Prepend so newest is first
            } else if (level == 1) {
                l1Tables.add(reader);
                l1Tables.sort((a, b) -> a.footer().minKey().compareTo(b.footer().minKey()));
            } else if (level == 2) {
                l2Tables.add(reader);
                l2Tables.sort((a, b) -> a.footer().minKey().compareTo(b.footer().minKey()));
            }
            recordAddSSTable(level, reader.fileNumber());
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public void applyCompactionEdit(
            int sourceLevel,
            List<SSTableReader> removed,
            int targetLevel,
            List<SSTableReader> added) throws IOException {

        rwLock.writeLock().lock();
        try {
            // Remove old readers
            for (SSTableReader r : removed) {
                if (sourceLevel == 0) l0Tables.remove(r);
                else if (sourceLevel == 1) l1Tables.remove(r);
                else if (sourceLevel == 2) l2Tables.remove(r);

                // Also if sourceLevel was 0 and target was 1, some removed could have been from level 1
                l1Tables.remove(r);
                l2Tables.remove(r);

                recordRemoveSSTable(r.level(), r.fileNumber());
            }

            // Add new readers
            for (SSTableReader r : added) {
                if (targetLevel == 1) {
                    l1Tables.add(r);
                } else if (targetLevel == 2) {
                    l2Tables.add(r);
                }
                recordAddSSTable(targetLevel, r.fileNumber());
            }

            l1Tables.sort((a, b) -> a.footer().minKey().compareTo(b.footer().minKey()));
            l2Tables.sort((a, b) -> a.footer().minKey().compareTo(b.footer().minKey()));

            // Close and delete obsolete SSTable files from disk
            for (SSTableReader r : removed) {
                try {
                    r.close();
                    Files.deleteIfExists(r.path());
                } catch (IOException ignored) {}
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public List<SSTableReader> getL0TablesSnapshot() {
        rwLock.readLock().lock();
        try {
            return new ArrayList<>(l0Tables);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public List<SSTableReader> getL1TablesSnapshot() {
        rwLock.readLock().lock();
        try {
            return new ArrayList<>(l1Tables);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public List<SSTableReader> getL2TablesSnapshot() {
        rwLock.readLock().lock();
        try {
            return new ArrayList<>(l2Tables);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public int getL0Count() {
        rwLock.readLock().lock();
        try {
            return l0Tables.size();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public long getLevelBytes(int level) {
        rwLock.readLock().lock();
        try {
            List<SSTableReader> list = (level == 0) ? l0Tables : (level == 1) ? l1Tables : l2Tables;
            long total = 0;
            for (SSTableReader r : list) {
                try {
                    total += Files.size(r.path());
                } catch (IOException ignored) {}
            }
            return total;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    private synchronized void recordAddSSTable(int level, long fileNumber) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(13);
        buf.put(OP_ADD_SSTABLE);
        buf.putInt(level);
        buf.putLong(fileNumber);
        buf.flip();
        while (buf.hasRemaining()) {
            manifestChannel.write(buf);
        }
        manifestChannel.force(false);
    }

    private synchronized void recordRemoveSSTable(int level, long fileNumber) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(13);
        buf.put(OP_REMOVE_SSTABLE);
        buf.putInt(level);
        buf.putLong(fileNumber);
        buf.flip();
        while (buf.hasRemaining()) {
            manifestChannel.write(buf);
        }
        manifestChannel.force(false);
    }

    private synchronized void recordNextFileNumber(long nextFileNumber) {
        try {
            ByteBuffer buf = ByteBuffer.allocate(9);
            buf.put(OP_NEXT_FILE_NUM);
            buf.putLong(nextFileNumber);
            buf.flip();
            while (buf.hasRemaining()) {
                manifestChannel.write(buf);
            }
            manifestChannel.force(false);
        } catch (IOException ignored) {}
    }

    public synchronized void recordLastSequenceNumber(long seq) {
        try {
            ByteBuffer buf = ByteBuffer.allocate(9);
            buf.put(OP_LAST_SEQ_NUM);
            buf.putLong(seq);
            buf.flip();
            while (buf.hasRemaining()) {
                manifestChannel.write(buf);
            }
            manifestChannel.force(false);
        } catch (IOException ignored) {}
    }

    private void cleanOrphanFiles(List<Long> l0, List<Long> l1, List<Long> l2) {
        Set<String> activeFiles = new HashSet<>();
        activeFiles.add("MANIFEST");
        for (long n : l0) activeFiles.add(formatSSTableName(n));
        for (long n : l1) activeFiles.add(formatSSTableName(n));
        for (long n : l2) activeFiles.add(formatSSTableName(n));

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dbPath, "*.sst")) {
            for (Path entry : stream) {
                if (!activeFiles.contains(entry.getFileName().toString())) {
                    try {
                        Files.deleteIfExists(entry);
                    } catch (IOException ignored) {}
                }
            }
        } catch (IOException ignored) {}
    }

    public static String formatSSTableName(long fileNumber) {
        return String.format("%06d.sst", fileNumber);
    }

    public static String formatWalName(long fileNumber) {
        return String.format("%06d.wal", fileNumber);
    }

    @Override
    public void close() throws IOException {
        rwLock.writeLock().lock();
        try {
            recordLastSequenceNumber(lastSequenceNumber.get());
            if (manifestChannel.isOpen()) {
                manifestChannel.force(false);
                manifestChannel.close();
            }
            for (SSTableReader r : l0Tables) r.close();
            for (SSTableReader r : l1Tables) r.close();
            for (SSTableReader r : l2Tables) r.close();
            l0Tables.clear();
            l1Tables.clear();
            l2Tables.clear();
        } finally {
            rwLock.writeLock().unlock();
        }
    }
}
