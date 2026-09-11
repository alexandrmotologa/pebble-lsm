# On-disk binary format specification

This document details the binary structures used by PebbleLSM on disk: the Write-Ahead Log (WAL) and the Sorted String Table (SSTable).

## Write-Ahead Log (WAL)

The WAL consists of consecutive binary frames appended sequentially to disk. PebbleLSM supports single-record frames (`PUT`, `DELETE`) and atomic batch frames (`BATCH`).

### Single record frame (PUT / DELETE)

```
+----------------+----------------+----------------+----------------+----------------+----------------+----------------+
|  CRC32 (4B)    |  OpType (1B)   |  KeyLen (4B)   |  Key (NB)      |  ValLen (4B)   |  Val (MB)      |  TTL (8B)      |
+----------------+----------------+----------------+----------------+----------------+----------------+----------------+
```

- `CRC32` (4 bytes, unsigned int): Checksum computed over `OpType`, `KeyLen`, `Key`, `ValLen`, `Val`, and `TTL`.
- `OpType` (1 byte): `0x01` for `PUT`, `0x02` for `DELETE` (tombstone). For delete records, `ValLen` is 0.
- `KeyLen` (4 bytes, int): Byte length of the key.
- `Key` (variable): Raw key bytes.
- `ValLen` (4 bytes, int): Byte length of the value (0 for deletions).
- `Val` (variable): Raw value bytes.
- `TTL` (8 bytes, long): Millisecond epoch timestamp when the record expires, or 0 if no TTL was set.

### Batch record frame (BATCH)

```
+----------------+----------------+----------------+----------------+--------------------------------------+
|  CRC32 (4B)    |  OpType=0x03   |  StartSeq (8B) |  Count (4B)    |  Operations Payload (variable)      |
+----------------+----------------+----------------+----------------+--------------------------------------+
```

- `CRC32` (4 bytes, unsigned int): Checksum computed over `OpType`, `StartSeq`, `Count`, and the operations payload.
- `OpType` (1 byte): `0x03` indicating a batch operation.
- `StartSeq` (8 bytes, long): Base sequence number assigned to the first operation in the batch.
- `Count` (4 bytes, int): Number of operations encoded in the payload.
- `Operations Payload`: Repeated records, each consisting of `[OpType: 1B][KeyLen: 4B][KeyBytes][ValLen: 4B][ValBytes][TTL: 8B]`.

### Crash recovery semantics

During engine startup, the recovery scanner reads the WAL from byte offset 0. Each frame checksum is verified. If an unexpected EOF or checksum mismatch occurs, the scanner checks whether the failure is at the end of the file. A partial frame at EOF (typically caused by an abrupt system crash mid-write) is truncated, while uncorrupted preceding records are loaded into the MemTable. Any corruption earlier in the log triggers an unrecoverable data exception.

## Sorted String Table (SSTable)

An SSTable is an immutable disk file divided into four sections: Data Blocks, Sparse Block Index, Bloom Filter, and Magic Footer.

```
+-------------------------------------------------------------+
| Data Block 0 (compressed or uncompressed, ~4KB)             |
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

Within an uncompressed data block, sorted key-value records are laid out sequentially:

```
[KeyLen: 4B][KeyBytes][ValLen: 4B][ValBytes][SeqNum: 8B][Type: 1B][TTL: 8B]
```

- `KeyLen` / `KeyBytes`: Length and bytes of the key.
- `ValLen` / `ValBytes`: Length and bytes of the value (0 bytes for tombstones).
- `SeqNum` (8 bytes): Sequence number at write time.
- `Type` (1 byte): `0x01` for PUT, `0x02` for DELETE.
- `TTL` (8 bytes): Expiration timestamp in epoch milliseconds (0 if permanent).

### Compressed data block on disk

When block compression is enabled (e.g. `CompressionType.LZ4`), each data block on disk is preceded by an envelope:

```
+-------------------+----------------------+--------------------+---------------------+
| CompressionType   | UncompressedLen (4B) | CompressedLen (4B) | Payload (variable)  |
| (1 byte)          |                      |                    |                     |
+-------------------+----------------------+--------------------+---------------------+
```

- `CompressionType` (1 byte): `0x00` for `NONE`, `0x01` for `LZ4`.
- `UncompressedLen` (4 bytes): Original size of the raw block data.
- `CompressedLen` (4 bytes): Size of the payload following this header.
- `Payload`: Raw compressed bytes (or uncompressed bytes if `NONE`).

During reads, the reader inspects `CompressionType` and decompress using `LZ4FastDecompressor` into a 4KB buffer.

### Sparse block index

The sparse block index contains one entry per data block:
- `Key`: First key of the block.
- `Offset`: Byte offset of the block from the start of the SSTable file.
- `Size`: Total byte size of the block on disk.

The reader performs binary search over the block index to identify the target block for point queries.

### Bloom filter

A bitset generated using MurmurHash3 dual-hashing ($h_i(x) = h_1(x) + i \cdot h_2(x)$). The default configuration allocates 10 bits per key, targeting a false positive probability under 1%.

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
