# On-disk binary format specification

This document details the binary structures used by PebbleLSM on disk: the Write-Ahead Log (WAL) and the Sorted String Table (SSTable).

## Write-Ahead Log (WAL)

The WAL consists of consecutive binary frames appended sequentially to disk.

```
+----------------+----------------+----------------+----------------+----------------+----------------+
|  CRC32 (4B)    |  OpType (1B)   |  KeyLen (4B)   |  Key (NB)      |  ValLen (4B)   |  Val (MB)      |
+----------------+----------------+----------------+----------------+----------------+----------------+
```

### Fields

- `CRC32` (4 bytes, unsigned int): Checksum computed over `OpType`, `KeyLen`, `Key`, `ValLen`, and `Val`.
- `OpType` (1 byte): `0x01` for `PUT`, `0x02` for `DELETE` (tombstone). For delete records, `ValLen` is 0.
- `KeyLen` (4 bytes, int): Length of the key in bytes.
- `Key` (variable): Raw key bytes.
- `ValLen` (4 bytes, int): Length of the value in bytes (0 for deletions).
- `Val` (variable): Raw value bytes.

### Crash semantics and recovery

During engine startup, the recovery scanner reads the WAL from byte offset 0. Each frame checksum is verified. If an unexpected EOF or checksum mismatch occurs, the scanner checks whether the failure is at the end of the file. A partial frame at EOF (typically caused by an abrupt system crash mid-write) is truncated, while uncorrupted preceding records are loaded into the MemTable. Any corruption earlier in the log triggers an unrecoverable data exception.

## Sorted String Table (SSTable)

An SSTable is an immutable disk file divided into four sections: Data Blocks, Sparse Block Index, Bloom Filter, and Magic Footer.

```
+-------------------------------------------------------------+
| Data Block 0 (sorted key-value records, ~4KB)               |
+-------------------------------------------------------------+
| Data Block 1                                                |
+-------------------------------------------------------------+
| ...                                                         |
+-------------------------------------------------------------+
| Data Block N                                                |
+-------------------------------------------------------------+
| Sparse Block Index                                          |
|   - Array of [KeyLen (4B), KeyBytes, Offset (8B), Size (4B)]|
+-------------------------------------------------------------+
| Bloom Filter                                                |
|   - Bitset array generated with MurmurHash3                 |
+-------------------------------------------------------------+
| Magic Footer (Fixed 48 bytes)                               |
+-------------------------------------------------------------+
```

### Data block structure

Keys and values are written in lexicographical byte order. Within a data block, records are laid out sequentially:

```
[KeyLen: 4B][KeyBytes][ValLen: 4B][ValBytes][SeqNum: 8B][Type: 1B]
```

When a data block reaches the target size (default 4KB), the writer seals the block and starts a new one.

### Sparse block index

The sparse block index contains one entry per data block:
- `Key`: First key of the block.
- `Offset`: Byte offset of the block from the start of the SSTable file.
- `Size`: Total byte size of the block.

To locate a key, the reader performs binary search over the block index to find the highest block key less than or equal to the target key.

### Bloom filter

A bitset generated using MurmurHash3 dual-hashing ($h_i(x) = h_1(x) + i \cdot h_2(x)$). Default configuration allocates 10 bits per key, targeting a false positive probability under 1%.

### Magic footer

The last 48 bytes of every SSTable file contain fixed-length metadata:

```
[IndexOffset: 8B][IndexLength: 4B][BloomOffset: 8B][BloomLength: 4B][NumEntries: 8B][Version: 4B][Magic: 4B]
```

- `IndexOffset` (8 bytes): File offset where the sparse index starts.
- `IndexLength` (4 bytes): Byte length of the sparse index.
- `BloomOffset` (8 bytes): File offset where the Bloom filter starts.
- `BloomLength` (4 bytes): Byte length of the Bloom filter.
- `NumEntries` (8 bytes): Total number of key-value pairs in this table.
- `Version` (4 bytes): Format version number (currently `1`).
- `Magic` (4 bytes): Fixed constant `0x50454242` ("PEBB" in ASCII).
