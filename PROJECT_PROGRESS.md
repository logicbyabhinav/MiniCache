# MiniCache & OmniDrive: Project & Mentorship Progress Log

> **Last Updated**: October 12, 2026  
> **Supervisor**: Antigravity AI  
> **Developer**: Abhinav (@logicbyabhinav)  
> **Status**: Phase 0 Complete | Ready for Phase 1 (Java Core Engine)  

---

## 1. Executive Context & Developer Profile

- **Developer Background**: Foundation in C, C++, Java (in progress), Python, and Express.js backends.
- **Current Academic Goal**: Under college academic pressure; actively mastering Java OOP (Encapsulation, Inheritance, Polymorphism), Error Handling, Multithreading/Concurrency, and Collections in `D:\Dev\JAVA DEV`.
- **Project Goal**: Build **MiniCache**—a standalone, in-memory, Redis-inspired key-value caching service in Java to learn systems engineering from first principles, and package it for reuse across any future project over TCP.
- **Side Project In Pipeline**: **OmniDrive**—a zero-backend, client-first cloud storage aggregator pooling multiple Google Drive accounts (3 x 15GB = 45GB) into a single virtual workspace.

---

## 2. Repositories Status

| Project | Local Path | GitHub Repository | Status |
| :--- | :--- | :--- | :--- |
| **MiniCache** | `D:\Dev\Mini-Cache` | [github.com/logicbyabhinav/MiniCache](https://github.com/logicbyabhinav/MiniCache) | Phase 0 completed, architecture committed, pushed to `main`. |
| **OmniDrive** | `D:\Dev\OmniDrive` | [github.com/logicbyabhinav/OmniDrive](https://github.com/logicbyabhinav/OmniDrive) | Initialized, zero-backend architecture documented, pushed to `main`. |

---

## 3. Milestones Completed (Phase 0 Summary)

### A. Architectural Topology (The Cache-Aside Pattern)
- Clarified that a cache is **not an HTTP proxy or gateway**. It sits **behind the app server alongside the database**.
- App server checks MiniCache over TCP first (~0.2ms). On miss, fetches from disk DB and populates cache.
- Any language (Node.js, Python, Java, Go) can talk to MiniCache because TCP sockets speak raw text/bytes, not Java.

### B. The Storage Data Model (The `CacheEntry` Envelope)
- Mapped RAM to an **Array of Lockers** ($O(1)$ constant time access by index).
- Mapped the **Hash Function** to a **Library Catalogue** turning string keys (`"product:iphone15"`) into locker indices.
- Identified and addressed **Hash Collisions** (chaining buckets in the map).
- Modeled the envelope object:
  ```java
  public class CacheEntry {
      private String key;
      private Object value;
      private long expiresAt;       // Absolute deadline in ms (-1 if none)
      private long lastAccessedAt;  // Timestamp for LRU ordering
      private long sizeBytes;       // Memory footprint estimation
  }
  ```

### C. Expiry & Eviction Mechanics
- **Dual Expiry**: Lazy eviction on-read (`currentTime >= expiresAt`) + Active background sweeping thread.
- **LRU vs LFU**: Differentiated frequency counting (LFU) vs recency tracking (LRU).
- **$O(1)$ LRU via Doubly Linked List + Map**: Avoided $O(N \log N)$ sorting spikes by using DLL head-splicing and tail-popping.
- **AOF Durability**: Mutating writes appended to an on-disk transaction log for crash replay.

### D. Core Command Grammar (v1)
- `PING` $\rightarrow$ `+PONG\r\n`
- `SET <key> <value> [EX seconds]` $\rightarrow$ `+OK\r\n`
- `GET <key>` $\rightarrow$ `$<len>\r\n<val>\r\n` (or `$-1\r\n` on miss)
- `DEL <key>` $\rightarrow$ `:1\r\n` (or `:0\r\n`)
- `EXISTS <key>` $\rightarrow$ `:1\r\n` (or `:0\r\n`)
- `TTL <key>` $\rightarrow$ `:<seconds>\r\n`

---

## 4. Immediate Next Step (Where We Resume)

### Milestone: Phase 1 — The Core In-Memory Engine (Java)
When returning, we will build the core data structures in Java without networking first:

1. **Create Package Structure**:
   ```
   Mini-Cache/src/com/minicache/core/
       ├── CacheEntry.java
       └── StorageEngine.java
   ```
2. **Implement `CacheEntry.java`**:
   - Encapsulated fields (`key`, `value`, `expiresAt`, `lastAccessedAt`, `sizeBytes`).
   - Helper methods: `isExpired(long currentTimeMillis)`, `touch(long currentTimeMillis)`.
3. **Implement `StorageEngine.java`**:
   - Backed by `Map<String, CacheEntry>` (`ConcurrentHashMap`).
   - Methods: `set(key, val)`, `set(key, val, ttlSeconds)`, `get(key)`, `delete(key)`, `has(key)`, `clear()`, `size()`.
   - Lazy expiration logic inside `get()` and `has()`.
4. **Write Unit Tests** in `test/com/minicache/core/StorageEngineTest.java` verifying:
   - Happy paths, missing keys, overwrites, and expired keys.

---

## 5. Reference Files in Workspace
- [Mini_Cache-notes.txt](file:///d:/Dev/Mini-Cache/Mini_Cache-notes.txt): Full conceptual notes and college syllabus mapping.
- [README.md](file:///d:/Dev/Mini-Cache/README.md): Comprehensive system architecture specifications.
- [CloudVault_Cache_Handbook.pdf](file:///d:/Dev/Mini-Cache/CloudVault_Cache_Handbook.pdf): The original project handbook.
- [Systems_Portfolio_PRD.pdf](file:///d:/Dev/Mini-Cache/Systems_Portfolio_PRD.pdf): Systems portfolio product specifications.
