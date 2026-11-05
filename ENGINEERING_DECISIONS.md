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


