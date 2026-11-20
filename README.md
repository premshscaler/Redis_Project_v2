# ⚡ Mini Multi-Type Cache Engine (Redis-Inspired)

A small Redis-like in-memory cache and leaderboard, built from scratch in **Java** (using only the JDK's built-in HTTP server) with a **Streamlit** dashboard on top.

The core is a **span-based Skip List** that powers a sorted-set leaderboard, plus string, list and hash data types, TTL expiry and LRU eviction.

## Features

- **Sorted Set (ZSET):** `ZADD`, `ZREM`, `ZRANK`, `ZRANGE` in expected O(log n)
- **Auto tiering:** players are split into Gold (>= 3000), Silver (1500-2999) and Bronze (< 1500) skip lists
- **Multi-type values:** String (`SET`), List (`LPUSH`), Hash (`HSET`)
- **TTL expiry:** lazy (checked on access) and active (background sweep every 5 seconds)
- **LRU eviction:** the least recently used key is evicted when capacity (50) is reached
- **Cache stats (INFO):** hits, misses, hit rate, evictions, key count
- **Dashboard:** leaderboard tabs, operation sidebar and a key inspector

## DSA Concepts Used

Skip list with spans (rank in O(log n)), hash map + skip list combo (like Redis ZSET), multi-key ordering (score desc, then earlier timestamp), multi-level linked list pointer rewiring, LRU eviction, lazy + active expiry, and percentile from rank.

## Project Structure

```
├── Server.java               # HTTP API (com.sun.net.httpserver)
├── SkipListLeaderboard.java  # Cache engine + SkipListTier (skip list)
├── SkipListNode.java         # Node with string, list and hash fields
└── app.py                    # Streamlit frontend
```

## API

Base path: `/leaderboard`

| Method | Query | Description |
|---|---|---|
| GET | *(none)* | Top 10 of each tier |
| GET | `?id=<key>` | Inspect a key (rank, percentile, string, list, hash) |
| GET | `?stats=true` | Cache metrics |
| POST | `?action=zadd&id=<key>&score=<n>` | Add or update a score |
| POST | `?action=expire&id=<key>&seconds=<n>` | Set TTL |
| POST | `?action=string&id=<key>&val=<text>` | Set string value |
| POST | `?action=lpush&id=<key>&val=<text>` | Push to list |
| POST | `?action=hset&id=<key>&field=<f>&val=<v>` | Set hash field |

## Run Locally

```bash
# Backend (port 8080)
javac *.java
java Server

# Frontend (in another terminal)
pip install streamlit requests
streamlit run app.py
```

## Tech Stack

Java 21 (JDK only, no frameworks) · Streamlit · Python `requests`

## Notes

All data is in memory, so it resets when the server restarts.
