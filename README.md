# MiniCache: High-Performance In-Memory Key-Value Store

> A lightweight, standalone, in-memory caching daemon built from first principles in Java (JDK 21+). Designed as an educational systems-engineering project inspired by Redis.

---

## 1. Executive Summary & Problem Statement

Modern web applications read repetitive data thousands of times per second: user sessions, authentication tokens, catalog prices, and directory listings. Fetching this data from disk-based relational databases (PostgreSQL, MySQL, SQLite) creates I/O bottlenecks and introduces unacceptable latency:

| Layer | Medium | Typical Latency | Speed Relative to RAM |
| :--- | :--- | :--- | :--- |
| **RAM (In-Memory)** | Volatile Memory | **~100 nanoseconds** | **1x (Baseline)** |
| **SSD Read** | NVMe / Flash Disk | ~100 microseconds | ~1,000x slower |
| **Network Database** | TCP Socket + Disk DB | ~1 to 20 milliseconds | ~10,000x to 200,000x slower |

**MiniCache** bridges this gap. It runs as an independent network daemon in memory, serving key-value queries in sub-milliseconds over TCP while providing time-based expiration (TTL), bounded-memory LRU eviction, and optional write-ahead durability (AOF).

---

## 2. High-Level Architectural Topology

### Placement: The Cache-Aside Pattern
MiniCache is **not an HTTP proxy or API gateway**. It does not intercept browser requests, terminate TLS, or manage web routes. Instead, it lives **behind the application server, alongside the primary database**:

```
[ User Browser / Client ]
            │
            │  HTTP / REST / GraphQL
            ▼
┌────────────────────────────────────────────────────────┐
│        Application Server (e.g., Node.js / Python)     │
│   • Authenticates users & validates session cookies   │
│   • Executes business logic & HTTP route handlers      │
└───────────────┬────────────────────────┬───────────────┘
                │                        │
       (1) Fast Cache Query              │ (3) DB Query on Cache Miss
           (Sub-millisecond)             │     (~15ms disk fetch)
                ▼                        ▼
    ┌───────────────────────┐   ┌────────────────────────┐
    │       MiniCache       │   │    Primary Database    │
    │   (Java TCP Daemon)   │   │  (PostgreSQL / SQLite) │
    │   Port: 6379 / 5500   │   │  Durable Disk Storage  │
    └───────────────────────┘   └────────────────────────┘
```

#### Request Flow Lifecycle:
1. **Application Query**: The application server receives a user request and queries MiniCache over TCP (`GET session:token_abc`).
2. **Cache HIT**: MiniCache returns the value directly from RAM (~0.2ms). The application responds immediately; the disk database is untouched.
3. **Cache MISS**: If MiniCache returns `NIL` (missing or expired), the application queries the primary disk database, then writes the result to MiniCache (`SET session:token_abc <data> EX 1800`) so subsequent requests hit memory.

---

## 3. Subsystem Architecture & Decoupled Layers

MiniCache is designed with strict separation of concerns across five core layers:

```
┌─────────────────────────────────────────────────────────────────────────┐
│                     Network Clients (TCP Sockets)                       │
│             Node.js Backend | Python Scripts | CLI Client | Telnet      │
└────────────────────────────────────┬────────────────────────────────────┘
                                     │ Raw Byte Stream (\r\n framed)
┌────────────────────────────────────▼────────────────────────────────────┐
│ 1. Network & Protocol Layer (com.minicache.server / .protocol)          │
│    • ServerSocket listener with multi-threaded connection worker pool   │
│    • Byte-stream framing: splits incoming TCP stream into command lines │
│    • Wire Protocol Parser & Serializer (RESP-compatible text format)    │
└────────────────────────────────────┬────────────────────────────────────┘
                                     │ Parsed Command Tokens
┌────────────────────────────────────▼────────────────────────────────────┐
│ 2. Command Dispatcher (com.minicache.command)                           │
│    • Polymorphic Command Pattern: PING, SET, GET, DEL, EXISTS, etc.     │
│    • Argument validation and error dispatch                             │
└───────────────────┬─────────────────────────────────┬───────────────────┘
                    │                                 │
                    │ Mutating commands               │ Read / Write
                    ▼                                 ▼
┌───────────────────────────────────────┐ ┌───────────────────────────────┐
│ 5. Durability Layer                   │ │ 3. Core Storage Engine        │
│    (com.minicache.persistence)        │ │    (com.minicache.core)       │
│    • Append-Only Log (AOF)            │ │    • O(1) Concurrent HashMap  │
│    • Crash-resilient file sync        │ │    • CacheEntry Envelopes     │
│    • Startup replay engine            │ │    • Atomic Counters & Types  │
└───────────────────────────────────────┘ └───────────────┬───────────────┘
                                                          │
                                         ┌────────────────┴───────────────┐
                                         │ 4. Eviction & Expiry Engines   │
                                         │    • Dual Expiry (Lazy+Active) │
                                         │    • O(1) LRU (DLL + Map)      │
                                         │    • Memory Budget Tracker     │
                                         └────────────────────────────────┘
```

---

## 4. Deep-Dive: Core Subsystems

### 4.1. The Data Model: The `CacheEntry` Envelope
Memory in MiniCache is structured as `CacheEntry` instances stored in an in-memory dictionary. Each entry encapsulates the data alongside its system metadata:

```
+-------------------------------------------------------------------------+
|                               CacheEntry                                |
|-------------------------------------------------------------------------|
| - key:             String    (Unique lookup identifier)                 |
| - value:           Object    (String, List, Map, or numeric counter)    |
| - expiresAt:       long      (Absolute millisecond deadline; -1 = none) |
| - lastAccessedAt:  long      (Timestamp of last access for LRU tracking)|
| - sizeBytes:       long      (Estimated memory consumption in bytes)    |
| - prev, next:      Node      (Pointers for LRU Doubly Linked List)      |
+-------------------------------------------------------------------------+
```

### 4.2. Expiry Mechanism (Dual-Engine TTL)
Relying on a single expiry technique creates systemic issues:
- **Lazy Expiry alone**: Expired entries that are never requested again leak RAM forever.
- **Active Expiry alone**: Sweeping millions of keys in a single background loop blocks the CPU and stalls user requests.

**MiniCache uses cooperating Dual Expiry**:
1. **Lazy Expiry (On-Read)**: When any read command (`GET`, `EXISTS`) accesses a key, it checks `currentTime >= entry.expiresAt`. If expired, it deletes the entry immediately, records a metric, and returns `NIL`.
2. **Active Expiry (Background Daemon)**: A scheduled background thread runs periodically (e.g., every 100ms) inspecting entries via a random sample or min-heap scheduler, evicting dead keys without freezing client threads.
3. **Monotonic Clock**: All internal timers use monotonic intervals (`System.nanoTime()` / `System.currentTimeMillis()`) to protect against system clock drift, manual time changes, and leap seconds.

### 4.3. Bounded Memory & $O(1)$ LRU Eviction
When RAM hits the configured ceiling (`max_memory`, e.g., 64 MB), MiniCache evicts data using **Least Recently Used (LRU)** eviction.

#### Why Sorting is Forbidden:
Sorting $N$ keys by access timestamp takes $O(N \log N)$ time. Under high write load, this causes massive latency spikes.

#### The $O(1)$ Solution: Hash Map + Doubly Linked List (DLL)
```
          [Head: Most Recently Used]
                     │
              ┌──────▼──────┐
              │ Node: keyA  │
              └──────▲──────┘
                     │ (prev / next pointers)
              ┌──────▼──────┐
              │ Node: keyB  │
              └──────▲──────┘
                     │
              ┌──────▼──────┐
              │ Node: keyC  │
              └─────────────┘
          [Tail: Least Recently Used (First candidate to evict)]
```
- **Accessing a key**: Look up the node in the Hash Map in $O(1)$, detach it from its current position in the list, and splice it to the `Head` in $O(1)$.
- **Evicting on full memory**: Pop the node at the `Tail` in $O(1)$, delete its entry from the Hash Map, subtract its `sizeBytes` from `used_memory`, and release it.

### 4.4. Durability: Append-Only Log (AOF)
To survive unexpected process crashes or restarts:
1. Every write mutation (`SET`, `DEL`, `INCR`) is formatted as a command string and appended to an on-disk transaction log (`appendonly.aof`).
2. Configurable synchronization policies:
   - `ALWAYS`: `fsync()` executed after every write (maximum safety, lower throughput).
   - `EVERYSEC`: Flushed to disk once per second via background thread (standard sweet spot).
3. **Recovery**: On boot, MiniCache reads `appendonly.aof` line-by-line, executing commands directly into RAM to rebuild exact state.

---

## 5. Wire Protocol & Command Grammar

MiniCache implements a text-based, line-delimited wire protocol framed by `\r\n` (CRLF), compatible with Redis RESP conventions:

### Protocol Framing Rules
- `+<text>\r\n`: Simple string / Success status (e.g., `+OK\r\n`)
- `-ERR <reason>\r\n`: Error reply
- `:<number>\r\n`: 64-bit signed integer (e.g., `:1\r\n`)
- `$<length>\r\n<data>\r\n`: Bulk String (e.g., `$5\r\nhello\r\n`)
- `$-1\r\n`: Null / Cache Miss (NIL)

### Command Reference (v1)

| Command | Arguments | Description | Example Request | Example Response |
| :--- | :--- | :--- | :--- | :--- |
| **`PING`** | `None` | Health/liveness heartbeat | `PING\r\n` | `+PONG\r\n` |
| **`SET`** | `<key> <val> [EX sec]` | Store key with optional TTL | `SET user:1 alice EX 60\r\n` | `+OK\r\n` |
| **`GET`** | `<key>` | Retrieve value by key | `GET user:1\r\n` | `$5\r\nalice\r\n` |
| **`DEL`** | `<key>` | Delete specified key | `DEL user:1\r\n` | `:1\r\n` |
| **`EXISTS`**| `<key>` | Check if key exists and is alive | `EXISTS user:1\r\n` | `:1\r\n` (or `:0\r\n`) |
| **`TTL`** | `<key>` | Get remaining lifetime in seconds | `TTL user:1\r\n` | `:58\r\n` (-1 if no TTL, -2 if missing) |
| **`INFO`** | `None` | Output server statistics & memory | `INFO\r\n` | Bulk string with stats |

---

## 6. Cross-Language Client Interoperability

Because MiniCache communicates over standard TCP sockets, any application can connect with native socket libraries:

### Node.js Example (CloudVault Integration)
```javascript
const net = require('net');

const client = net.createConnection({ port: 6379 }, () => {
    client.write('SET session:token_99 "user_active" EX 300\r\n');
});

client.on('data', (buffer) => {
    console.log('MiniCache Reply:', buffer.toString());
});
```

### Python Example
```python
import socket

with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
    s.connect(('localhost', 6379))
    s.sendall(b'GET session:token_99\r\n')
    print(s.recv(1024).decode())
```

---

## 7. Project Structure & Build Plan

```
Mini-Cache/
├── src/
│   └── com/minicache/
│       ├── core/
│       │   ├── CacheEntry.java            # Envelope model & metadata
│       │   ├── StorageEngine.java          # In-memory dictionary & API
│       │   ├── EvictionPolicy.java        # Strategy interface
│       │   └── LruEvictionPolicy.java     # Doubly linked list LRU
│       ├── command/
│       │   ├── Command.java               # Command interface
│       │   ├── CommandParser.java         # Tokenizer & dispatcher
│       │   ├── SetCommand.java
│       │   ├── GetCommand.java
│       │   └── ...
│       ├── protocol/
│       │   ├── ProtocolParser.java        # Byte framing & parsing
│       │   └── ResponseSerializer.java    # Output formatters (+OK, $, :, -ERR)
│       ├── server/
│       │   ├── MiniCacheServer.java       # ServerSocket & lifecycle
│       │   └── ClientHandler.java         # Worker thread per connection
│       └── persistence/
│           ├── AofWriter.java             # Append-only transaction logger
│           └── AofReader.java             # Startup log replayer
└── test/
    └── com/minicache/
        ├── core/StorageEngineTest.java
        ├── core/LruEvictionTest.java
        └── server/ConcurrentLoadTest.java
```

### Roadmap Phases:
- [x] **Phase 0**: Architecture, specifications, command grammar, and notes ([Mini_Cache-notes.txt](file:///d:/Dev/Mini-Cache/Mini_Cache-notes.txt)).
- [ ] **Phase 1**: Core In-Memory Engine (`CacheEntry`, `StorageEngine`, basic unit tests).
- [ ] **Phase 2**: Dual Expiry (Lazy removal, background active sweeper, monotonic clock).
- [ ] **Phase 3**: Memory Tracker & $O(1)$ LRU Doubly Linked List eviction.
- [ ] **Phase 4**: Multi-client TCP Server & Protocol Parser.
- [ ] **Phase 5**: AOF Durability & Startup Replay.
- [ ] **Phase 6**: CLI REPL Client & Node.js/Java Client SDK.
- [ ] **Phase 7**: Concurrency stress-testing (100+ clients) and latency benchmarks.
