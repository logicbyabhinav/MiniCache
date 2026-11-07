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

**MiniCache** bridges this gap. It runs as an independent network daemon in memory, serving key-value queries in sub-milliseconds over TCP while providing time-based expiration (TTL), bounded-memory LRU eviction, and write-ahead durability (AOF).

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

## 3. The 3-Pillar Streamlined Architecture

MiniCache is strictly organized into **three cohesive, decoupled pillars** to eliminate over-modularization and enable transparent bug isolation:

```
                              +---------------------------------+
                              |          MiniCache.java         |
                              |       (App Entry Point)         |
                              +---------------------------------+
                                               |
                                               v
+-----------------------------------------------------------------------------------------------+
| PILLAR 1: SERVER (Middleware Network Layer) : com.minicache.server                            |
| - MiniCacheServer.java                                                                        |
|   • Binds to the TCP port (e.g. 6379).                                                        |
|   • Accepts incoming connections across multi-core worker threads.                            |
|   • Maintains socket streams and forwards raw command lines.                                  |
+-----------------------------------------------------------------------------------------------+
                                               |
                                               v
+-----------------------------------------------------------------------------------------------+
| PILLAR 2: COMMAND (Protocol & Dispatcher Layer) : com.minicache.command                       |
| - CommandHandler.java                                                                         |
|   • Receives raw string tokens from the socket.                                               |
|   • Validates arguments and syntax (PING, SET, GET, DEL, EXISTS, TTL).                        |
|   • Dispatches requests to the Engine.                                                        |
|   • Formats responses into Redis-compatible RESP protocol (+OK, $5, -ERR, :1).                |
+-----------------------------------------------------------------------------------------------+
                                               |
                                               v
+-----------------------------------------------------------------------------------------------+
| PILLAR 3: ENGINE (Storage, Eviction & Controller Layer) : com.minicache.engine                |
| 1. CacheObject.java                                                                           |
|    • The atomic in-memory data envelope (Key, Value, expiresAt, lastAccessedAt).              |
|    • Absolute deadline time math and fail-fast validation.                                    |
| 2. CacheMap.java (The Storage Cabinet)                                                        |
|    • The custom in-memory hash table built from raw arrays and linked list collision nodes.   |
|    • Polynomial rolling hash function and O(1) bitwise bucket mapping.                        |
| 3. CacheEngine.java (The Policy Controller & Store Manager)                                   |
|    • Governs the storage cabinet (CacheMap).                                                  |
|    • Coordinates Dual Expiration (Lazy check on read + Active background sweeper).            |
|    • Coordinates O(1) LRU (Least Recently Used) Eviction Policy.                              |
+-----------------------------------------------------------------------------------------------+
```

---

## 4. Deep-Dive: The Engine Subsystems

### 4.1. The Data Envelope (`CacheObject.java`)
Every item stored in MiniCache is wrapped in an atomic `CacheObject` envelope. It bundles raw values with systems metadata:
- `key`: The unique string identifier.
- `value`: The raw string payload (schema-agnostic, zero-overhead blob).
- `expiresAt`: Absolute epoch millisecond deadline (typed as `long` to eliminate the 2038 overflow bug; `-1L` sentinel for permanent keys).
- `lastAccessedAt`: Updated on every read/write (`touch()`) for LRU eviction.

### 4.2. The Pluggable Storage Cabinet (`CacheMap.java`)
`CacheMap` is the custom in-memory hash table implemented from first principles:
- **Raw Array:** `Node[] table` storing bucket heads.
- **Separate Chaining:** Handles collisions via singly linked list nodes (`Node.next`).
- **Hash Compression:** Uses polynomial rolling hashing with prime factor 31 and bitwise power-of-2 index compression (`hash & (capacity - 1)`).
- **Pluggable Architecture:** Isolating the raw cabinet container allows MiniCache to be benchmarked with a custom array map first, and later seamlessly upgraded to `ConcurrentHashMap` or off-heap memory without altering controller logic.

### 4.3. The Policy Controller (`CacheEngine.java`)
`CacheEngine` acts as the mastermind managing `CacheMap`:
- **Dual Expiration Mechanics:**
  1. *Lazy Expiration:* On `get(key)`, the entry's expiration deadline is inspected. Expired entries are immediately pruned from `CacheMap` and return `null`.
  2. *Active Expiration:* A background thread periodically samples keys and purges expired entries to prevent dead memory leaks.
- **O(1) LRU Eviction:**
  Uses a Doubly Linked List with Sentinel Head and Tail nodes combined with hash indexing to achieve strictly bound $O(1)$ node splicing and eviction under memory bounds.

---

## 5. Architectural Decision Records (ADR) Index

Detailed first-principles reasoning and mathematical derivations are permanently maintained in [ENGINEERING_DECISIONS.md](file:///d:/Dev/Mini-Cache/ENGINEERING_DECISIONS.md):
- **ADR-001:** The 3-Pillar Architecture vs. Micro-Package Sprawl
- **ADR-002:** Universal Schema-Agnostic Raw Strings vs. Positional Arrays (`String[]`)
- **ADR-003:** Timestamp Precision & The 2038 Integer Overflow Trap (`long` vs `int`)
- **ADR-004:** Absolute Deadline Expiration vs Relative Countdown (`-1L` Sentinel)
- **ADR-005:** Composition over Inheritance in Cache Envelopes
- **ADR-006:** LRU Access Tracking via `lastAccessedAt` and `touch()`
- **ADR-007:** Elimination of Double-Binding Indirection & Class Naming as `CacheObject`
- **ADR-008:** Fail-Fast Exception Architecture, Checked vs Unchecked, and Error Bubbling
- **ADR-009:** Hash Table Physics, Collision Resolution, and Multi-Threading Concurrency
- **ADR-010:** Separation of the Storage Cabinet (`CacheMap`) from the Policy Controller (`CacheEngine`)

---

## 6. Project Directory Layout

```text
D:\Dev\Mini-Cache\
├── src\
│   └── com\
│       └── minicache\
│           ├── MiniCache.java              <-- Application Bootstrap
│           │
│           ├── server\
│           │   └── MiniCacheServer.java    <-- Multi-threaded TCP Socket Listener
│           │
│           ├── command\
│           │   └── CommandHandler.java     <-- Protocol Parser, Dispatcher & RESP Serializer
│           │
│           └── engine\
│               ├── CacheObject.java        <-- Atomic Data Envelope
│               ├── CacheMap.java           <-- Custom In-Memory Hash Table (Array + Chaining)
│               └── CacheEngine.java        <-- Policy Controller (TTL + LRU Manager)
│
├── ENGINEERING_DECISIONS.md                <-- Authoritative Architecture Decision Records
└── README.md                               <-- System Blueprint
```
