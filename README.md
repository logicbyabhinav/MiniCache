# MiniCache

A lightweight, standalone, in-memory key-value caching service built from first principles in Java. Inspired by Redis.

## Overview
MiniCache is a fast in-memory store designed to run as an independent daemon over TCP sockets. Any client written in any language (Node.js, Python, Java, Go, etc.) can connect and interact with MiniCache using a simple text protocol.

## Core Features (Roadmap)
- **In-Memory Key-Value Store**: Sub-millisecond reads and writes.
- **Per-Key TTL (Time-To-Live)**: Expiry with lazy eviction and active background sweeps.
- **Memory Bounds & Eviction**: Byte-level memory estimation with $O(1)$ LRU (Least Recently Used) cache eviction using a Doubly Linked List + HashMap.
- **Standalone TCP Server**: Multi-client socket server on port `6379`.
- **Durability (AOF)**: Append-Only Log recording write mutations for crash recovery.
- **CLI & Client SDK**: Interactive REPL client and reusable client library.

## Command Set (v1)
| Command | Syntax | Description | Success Response |
| :--- | :--- | :--- | :--- |
| `PING` | `PING` | Health check | `+PONG\r\n` |
| `SET` | `SET <key> <value> [EX seconds]` | Store key-value with optional TTL | `+OK\r\n` |
| `GET` | `GET <key>` | Retrieve value by key | `$<length>\r\n<value>\r\n` (or `$-1\r\n` if missing) |
| `DEL` | `DEL <key>` | Delete a key | `:1\r\n` (or `:0\r\n` if not found) |
| `EXISTS` | `EXISTS <key>` | Check if a key exists and is valid | `:1\r\n` (or `:0\r\n` if missing) |

## Architecture
MiniCache is built with strict separation of layers:
1. `com.minicache.core`: In-memory storage, CacheEntry envelope, eviction policies.
2. `com.minicache.command`: Command pattern handlers (`SetCommand`, `GetCommand`, etc.).
3. `com.minicache.protocol`: Wire framing and message serialization.
4. `com.minicache.server`: TCP `ServerSocket` listener and multi-threaded client connection pool.
5. `com.minicache.persistence`: Append-Only Log (AOF) durability engine.

## Getting Started
See [Mini_Cache-notes.txt](file:///d:/Dev/Mini-Cache/Mini_Cache-notes.txt) for detailed design specifications and build notes.
