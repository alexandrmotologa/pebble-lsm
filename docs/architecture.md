# Storage engine architecture

This document describes the internal components of PebbleLSM, how writes are buffered and written to disk, and how the read path navigates memory and storage tiers.

## Write path

Write operations (`put` and `delete`) execute in two synchronous steps:

1. The engine appends the operation to the active Write-Ahead Log (WAL) file using `FileChannel`. The entry includes a CRC32 checksum, record type (PUT or DELETE tombstone), key length, key bytes, value length, and value bytes. By default, the WAL forces writes to storage according to the configured synchronization policy (`SyncPolicy.ALWAYS` or `SyncPolicy.BUFFERED`).
2. The engine inserts the key and value into the active `MemTable`, an in-memory concurrent skip list map (`ConcurrentSkipListMap`). The table updates an atomic byte counter estimating total memory usage based on key, value, and node overhead.

Because writes only append to disk and insert into memory, write latency remains small and predictable, avoiding the random in-place updates typical of B-Tree structures.

### MemTable lifecycle

When the active MemTable reaches its configured byte limit (such as 32 MB):

1. The engine atomically moves the active MemTable reference into an immutable `ReadOnlyMemTable` slot.
2. A new empty `MemTable` and a new `WriteAheadLog` are created to accept ongoing write operations without interruption.
3. The engine submits a flush task to a virtual-thread executor. The worker reads all key-value pairs from the read-only table in sorted order, writes them to a new Level 0 SSTable file, and registers the SSTable with the `ManifestManager`.
4. Once the SSTable is safely synced to disk and the manifest is updated, the previous WAL file and the in-memory read-only table are released and deleted.

## Read path

Point lookups (`get`) search through levels in strict chronological order, from newest to oldest:

1. **Active MemTable**: If the key exists, the engine returns the value (or empty if it is a tombstone).
2. **Immutable MemTables**: If a flush is in progress, the read checks any frozen in-memory tables.
3. **Level 0 SSTables**: L0 files may have overlapping key ranges because each file represents an independent MemTable flush. The engine checks L0 files from newest to oldest. For each file:
   - The engine checks whether the requested key falls within the file min/max key bounds recorded in the footer. If not, it skips the file immediately.
   - The engine queries the SSTable Bloom filter. If the filter returns false, the key is guaranteed not to exist in this file, avoiding disk I/O.
   - If the Bloom filter returns true, the engine binary searches the sparse block index to identify the specific 4KB data block containing the key.
   - The 4KB data block is fetched from the block cache if present; otherwise, it is read from disk and cached. The block is then searched for the key.
4. **Level 1 and Level 2 SSTables**: If L0 does not contain the key, the search continues to L1, then L2. Because key ranges within L1 and L2 are guaranteed not to overlap, at most one SSTable per level needs to be checked.

## Range scans

Range scans (`scan(fromKey, toKey)`) construct a `PriorityQueueMergeIterator` combining iterators over:

- The active MemTable.
- Any immutable MemTables.
- All relevant SSTables at L0, L1, and L2.

The merge iterator uses a min-heap sorted by key and sequence number. When duplicate keys appear across different levels, the iterator yields only the newest version and advances past older versions. If the newest version is a delete tombstone, the key is omitted from the scan results.
