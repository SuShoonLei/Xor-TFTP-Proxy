# TFTP Proxy Server

A Java implementation of an HTTP proxy server backed by a custom TFTP file transfer protocol over UDP. Built for a Computer Networks course at SUNY Oswego.

The system has three components: a **DataServer** that stores and streams files, a **ProxyServer** that bridges HTTP clients to the DataServer, and a **ClientTFTP** test client. The TFTP layer uses a TCP-style sliding window, per-session XOR encryption, and RTO-based retransmission — all from scratch on top of UDP.

---

## Architecture

```
Browser / ClientTFTP
        │
        │  HTTP/TCP :8080
        ▼
   ProxyServer          ← LRU cache (10 entries)
        │
        │  Custom TFTP+XOR / UDP :8069
        ▼
   DataServer           ← serves files from photos/
```

| Component | Protocol | Port | Role |
|-----------|----------|------|------|
| `DataServer` | UDP | 8069 | File origin — reads from `photos/`, encrypts and streams |
| `ProxyServer` | TCP (HTTP) + UDP (TFTP) | 8080 / 8069 | Proxy — caches, translates HTTP↔TFTP |
| `ClientTFTP` | TCP (HTTP) | 8080 | Test client — fetches files, saves to `/tmp/client_out/` |

---

## Features

- **Custom TFTP over UDP** — RFC 1350 packet structure with extensions
- **TCP-style sliding window** — configurable window size (1, 8, 64, …)
- **TCP-style RTO** — Jacobson/Karels SRTT + RTTvar algorithm (RFC 6298)
- **Per-session XOR encryption** — XorShift-64 stream cipher, block-indexed salt
- **Nonce-based key exchange** — both sides contribute a random nonce; `key = serverNonce XOR clientNonce`
- **LRU cache** — `LinkedHashMap` with access-order eviction, max 10 entries
- **Packet drop simulation** — `--drop` flag simulates 1% random loss for testing
- **File integrity validation** — received files compared byte-for-byte with source using `cmp`
- **Concurrent sessions** — thread-per-client, each session on its own ephemeral UDP socket
- **Browser-friendly** — any HTTP client works; images render directly in the browser

---

## Quick Start

### Prerequisites

- Java 17+
- Files to serve placed in `photos/` (or `../photos/` relative to where you run DataServer)

### Compile

```bash
javac -d bin src/pj2/*.java
```

### Run

Open three terminals:

```bash
# Terminal 1 — DataServer
java -cp bin pj2.DataServer

# Terminal 2 — ProxyServer
java -cp bin pj2.ProxyServer

# Terminal 3 — test client (or just use a browser)
java -cp bin pj2.ClientTFTP Su.png
```

Then open your browser to any file in `photos/`, for example:

```
http://localhost:8080/Su.png
http://localhost:8080/magic.png
```

---

## Command-Line Options

### DataServer

| Flag | Default | Description |
|------|---------|-------------|
| `--port=N` | `8069` | UDP port to listen on |
| `--drop` | off | Simulate 1% random packet loss |

```bash
java -cp bin pj2.DataServer --port=8069 --drop
```

### ProxyServer

| Flag | Default | Description |
|------|---------|-------------|
| `--port=N` | `8080` | HTTP port to listen on |
| `--data-port=N` | `8069` | UDP port DataServer is on |
| `--window=N` | `8` | Sliding window size (1–64) |
| `--drop` | off | Simulate 1% random packet loss on receive |

```bash
java -cp bin pj2.ProxyServer --port=8080 --window=8 --drop
```

### ClientTFTP

```bash
java -cp bin pj2.ClientTFTP Su.png
java -cp bin pj2.ClientTFTP magic.png
java -cp bin pj2.ClientTFTP http://localhost:8080/Su.png
```

---

## Protocol Details

### Packet Opcodes

| Opcode | Name | Description |
|--------|------|-------------|
| `1` | RRQ | Read request — filename + `windowsize` option (RFC 7440) |
| `3` | DATA | `[opcode 2B][block# 2B][payload ≤1024B]` — payload is XOR-encrypted |
| `4` | ACK | `[opcode 2B][block# 2B]` — cumulative acknowledgment |
| `5` | ERROR | `[opcode 2B][code 2B][message\0]` |
| `11` | HANDSHAKE | `[opcode 2B][senderID 4B][nonce 8B]` — custom, sent by DataServer |
| `12` | HANDSHAKE_ACK | Same layout — sent by ProxyServer |

### Session Flow

```
ProxyServer  ──── RRQ (opcode 1, windowsize option) ──────►  DataServer :8069
                                                               (opens ephemeral socket)
ProxyServer  ◄─── HANDSHAKE (opcode 11, serverNonce) ────────  DataServer :EPHEMERAL
ProxyServer  ──── HANDSHAKE_ACK (opcode 12, clientNonce) ──►  DataServer :EPHEMERAL

key = serverNonce XOR clientNonce   (computed independently on both sides)

ProxyServer  ◄─── DATA block 1..W (XOR-encrypted) ──────────  DataServer
ProxyServer  ──── ACK (cumulative) ────────────────────────►  DataServer
             ◄─── DATA block W+1..2W ────────────────────────
             ──── ACK ──────────────────────────────────────►
             ... until final block (len < 1024) received
```

### Encryption

Each DATA block is encrypted with a XorShift-64 stream cipher:

```
seed = masterKey XOR (blockIndex << 16)
for each byte:
    seed ^= (seed << 13)
    seed ^= (seed >>> 7)
    seed ^= (seed << 17)
    output[i] = input[i] XOR (seed & 0xFF)
```

The block index is used as a salt so each block has a unique key stream. Decryption is identical to encryption (XOR is its own inverse).

### Sliding Window + RTO

The sender transmits up to `windowSize` blocks before waiting for an ACK. On timeout, it retransmits from the oldest unACK'd block (Go-Back-N on the sender). The receiver buffers out-of-order blocks in a `TreeMap` and sends cumulative ACKs.

RTO follows RFC 6298:

```
RTTVAR = 0.75×RTTVAR + 0.25×|SRTT - sample|
SRTT   = 0.875×SRTT  + 0.125×sample
RTO    = clamp(SRTT + 4×RTTVAR, 200ms, 60000ms)
```

Timeout doubles RTO (exponential backoff).

---

## HTTP Endpoints

| URL | Description |
|-----|-------------|
| `http://localhost:8080/<filename>` | Fetch and display a file |
| `http://localhost:8080/api/cache` | View current LRU cache contents |
| `http://localhost:8080/throughput` | Throughput results table |

Response headers include `X-Cache: HIT` or `X-Cache: MISS` so you can observe caching from browser DevTools.

---

## File Layout

```
Project2/
├── src/pj2/
│   ├── DataServer.java      # UDP file server
│   ├── ProxyServer.java     # HTTP↔TFTP proxy with LRU cache
│   └── ClientTFTP.java      # HTTP test client
├── photos/                  # Put image files here
│   ├── Su.png
│   └── magic.png
└── bin/                     # Compiled classes (after javac)
```

DataServer looks for files in `../photos/` then `./photos/`, case-insensitive. Received files are written to `/tmp/proxy_recv/` for validation.

---

## Throughput Results

Measured on localhost (same machine) and across two machines. Window sizes 1, 8, 64 with and without 1% packet drop simulation.

| Window | No drop | 1% drop |
|--------|---------|---------|
| 1 | — kbps | — kbps |
| 8 | — kbps | — kbps |
| 64 | — kbps | — kbps |

*(Fill in from DataServer console output — look for the line: `Done 'file.png' N bytes X.X kbps retx=N`)*

---

## Limitations

- Block numbers are 16-bit: maximum file size ~64 MB (65535 × 1024 bytes)
- Cache has no TTL — stale entries persist until evicted by LRU pressure
- No OACK (options acknowledgment, RFC 2347) — window size is accepted silently
- XorShift-64 is not cryptographically secure; susceptible to known-plaintext attacks on image headers

---

## Course

Computer Networks @ SUNY Oswego 
Project 2: Proxy Server with custom TFTP + sliding window + encryption
