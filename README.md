<p align="center">
  <img src="docs/images/logo.png?raw=true" alt="PebbleLSM Logo" width="140" style="border-radius: 28px;" />
</p>

<h1 align="center">PebbleLSM</h1>

<p align="center">
  <b>High-throughput embedded Log-Structured Merge-tree (LSM) key-value storage engine in Java 21 LTS.</b>
</p>

<p align="center">
  <a href="https://github.com/alexandrmotologa/pebble-lsm"><img src="https://img.shields.io/badge/Java-21%20LTS-ED8B00?style=flat-square&logo=openjdk&logoColor=white" alt="Java 21" /></a>
  <a href="https://github.com/alexandrmotologa/pebble-lsm"><img src="https://img.shields.io/badge/Architecture-LSM--Tree-0284c7?style=flat-square" alt="LSM-Tree" /></a>
  <a href="https://github.com/alexandrmotologa/pebble-lsm"><img src="https://img.shields.io/badge/Network-Redis%20RESP-DC382D?style=flat-square&logo=redis&logoColor=white" alt="Redis RESP" /></a>
  <a href="https://github.com/alexandrmotologa/pebble-lsm"><img src="https://img.shields.io/badge/Tests-22%20Passed-22c55e?style=flat-square" alt="Tests" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-slate?style=flat-square" alt="License" /></a>
</p>

---

## Overview

Traditional B-Trees modify disk blocks in place, causing random disk I/O and lock contention under sustained write workloads. PebbleLSM converts all modifications into sequential append-only writes across an active memory skip list, immutable on-disk sorted string tables, sparse block indexes, and background leveled compaction.

PebbleLSM runs on Java 21 Virtual Threads, providing predictable sub-millisecond latencies, atomic batching, snapshot isolation (MVCC), transparent LZ4 block compression, per-key TTL expiration, and an embedded Redis-compatible RESP network interface.

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

---

## Core Features

- **Sequential write path**: Append-only Write-Ahead Log (WAL) with per-record and per-batch CRC32 validation.
- **Probabilistic filtering**: Dual-hash MurmurHash3 Bloom filters targeting under 1% false positive probability to bypass disk I/O on non-existent keys.
- **Sparse block indexing**: 4KB data blocks indexed by first key in memory for logarithmic point searches.
- **Concurrent LRU cache**: In-memory cache for frequently accessed SSTable data blocks.
- **Multi-way merge scanner**: Lexicographical range scans (`scan(fromKey, toKey)`) using a min-heap merge iterator over memory and disk tiers.
- **Leveled compaction**: Background workers merge overlapping L0 files into non-overlapping L1 and L2 levels, purging superseded records and tombstones.
- **Atomic write batches (`WriteBatch`)**: Group multiple updates and deletes into a single atomic disk frame and single sync.
- **Snapshot isolation (MVCC)**: Point-in-time point lookups and range scans across consistent sequence numbers without locking writers.
- **Transparent LZ4 compression**: Optional block-level compression reducing on-disk storage footprint.
- **Time-To-Live (TTL)**: Automatic per-key expiration with instant read masking and space reclamation during compaction.
- **Write-stall backpressure**: Dynamically throttles and blocks writes when L0 file counts accumulate, preventing read degradation.
- **Interactive REPL shell**: CLI shell with command history, tab completion, and formatted tabular scan displays.
- **Embedded Redis RESP server**: Network TCP server running on Java 21 Virtual Threads, compatible with standard `redis-cli` and language drivers.

---

## Performance & Benchmarks

Head-to-head benchmark evaluation under 100,000 sequential writes and 50,000 random point reads against RocksDB JNI (v8.10.0) on PCIe 4.0 NVMe storage:

<p align="center">
  <img src="docs/images/benchmark-preview.png?raw=true" alt="PebbleLSM vs RocksDB Benchmark" width="100%" />
</p>

| Metric | PebbleLSM (Java 21) | RocksDB (v8.10.0 JNI) | Difference |
| :--- | :--- | :--- | :--- |
| **Random Read Throughput** | **164,967 ops/sec** | 141,399 ops/sec | **+16.7% faster** |
| **Read Duration (50K keys)** | **303 ms** | 353 ms | In-memory block index + MurmurHash3 filter |
| **Sequential Write Throughput**| **77,516 ops/sec** | 104,765 ops/sec | Sequential append + sync |
| **Bloom Filter Miss Latency** | **6.93 µs/op** | ~7.50 µs/op | 100% of non-existent keys bypass disk I/O |

---

## Visual Interfaces & Tooling

### Diagnostic Storage Visualizer (TUI)

Real-time terminal HUD displaying memory consumption, level distribution, cache hit ratios, and background compaction status:

<p align="center">
  <img src="docs/images/tui-preview.png?raw=true" alt="PebbleLSM Diagnostic TUI HUD" width="100%" />
</p>

Run the live visualizer:

```bash
java -jar target/pebble-lsm-1.0.0-SNAPSHOT.jar tui --path ./data
```

### Interactive REPL Shell

Directly query, write, scan, and inspect the database engine through an interactive terminal session with tab auto-completion:

<p align="center">
  <img src="docs/images/shell-preview.png?raw=true" alt="PebbleLSM Interactive REPL Shell" width="100%" />
</p>

Launch the shell:

```bash
java -jar target/pebble-lsm-1.0.0-SNAPSHOT.jar shell --path ./data
```

Supported shell commands:
- `put <key> <value> [ttlSeconds]`
- `get <key>`
- `delete <key>`
- `scan [fromKey] [toKey] [limit]`
- `stats`
- `compact`
- `flush`
- `help` / `exit`

### Embedded Redis-Compatible RESP Server

PebbleLSM runs as a standalone network key-value store compatible with Redis:

<p align="center">
  <img src="docs/images/resp-server-preview.png?raw=true" alt="PebbleLSM Redis RESP Server" width="100%" />
</p>

Start the server on port 6379:

```bash
java -jar target/pebble-lsm-1.0.0-SNAPSHOT.jar server --port 6379 --path ./data
```

Connect using standard tools such as `redis-cli`:

```bash
$ redis-cli -p 6379
127.0.0.1:6379> PING
PONG
127.0.0.1:6379> SET user:1001 "Alice"
OK
127.0.0.1:6379> GET user:1001
"Alice"
127.0.0.1:6379> MGET user:1001 user:1002
1) "Alice"
2) (nil)
127.0.0.1:6379> DBSIZE
(integer) 1
```

---

## Quick Start

### Prerequisites

- Java Development Kit (JDK) 21 or later
- Apache Maven 3.9 or later

### Building from source

```bash
git clone https://github.com/alexandrmotologa/pebble-lsm.git
cd pebble-lsm
mvn clean package
```

The build compiles the engine and produces a shaded standalone JAR file at `target/pebble-lsm-1.0.0-SNAPSHOT.jar`.

### Embedded Java API

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

            // Snapshot isolation (MVCC)
            try (Snapshot snapshot = engine.getSnapshot()) {
                engine.put(key, "new_value".getBytes(StandardCharsets.UTF_8));

                // Snapshot point lookup observes the point-in-time state
                Optional<byte[]> snapValue = engine.get(snapshot, key);
                System.out.println("Snapshot value: " + new String(snapValue.orElseThrow(), StandardCharsets.UTF_8));
            }

            // Delete
            engine.delete(key);
        }
    }
}
```

---

## Running Benchmarks

Execute the comparative benchmark harness against RocksDB JNI:

```bash
# Sequential write benchmark (1,000,000 operations)
mvn test-compile exec:java -Dexec.mainClass="com.engine.pebble.benchmarks.SequentialWriteBenchmark"

# Random read benchmark (50,000 lookups against 100,000 keys)
mvn test-compile exec:java -Dexec.mainClass="com.engine.pebble.benchmarks.RandomReadBenchmark"

# Head-to-head comparison benchmark against RocksDB
mvn test-compile exec:java -Dexec.mainClass="com.engine.pebble.benchmarks.RocksDbComparisonBenchmark"
```

---

## Documentation

- [Architecture Guide](docs/architecture.md): Write pipeline, read path, MVCC snapshot isolation, and write-stall backpressure.
- [SSTable Format Specification](docs/format-spec.md): Binary layout, WAL batch framing, LZ4 block compression, and magic footers.
- [Compaction Mechanics](docs/compaction.md): Leveled compaction algorithm, tombstone purging, and TTL expiration.

---

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE) for details.
