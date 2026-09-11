# PebbleLSM

PebbleLSM is an embedded Log-Structured Merge-tree (LSM) key-value storage engine written in Java 21 LTS. It uses append-only disk writes, an in-memory skip list, immutable sorted string tables, block indexes, leveled compaction, and an embedded RESP network server.

## Architecture overview

Traditional B-Trees modify disk blocks in place, causing random I/O and lock contention when writes are frequent. PebbleLSM converts updates into sequential appends through a pipeline of memory buffers and on-disk tiers:

1. Writes append to a Write-Ahead Log (WAL) on disk for durability, then insert into an active in-memory MemTable backed by a concurrent skip list.
2. When the active MemTable reaches its memory limit (e.g. 32 MB), the engine freezes it into a read-only table and allocates a new active MemTable.
3. A background worker flushes frozen tables to Level 0 (L0) Sorted String Tables (SSTables) on disk.
4. Point lookups query the active MemTable, frozen MemTables, and disk SSTables from newest to oldest. Bloom filters and sparse block indexes prune unnecessary disk reads.
5. A background compaction manager merges overlapping SSTables across levels (L0 to L1 to L2), sorting keys, discarding superseded versions, and reclaiming space from deleted keys (tombstones).

```
[Client Request: put(k, v) / delete(k) / write(batch)]
          │
          ├──► Write-Ahead Log (WAL) [append-only disk file with CRC32 framing]
          │
          └──► Active MemTable [ConcurrentSkipListMap with MVCC version chains]
                     │
                     ▼ (freeze on capacity)
               ReadOnly MemTable
                     │
                     ▼ (background virtual-thread flush)
               Level 0 SSTables (overlapping key ranges, optional LZ4 blocks)
                     │
                     ▼ (leveled compaction)
               Level 1 SSTables (partitioned, non-overlapping)
                     │
                     ▼ (cascading compaction)
               Level 2 SSTables (larger capacity, tombstone purging)
```

## Features

- Java 21 runtime with virtual threads for asynchronous flush, compaction, and client network connections.
- Crash durability with per-record and per-batch CRC32 verification and automatic recovery on startup.
- Fast point queries using MurmurHash3 Bloom filters (1% false positive rate) and memory-mapped sparse block indexes.
- K-way merge iterator supporting lexicographically sorted range scans (`scan(fromKey, toKey)`).
- Leveled Compaction worker to bound read and space amplification.
- In-memory LRU block cache for hot SSTable data blocks.
- Atomic batch operations (`WriteBatch`) written with a single WAL sync.
- Snapshot isolation (MVCC) providing point-in-time point lookups and range scans.
- Transparent LZ4 block compression reducing SSTable footprint on disk.
- Per-record Time-To-Live (TTL) with automatic pruning during reads and compactions.
- Dynamic write-stall backpressure to prevent L0 file accumulation during burst writes.
- Interactive JLine3 REPL shell with command history and tab completion.
- Embedded Redis-compatible RESP server on port 6379, accessible with standard Redis clients (`redis-cli`).
- Real-time diagnostic terminal UI showing level distribution, write throughput, and compaction metrics.
- Benchmark module comparing throughput and latency against RocksDB JNI.

## Quick start

### Prerequisites

- Java Development Kit (JDK) 21 or later
- Apache Maven 3.9 or later

### Building from source

```bash
git clone https://github.com/alexandrmotologa/pebble-lsm.git
cd pebble-lsm
mvn clean package
```

The build produces a shaded standalone JAR file at `target/pebble-lsm-1.0.0-SNAPSHOT.jar`.

### Basic usage

```java
import com.engine.pebble.domain.batch.WriteBatch;
import com.engine.pebble.domain.model.Snapshot;
import com.engine.pebble.domain.sstable.CompressionType;
import com.engine.pebble.engine.PebbleEngine;
import com.engine.pebble.engine.PebbleOptions;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

public class Example {
    public static void main(String[] args) throws Exception {
        PebbleOptions options = PebbleOptions.builder()
                .dbPath(Path.of("./data"))
                .memTableThresholdBytes(32 * 1024 * 1024)
                .compressionType(CompressionType.LZ4)
                .l0CompactionThreshold(4)
                .build();

        try (PebbleEngine engine = PebbleEngine.open(options)) {
            byte[] key = "user:1001".getBytes(StandardCharsets.UTF_8);
            byte[] value = "{\"name\":\"Alex\",\"role\":\"engineer\"}".getBytes(StandardCharsets.UTF_8);

            // Point write and read
            engine.put(key, value);
            Optional<byte[]> result = engine.get(key);
            result.ifPresent(v -> System.out.println("Found: " + new String(v, StandardCharsets.UTF_8)));

            // Write with Time-To-Live (TTL)
            byte[] sessionKey = "session:xyz".getBytes(StandardCharsets.UTF_8);
            byte[] sessionVal = "active".getBytes(StandardCharsets.UTF_8);
            engine.put(sessionKey, sessionVal, Duration.ofSeconds(60));

            // Atomic batch write
            try (WriteBatch batch = new WriteBatch()) {
                batch.put("account:A".getBytes(StandardCharsets.UTF_8), "balance:500".getBytes(StandardCharsets.UTF_8));
                batch.put("account:B".getBytes(StandardCharsets.UTF_8), "balance:750".getBytes(StandardCharsets.UTF_8));
                batch.delete("account:C".getBytes(StandardCharsets.UTF_8));
                engine.write(batch);
            }

            // Snapshot isolation
            try (Snapshot snapshot = engine.getSnapshot()) {
                engine.put(key, "new_value".getBytes(StandardCharsets.UTF_8));

                // Reads from snapshot observe the state at creation time
                Optional<byte[]> snapValue = engine.get(snapshot, key);
                System.out.println("Snapshot value: " + new String(snapValue.orElseThrow(), StandardCharsets.UTF_8));
            }

            // Delete
            engine.delete(key);
        }
    }
}
```

### Interactive REPL shell

Launch the interactive shell to run queries, scans, and inspect stats directly:

```bash
java -jar target/pebble-lsm-1.0.0-SNAPSHOT.jar shell --path ./data
```

Supported shell commands:
- `put <key> <value> [ttl_seconds]`
- `get <key>`
- `delete <key>`
- `scan [from_key] [to_key] [limit]`
- `flush`
- `compact`
- `stats`
- `help` / `exit`

### Embedded Redis RESP server

Run PebbleLSM as a standalone network key-value store compatible with Redis:

```bash
java -jar target/pebble-lsm-1.0.0-SNAPSHOT.jar server --port 6379 --path ./data
```

Connect using standard tools such as `redis-cli`:

```bash
redis-cli -p 6379 ping
# PONG

redis-cli -p 6379 set user:1001 "Alice"
# OK

redis-cli -p 6379 get user:1001
# "Alice"

redis-cli -p 6379 mget user:1001 user:1002
# 1) "Alice"
# 2) (nil)

redis-cli -p 6379 dbsize
# (integer) 1

redis-cli -p 6379 info
```

### Diagnostic TUI

Inspect level distribution, live write rates, and compaction progress:

```bash
java -jar target/pebble-lsm-1.0.0-SNAPSHOT.jar tui --path ./data
```

### Running benchmarks

Execute the benchmark suite comparing PebbleLSM to RocksDB:

```bash
# Sequential write benchmark (1,000,000 operations)
mvn test-compile exec:java -Dexec.mainClass="com.engine.pebble.benchmarks.SequentialWriteBenchmark"

# Random read benchmark
mvn test-compile exec:java -Dexec.mainClass="com.engine.pebble.benchmarks.RandomReadBenchmark"

# RocksDB comparison benchmark
mvn test-compile exec:java -Dexec.mainClass="com.engine.pebble.benchmarks.RocksDbComparisonBenchmark"
```

## Documentation

- [Architecture Guide](docs/architecture.md): Write pipeline, read path, MVCC snapshot isolation, and write stall backpressure.
- [SSTable Format Specification](docs/format-spec.md): Binary layout, WAL batch framing, LZ4 block compression, and magic footers.
- [Compaction Mechanics](docs/compaction.md): Leveled compaction algorithm, tombstone purging, and TTL expiration.

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE) for details.
