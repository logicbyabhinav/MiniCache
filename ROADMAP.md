# MiniCache: Technical Roadmap & Future Versions

> A structured engineering blueprint outlining upcoming milestones, architectural evolutions, and feature specifications for MiniCache.

---

## Current Release: v1.0.0 (Shipped)

The baseline production-grade in-memory caching daemon:
- [x] **Pillar 3 (Engine)**: Custom separate-chaining hash table with polynomial rolling hash ($p=31$).
- [x] **Pillar 3 (Eviction)**: Strict $O(1)$ LRU queue via Doubly Linked List with dummy Sentinel boundaries.
- [x] **Pillar 3 (Expiration)**: Absolute 64-bit epoch timestamp deadlines with lazy on-access eviction.
- [x] **Pillar 2 (Protocol)**: Wire-compliant Redis Serialization Protocol (RESP 2) command parser (`PING`, `SET`, `GET`, `DEL`, `EXISTS`).
- [x] **Pillar 1 (Server)**: Multi-threaded TCP `ServerSocket` listener on port 6379 with thread pool dispatch.
- [x] **Lifecycle**: Graceful shutdown hook (`Runtime.getRuntime().addShutdownHook`) releasing OS socket bindings.
- [x] **Testing**: End-to-end unit, integration, and live concurrent TCP socket test harnesses.

---

## Milestone v1.1.0: Protocol Completeness & Multi-Line Array Parser

**Target**: Full binary safety, multi-key operations, and active TTL reclamation.

### 1. Multi-Line RESP Array Wire Parser (`*<count>\r\n`)
* **Problem**: Inline single-line parsing cannot reliably handle payload strings containing spaces, newlines, or binary data unless wrapped in quotes.
* **Specification**: Implement full RESP 2 Array deserialization:
  ```text
  *3\r\n$3\r\nSET\r\n$7\r\nmessage\r\n$11\r\nhello world\r\n
  ```
* **Impact**: Native, drop-in compatibility with official production client drivers across all languages (`Jedis`, `Lettuce`, `redis-py`, `ioredis`, `go-redis`).

### 2. Active Background TTL Sweeping Daemon
* **Problem**: Lazy expiration only prunes dead keys when they are read via `GET`. Keys that are written once with a TTL and never queried again linger in RAM indefinitely.
* **Specification**: A dedicated background daemon thread running an active probabilistic sweep:
  - Every 100 milliseconds, samples 20 random keys with active TTLs.
  - Evicts all expired keys found in the sample.
  - If $>25\%$ of the sample is expired, immediately repeats the sweep to aggressively reclaim RAM.

### 3. Extended String & Counter Commands
* **`INCR <key>` / `DECR <key>`**: Atomic 64-bit integer counter increment/decrement.
* **`MGET <key1> <key2> ...`**: Multi-key atomic batch retrieval in a single round-trip.
* **`MSET <key1> <val1> <key2> <val2> ...`**: Multi-key atomic batch storage.
* **`APPEND <key> <value>`**: In-place string concatenation.
* **`STRLEN <key>`**: $O(1)$ string length query.

---

## Milestone v1.2.0: Durability & Persistence (AOF Engine)

**Target**: Zero data loss on server crash or restart.

### 1. Append-Only File (AOF) Engine
* **Specification**: Write every mutating command (`SET`, `DEL`, `INCR`) to an append-only disk log (`minicache.aof`) before returning `+OK\r\n` to the client.
* **Format**: Standard RESP wire format commands appended sequentially for human readability and instant replayability.

### 2. Startup Replay Recovery
* During application bootstrap in `MiniCache.java`:
  1. Detect existing `minicache.aof` file.
  2. Parse the command stream sequentially through `CommandHandler.handle()`.
  3. Rebuild the in-memory hash table and LRU linked list state before binding the network port.

### 3. Configurable `fsync` Durability Policies
* **`fsync always`**: Disk flush after every write system call. Maximum safety, I/O bound.
* **`fsync everysec`** (Default): Background disk flush thread every 1,000ms. High performance with at most 1 second of potential data loss.
* **`fsync no`**: OS kernel controls buffer flushing. Maximum raw throughput.

### 4. Background AOF Compaction (`BGREWRITEAOF`)
* Prevents the disk log from growing infinitely by collapsing historical mutations into point-in-time snapshots (e.g., 100 sequential `INCR count` commands collapsed into a single `SET count 100`).

---

## Milestone v1.3.0: Complex Data Structures

**Target**: Expanding beyond key-value strings into rich collection primitives.

### 1. Doubly-Linked Lists (`LPUSH`, `RPUSH`, `LPOP`, `RPOP`, `LLEN`, `LRANGE`)
* Implemented using our custom Doubly Linked List primitives.
* Enables high-speed FIFO message queues and worker job buffers.

### 2. Hash Maps (`HSET`, `HGET`, `HDEL`, `HGETALL`, `HLEN`)
* Embedded sub-dictionaries using nested `CacheMap` instances.
* Allows storing and querying structured object fields without JSON serialization overhead.

### 3. Unique Sets (`SADD`, `SREM`, `SMEMBERS`, `SISMEMBER`, `SCARD`)
* Hash-set structures for unordered, deduplicated membership testing.

---

## Milestone v2.0.0: Non-Blocking High-Throughput I/O (Java NIO Reactor)

**Target**: Maximum throughput matching Redis C benchmarks (>80,000 ops/sec).

### 1. Single-Threaded Reactor Event Loop
* Transition from thread-per-client `ServerSocket` to non-blocking Java NIO (`ServerSocketChannel`, `SocketChannel`, and `Selector`).
* Eliminates OS thread context-switching overhead.
* Handles 50,000+ concurrent idle client connections on a single CPU core.

### 2. Socket Pipelining
* Allows clients to transmit multiple commands down the TCP pipe without waiting for intermediate replies.
* Server reads buffered chunks and streams back batched RESP responses.

### 3. Automated Benchmark Harness
* Benchmark integration running against official `redis-benchmark`:
  ```bash
  redis-benchmark -p 6379 -t set,get -n 100000 -q
  ```
* Generates latency distribution graphs ($p50$, $p99$, $p99.9$) and requests-per-second telemetry.
