# Compaction mechanics

This document details the leveled compaction process used by PebbleLSM to limit read amplification and reclaim disk space.

## Why compaction is required

Because the engine writes updates and deletes as new entries rather than in-place modifications, stale versions and delete tombstones accumulate on disk over time. Without compaction:

- Disk space usage grows indefinitely.
- Point lookups and range scans must inspect multiple SSTable files, increasing read latency.

## Leveled compaction hierarchy

PebbleLSM organizes SSTables into levels:

- **Level 0 (L0)**: SSTables created directly by MemTable flushes. Files in L0 may have overlapping key ranges because each file reflects a chronological snapshot of writes.
- **Level 1 (L1)**: SSTables created by compacting L0 files. Files in L1 have mutually exclusive, non-overlapping key ranges. Target aggregate size is 10 MB.
- **Level 2 (L2)**: Higher capacity level. Target aggregate size is 100 MB. Key ranges are strictly non-overlapping.

```
L0 (Overlapping):      [b - g]    [d - m]    [a - k]    [h - z]
                              │
                              ▼ (L0 -> L1 Compaction)
L1 (Non-overlapping):  [a - f]    [g - l]    [m - r]    [s - z]
                              │
                              ▼ (L1 -> L2 Compaction)
L2 (Non-overlapping):  [a - c]  [d - g]  [h - k]  [l - p]  [q - u]  [v - z]
```

## Compaction trigger and execution

1. **Trigger criteria**:
   - L0 compaction triggers when the number of L0 files reaches or exceeds the threshold (default: 4 files).
   - L1 to L2 compaction triggers when the aggregate byte size of L1 exceeds the level capacity limit (default: 10 MB).

2. **Selecting input files**:
   - For L0 to L1 compaction, all current L0 files are selected because their ranges may overlap with any part of L1.
   - The compaction manager computes the aggregate key range `[minKey, maxKey]` across the selected L0 files, then selects all L1 files that intersect this range.

3. **Multi-way merge**:
   - A `PriorityQueueMergeIterator` streams through all input SSTables in sorted key order.
   - For duplicate keys, only the newest entry (highest sequence number) is retained; older versions are discarded.

4. **Tombstone elimination rule**:
   - A delete tombstone cannot be dropped if older versions of that key might still reside in deeper levels.
   - A tombstone is safely dropped only during compaction into the maximum level (L2), or when the compaction manager verifies that no deeper level contains the key.

5. **Emitting new SSTables**:
   - The merged stream is written to new SSTable files targeting a maximum file size (default: 2 MB per file).
   - Each new file is registered at the target level.

6. **Atomic manifest commit**:
   - The `ManifestManager` appends a version edit record to the manifest log, listing the newly added SSTable file IDs and the obsolete SSTable file IDs.
   - Once the manifest write completes and syncs, obsolete SSTables are safely unlinked from disk.
