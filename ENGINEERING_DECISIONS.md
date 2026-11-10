# MiniCache: Architectural Decision Records & Engineering Log
## Author: Abhinav (@logicbyabhinav) & Antigravity AI
### Last Updated: November 5, 2026

---

## Overview
This document serves as the authoritative, uncompromising record of all architectural designs, trade-off evaluations, mathematical considerations, and engineering decisions made during the creation of **MiniCache**. Anyone inspecting this repository can trace the exact first-principles reasoning behind every data structure, protocol choice, and structural boundary.

---

## ADR-001: Project Layering — The 3-Pillar Architecture vs. Deep Micro-Package Modularization

### Context
When structuring a standalone caching service, a common architectural pattern is enterprise-style multi-package modularization: separating the core into distinct `core`, `eviction`, `expiration`, `protocol`, `parser`, `dispatcher`, `server`, `network`, `config`, and `persistence` packages.

### Options Considered
1. **Option A: Highly Granular Modularization (7+ packages)**:
   - *Pros:* Strict theoretical isolation; each file has a single narrow responsibility.
   - *Cons:* High abstraction bloat for a focused middleware service. Excessive file-hopping. Complex dependency injection wiring. Crucially, integration bug hunting becomes significantly harder because tracking a single request requires stepping through 6 to 8 intermediate boundary layers.
2. **Option B: Monolithic Single-File / Two-File Structure**:
   - *Pros:* Zero wiring overhead.
   - *Cons:* Violates Separation of Concerns; networking code pollutes data structures; impossible to unit test in-memory algorithms without binding TCP sockets.
3. **Option C: The Pragmatic 3-Pillar Architecture (Selected)**:
   - `com.minicache.engine`: In-memory data store, cache envelope, eviction policies, and TTL expiration. Completely isolated from sockets or protocols.
   - `com.minicache.command`: Parsing raw text/RESP streams, validating arguments, routing to the engine, and formatting standardized response strings.
   - `com.minicache.server`: Sockets, thread pools, client lifecycle, and port binding.
   - Top-level `MiniCache.java`: The bootstrap orchestrator.

### Decision & Trade-Off Analysis
We selected **Option C (The 3-Pillar Architecture)**.

```
Request Lifecycle Flow:
[ Client Socket ]
       |  (Raw Bytes)
       v
[ 1. MiniCacheServer ] -----> Manages TCP port 6379, connections, thread pool
       |  (Raw String Request)
       v
[ 2. CommandHandler ]  -----> Parses command grammar, validates arguments, formats response
       |  (Typed Method Calls: get, put, delete)
       v
[ 3. CacheEngine ]     -----> In-memory state, O(1) LRU eviction, TTL evaluation
```

**Why this wins for integration bug hunting:**
The fault isolation boundary is immediate:
- Socket drops or connection timeouts $\to$ **Server Layer**.
- Argument count mismatches or syntax errors $\to$ **Command Layer**.
- Data loss, stale reads, or memory leaks $\to$ **Engine Layer**.

---

## ADR-002: Storage Envelope — Schema-Agnostic Raw Strings vs. Structured Positional Arrays (`String[]`)

### Context
When storing values under a cache key (e.g., storing a user's `username`, `sessionToken`, and `OTP`), should the cache envelope natively store an array of arguments (`String[] values`) or a single universal payload (`String value`)?

### The Dilemma Explored
The initial hypothesis for a structured array (`String[]` / `Value` class) was to allow clients to fetch only the required positional field (e.g., retrieving only the OTP at index 2 without downloading the entire user record).

### Deep Comparative Analysis

| Dimension | Structured Array (`String[]`) | Universal Payload (`String`) + Namespaced Keys |
| :--- | :--- | :--- |
| **Cache Responsibility** | Stateful / Schema-aware | Completely schema-agnostic ("Dumb Cache") |
| **Client Coupling** | **Tight coupling:** Client must know exact index positions (`index 0 = user`, `index 1 = token`, `index 2 = otp`). | **Loose coupling:** Client controls serialization format (JSON, Base64, raw string). |
| **Schema Evolution** | **Fragile:** Inserting a new field at index 1 breaks all legacy clients expecting token at index 1. | **Robust:** JSON keys or namespaced keys are independent of order. |
| **Expiration (TTL) Granularity** | **Coarse:** All items in the array share the exact same `expiresAt` deadline. | **Fine-grained:** OTP can expire in 5 minutes (`user:101:otp`), while session token expires in 24 hours (`user:101:token`). |
| **Wire Protocol Complexity**| Requires complex index-slicing command syntax (`GETFIELD key index`). | Clean, standard, ultra-fast Redis command grammar (`GET key`, `SET key value`). |

### Decision
We adopted the **Universal Payload (`String value`) with Namespaced Keys pattern**.
- A high-performance cache is fundamentally an $O(1)$ memory lookup engine, not a relational table.
- Storing raw string payloads keeps the engine decoupled from the application layer.
- If a client wants sub-field access, the production-standard approach is using **namespaced keys** (`category:id:attribute`), which provides $O(1)$ isolated access and independent TTL expiration per field.

---

## ADR-003: Timestamp Precision & Memory Representation — Avoiding the 2038 Integer Overflow Trap

### Context
In [CacheEntry.java](file:///d:/Dev/Mini-Cache/src/com/minicache/engine/CacheEntry.java), we track:
1. `expiresAt`: The absolute moment an item becomes invalid.
2. `lastAccessedAt`: The moment an item was last touched (for LRU eviction).

### Mathematical Analysis: `int` vs. `long`

A standard 32-bit signed Java `int` has a range of:
$$-2^{31} \text{ to } 2^{31}-1 = -2,147,483,648 \text{ to } +2,147,483,647$$

`System.currentTimeMillis()` returns milliseconds elapsed since the Unix epoch (January 1, 1970 UTC).
- As of late 2026, `System.currentTimeMillis()` evaluates to approximately:
  $$1,793,800,000,000 \text{ ms}$$
- Notice that:
  $$1.79 \times 10^{12} \gg 2.14 \times 10^9$$
- If epoch milliseconds are stored in a 32-bit `int`, the value overflows immediately into negative numbers, causing every cache entry to be treated as instantly expired upon insertion.

### Decision
- All epoch timestamps (`expiresAt`, `lastAccessedAt`) **must be strictly typed as 64-bit `long`**.
- A 64-bit signed `long` accommodates up to $+9.22 \times 10^{18}$, which prevents timestamp overflow for approximately 292 million years.

---

## ADR-004: Expiration Mechanics — Absolute Deadline vs. Relative Countdown

### Context
When a user sets a key with a time-to-live (`SET session "abc" EX 60`), should the cache store the relative countdown (`60 seconds`) or compute the absolute deadline upfront?

### Algorithmic Comparison

#### Option 1: Storing Relative Duration
```java
// Requires tracking creation time + relative TTL:
long createdAt;
long ttlSeconds;

boolean isExpired() {
    long elapsed = (System.currentTimeMillis() - createdAt) / 1000L;
    return elapsed >= ttlSeconds;
}
```
*Downside:* Performs clock subtraction, division, and comparison on every single read operation.

#### Option 2: Precomputed Absolute Deadline (Selected)
```java
// Computed once at insertion:
long expiresAt = System.currentTimeMillis() + (ttlSeconds * 1000L);

boolean isExpired() {
    return System.currentTimeMillis() >= expiresAt;
}
```
*Benefit:* Evaluating expiration on read reduces to a single, lightning-fast CPU comparison (`currentTime >= expiresAt`).

### Sentinel Value for Non-Expiring Keys
- What if an entry has no TTL (it should persist until explicitly deleted or evicted by LRU)?
- **Sentinel Decision:** `expiresAt = -1L`.
- When `expiresAt == -1L`, `isExpired()` immediately returns `false` without querying the system clock.

---

## ADR-005: Object-Oriented Modeling — Composition vs. Inheritance

### Context
Early drafts proposed `class CacheObject extends Value`.

### Analysis
- **Inheritance ("IS-A"):** A subclass should be a specialized version of its superclass (e.g., `Dog extends Animal`). A cache entry is not a specialized string value; it is a metadata envelope.
- **Composition ("HAS-A"):** A cache entry contains a value, an expiration time, and access statistics.
- Coupling `CacheEntry` to `Value` via inheritance violates the Single Responsibility Principle and creates rigid hierarchies.

### Decision
We enforce **Composition over Inheritance**:
`CacheEntry` is a standalone public class encapsulating private state variables with public accessor methods (`getKey()`, `getValue()`, `setValue()`, `isExpired()`, `touch()`).

---

## ADR-006: Eviction Strategy — LRU (Least Recently Used) Access Tracking

### Context
When RAM reaches capacity, MiniCache must evict keys to make room for new writes.

### Decision
- Every `CacheObject` tracks `private long lastAccessedAt`.
- When an entry is created or retrieved, `touch()` is called:
  ```java
  public void touch() {
      this.lastAccessedAt = System.currentTimeMillis();
  }
  ```
- This timestamp powers the $O(1)$ LRU eviction queue inside `CacheEngine`.

---

## ADR-007: Elimination of Double-Binding Indirection (Pointer Chasing vs. Single-Hop Envelope) & Naming as `CacheObject`

### Context
A proposal was evaluated to separate the storage envelope into two distinct tiers:
1. `CacheEntry`: Acting as a front-facing gateway / entry point.
2. `CacheObject`: Dedicated solely to wrapping the payload in memory.

### Analysis: The Cost of "Double Binding" (Pointer Chasing)
In low-latency systems programming, indirection is not free. We analyzed what happens inside the JVM and hardware CPU:

1. **JVM Memory Layout & Object Headers:**
   - Every object on a 64-bit JVM carries a **16-byte Object Header** (Mark Word + Klass Pointer) plus alignment padding.
   - For 1,000,000 cached keys:
     - *Single-hop model:* Allocates 1,000,000 envelope objects on heap.
     - *Two-tier double-binding model:* Allocates 2,000,000 objects on heap.
   - This doubles memory allocation overhead and dramatically increases Garbage Collection (GC) pressure, leading to longer GC stop-the-world pauses.

2. **CPU Cache Locality & Hardware Latency:**
   - RAM access is slow (~100 nanoseconds), while CPU L1/L2 cache is fast (~1 to 4 nanoseconds).
   - In a single-hop envelope, fields (`key`, `value`, `expiresAt`, `lastAccessedAt`) reside in a contiguous 64-byte CPU Cache Line.
   - In a two-tier model, accessing the value forces the CPU to "wander" (chase a secondary pointer across arbitrary heap addresses), resulting in CPU **L1/L2 Cache Misses** and pipeline stalls.

3. **Software Architecture (The Middleman Anti-Pattern):**
   - `CacheEngine` already serves as the gateway and storage coordinator.
   - A `CacheEntry` that merely forwards calls to a `CacheObject` without adding polymorphic behavior is an anemic middleman.

### Decision
- **Eliminate the Middleman:** Do not create a separate gateway wrapper around the payload object.
- **Single-Hop Envelope:** The envelope directly encapsulates the key, value reference, expiration deadline, and access timestamp.
- **Class Naming:** The envelope is officially named **`CacheObject`** (stored in `CacheObject.java`), clearly identifying it as the atomic data unit stored in RAM.

---

## ADR-008: Fail-Fast Exception Architecture — Checked vs. Unchecked Exceptions, The "Catch-and-Swallow" Anti-Pattern, and Error Bubbling in Network Middleware

### Context
During the implementation of input validation in `CacheObject.java`, a custom exception `InvalidCacheObjectException` was introduced to reject null or empty keys. Two architectural questions emerged:
1. Should input validation exceptions be **Checked (`extends Exception`)** or **Unchecked (`extends RuntimeException`)**?
2. How should validation errors be propagated from the innermost storage envelope out to the network socket without crashing the server or corrupting memory state?

---

### Part 1: The "Catch-and-Swallow" Constructor Anti-Pattern

An initial attempt structured the constructor validation as:
```java
// ANTI-PATTERN: Catching and swallowing inside the constructor
public CacheObject(String key, String value) throws InvalidCacheObjectException {
    try {
        if (key == "") {
            throw InvalidCacheObjectException("Key is required");
        }
    } catch (Exception e) {
        return; // DANGEROUS!
    }
}
```

#### Why This is Catastrophic in Systems Programming:
1. **The Error is Silently Extinguished:**
   The `catch (Exception e)` block catches its own freshly thrown exception on line 21. The caller never receives the exception, remaining completely oblivious that the object failed to construct.
2. **Zombie Objects & Memory Corruption:**
   Executing `return;` inside a constructor exits prematurely. Fields marked `final` (like `key` and `expiresAt`) remain uninitialized or hold default `null` / `0` values.
   The caller receives a reference to a corrupted, half-initialized "zombie object" in RAM. When this zombie object is subsequently inserted into the storage map, downstream operations crash with `NullPointerException` or corrupted hashing behavior.
3. **The Fail-Fast Invariant:**
   A constructor must be an **atomic state barrier**. If the inputs are invalid, the object **must refuse to exist**. The exception must be thrown directly out of the constructor so execution halts immediately:
   ```java
   if (key == null || key.trim().isEmpty()) {
       throw new InvalidCacheObjectException("Key cannot be null or empty");
   }
   ```

---

### Part 2: Demystifying "Unchecked" vs. "Checked" Exceptions

A common misconception is that an *Unchecked Exception* is "ignored by the JVM". 

#### What "Unchecked" Actually Means:
- **Checked Exception (`extends Exception`):**
  The **Java Compiler** checks for it at build time. Every caller in the call hierarchy is forced by the compiler to either explicitly catch it (`try-catch`) or declare it (`throws`).
- **Unchecked Exception (`extends RuntimeException`):**
  The **Java Compiler** does not force mandatory boilerplate at compile time.

#### How the JVM Handles Unchecked Exceptions at Runtime:
The JVM **never ignores an unchecked exception**.
1. When `throw new InvalidCacheObjectException(...)` is executed, the JVM immediately stops the normal instruction pipeline.
2. The JVM unwinds the call stack frame by frame.
3. If no method in the stack catches it, the JVM terminates the thread and prints a full stack trace to `stderr`.

---

### Part 3: The Middleware Error-Bubbling Flow (How the Mischievous Caller Gets Caught)

In MiniCache, we do **not** want a bad client command to crash the entire server thread. Nor do we want to silently ignore bad input.

Here is the exact propagation lifecycle across our 3-Pillar Architecture:

```
[ TCP Client: Sends 'SET "" "value"' ]
                  |
                  v
[ Pillar 1: MiniCacheServer ] (Socket Worker Thread)
                  |
                  v
[ Pillar 2: CommandHandler ]
  try {
      engine.put(key, value);
  } catch (InvalidCacheObjectException e) {
      // THE CLIENT GETS CAUGHT RED-HANDED:
      socketWriter.write("-ERR " + e.getMessage() + "\r\n");
  }
                  |
                  v
[ Pillar 3: CacheEngine ]
      | calls new CacheObject(key, value)
      v
[ CacheObject Constructor ]
      if (key == null || key.trim().isEmpty()) {
          throw new InvalidCacheObjectException("Key cannot be empty");
      }
```

1. **At the Bottom (CacheObject):**
   Fails fast and throws `InvalidCacheObjectException`. No zombie object is created.
2. **In the Middle (CacheEngine):**
   The map is never touched; RAM remains 100% clean.
3. **At the Edge (CommandHandler):**
   Catches the `InvalidCacheObjectException`, converts the exception message into a standardized Redis-compatible error protocol string (`-ERR Key cannot be empty\r\n`), and transmits it directly back to the TCP client.
4. **The Result:**
   - The mischievous client receives an immediate error response explaining their violation.
   - The server process remains healthy, robust, and running.

---

### Part 4: String Content Validation — `==` vs. `.isEmpty()`

In Java, String validation must guard against two distinct failure modes:
1. `key == null`: The pointer references no memory address at all.
2. `key.trim().isEmpty()`: The pointer references a valid string, but the string contains only empty space or tabs (`""` or `"   "`).

Using `key == ""` checks reference identity against the string intern pool, which fails if `key` was dynamically allocated on the heap (e.g., from network socket buffers). 

**The Production Invariant:**
```java
if (key == null || key.trim().isEmpty()) {
    throw new InvalidCacheObjectException("Key cannot be null or empty");
}
```

---

## ADR-009: In-Memory Storage Mechanics — Hash Table Physics, Collision Resolution, and Multi-Threaded Concurrency Synchronization

### Context
In `CacheEngine.java`, we must select the central data structure to store millions of `CacheObject` envelopes in memory. The storage structure must guarantee:
1. True $O(1)$ average-case retrieval and insertion time by string keys.
2. Mathematically sound collision handling when arbitrary strings map to the same internal array bucket.
3. Thread-safe execution under simultaneous multi-client load across multi-core CPUs without memory corruption or global thread contention.

---

### Part 1: The Physics of Hashing & Array Mapping

In hardware RAM, an array is the fastest physical structure because index access executes in a single CPU instruction via direct memory offset calculation:
$$\text{Memory Address} = \text{Base Address} + (\text{Index} \times \text{Word Size})$$

Because cache keys are strings (`"session:9a8b"`, `"user:101:profile"`) rather than sequential integers, the storage engine must transform variable-length text into a fixed array slot in $O(1)$ time.

```
+-----------------------------------------------------------------------------------+
|                              THE HASHING PIPELINE                                 |
+-----------------------------------------------------------------------------------+
| [ Key: "user:101" ]                                                               |
|        |                                                                          |
|        v                                                                          |
| [ 1. Hash Function (hashCode()) ]                                                 |
|      Polynomial rolling computation -> 32-bit signed integer (e.g. 1,842,918,342) |
|        |                                                                          |
|        v                                                                          |
| [ 2. Bitwise Index Masking ]                                                      |
|      Index = hash & (N - 1)  (where capacity N = 16)                              |
|      1,842,918,342 & 00001111_2 = 6                                               |
|        |                                                                          |
|        v                                                                          |
| [ 3. Direct RAM Array Bucket ]                                                    |
|      Jumps directly to Bucket 6 in 1 CPU instruction                              |
+-----------------------------------------------------------------------------------+
```

#### Why Capacity Must Be a Power of 2 ($N = 2^k$):
Standard modulo arithmetic (`hash % N`) requires an expensive integer division instruction on hardware CPUs (~15 to 40 CPU clock cycles).
When capacity $N$ is strictly a power of 2 ($16, 32, 64, \dots$):
$$N - 1 = 2^k - 1 = \underbrace{000\dots0111\dots1}_{k \text{ ones}}$$
Performing a bitwise AND (`hash & (N - 1)`) masks out all high bits and preserves only the lowest $k$ bits, mathematically guaranteeing that the result falls in $[0, N - 1]$. A bitwise AND executes in a **single CPU cycle ($0.3\text{ to }0.5\text{ ns}$)**.

---

### Part 2: Collision Resolution Mechanics (Separate Chaining)

The **Pigeonhole Principle** dictates that mapping an infinite domain of strings into a finite 32-bit integer space (and subsequently into $N$ buckets) will inevitably produce collisions:
$$\text{Hash}(\text{"Alice"}) \longrightarrow \text{Bucket } 4$$
$$\text{Hash}(\text{"Bob"}) \longrightarrow \text{Bucket } 4$$

#### Physical Memory Representation:
Java resolves this collision through **Separate Chaining**. A bucket does not store a lone element; it stores the head pointer of a singly linked list composed of `Node<K, V>` objects:

```
Bucket Array:
[ 0 ] -> null
[ 1 ] -> null
[ 2 ] -> null
[ 3 ] -> null
[ 4 ] -> [ Node 1 ] -----------------------------> [ Node 2 ] -> null
         - hash: 1842918342                         - hash: 994821044
         - key:  "Alice"                            - key:  "Bob"
         - val:  CacheObject@0x100                  - val:  CacheObject@0x200
         - next ------------------------------------+
[ 5 ] -> null
```

#### Lookup Invariant:
When executing `get("Bob")`:
1. **Direct Leap ($O(1)$):** Hash is evaluated $\to$ CPU immediately addresses Bucket 4.
2. **Chain Traversal:**
   - Node 1 inspected: `key.equals("Alice")` is `false`. CPU follows `node1.next`.
   - Node 2 inspected: `key.equals("Bob")` is `true`. CPU returns `node2.val`.

#### Java 8 Treeification Guard:
If an adversarial client floods the cache with keys intentionally designed to produce identical bucket indices (a Hash DoS attack), a linked list degrades from $O(1)$ to $O(N)$.
- **Threshold Rule:** When any bucket's chain length reaches **`TREEIFY_THRESHOLD = 8`** and total table capacity $\ge 64$, Java automatically converts the linked list into a balanced **Red-Black Binary Search Tree**.
- **Result:** Worst-case lookup time is strictly bound to **$O(\log N)$**.

---

### Part 3: Concurrency Hazards in Plain `HashMap`

A plain `java.util.HashMap` is strictly designed for single-threaded execution. In a network server like MiniCache where dozens of client sockets run on parallel worker threads, `HashMap` fails with severe corruption:

#### Hazard 1: Lost Updates (The Race Condition)
Suppose Bucket 4 is currently empty (`null`). Thread 1 and Thread 2 execute simultaneously:

```
Timeline:
Thread 1 (Worker A)                        Thread 2 (Worker B)
------------------------------------       ------------------------------------
1. Inspects Bucket 4: sees null            
                                           1. Inspects Bucket 4: sees null
2. Instantiates Node("Alice")              
                                           2. Instantiates Node("Bob")
3. Writes Bucket 4 = Node("Alice")         
                                           3. Writes Bucket 4 = Node("Bob")! (OVERWRITE)
```
**Outcome:** Node `"Alice"` is completely unlinked from the bucket. The memory allocation is leaked, and Thread 1's data vanishes silently without throwing an exception.

#### Hazard 2: Cyclic Pointer Corruption During Resizing (100% CPU Freeze)
When a table exceeds its `threshold = capacity * loadFactor (0.75)`, it invokes `resize()`, doubling the bucket array and transferring existing linked lists.
If Thread 1 and Thread 2 trigger `resize()` concurrently, their pointer updates interleave:
```
Node A.next = Node B
Node B.next = Node A  <-- CIRCULAR CYCLE CREATED
```
The next `get()` request traversing this bucket enters an **infinite while-loop**, locking a CPU core at 100% utilization and causing the server to freeze permanently.

---

### Part 4: Concurrency Solutions Compared

```
Model 1: Hashtable / Collections.synchronizedMap(map)
[ Thread 1 (Writing Key A) ] --------> [ ACQUIRES GLOBAL LOCK (this) ]
                                                   |
[ Thread 2 (Reading Key B) ] --------> BLOCKED! (Must wait for Thread 1)
[ Thread 3 (Writing Key C) ] --------> BLOCKED! (Must wait for Thread 1)
```
*Verdict:* **Coarse-Grained Bottleneck.** A single global monitor lock forces all multi-core processors into a single-file sequential queue. Unacceptable for high-throughput caching.

```
Model 2: java.util.concurrent.ConcurrentHashMap (Lock Striping & Fine-Grained Locks)
[ Thread 1 (Writing to Bucket 4) ] ---> [ Synchronizes ONLY Bucket 4 Node Head ]
[ Thread 2 (Reading from Bucket 7) ] -> Reads via volatile memory (LOCK-FREE!)
[ Thread 3 (Writing to Bucket 0) ] ---> [ Synchronizes ONLY Bucket 0 Node Head ]
```
*Verdict:* **Selected for MiniCache.**
1. **Fine-Grained Bucket Locks:** Thread 1 and Thread 3 write concurrently without interference. Contention only occurs if two threads target the exact same bucket simultaneously.
2. **Lock-Free Reads ($O(1)$):** The `val` and `next` pointers of `Node` are declared `volatile`. Reads traverse the memory barrier without acquiring locks, guaranteeing maximum multi-core CPU throughput.

---

### Part 5: Comprehensive Technical Comparison Matrix

| Evaluation Criteria | `java.util.HashMap` | `java.util.Hashtable` | `Collections.synchronizedMap` | `ConcurrentHashMap` (Selected) |
| :--- | :--- | :--- | :--- | :--- |
| **Thread Safety** | Non-Thread-Safe | Thread-Safe | Thread-Safe | **Thread-Safe** |
| **Locking Strategy** | None (Corrupts state) | Coarse-Grained (`synchronized this`) | Coarse-Grained (`synchronized mutex`) | **Fine-Grained Lock Striping** |
| **Read Concurrency** | Race condition risk | Blocks on writes | Blocks on writes | **100% Lock-Free (`volatile`)** |
| **Write Concurrency** | Race condition risk | Single-threaded serialization | Single-threaded serialization | **Parallel writes across distinct buckets** |
| **Null Keys / Values**| Allowed (`null -> 0`) | Throws `NullPointerException` | Allowed | **Disallowed** (prevents multi-thread ambiguity) |
| **Rehashing Hazard** | Infinite loop / memory leak | Safe but locks whole table | Safe but locks whole table | **Thread-safe incremental multi-threaded transfer** |
| **Collision Worst-Case**| $O(N)$ or $O(\log N)$ (single-threaded) | $O(N)$ | $O(N)$ | **$O(\log N)$ (Red-Black Tree)** |
| **Architectural Role** | Local single-thread utility | Obsolete Java 1.0 legacy | Basic synchronized wrapper | **Production Systems Middleware Core** |

---

## ADR-010: Separation of the Storage Cabinet (`CacheMap`) from the Policy Controller (`CacheEngine`)

### Context
When implementing `CacheEngine.java`, a critical design decision was raised:
Should `CacheEngine` directly implement the raw hash table internals (arrays, hashing math, collision buckets), or should the storage container be decoupled into a dedicated `CacheMap` class?

### The Pluggable Cabinet Architecture
We decided to decouple the engine into two specialized components:
1. **`CacheMap.java` (The Storage Cabinet):**
   - Strictly responsible for physical RAM storage.
   - Holds the raw array of buckets (`Node[] table`).
   - Implements polynomial rolling hash functions and bitwise index masking (`hash & (capacity - 1)`).
   - Manages collision chaining via `Node.next` pointers.
   - Manages dynamic resizing and rehashing when load factor $\ge 0.75$.
2. **`CacheEngine.java` (The Store Manager & Policy Controller):**
   - Manages `CacheMap` as its internal cabinet.
   - Governs higher-order caching policies:
     - **Dual Expiration Mechanics:** Lazy expiration on read (`get()`) + Active background sweeping thread.
     - **LRU Eviction Coordination:** $O(1)$ Doubly Linked List with Sentinel nodes.
     - **Memory Accounting:** Tracking entry counts and memory budgets.

```
+-----------------------------------------------------------------------------------+
|                        CacheEngine (Policy Controller)                            |
|  - Coordinates TTL Expiration (Lazy + Active Cleaner)                            |
|  - Coordinates O(1) LRU Eviction Queue                                            |
|                               |                                                   |
|                               v  (Delegates raw storage)                          |
|                 CacheMap (The Storage Cabinet)                                    |
|                 - Raw Array: Node[] table                                         |
|                 - Polynomial Rolling Hash Function                                |
|                 - Collision Resolution via Chaining                               |
|                 - Dynamic Array Resizing & Rehashing                              |
+-----------------------------------------------------------------------------------+
```

### Architectural Benefits:
1. **Separation of Concerns:** Low-level memory indexing (`CacheMap`) is completely separated from cache lifecycle policies (`CacheEngine`).
2. **Pluggable Evolution:** We build `CacheMap` from raw arrays first to master fundamental DSA mechanics. Later, upgrading the backing cabinet to `ConcurrentHashMap` or off-heap memory requires zero rewrites of `CacheEngine`'s eviction or expiration logic.
3. **Transparent Debugging:** If an item cannot be found or collides, we inspect `CacheMap`. If an item is evicted prematurely or fails TTL deadlines, we inspect `CacheEngine`.

---

## ADR-011: Intrusive Linked List Architecture vs. Generic Node Wrappers (Elimination of Secondary Allocation & Zero-Hop Collision Chaining)

### Context
When implementing collision resolution in `CacheMap.java`, the traditional Java textbook pattern uses an internal wrapper class `Node`:
```java
// Traditional Textbook Model:
class Node {
    String key;
    CacheObject value;
    Node next;
}
```
A design critique was raised: wrapping `CacheObject` inside a secondary `Node` forces the CPU to chase two pointers across the heap (`store[i] -> Node -> CacheObject`) and wastes 16 bytes of JVM object header overhead per entry.

### Analysis: Intrusive Data Structures in Systems Engineering
In high-performance systems (such as the Linux kernel's `list_head` and Redis's internal `robj`), data structures are often **intrusive**:
- In an **intrusive linked list**, the data object itself embeds the link pointers (`next`), rather than being wrapped inside an external container.
- Java's `HashMap<K, V>` cannot do this because it is generic and cannot modify arbitrary user classes `V`.
- In MiniCache, because we own both `CacheObject` and `CacheMap`, we can embed `next` directly inside `CacheObject`.

```
Textbook Wrapper Model:
Bucket[i] ---> [ Node Object (16-byte header) ]
                     |
                     +---> [ CacheObject on Heap ] ---> String Value

Intrusive Model (Selected):
Bucket[i] ---> [ CacheObject on Heap (embeds .next) ] ---> String Value
```

### Quantified Advantages:
1. **Zero Intermediate Allocations:** Eliminates $N$ wrapper objects on the heap. For 1,000,000 keys, this saves 16 MB of JVM object header overhead and reduces GC scan cycles.
2. **Zero-Hop Cache Locality:** Finding an entry in a collision chain jumps directly from `store[i]` to `CacheObject`. The CPU loads all fields (`key`, `value`, `expiresAt`, `next`) in a single CPU cache line.
3. **Architectural Purity:** `CacheMap` becomes a direct array: `private CacheObject[] store = new CacheObject[capacity];`.

### Decision: Evaluation & Subsequent Reversion in ADR-012
While the intrusive model offered theoretical zero-allocation advantages, it fundamentally blurred domain boundaries by polluting the data model with infrastructure pointers. See ADR-012 for the final architectural consensus.

---

## ADR-012: Domain Purity & Single Responsibility — Reversion to `Node` Wrappers ("Books Cannot Make a Shelf")

### Context
Following ADR-011, `CacheObject` was evaluated with an intrusive `public CacheObject next;` field. A critical domain design conflict was identified:
- A `CacheObject` is fundamentally a **Domain Data Envelope** representing cached business payload, expiration timestamps, and access metadata.
- Forcing `CacheObject` to carry `.next` pointers compels the data entity to act as a linked-list collection node.
- As captured by developer Abhinav: *"Books cannot make a shelf."* The data stored on a shelf must not be polluted with the mechanical fixtures of the bookcase.

### Analysis: Domain Model vs. Collection Infrastructure

```
Violated Responsibility (Intrusive):
[ CacheObject ] = Data Entity + Linked List Node (Dual Responsibility)

Restored Responsibility (Node Wrapper):
[ CacheObject ] = PURE DATA (Key, Value, TTL, Access Time)
[ Node ]        = PURE INFRASTRUCTURE (Hook holding CacheObject + 'next' pointer)
[ CacheMap ]    = PURE CONTAINER (Array of Node bucket chains)
```

1. **Clean Separation of Concerns:**
   - `CacheObject` knows nothing about hashmaps, buckets, collision chains, or linked lists. It remains completely reusable across any storage back-end.
   - `Node` is a private static implementation detail of `CacheMap`.
2. **Mental Clarity & Maintenance:**
   - Isolating collection pointers inside `Node` prevents cyclic reference bugs from leaking into cache serialization, logging, or LRU queues.
   - Eliminates confusion regarding object hierarchies.

### Decision
We strictly enforce the **Single Responsibility Principle**.
- `CacheObject` remains 100% pure (no `next` field).
- `CacheMap` encapsulates `static class Store` to manage separate chaining collisions.
- The shelf array is typed as `private Store[] store = new Store[capacity];`.

---

## ADR-013: Collision Resolution Mechanics — The Chasing Pointer Deletion Pattern & Pop-Style Atomic Removal

### Context
When implementing `remove(String key)` in `CacheMap.java`, separate chaining presents a structural challenge: removing a node from a singly linked list requires reconnecting the predecessor node (`previous`) directly to the successor node (`current.next`). In a singly linked list, pointers only face forward (`next`), making backward navigation impossible without tracking history.

### The Mechanics: Two-Pointer Traversal ("Chasing Pointer Pattern")
To delete an arbitrary node in $O(1)$ amortized time within a bucket chain:
1. Two pointer references (`current` and `previous`) traverse the chain in tandem:
   - `current` leads the inspection.
   - `previous` trails exactly one step behind.
2. **Head Node Removal ($current == previous$):**
   - If the head node matches the target key, no predecessor exists.
   - The bucket slot itself is updated: `store[index] = current.next`.
   - The old head is unlinked and reclaimed by the JVM Garbage Collector.
3. **Middle or Tail Node Removal ($current \ne previous$):**
   - The predecessor's forward link is rewired to bypass the target: `previous.next = current.next`.
   - The target node is isolated from the chain in a single atomic pointer write.

```
Middle Node Deletion:
                 previous                     current
                    |                            |
                    v                            v
store[index] -> [ Node A ] ----------------> [ Node B ] -> [ Node C ] -> null
                    |                                          ^
                    +------------------------------------------+
                           (previous.next = current.next)
```

### Pop-Style Return Semantics
Rather than returning a bare boolean (`true`/`false`), `remove(String key)` implements **Pop-Style Semantics**:
- Returns the unlinked `CacheObject` on success, or `null` if the key was absent.
- **Architectural Rationale:** `CacheEngine` (the policy controller) needs access to the removed object to prune it from the LRU Doubly Linked List, decrement memory tracking metrics, and log diagnostic statistics without issuing a redundant `get()` before `remove()`.

---

## ADR-014: O(1) LRU Eviction via Doubly Linked List with Sentinels & Coordinated Lazy Expiration

### Context
When memory reaches `maxCapacity`, MiniCache must evict the Least Recently Used (LRU) entry. Searching through an array or hash map for the oldest timestamp is an $O(N)$ operation that would cause unacceptable latency spikes. To guarantee strict $O(1)$ performance for all cache operations, MiniCache coordinates an in-memory Doubly Linked List alongside `CacheMap`.

### The Architecture: Doubly Linked List with Sentinel Boundaries
`CacheEngine` maintains two dummy sentinel nodes: `head` (Most Recently Used / MRU) and `tail` (Least Recently Used / LRU).
- **Sentinel Invariant:** The list is never empty; at minimum, `head.next = tail` and `tail.prev = head`. This completely eliminates null-pointer checks during boundary insertions and deletions.
- **O(1) Insertion (`addToHead`):** New or accessed items are spliced right behind `head` in exactly 4 pointer assignments.
- **O(1) Eviction (`eviction`):** When capacity is full and a new key arrives, the engine grabs `tail.prev`, deletes the key from `CacheMap`, and unhooks the node in 2 pointer assignments.
- **O(1) Access Promotion (`moveToHead`):** When a key is accessed via `get()`, its corresponding `Node` is unhooked from its current position and spliced behind `head`.

```
[ HEAD Sentinel ] <=====> [ Node 1 (MRU) ] <=====> [ Node 2 ] <=====> [ Node 3 (LRU) ] <=====> [ TAIL Sentinel ]
```

### Direct Reference Coupling (Zero-Lookup Pointer Hop)
To achieve $O(1)$ promotion during `get()` without maintaining a secondary reverse-lookup hash map:
- `CacheObject` encapsulates a direct reference `protected Object lruNode`.
- When `storage.get(key)` returns the `CacheObject`, `CacheEngine` directly casts `(Node) data.getLruNode()` and calls `moveToHead()`.
- Lookup time to locate the LRU carriage is **0 nanoseconds**.

### Coordinated Lazy Expiration
When `get(key)` detects that `data.isExpired()` evaluates to `true`:
1. `storage.remove(key)` removes the dead item from the storage cabinet.
2. `removeNode((Node) data.getLruNode())` unlinks the expired carriage from the LRU track.
3. The method returns `null` (Cache Miss) to the client.
This ensures that dead items never linger as zombie nodes in the LRU queue.

---

## ADR-015: Redis Serialization Protocol (RESP) Parser & Length-Prefixed Framing Architecture

### Context
In designing client-server caching systems, transport formats range from plain newline-delimited text to JSON, Protobuf, and specialized wire protocols. MiniCache adopts the **Redis Serialization Protocol (RESP 2)** to provide native drop-in compatibility with the vast ecosystem of existing Redis tools (`redis-cli`, application client libraries, load balancers, and monitoring daemons).

### The Challenge of Plain Text vs. Binary Safety
In a naive plain-text protocol, commands and responses are terminated strictly by `\r\n`. This creates a severe limitation known as the **"Data Delimiter Collision"**:
- If a stored value contains arbitrary user payloads (e.g., multiline JSON `{"k": "v\r\n"}` or serialized binary bytes), a receiver reading until `\r\n` prematurely truncates the transmission, corrupting subsequent protocol frames.

### Architectural Solution: Length-Prefixed Framing (Bulk Strings)
RESP solves this via length-prefixed framing. The server explicitly declares the byte length before emitting payload bytes:
```text
$<byte_length>\r\n<payload>\r\n
```
1. **Binary Safety**: The client parses the length integer first, then reads exactly that many bytes directly from the socket stream without parsing content.
2. **Deterministic Memory Allocation ($O(1)$)**: When the client reads `$1048576\r\n`, it allocates a single 1MB memory buffer upfront rather than repeatedly resizing dynamic arrays.
3. **Disambiguation of State**:
   - Cache Miss / Expired Key: Evaluates to Null Bulk String (`$-1\r\n`).
   - Empty String: Evaluates to Zero-length Bulk String (`$0\r\n\r\n`).
   - String containing `"nil"`: Evaluates to 3-byte Bulk String (`$3\r\nnil\r\n`).

### Dispatcher Routing & Command Arity Enforcement
`CommandHandler` validates and dispatches commands through modern Java `switch` pattern expressions:
- **`PING [msg]`**: Returns simple string `+PONG\r\n` or bulk string `$<len>\r\n<msg>\r\n`.
- **`SET key val [EX seconds]`**: Enforces strict arity (3 or 5 tokens), validates integer seconds ($ttl > 0$), and returns `+OK\r\n`.
- **`GET key`**: Single argument verification; queries `CacheEngine.get(key)`. Returns `$-1\r\n` on cache miss or lazy expiry; returns `$<len>\r\n<val>\r\n` on cache hit.
- **`DEL key`**: Deletes entry; returns integer `:1\r\n` or `:0\r\n`.
- **`EXISTS key`**: Probes existence without modifying LRU queue position; returns `:1\r\n` or `:0\r\n`.
- **Unknown Command**: Fallback returns standardized `-ERR unknown command '<verb>'\r\n`.

---

## ADR-016: Multi-Threaded TCP ServerSocket & Graceful Lifecycle Architecture

### Context
In-memory caches serve high-throughput workloads with hundreds of client connections. A single-threaded listening loop that executes client requests synchronously blocks all other clients whenever one connection is idle or latency-bound.

### Architectural Separation: Acceptor vs. Worker Pool
`MiniCacheServer` separates connection-accepting from socket I/O handling:
1. **Acceptor Loop (Main Server Thread)**:
   - Executes an uninterrupted `serverSocket.accept()` loop on port `6379`.
   - As soon as the OS completes a TCP 3-way handshake, `accept()` returns a dedicated client `Socket`.
   - The connection is immediately handed off to an `ExecutorService` thread pool via `threadPool.submit(() -> handleClient(socket))`.
2. **Client Worker Routine (`handleClient`)**:
   - Each client connection is owned by an independent worker thread.
   - Encapsulates `socket.getInputStream()` in `InputStreamReader` and `BufferedReader` for character-buffered line processing.
   - Encapsulates `socket.getOutputStream()` in auto-flushing `PrintWriter` to guarantee immediate wire transmission.
   - Loops on `in.readLine()` until EOF (`null`), signaling normal client disconnect.

### Thread Safety Guarantee
Concurrency is safely handled through the bottom-up design established in ADR-001 and ADR-014:
- All mutating state operations in `CacheEngine` (`set`, `get`, `delete`, `exists`) are protected by object monitor locks via `synchronized`.
- Parallel worker threads concurrently read from sockets and serialize RESP frames, but synchronize at the engine boundary, guaranteeing zero race conditions, pointer corruption, or dirty writes.

### Graceful Daemon Shutdown Hook
To prevent OS socket resource leaks and port rebind failures (`Address already in use`):
- `MiniCache` registers a JVM shutdown hook (`Runtime.getRuntime().addShutdownHook(...)`).
- On `SIGINT` (`Ctrl+C`) or `SIGTERM`, `server.stop()` flips `volatile boolean running = false`, closes `serverSocket` to unblock `.accept()`, and invokes `threadPool.shutdown()` to drain worker threads cleanly before the process exits with code 0.




