# PebbleLSM

PebbleLSM is an embedded Log-Structured Merge-tree (LSM) key-value storage engine written in Java 21 LTS. It uses append-only disk writes, an in-memory skip list, immutable sorted string tables, block indexes, and leveled compaction to achieve high write throughput and predictable read latency.

## Architecture overview

Traditional B-Trees modify disk blocks in place, causing random I/O and lock contention when writes are frequent. PebbleLSM converts updates into sequential appends through a pipeline of memory buffers and on-disk tiers:

1. Writes append to a Write-Ahead Log (WAL) on disk for durability, then insert into an active in-memory MemTable backed by a concurrent skip list.
2. When the active MemTable reaches its memory limit (e.g. 32 MB), the engine freezes it into a read-only table and allocates a new active MemTable.
3. A background worker flushes frozen tables to Level 0 (L0) Sorted String Tables (SSTables) on disk.
4. Point lookups query the active MemTable, frozen MemTables, and disk SSTables from newest to oldest. Bloom filters and sparse block indexes prune unnecessary disk reads.
5. A background compaction manager merges overlapping SSTables across levels (L0 to L1 to L2), sorting keys, discarding superseded versions, and reclaiming space from deleted keys (tombstones).

```
[Client Request: put(k, v) / delete(k)]
          │
          ├──► Write-Ahead Log (WAL) [append-only disk file with CRC32]
          │
          └──► Active MemTable [ConcurrentSkipListMap in RAM]
                     │
                     ▼ (freeze on capacity)
               ReadOnly MemTable
                     │
                     ▼ (background flush)
               Level 0 SSTables (overlapping key ranges)
                     │
                     ▼ (leveled compaction)
               Level 1 SSTables (partitioned, non-overlapping)
                     │
                     ▼ (cascading compaction)
               Level 2 SSTables (larger capacity)
```

## Features

- Java 21 runtime with virtual threads for asynchronous flush and compaction operations.
- Crash durability with per-record CRC32 verification and automatic recovery on startup.
- Fast point queries using MurmurHash3 Bloom filters (1% false positive rate) and memory-mapped sparse block indexes.
- K-way merge iterator supporting lexicographically sorted range scans (`scan(fromKey, toKey)`).
- Concurrent Leveled Compaction worker to minimize read and space amplification.
- In-memory LRU block cache for hot 4KB SSTable data blocks.
- Real-time diagnostic terminal UI showing level distribution, write throughput, and compaction metrics.
- Benchmark module comparing performance directly against RocksDB JNI.

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

### Basic usage

```java
import com.engine.pebble.engine.PebbleEngine;
import com.engine.pebble.engine.PebbleOptions;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

public class Example {
    public static void main(String[] args) throws Exception {
        PebbleOptions options = PebbleOptions.builder()
                .dbPath(Path.of("./data"))
                .memTableThresholdBytes(32 * 1024 * 1024) // 32MB
                .l0CompactionThreshold(4)
                .build();

        try (PebbleEngine engine = PebbleEngine.open(options)) {
            byte[] key = "user:1001".getBytes(StandardCharsets.UTF_8);
            byte[] value = "{\"name\":\"Alex\",\"role\":\"engineer\"}".getBytes(StandardCharsets.UTF_8);

            // Put
            engine.put(key, value);

            // Get
            Optional<byte[]> result = engine.get(key);
            result.ifPresent(v -> System.out.println("Found: " + new String(v, StandardCharsets.UTF_8)));

            // Delete
            engine.delete(key);
        }
    }
}
```

### Running the diagnostic TUI

```bash
java -jar target/pebble-lsm-1.0.0-SNAPSHOT.jar tui --path ./data
```

### Running benchmarks

Run sequential write, point lookup, and RocksDB comparison suites:

```bash
# Sequential write benchmark (1,000,000 operations)
mvn test-compile exec:java -Dexec.mainClass="com.engine.pebble.benchmarks.SequentialWriteBenchmark"

# RocksDB comparison benchmark
mvn test-compile exec:java -Dexec.mainClass="com.engine.pebble.benchmarks.RocksDbComparisonBenchmark"
```

## Documentation

- [Architecture Guide](docs/architecture.md): Internal design, memory management, and lookup path.
- [SSTable Format Specification](docs/format-spec.md): Binary file layout, sparse index, and footer format.
- [Compaction Mechanics](docs/compaction.md): Leveled compaction algorithm, tombstone purging, and manifest updates.

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE) for details.
