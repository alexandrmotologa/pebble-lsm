# Storage engine architecture

This document describes the internal components of PebbleLSM, how writes are buffered and written to disk, how the read path navigates memory and storage tiers, and how snapshot isolation and write backpressure operate.

## Write path

Write operations (`put`, `delete`, and `write(batch)`) execute in two synchronous steps:

1. The engine appends the operation to the active Write-Ahead Log (WAL) file using `FileChannel`. Single records write a `PUT` or `DELETE` frame. Batches write a single `BATCH` frame containing all operations wrapped in one CRC32 checksum. By default, the WAL forces writes to storage according to the configured synchronization policy (`SyncPolicy.ALWAYS` or `SyncPolicy.BUFFERED`).
2. The engine inserts the operations into the active `MemTable`, an in-memory concurrent skip list map (`ConcurrentSkipListMap`). For each key, new entries prepend to an atomic version chain (`previousVersion`), preserving earlier sequence numbers for active snapshots. The table updates an atomic byte counter tracking memory usage based on key, value, and node overhead.

Because writes append to disk and insert into memory, write latency remains small and predictable, avoiding the random in-place updates typical of B-Tree structures.

### WriteBatch semantics

`WriteBatch` groups multiple updates and deletes into a single atomic write. The engine assigns consecutive sequence numbers to the batch operations under a write lock, writes the entire batch to the WAL in a single I/O operation, and inserts the operations into the MemTable. Readers observe either all batch updates or none.

### MemTable lifecycle

When the active MemTable reaches its configured byte limit (such as 32 MB):

1. The engine atomically moves the active MemTable reference into an immutable `ReadOnlyMemTable` slot.
2. A new empty `MemTable` and a new `WriteAheadLog` are created to accept ongoing write operations without interruption.
3. The engine submits a flush task to a virtual-thread executor. The worker reads all key-value pairs from the read-only table in sorted order, writes them to a new Level 0 SSTable file, and registers the SSTable with the `ManifestManager`.
4. Once the SSTable is safely synced to disk and the manifest is updated, the previous WAL file and the in-memory read-only table are released and deleted.

### Write-stall backpressure

When disk write speeds or compaction workers lag behind client write rates, Level 0 files can accumulate. Because L0 files have overlapping key ranges, excess files hurt read performance. PebbleLSM implements dynamic backpressure based on L0 file counts:

- **Slowdown threshold** (default 8 files): When L0 contains at least 8 files, writes sleep for 1 millisecond per call, slowing down producers.
- **Stop threshold** (default 12 files): When L0 reaches 12 files, the engine blocks writers in a wait loop until the compaction worker reduces the L0 file count below the threshold.

## Read path

Point lookups (`get`) search through levels in chronological order, from newest to oldest:

1. **Active MemTable**: If the key exists, the engine returns the newest entry matching the sequence criteria (or empty if it is a tombstone or expired by TTL).
2. **Immutable MemTables**: If a flush is in progress, the read checks frozen in-memory tables.
3. **Level 0 SSTables**: L0 files may have overlapping key ranges. The engine checks L0 files from newest to oldest:
   - The engine checks whether the requested key falls within the file min/max key bounds recorded in the footer. If not, it skips the file immediately.
   - The engine queries the SSTable Bloom filter. If the filter returns false, the key is guaranteed not to exist in this file, avoiding disk I/O.
   - If the Bloom filter returns true, the engine binary searches the sparse block index to locate the target 4KB data block.
   - The data block is retrieved from the LRU block cache or read from disk, decompressed with LZ4 if compression is enabled, and searched.
4. **Level 1 and Level 2 SSTables**: If L0 does not contain the key, the search continues to L1, then L2. Because key ranges within L1 and L2 do not overlap, at most one SSTable per level needs to be checked.

## Snapshot isolation (MVCC)

PebbleLSM supports snapshot isolation for point reads and range scans. Calling `engine.getSnapshot()` captures the current sequence number without acquiring long-lived locks.

- **MemTable visibility**: When reading with a snapshot, the engine walks the `previousVersion` chain on the `ValueEntry` until it finds the version with `sequenceNumber <= snapshot.getSequenceNumber()`. Writes made after snapshot creation are invisible.
- **SSTable visibility**: Each record in SSTable data blocks stores its sequence number. Iterators and readers discard records with sequence numbers greater than the snapshot sequence number.

## Range scans

Range scans (`scan(fromKey, toKey)`) construct a `PriorityQueueMergeIterator` combining iterators over:

- The active MemTable.
- Any immutable MemTables.
- All relevant SSTables at L0, L1, and L2.

The merge iterator uses a min-heap sorted by key and sequence number. When duplicate keys appear across different levels, the iterator yields only the newest visible version and advances past older versions. If the newest version is a delete tombstone or has expired past its TTL timestamp, the key is omitted from scan results.

## Embedded network server

PebbleLSM includes an embedded TCP server implementing the Redis Serialization Protocol (RESP). The server runs on Java 21 virtual threads, spawning a lightweight virtual thread per client connection. Supported operations include `PING`, `SET`, `GET`, `DEL`, `MGET`, `MSET`, `DBSIZE`, `INFO`, and `FLUSHDB`. This allows external services and CLI clients to interact with PebbleLSM without custom client libraries.
