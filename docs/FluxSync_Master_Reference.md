# FluxSync — Master Vibe Coding Reference

## TABLE OF CONTENTS

|----|----------------------------------------------------------------------------------------------|
| #  | Section                                                                                      |
| 1  | [Project Overview & Design Rules](#1-project-overview--design-rules)                         |
| 2  | [Tech Stack & Module Structure](#2-tech-stack--module-structure)                             |
| 3  | [Core Protocol & Wire Format](#3-core-protocol--wire-format)                                 |
| 4  | [Dynamic Chunk Sizing](#4-dynamic-chunk-sizing)                                              |
| 5  | [Storage Engine — Zero-Copy FileStreamer](#5-storage-engine--zero-copy-filestreamer)         |
| 6  | [DRTLB — Pull-Based Load Balancer](#6-drtlb--pull-based-load-balancer)                       |
| 7  | [Chunk Assembly — Receiver Side](#7-chunk-assembly--receiver-side)                           |
| 8  | [Hard Resumability — BitTorrent Style](#8-hard-resumability--bittorrent-style)               |
| 9  | [Discovery Module](#9-discovery-module)                                                      |
| 10 | [Security — TOFU + TLS 1.3](#10-security--tofu--tls-13)                                      |
| 11 | [Session State Machine](#11-session-state-machine)                                           |
| 12 | [Android — Foreground Service & OEM Defense](#12-android--foreground-service--oem-defense)   |
| 13 | [Desktop — ADB Lifecycle Manager](#13-desktop--adb-lifecycle-manager)                        |
| 14 | [Desktop — mDNS with JmDNS](#14-desktop--mdns-with-jmdns)                                    |
| 15 | [Desktop — UI, System Tray & Window](#15-desktop--ui-system-tray--window)                    |
| 16 | [Desktop — Platform Setup (Windows & Linux)](#16-desktop--platform-setup-windows--linux)     |
| 17 | [Error Handling & Resilience Patterns](#17-error-handling--resilience-patterns)              |
| 18 | [UI/UX — Full Specification](#18-uiux--full-specification)                                   |
| 19 | [Transfer History Screen](#19-transfer-history-screen)                                       |
| 20 | [Known Pitfalls & Defensive Fixes](#20-known-pitfalls--defensive-fixes)                      |
| 21 | [Port & Network Strategy](#21-port--network-strategy)                                        |
| 22 | [Vibe Coding Prompts Cheatsheet](#22-vibe-coding-prompts-cheatsheet)                         |

---

## 1. Project Overview & Design Rules

### What FluxSync Is

A cross-platform file transfer ecosystem (Android ↔ PC) that aggregates **Wi-Fi and USB/ADB channels simultaneously**, dynamically splitting traffic across both for maximum throughput. Two apps, one shared core.

- **FluxSync Android** — Kotlin, Jetpack Compose, targets SDK 35
- **FluxSync Desktop** — Kotlin/JVM, Compose Multiplatform, Windows + Linux

### The Two Golden Rules

1. **Files are NEVER fully loaded into heap.** 64KB–1MB chunks only, via `FileChannel`. Always.
2. **Fail loudly, recover gracefully.** No silent failures. Every degraded state has a name, a log entry, and a UI representation.

### Core Design Principles

- **Performance First** — Saturate all available bandwidth simultaneously
- **Zero-Copy Memory Safety** — `FileChannel` → fixed `ByteBuffer` → never heap-loaded
- **64MB Global Memory Cap** — enforced via bounded Kotlin `Channel` queues on both sender AND receiver
- **Hard Resumability** — Survives app kills, reboots, and lost connections. `.fluxpart` files on disk.
- **Ecosystem-Ready Protocol** — `:core:protocol` is KMP; Android and Desktop share identical serialization code
- **Zero-Config Discovery** — mDNS with manual IP + QR code fallback
- **AWAITING_CONSENT gatekeeper** — even trusted devices must be explicitly accepted before bytes move
- **"Cockpit" UI philosophy** — show the performance, don't just describe it

---

## 2. Tech Stack & Module Structure

### Android Modules
```
:app                          # Android UI, Compose screens, ViewModels
:core:protocol                # KMP — packet definitions, serialization
:core:transfer-engine         # KMP — DRTLB, FileStreamer, ChunkAssembler
:core:security                # KMP — certificate gen, TOFU trust store
:core:resumability            # KMP — .fluxpart read/write, debounce logic
:android:data:network         # Android-specific socket/channel impl
:android:data:storage         # Android-specific FileChannel + SAF/URI impl
:android:service              # ForegroundService, WakeLock, RemoteViews notification
```

### Desktop Modules
```
:desktop:app                  # Compose Multiplatform UI, system tray, entry point
:desktop:data:adb             # ADB lifecycle manager (Desktop-only)
:desktop:data:network         # JVM socket/channel impl
:desktop:data:storage         # JVM FileChannel impl
:core:protocol                # Same KMP source as Android
:core:transfer-engine         # Same KMP source as Android
:core:security                # Same KMP source as Android
:core:resumability            # Same KMP source as Android
```

### Key Dependencies
| Dependency | Purpose |
|---|---|
| `kotlinx.coroutines` | All async — no raw thread management |
| `kotlinx.serialization` | Control packet serialization |
| `JmDNS` | Desktop mDNS (pure Java, JVM) |
| Android `NsdManager` | Android mDNS |
| Compose Multiplatform | Desktop UI + drag-and-drop |
| Jetpack Compose + Material 3 | Android UI |
| Android Keystore | Cert storage on Android |
| `java.nio.channels.FileChannel` | Shared on both platforms via KMP |
| `AutoStarter` (optional) | OEM battery optimization detection |

---

## 3. Core Protocol & Wire Format

### Chunk Packet — Hand-Rolled Binary
> Used for the high-frequency file data stream. Zero allocation on the hot path.

```
| Field          | Size    | Type              | Notes                          |
|----------------|---------|-------------------|--------------------------------|
| sessionId      | 8 bytes | Long, Big-Endian  |                                |
| fileId         | 4 bytes | Int, Big-Endian   |                                |
| chunkIndex     | 4 bytes | Int, Big-Endian   |                                |
| offset         | 8 bytes | Long, Big-Endian  | Byte offset in file            |
| payloadLength  | 4 bytes | Int, Big-Endian   | Actual bytes in payload        |
| checksum       | 4 bytes | Int, Big-Endian   | CRC32 of payload only          |
| payload        | N bytes | Raw bytes         | Max = negotiated chunk size    |
```

**Rules:**
- All multi-byte integers: **Big-Endian** (`ByteBuffer.order(ByteOrder.BIG_ENDIAN)`)
- Reuse the same `ByteBuffer.allocateDirect()` — never allocate per-chunk
- Chunk size is negotiated per-file during handshake (see Section 4)

### Control Packets — kotlinx.serialization
> Infrequent control messages. Schema safety matters more than allocation here.

```kotlin
@Serializable
data class HandshakePacket(
    val protocolVersion: Int,
    val deviceName: String,
    val certFingerprint: String,
    val maxChunkSizeBytes: Int,      // Device's current memory ceiling
    val availableMemoryMb: Int       // Helps peer make smarter chunk size decisions
)

@Serializable
data class ResumeValidation(
    val fileId: String,
    val expectedSizeBytes: Long,     // Must match exactly
    val lastModifiedEpochMs: Long,   // 1-second precision — always pair with size check
    val firstChunkChecksum: Int      // CRC32 of chunk 0 — cheap sanity check
)

@Serializable
data class FileManifest(
    val sessionId: Long,
    val files: List<FileEntry>
)

@Serializable
data class FileEntry(
    val fileId: Int,
    val name: String,
    val sizeBytes: Long,
    val totalChunks: Int,
    val negotiatedChunkSizeBytes: Int,
    val resumeValidation: ResumeValidation?  // null = fresh transfer
)

@Serializable
data class RetryRequestPacket(
    val sessionId: Long,
    val fileId: Int,
    val failedChunkIndices: List<Int>
)

@Serializable data class SessionCompletePacket(val sessionId: Long)
@Serializable data class SessionCancelPacket(val sessionId: Long, val reason: String)
@Serializable data class ConsentRequestPacket(val sessionId: Long, val manifest: FileManifest)
@Serializable data class ConsentResponsePacket(val sessionId: Long, val accepted: Boolean)
```

---

## 4. Dynamic Chunk Sizing

### Size Tiers (Negotiated Per-File)
| Tier | Chunk Size | Queue Capacity | Total Buffer | File Size Trigger |
|------|-----------|----------------|--------------|-------------------|
| Small | 64 KB | 1024 chunks | 64 MB | < 100 MB |
| Normal | 256 KB | 256 chunks | 64 MB | 100 MB – 1 GB |
| Big | 512 KB | 128 chunks | 64 MB | 1 GB – 5 GB |
| Massive | 1 MB | 64 chunks | 64 MB | > 5 GB |

> The buffer footprint is always flat at **64 MB** regardless of tier. Queue capacity scales inversely with chunk size.

### Negotiation Logic
```kotlin
fun negotiateChunkSize(fileSizeBytes: Long, peerMaxChunkBytes: Int): Int {
    val preferred = when {
        fileSizeBytes < 100 * 1024 * 1024L  -> 64 * 1024       // Small
        fileSizeBytes < 1024 * 1024 * 1024L -> 256 * 1024      // Normal
        fileSizeBytes < 5L * 1024 * 1024 * 1024 -> 512 * 1024  // Big
        else -> 1024 * 1024                                      // Massive
    }
    // Take the smaller of our preference and peer's memory ceiling
    return minOf(preferred, peerMaxChunkBytes)
}

fun queueCapacityForChunkSize(chunkSizeBytes: Int): Int {
    val targetBufferBytes = 64 * 1024 * 1024 // 64MB flat cap
    return targetBufferBytes / chunkSizeBytes
}
```

### HandshakePacket — maxChunkSizeBytes
- Each device advertises its current `maxChunkSizeBytes` based on available memory at connection time
- Sender takes `min(peerMax, myPreferred)` — defensive by default
- If a device is under memory pressure, it can advertise a smaller ceiling and the peer respects it

---

## 5. Storage Engine — Zero-Copy FileStreamer

```kotlin
class FileStreamer(
    private val file: File,
    private val outChannel: SendChannel<ChunkPacket>,
    private val chunkSizeBytes: Int,   // From negotiation — not hardcoded
    private val sessionId: Long,
    private val fileId: Int
) {
    suspend fun stream() {
        FileChannel.open(file.toPath(), StandardOpenOption.READ).use { fc ->
            // CRITICAL: allocateDirect = off-heap, no GC pressure
            val buffer = ByteBuffer.allocateDirect(chunkSizeBytes)
            var offset = 0L
            var chunkIndex = 0
            while (true) {
                buffer.clear()
                val bytesRead = fc.read(buffer, offset)
                if (bytesRead <= 0) break
                buffer.flip()
                val payload = ByteArray(bytesRead).also { buffer.get(it) }
                val chunk = ChunkPacket(
                    sessionId = sessionId,
                    fileId = fileId,
                    chunkIndex = chunkIndex++,
                    offset = offset,
                    payloadLength = bytesRead,
                    checksum = CRC32.compute(payload),
                    payload = payload
                )
                outChannel.send(chunk)  // SUSPENDS if queue full — this IS the backpressure
                offset += bytesRead
            }
        }
    }
}
```

**Invariants — never break these:**
- `ByteBuffer.allocateDirect()` — always, never `ByteBuffer.allocate()`
- Read by offset, never sequential stream — enables retry of specific chunks
- `outChannel` capacity = `queueCapacityForChunkSize(chunkSizeBytes)` (see Section 4)
- FileStreamer auto-pauses when DRTLB is slow — no manual rate limiting needed

---

## 6. DRTLB — Pull-Based Load Balancer

> The DRTLB no longer calculates rigid percentages. It uses a **pull-based `select` race** — each socket pulls chunks as fast as its hardware allows. Natural self-balancing with no math.

### Channel States
```
ACTIVE      — healthy, participating in the select race
DEGRADED    — IOException caught, excluded from race, chunks re-routed
OFFLINE     — fully removed from pool (e.g. USB unplugged, "Force Wi-Fi" mode)
```

### Core Pull-Based Dispatch
```kotlin
class DRTLB(
    private val chunkSource: ReceiveChannel<ChunkPacket>,
    private val retrySlot: Channel<ChunkPacket> = Channel(capacity = 64)
) {
    private val channels = mutableListOf<NetworkChannel>()

    suspend fun run() = coroutineScope {
        channels.filter { it.state == ACTIVE }.forEach { channel ->
            launch { channelWorker(channel) }
        }
    }

    // Each active channel runs its own worker — pulls as fast as hardware allows
    private suspend fun channelWorker(channel: NetworkChannel) {
        while (isActive && channel.state == ACTIVE) {
            // Priority: retry queue first, then main chunk source
            val chunk = select {
                retrySlot.onReceive { it }         // HIGH PRIORITY — failed chunks
                chunkSource.onReceive { it }        // Normal chunk stream
            }
            try {
                channel.send(chunk)
                channel.recordSuccess(chunk.payloadLength.toLong())
            } catch (e: IOException) {
                channel.state = DEGRADED
                retrySlot.send(chunk)              // Re-queue for another channel
                onChannelDegraded(channel)
            }
        }
    }
}
```

### Telemetry (UI Only — Does NOT affect transfer)
```kotlin
// Runs separately. If this coroutine dies, transfer continues at full speed.
suspend fun telemetryLoop() {
    while (isActive) {
        delay(200) // 5Hz — matches UI sample rate
        val stats = channels.map { ch ->
            ChannelTelemetry(
                channelId = ch.id,
                throughputBytesPerSec = ch.measuredThroughput,
                weightFraction = ch.measuredThroughput.toFloat() / totalThroughput,
                bufferFillPercent = ch.sendBuffer.size.toFloat() / ch.sendBuffer.capacity,
                latencyMs = ch.lastLatencyMs
            )
        }
        _telemetryFlow.emit(stats)
    }
}
```

### "Force Wi-Fi Only" Mode
```kotlin
// This is a REGISTRATION GATE — not a weight override
// When enabled, AdbLifecycleManager skips drtlb.registerChannel() entirely
// The USB channel never exists; it doesn't waste a socket or a coroutine
fun onForceWifiOnlyChanged(enabled: Boolean) {
    if (enabled) {
        channels.removeAll { it.type == ChannelType.USB_ADB }
        // AdbLifecycleManager also stops calling drtlb.registerChannel() for new USB devices
    }
}
```

---

## 7. Chunk Assembly — Receiver Side

### Bounded Inbound Queue (OOM Prevention)
```kotlin
class ChunkReceiver(
    private val assembler: ChunkAssembler,
    private val chunkSizeBytes: Int    // From negotiated tier
) {
    // Capacity matches sender's queue — always flat 64MB
    private val inboundQueue = Channel<ChunkPacket>(
        capacity = queueCapacityForChunkSize(chunkSizeBytes)
    )

    // One coroutine per network channel — Wi-Fi and USB run concurrently
    // Both feed the SAME bounded queue
    suspend fun socketReadLoop(socket: Socket) {
        while (isActive) {
            val chunk = readChunkPacketFromSocket(socket)
            inboundQueue.send(chunk)   // Suspends when queue full → TCP backpressure → sender slows
        }
    }

    // Single coroutine serializes all disk writes — no concurrent write conflicts
    suspend fun assemblyLoop() {
        for (chunk in inboundQueue) {
            assembler.writeChunk(chunk)
        }
    }
}
```

### Thread-Safe ChunkAssembler
```kotlin
class ChunkAssembler(
    private val targetFile: RandomAccessFile,
    private val totalChunks: Int,
    private val resumeState: FluxPartState?  // null = fresh transfer
) {
    private val completionBitmap = AtomicIntegerArray(totalChunks).also { bitmap ->
        // Pre-populate from .fluxpart if resuming
        resumeState?.completedChunks?.forEach { bitmap.set(it, 1) }
    }
    private val writeMutex = Mutex()
    private val failedChunks = ConcurrentHashMap.newKeySet<Int>()
    private var retryCount = 0
    private val maxRetries = 3

    suspend fun writeChunk(chunk: ChunkPacket): WriteResult {
        // Skip if already written (resume scenario)
        if (completionBitmap.get(chunk.chunkIndex) == 1) return WriteResult.AlreadyComplete

        // 1. CRC check FIRST — fail fast before acquiring mutex
        if (CRC32.compute(chunk.payload) != chunk.checksum) {
            failedChunks.add(chunk.chunkIndex)
            return WriteResult.ChecksumFailure(chunk.chunkIndex)
        }

        // 2. Write under mutex — serialize concurrent Wi-Fi + USB writes
        writeMutex.withLock {
            targetFile.seek(chunk.offset)
            targetFile.write(chunk.payload)
        }

        // 3. Mark complete ONLY after write is confirmed — NEVER before
        completionBitmap.set(chunk.chunkIndex, 1)

        // 4. Debounce .fluxpart persistence (2s) — protects flash storage
        fluxPartDebouncer.scheduleWrite(completionBitmap.snapshot())

        checkCompletion()
        return WriteResult.Success
    }

    private fun checkCompletion() {
        if ((0 until totalChunks).all { completionBitmap.get(it) == 1 }) {
            if (failedChunks.isEmpty()) {
                onFileComplete()
            } else if (retryCount < maxRetries) {
                retryCount++
                onRetryRequired(failedChunks.toList())
            } else {
                onFileFailed("Max retries ($maxRetries) exceeded. Failed chunks: $failedChunks")
            }
        }
    }
}
```

**Invariants — never break these:**
- Order is always: **CRC check → write → mark complete**. This order is not negotiable.
- `AtomicIntegerArray` for bitmap — concurrent-safe reads/writes
- Kotlin `Mutex` for file writes — serializes Wi-Fi + USB concurrent chunk arrival
- Pre-allocate target file to full size before assembly begins (enables offset writes)
- Skip already-completed chunks silently (resume scenario)

---

## 8. Hard Resumability — BitTorrent Style

### .fluxpart File Format (Binary)
```
| Field             | Size     | Content                                     |
|-------------------|----------|---------------------------------------------|
| magic             | 4 bytes  | 0x464C5558 ("FLUX")                         |
| version           | 2 bytes  | Format version (currently 1)                |
| sessionId         | 8 bytes  | Original session ID                         |
| fileId            | 4 bytes  |                                             |
| totalChunks       | 4 bytes  |                                             |
| chunkSizeBytes    | 4 bytes  | Which tier was negotiated                   |
| expectedSizeBytes | 8 bytes  | Source file size at transfer start          |
| lastModifiedMs    | 8 bytes  | Source file lastModified at transfer start  |
| firstChunkCRC32   | 4 bytes  | CRC32 of chunk 0 — resume sanity check      |
| createdAtMs       | 8 bytes  | When this .fluxpart was created             |
| completionBitmap  | N bytes  | ceil(totalChunks / 8) bytes, bit-packed     |
```

### File Naming & Hiding
```
During transfer:  filename.mp4.fluxdownload   (hidden from media scanners)
                  filename.mp4.fluxpart        (progress tracker, binary)
On completion:    filename.mp4                 (atomic rename of .fluxdownload)
                  filename.mp4.fluxpart        (deleted)
```

- A `.nomedia` file is written to the download directory to prevent media apps from indexing incomplete `.fluxdownload` files
- Always use atomic rename (`File.renameTo()`) for the final file promotion

### .fluxpart Debounced Write
```kotlin
class FluxPartDebouncer(
    private val fluxPartFile: File,
    private val scope: CoroutineScope,
    private val debounceMs: Long = 2000  // 2 seconds — protects flash storage
) {
    private var pendingJob: Job? = null

    fun scheduleWrite(bitmap: IntArray) {
        pendingJob?.cancel()
        pendingJob = scope.launch {
            delay(debounceMs)
            writeFluxPart(bitmap)
        }
    }

    // Call this on session end to flush immediately regardless of debounce
    suspend fun flush(bitmap: IntArray) {
        pendingJob?.cancel()
        writeFluxPart(bitmap)
    }
}
```

### Frankenstein Resume Validation (Source File Changed)
```kotlin
fun validateResumeCandidate(
    fluxPart: FluxPartState,
    currentSourceFile: File
): ResumeValidationResult {
    // Check 1: Size must match exactly
    if (currentSourceFile.length() != fluxPart.expectedSizeBytes) {
        return ResumeValidationResult.ABORT_FILE_CHANGED
    }

    // Check 2: lastModified (1-second precision on most filesystems)
    // Always pair with size check — never rely on timestamp alone
    if (currentSourceFile.lastModified() != fluxPart.lastModifiedMs) {
        return ResumeValidationResult.ABORT_FILE_CHANGED
    }

    // Check 3: CRC32 of chunk 0 — cheap sanity check (only reads first chunkSize bytes)
    val firstChunkActualCRC = computeFirstChunkCRC(currentSourceFile, fluxPart.chunkSizeBytes)
    if (firstChunkActualCRC != fluxPart.firstChunkCRC32) {
        return ResumeValidationResult.ABORT_FILE_CHANGED
    }

    return ResumeValidationResult.OK
}
```

### Orphan Cleanup
- A `.fluxpart` is orphaned if its `createdAtMs` is older than **7 days** (configurable in Settings)
- Settings > Storage shows orphaned files with: source device name, file name, size, age
- Auto-cleanup is **opt-in** — never silently delete
- Also clean up `.fluxdownload` files with no matching `.fluxpart`

---

## 9. Discovery Module

### mDNS Service Details
```
Service type:  _fluxsync._tcp
Port:          5001 (or actual bound port from 5001–5099)
TXT record:    protocolVersion=<int>
               certFingerprint=<SHA-256 hex>
               port=<actual bound port>   ← CRITICAL for fallback port detection
```

### Android — NsdManager
```kotlin
// Register on app launch
val serviceInfo = NsdServiceInfo().apply {
    serviceName = "FluxSync-${Build.MODEL}"
    serviceType = "_fluxsync._tcp"
    port = actualBoundPort
}
nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)

// Discover simultaneously — bidirectional
nsdManager.discoverServices("_fluxsync._tcp", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
```

### Android — USB Tunnel Detection
```kotlin
suspend fun pollForUsbTunnel() {
    while (isActive) {
        delay(1000)
        val tunnelActive = isPortReachable("localhost", 5002)
        if (tunnelActive && usbChannel.state != ACTIVE) {
            when (verifyTunnelBinding(5002)) {
                TunnelStatus.ACTIVE -> drtlb.registerChannel(usbChannel)
                TunnelStatus.PORT_COLLISION -> notifyUi(UiEvent.PortCollisionDetected(5002))
                TunnelStatus.PORT_FREE_NO_TUNNEL -> { /* tunnel not ready yet */ }
            }
        } else if (!tunnelActive && usbChannel.state == ACTIVE) {
            drtlb.removeChannel(usbChannel)
        }
    }
}
```

### Port 5002 Handshake Ping/Pong
```kotlin
// After detecting port 5002 is reachable, verify it's actually FluxSync
fun isFluxSyncResponding(port: Int): Boolean {
    return try {
        Socket("localhost", port).use { s ->
            s.soTimeout = 1000
            s.getOutputStream().write(0x0F)     // FluxSync probe byte
            val response = s.getInputStream().read()
            response == 0xF0                    // Expected FluxSync pong
        }
    } catch (e: Exception) { false }
}
```

### Discovery Fallback Ladder (in priority order)
```
1. mDNS (NsdManager / JmDNS) — zero config, automatic
2. Manual IP entry            — always visible in Discovery UI footer
3. QR Code pairing            — Desktop displays QR, Android scans
                                QR encodes: fluxsync://<ip>:<port>/<certFingerprint>
                                Also pre-validates cert fingerprint — free TOFU bonus
```

**Never let mDNS failure = total failure.**

---

## 10. Security — TOFU + TLS 1.3

### Certificate Generation (First Launch)
```kotlin
// Android: Self-signed X.509 → stored in Android Keystore
// Desktop: Self-signed X.509 → stored at platform config dir
// Both: Derive SHA-256 fingerprint → used as trust store key
```

### Correct TOFU Sequence — NEVER deviate from this order
```
Step 1: TCP connection established
Step 2: TLS 1.3 handshake — both sides accept peer's self-signed cert (unverified)
        [Connection is now encrypted]
Step 3: Server generates 4-digit PIN, displays it on screen
Step 4: Client user reads PIN from Server screen, types it into Client
Step 5: Client sends PIN over the already-encrypted TLS channel
Step 6: Server verifies PIN
        — A MITM who captured the TLS handshake still cannot know the PIN
Step 7: On success: persist peer cert SHA-256 fingerprint to trust store
Step 8: Future connections from this device: skip PIN, go straight to AWAITING_CONSENT
```

### AWAITING_CONSENT — The Gatekeeper State
```
Even trusted devices DO NOT start transferring automatically.
On every incoming connection (trusted or not):
  → Receiver enters AWAITING_CONSENT state
  → UI shows consent prompt with file manifest summary
  → Sender enters PENDING_CONSENT state (shows "Waiting for [Device] to accept...")
  → Sender has a 60-second timeout before auto-cancel
  → Only after explicit Accept → session proceeds to TRANSFERRING
```

### Trust Store
```kotlin
// Key:   SHA-256 cert fingerprint (hex string)
// Value: TrustedDevice(name, lastSeenMs, trusted=true)
// Android: EncryptedSharedPreferences or Room DB
// Desktop: $CONFIG_DIR/fluxsync/truststore.json
```

### TLS Cipher Suite — Verify Hardware Acceleration
```kotlin
// After TLS handshake, assert cipher suite
val session = sslSocket.session
val cipher = session.cipherSuite
Log.d("FluxSync", "Cipher: $cipher")

// GOOD — hardware AES on ARMv8:
//   TLS_AES_128_GCM_SHA256
//   TLS_AES_256_GCM_SHA384
// BAD — software cipher, CPU bottleneck at high speeds:
//   TLS_CHACHA20_POLY1305

if (cipher.contains("CHACHA20")) {
    Log.w("FluxSync", "Hardware AES not available — may CPU-bottleneck at high speeds")
    notifyUi(UiEvent.SoftwareCipherWarning)
}
```

### Desktop — Certificate Storage Paths
```
Windows: %APPDATA%\FluxSync\certs\device.p12
         %APPDATA%\FluxSync\truststore.json
Linux:   $HOME/.config/fluxsync/certs/device.p12
         $HOME/.config/fluxsync/truststore.json
```

---

## 11. Session State Machine

### State Diagram
```
IDLE
  └─► CONNECTING
        └─► HANDSHAKING
              ├─► PAIRING          (if device not in trust store — PIN exchange)
              │     └─► AWAITING_CONSENT
              └─► AWAITING_CONSENT (if trusted — skip PIN)
                    └─► TRANSFERRING
                          ├─► RETRYING      (RetryRequestPacket sent)
                          │     └─► TRANSFERRING (loop back)
                          ├─► COMPLETED
                          ├─► FAILED
                          └─► CANCELLED
```

### Sender Mirror States
```
IDLE → CONNECTING → HANDSHAKING → [PAIRING] → PENDING_CONSENT → TRANSFERRING → COMPLETED/FAILED/CANCELLED
```

> `PENDING_CONSENT` — sender's mirror of `AWAITING_CONSENT`. Shows "Waiting for [Device] to accept..." with 60-second countdown and cancel button.

### Session Rules
- Both Client and Server track state independently
- State divergence = immediate `SESSION_CANCEL` + full cleanup
- **Heartbeat timeout:** 10 seconds with no state-advancing packet → `SESSION_CANCEL`
- On cancel: close all sockets, release WakeLock, flush `.fluxpart`, notify UI
- On complete: rename `.fluxdownload` → final filename, delete `.fluxpart`, log to history

### Packet Flow
```
Sender  →  SERVER_CONNECT
Receiver → SESSION_ACK + HandshakePacket (with maxChunkSizeBytes)
[TLS 1.3 upgrade]
[PIN exchange if not trusted]
Receiver → CONSENT_REQUEST (enters AWAITING_CONSENT)
Sender   → enters PENDING_CONSENT, displays "Waiting..."
[User accepts on Receiver]
Receiver → CONSENT_RESPONSE(accepted=true)
Sender   →  FileManifest (with negotiated chunk sizes per file)
Receiver → MANIFEST_ACK
[Chunk stream begins — both Wi-Fi (5001) and USB (5002) channels]
Receiver → SESSION_COMPLETE or RetryRequestPacket
[If retry: sender re-reads only failed chunks, resends]
Receiver → SESSION_COMPLETE
[Sender logs to TransferHistory, UI shows completion]
```

---

## 12. Android — Foreground Service & OEM Defense

### Manifest Entries
```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.CHANGE_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.USE_FULL_SCREEN_INTENT" />
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />

<!-- API 33+: No location dialog -->
<uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES"
    android:usesPermissionFlags="neverForLocation" />

<!-- API ≤ 32: Must explain in UI -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />

<service
    android:name=".TransferForegroundService"
    android:foregroundServiceType="dataSync" />
```

### ForegroundService Implementation
```kotlin
class TransferForegroundService : Service() {
    private lateinit var wakeLock: PowerManager.WakeLock

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // REQUIRED: Call startForeground within 5 seconds of service creation
        startForeground(NOTIFICATION_ID, buildTransferNotification())

        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "FluxSync::TransferLock"
        ).apply { acquire(30 * 60 * 1000L) }  // 30 min max — release on completion

        return START_STICKY
    }

    override fun onDestroy() {
        if (::wakeLock.isInitialized && wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }
}
```

### TCP Socket Keep-Alive (Detect OEM Kills Fast)
```kotlin
fun configureSocket(socket: Socket) {
    socket.setOption(StandardSocketOptions.SO_KEEPALIVE, true)
    // API 31+ or via reflection on older:
    socket.setOption(ExtendedSocketOptions.TCP_KEEPIDLE, 5)      // Probe after 5s idle
    socket.setOption(ExtendedSocketOptions.TCP_KEEPINTERVAL, 2)  // Every 2s
    socket.setOption(ExtendedSocketOptions.TCP_KEEPCOUNT, 3)     // 3 failures = dead
}
```

### Onboarding Battery Strategy ("Power User" Mode)
```kotlin
// Pre-emptive demand during onboarding — not a passive suggestion
// Full-screen prompt on the onboarding screen
fun requestBatteryOptimizationExemption(context: Context) {
    val pm = context.getSystemService(PowerManager::class.java)
    if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        context.startActivity(intent)
    }
}
```

### Consent Notification with Full-Screen Intent
```kotlin
// For incoming file requests — guarantees visibility even on locked screen
fun buildConsentNotification(manifest: FileManifest): Notification {
    val acceptIntent = PendingIntent.getBroadcast(...)  // Accept action
    val declineIntent = PendingIntent.getBroadcast(...) // Decline action
    val fullScreenIntent = PendingIntent.getActivity(...)  // Full consent screen

    return NotificationCompat.Builder(context, CONSENT_CHANNEL_ID)
        .setContentTitle("Incoming transfer from ${manifest.senderName}")
        .setContentText("${manifest.files.size} files · ${manifest.totalSizeFormatted}")
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setFullScreenIntent(fullScreenIntent, true)  // Incoming-call style
        .addAction(R.drawable.ic_accept, "Accept", acceptIntent)
        .addAction(R.drawable.ic_decline, "Decline", declineIntent)
        .build()
}
```

### Notification Splitter Bar (RemoteViews)
```kotlin
// The Splitter Bar shown directly in the notification shade
// Uses RemoteViews since Compose can't render in notifications
fun buildTransferNotification(wifiPercent: Float, usbPercent: Float, speedMbs: Float): Notification {
    val remoteViews = RemoteViews(packageName, R.layout.notification_transfer).apply {
        setTextViewText(R.id.tv_speed, "%.1f MB/s".format(speedMbs))
        // Set Wi-Fi segment width proportionally
        setInt(R.id.wifi_bar, "setLayoutWeight", (wifiPercent * 100).toInt())
        setInt(R.id.usb_bar, "setLayoutWeight", (usbPercent * 100).toInt())
    }
    return NotificationCompat.Builder(context, TRANSFER_CHANNEL_ID)
        .setCustomBigContentView(remoteViews)
        .setOngoing(true)
        .build()
}
```

### Runtime Permission Strategy
```
POST_NOTIFICATIONS:    Request at onboarding, before first transfer
NEARBY_WIFI_DEVICES:   API 33+ — no location dialog (neverForLocation flag)
ACCESS_FINE_LOCATION:  API ≤ 32 — explain: "Required to find nearby devices on Android 12 and below"
USE_FULL_SCREEN_INTENT: API 34+ — requires explicit permission grant
Storage:               Use SAF file picker (ActivityResultContracts.OpenDocumentTree)
                       Persist URI: contentResolver.takePersistableUriPermission()
```

### Scoped Storage — First-Run Folder Picker
```kotlin
// This replaces all storage permission requests
// User selects a "Drop Zone" folder — persisted indefinitely
val folderPickerLauncher = registerForActivityResult(
    ActivityResultContracts.OpenDocumentTree()
) { uri ->
    uri?.let {
        // Persist the permission across reboots
        contentResolver.takePersistableUriPermission(
            it,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        prefs.saveDropZoneUri(it.toString())
    }
}
```

---

## 13. Desktop — ADB Lifecycle Manager

### ADB Binary Resolution (Version Conflict Safe)
```kotlin
fun resolveAdbBinary(): File {
    // CRITICAL: Check if ADB server is already running (Android Studio, etc.)
    // ADB daemon always listens on port 5037
    val serverAlreadyRunning = isPortReachable("localhost", 5037)

    if (serverAlreadyRunning) {
        // Defer to the running server — don't launch competing version
        findOnPath("adb")?.let {
            Log.i("ADB", "Using system adb (server already running on 5037)")
            return it
        }
    }

    // Use bundled adb — platform-specific binary
    val platform = when {
        System.getProperty("os.name").contains("Windows", ignoreCase = true) -> "windows/adb.exe"
        System.getProperty("os.name").contains("Linux", ignoreCase = true)   -> "linux/adb"
        else -> throw UnsupportedOperationException("macOS not supported in v1")
    }
    return File(resourceDir, "adb/$platform").also {
        if (!it.canExecute()) it.setExecutable(true)
    }
}
```

### ADB Manager State Machine (Per Device Serial)
```
ABSENT → DETECTED → AUTHORISED → TUNNEL_ACTIVE → TUNNEL_TORN_DOWN
                  ↘ UNAUTHORISED → (retry every 3s × 30s) → ABSENT
```

### Device Polling Loop
```kotlin
suspend fun devicePollingLoop() {
    while (isActive) {
        delay(2000)
        val output = runCommand("$adbBinary devices")
        val currentDevices = parseAdbDevices(output) // Map<Serial, "device"|"unauthorized"|"offline">

        // Handle new devices
        currentDevices.forEach { (serial, status) ->
            when {
                serial !in trackedDevices && status == "device" -> onAuthorised(serial)
                serial !in trackedDevices && status == "unauthorized" -> onUnauthorised(serial)
                trackedDevices[serial]?.state == AUTHORISED && status == "unauthorized" -> onBecameUnauthorised(serial)
            }
        }

        // Handle disconnections
        trackedDevices.keys
            .filter { it !in currentDevices }
            .forEach { onDisconnected(it) }
    }
}

suspend fun onAuthorised(serial: String) {
    runCommand("$adbBinary -s $serial reverse tcp:5002 tcp:5002")
    trackedDevices[serial] = DeviceRecord(serial, state = TUNNEL_ACTIVE)

    // Only register with DRTLB if "Force Wi-Fi Only" is not enabled
    if (!settings.forceWifiOnly) {
        drtlb.registerChannel(UsbAdbChannel(serial, port = 5002))
    }
}

suspend fun onDisconnected(serial: String) {
    runCommand("$adbBinary -s $serial reverse --remove tcp:5002")
    drtlb.removeChannel(serial)
    trackedDevices.remove(serial)
}
```

### Bundled ADB Binaries (Installer Must Include)
```
resources/adb/windows/adb.exe
resources/adb/windows/AdbWinApi.dll
resources/adb/windows/AdbWinUsbApi.dll
resources/adb/linux/adb
```

---

## 14. Desktop — mDNS with JmDNS

### Interface Selection (Multi-Homed Defense)
```kotlin
// Developer PCs: Ethernet + Wi-Fi + WSL adapter + VPN = JmDNS binds wrong interface
// Always bind to the interface with the default gateway

fun getDefaultGatewayInterface(): InetAddress {
    // Parse: `route print` (Windows) or `ip route show default` (Linux)
    // Return the local address of the NIC that owns the default route
    // Expose Settings > Network > "Interface" selector as override
    return resolveFromRoutingTable() ?: InetAddress.getLocalHost()
}

val jmdns = JmDNS.create(getDefaultGatewayInterface())
```

### Service Registration
```kotlin
fun registerService(actualPort: Int) {
    val txtRecord = mapOf(
        "protocolVersion" to "1",
        "certFingerprint" to myCertFingerprint,
        "port" to actualPort.toString()  // CRITICAL — Android reads this for fallback ports
    )
    val serviceInfo = ServiceInfo.create(
        "_fluxsync._tcp.local.",
        "FluxSync-${InetAddress.getLocalHost().hostName}",
        actualPort,
        0, 0,
        txtRecord
    )
    jmdns.registerService(serviceInfo)
    currentServiceInfo = serviceInfo
}
```

### Android Device Discovery
```kotlin
jmdns.addServiceListener("_fluxsync._tcp.local.", object : ServiceListener {
    override fun serviceAdded(event: ServiceEvent) {
        jmdns.requestServiceInfo(event.type, event.name, true) // true = force fresh resolve
    }
    override fun serviceResolved(event: ServiceEvent) {
        val ip = event.info.inet4Addresses.firstOrNull() ?: return
        val port = event.info.getPropertyString("port")?.toIntOrNull() ?: event.info.port
        val fingerprint = event.info.getPropertyString("certFingerprint")
        onAndroidDeviceDiscovered(ip, port, fingerprint)
    }
    override fun serviceRemoved(event: ServiceEvent) = onAndroidDeviceGone(event.name)
})
```

### Port Fallback + TXT Re-advertisement
```kotlin
fun bindAndAdvertise(preferredPort: Int = 5001): Int {
    val actualPort = (preferredPort..5099).first { isPortAvailable(it) }

    if (::currentServiceInfo.isInitialized) {
        jmdns.unregisterService(currentServiceInfo)
    }
    registerService(actualPort)

    // Wait for mDNS re-advertisement before accepting connections
    // Android's NsdManager may cache the old port otherwise
    delay(500)
    return actualPort
}
```

---

## 15. Desktop — UI, System Tray & Window

### Entry Points
- **System Tray** — always running. `java.awt.SystemTray`. Icon pulses Amber↔Blue during transfer.
- **Main Window** — Compose Multiplatform. Opens on demand or at first launch.
- **Window close = minimize to tray.** Never quit on window close.

### Tray Context Menu
```
Open FluxSync
Send File
──────────
Pause (shown only during active transfer)
──────────
Quit
```

### Tray Tooltip
```
Idle:        "FluxSync — Ready"
Transferring: "Syncing... 85% at 120 MB/s"
Paused:       "FluxSync — Paused (42%)"
```

### Main Window Screens
```
HomeScreen         — Discovery hub, drop zone, device list
TransferScreen     — The Cockpit: speed gauge, splitter bar, file list
ConsentScreen      — Incoming file approval (floating overlay or modal)
PairingScreen      — PIN entry (4 large digits, monospaced)
HistoryScreen      — Transfer history log (see Section 19)
SettingsScreen     — The Engine Room
```

### Drag-and-Drop (Entire Window Surface)
```kotlin
Box(
    modifier = Modifier
        .fillMaxSize()
        .onExternalDrag { state ->
            when (state.dragData) {
                is DragData.FilesList -> {
                    val files = (state.dragData as DragData.FilesList)
                        .readFiles()
                        .map { File(URI(it)) }
                    viewModel.onFilesDropped(files)
                }
                else -> { /* ignore non-file drags */ }
            }
        }
)
```

### Consent Floating Window (Corner Overlay)
```kotlin
// Appears in bottom-right corner when app is minimized to tray
// Shows: sender name, file count, total size, [Accept] [Decline]
// If main window is open: shows as a modal dialog instead
Window(
    onCloseRequest = { viewModel.declineConsent() },
    state = WindowState(
        position = WindowPosition(Alignment.BottomEnd),
        size = DpSize(380.dp, 200.dp)
    ),
    alwaysOnTop = true,
    undecorated = false
) { ConsentOverlayContent() }
```

### Startup Registration
```
Windows: HKCU\Software\Microsoft\Windows\CurrentVersion\Run = "FluxSync" → app path
         (No elevation required — user-level registry key)
Linux:   $HOME/.config/autostart/fluxsync.desktop
         [Desktop Entry]
         Type=Application
         Name=FluxSync
         Exec=/path/to/fluxsync
         X-GNOME-Autostart-enabled=true
```

---

## 16. Desktop — Platform Setup (Windows & Linux)

### Windows

**Firewall Rules (Installer — REQUIRED or mDNS silently fails)**
```batch
netsh advfirewall firewall add rule name="FluxSync TCP In" protocol=TCP dir=in localport=5001 action=allow
netsh advfirewall firewall add rule name="FluxSync mDNS" protocol=UDP dir=in localport=5353 action=allow
```

**Onboarding "Auto-Configure Firewall" button** — executes these via `ProcessBuilder` with elevation prompt (UAC).

**ADB Driver**
- Cannot auto-install — requires signed driver
- Installer prompts: "Enable Developer Options → USB Debugging → Trust this PC"
- Provide link to Android docs if device not detected after 30 seconds

**Installer**: `jpackage` → `.msi` (enterprise) or `.exe` (consumer via WiX/NSIS)

### Linux

**udev Rule (REQUIRED for non-root ADB)**
```bash
# File: /etc/udev/rules.d/51-android.rules
SUBSYSTEM=="usb", ATTR{idVendor}=="*", MODE="0666", GROUP="plugdev"

# Apply:
udevadm control --reload-rules && udevadm trigger
```

**AppImage First-Launch Setup Dialog**
```
AppImage cannot run post-install scripts.
On first launch, if adb devices fails:
  → Show setup dialog with copy buttons for the exact commands
  → Offer "Auto-setup (requires admin)" button → pkexec elevation via polkit
  → Fallback: USB unavailable, Wi-Fi still works, persistent "Fix USB" banner
```

**GNOME Tray**
```kotlin
// Detect on startup
val desktop = System.getenv("XDG_CURRENT_DESKTOP") ?: System.getenv("DESKTOP_SESSION")
if (desktop?.contains("GNOME", ignoreCase = true) == true) {
    // Check if AppIndicator extension is installed
    // If not: show first-run warning with link to extension
    showGnomeTrayWarning()
}
```

**Installers**: `.deb` (jpackage native) + `.AppImage` (universal, self-contained)

---

## 17. Error Handling & Resilience Patterns

### Channel Failure Mid-Transfer
```
1. Socket write throws IOException in channelWorker coroutine
2. Channel marked DEGRADED — excluded from pull-based select race
3. Failed chunk sent to retrySlot (high-priority queue)
4. Another active channel picks it up immediately
5. Transfer continues — UI shows channel status change
6. No user intervention required
```

### OOM Prevention (Both Sides)
```
Sender:    FileStreamer → bounded Channel (64MB cap) → DRTLB
Receiver:  Socket reads → bounded Channel (64MB cap) → ChunkAssembler → disk

If disk write is slow:
  Assembly loop slows → inbound queue fills → socket reads suspend
  → TCP flow control activates → sender's DRTLB slows → FileStreamer suspends
  = Zero manual rate limiting. Zero OOM risk. Fully automatic.
```

### Checksum Mismatch & Retry
```
1. CRC32 mismatch in ChunkAssembler → chunk index added to failedChunks set
2. After all chunks received: send RetryRequestPacket(failedChunkIndices)
3. Sender re-reads ONLY those chunks from disk (by offset), retransmits
4. Max retries: 3 (configurable). After 3: file marked FAILED, notify UI
5. Other files in the manifest continue transferring — one file failure != session failure
```

### Session Heartbeat
```kotlin
// Both sides reset this timer on every state-advancing packet
// If it fires: SESSION_CANCEL + full cleanup
val heartbeatWatchdog = scope.launch {
    delay(10_000)
    sendSessionCancel("Heartbeat timeout — no state packet in 10s")
    cleanupSession()
}
fun onStateAdvancingPacketReceived() {
    heartbeatWatchdog.cancel()
    // Restart it
}
```

### In-Memory Debug Log (Circular Buffer)
```kotlin
// NEVER write logs to disk during transfer — causes I/O contention with chunk writes
object DebugLog {
    private val buffer = ArrayDeque<LogEntry>(maxSize = 500)  // ~500KB max

    fun log(level: Level, tag: String, message: String) {
        if (buffer.size >= 500) buffer.removeFirst()
        buffer.addLast(LogEntry(System.currentTimeMillis(), level, tag, message))
    }

    // Only called from Settings screen or on user request — never during transfer
    fun exportToFile(file: File) {
        file.writeText(buffer.joinToString("\n") { it.format() })
    }
}
```

### UI Throttle Pattern — MANDATORY
```kotlin
// In every ViewModel that touches telemetry data
// NEVER forward raw engine events to Compose — causes recomposition storms

transferEngine.bytesSentFlow
    .sample(200)  // 5Hz — matches Splitter Bar update rate
    .conflate()   // Drop intermediate frames if UI thread is busy
    .onEach { bytes -> _uiState.update { it.copy(bytesSent = bytes) } }
    .launchIn(viewModelScope)

drtlb.telemetryFlow
    .sample(200)
    .conflate()
    .onEach { telemetry -> _uiState.update { it.copy(channelStats = telemetry) } }
    .launchIn(viewModelScope)
```

---

## 18. UI/UX — Full Specification

### Color Language
| Color | Meaning | Usage |
|-------|---------|-------|
| Blue | Wi-Fi channel | Splitter bar, device badge, channel chip |
| Amber | USB/ADB channel | Splitter bar, device badge, channel chip |
| Grey | Degraded / recovering | Dead channel slot in Splitter Bar |
| Green | Healthy / complete | Transfer complete state |
| Red | Error / failed | Failed file, session cancelled |

### Screen 1 — Onboarding (Android)
```
1. Welcome screen — "Need to share a movie? Lemme FluxSync it to you!"
2. Drop Zone folder picker — SAF DocumentTree picker
   Explain: "Required for background writing without system-wide file access"
3. Battery optimization — FULL SCREEN prompt
   "Your phone's battery saver will kill high-speed transfers. Tap to exempt FluxSync."
   → Deep link to Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
4. Notification permission — request POST_NOTIFICATIONS
```

### Screen 1 — Onboarding (Desktop)
```
1. Welcome — "FluxSync for Desktop is ready."
2. Download path — input field + Browse button
3. Firewall check — "Auto-Configure Firewall" button (runs netsh on Windows)
   Shows success/failure result inline
```

### Screen 2 — Home / Discovery Hub
```
Android:
  Prominent FAB or card: "Send Files"
  Peer list below — DeviceCards
  Footer: "Device not appearing? [Use Manual IP] [Show QR Code]"

Desktop:
  Center: Dashed drop zone — "Drop files here to send"
  On hover: "Ready to Sync" glow animation
  Peer list: DeviceCards with connection badges
  Footer: "Device not appearing? [Use Manual IP] [Show QR Code]"
```

**DeviceCard badges:**
```
[WiFi] Blue chip     — Peer seen on mDNS
[USB]  Amber chip    — ADB tunnel active
[★]    Star badge    — Cert in trust store (no PIN needed)
```

### Screen 3 — Active Transfer: The Cockpit

```
┌─────────────────────────────────────────┐
│  [Device Name]  [WiFi] [USB]            │
│                                         │
│  ████████████████████  142 MB/s         │  ← Aggregate speed gauge (large typography)
│                                         │
│  ╔══════════════════════════════════╗   │
│  ║██████████████████░░░░░░░░░░░░░░░ ║   │  ← Splitter Bar
│  ║    Wi-Fi 71%     ║   USB 29%     ║   │    Blue = Wi-Fi, Amber = USB, Grey = degraded
│  ╚══════════════════════════════════╝   │
│                                         │
│  [WiFi chip: 45ms · -62dBm]             │  ← Tappable channel detail chips
│  [USB chip:  1ms  · USB 3.0]            │    Shows: latency, signal/bus, buffer fill %
│                                         │
│  ETA: 1m 23s                            │
│                                         │
│  ┌─────────────────────────────────┐    │
│  │ vacation_4k.mp4      ████ 72%   │    │  ← Scrollable file list
│  │ photos_backup.zip    ██   38%   │    │    Resumability badge if .fluxpart exists
│  │ project_files.tar    ░    6%    │    │
│  └─────────────────────────────────┘    │
│                                         │
│  [Pause / Resume]        [Cancel]       │
└─────────────────────────────────────────┘
```

**Splitter Bar behavior:**

- Width of Blue vs. Amber segment = live DRTLB telemetry weight fractions
- Updates at 5Hz (200ms sample)
- If a channel dies mid-transfer: its segment briefly turns Grey (recovering), then shrinks as the surviving channel absorbs load — animated, not a hard snap

### Screen 4 — Pairing (PIN Dialog)

```
┌─────────────────┐
│   Enter PIN     │
│                 │
│  ┌─┐ ┌─┐ ┌─┐ ┌─┐  │  ← 4 massive digits, monospaced font
│  │4│ │7│ │2│ │_│  │
│  └─┘ └─┘ └─┘ └─┘  │
│                 │
│  [1][2][3]      │  ← Keypad (Android BottomSheet or Desktop dialog)
│  [4][5][6]      │
│  [7][8][9]      │
│     [0]         │
└─────────────────┘
```

### Screen 5 — AWAITING_CONSENT (Receiver)

**Android (foreground):** BottomSheet sliding up from bottom
```
"Pixel 8 wants to send:"
"vacation_4k.mp4 · project_files.tar · +11 more (4.2 GB)"
[DECLINE]              [ACCEPT]
```

**Android (background):** Full-screen intent notification (incoming call style)
```
NOTIFICATION: "Incoming: Pixel 8 is sending 13 files (4.2 GB)"
Actions: [ACCEPT] [DECLINE]
```

**Desktop (main window open):** Modal dialog, blurred background
```
"Pixel 8 is sending 'Vacation_4K.mp4' and 12 others (4.2 GB)"
[Decline]              [Accept]
```

**Desktop (minimized):** Floating window, bottom-right corner, always-on-top

**Sender (PENDING_CONSENT) — both platforms:**
```
"Waiting for [Device Name] to accept..."
[████████████████] animating pulse
Timeout: 60 seconds — countdown shown
[Cancel]
```

### Screen 6 — Settings: The Engine Room
```
STORAGE
  Download directory: [/path/to/folder]  [Change]
  Orphaned .fluxpart files: 3 files, 2.1 GB  [View & Clean]

NETWORK
  Routing mode: [● Opportunistic (Auto)]  [○ Force Wi-Fi Only]
  Port override: [5001]
  Interface (Desktop): [eth0 — 192.168.1.5 ▼]

DISCOVERY
  My fingerprint: AB:CD:EF:...  [Copy]
  Trusted devices: [Manage →]
    (shows list of trusted certs with Revoke option per device)

ADVANCED
  Hardware AES: [● Auto-detect]  [○ Force software]  (shows current cipher suite)
  Debug log: [View →]  [Export log file]
  Protocol version: 1
```

### Screen 7 — Background States

**Android Foreground Service Notification:**
```
FluxSync — Sending to Desktop-PC
[████████████████████░░░░░] 72%  142 MB/s
[Wi-Fi ████████████████░░]  [USB ████░░░░░░░]   ← RemoteViews Splitter Bar
```

**Desktop System Tray:**
```
Idle:        Static icon
Transferring: Pulsing Amber/Blue animation
Hover tooltip: "Syncing... 85% at 120 MB/s"
Menu:        [Open Hub] [Pause] [Exit]
```

---

## 19. Transfer History Screen

### What to Store (TransferHistoryEntry)

```kotlin
@Serializable
data class TransferHistoryEntry(
    val id: String,                          // UUID
    val sessionId: Long,
    val direction: TransferDirection,        // SENT or RECEIVED
    val peerDeviceName: String,
    val peerCertFingerprint: String,
    val files: List<HistoryFileEntry>,
    val totalSizeBytes: Long,
    val startedAtMs: Long,
    val completedAtMs: Long?,                // null if failed/cancelled
    val outcome: TransferOutcome,            // COMPLETED, FAILED, CANCELLED, PARTIAL
    val averageSpeedBytesPerSec: Long,
    val channelsUsed: Set<ChannelType>,      // {WIFI, USB_ADB}
    val failedFileCount: Int
)

@Serializable
data class HistoryFileEntry(
    val name: String,
    val sizeBytes: Long,
    val outcome: FileOutcome    // COMPLETED, FAILED, SKIPPED
)

enum class TransferDirection { SENT, RECEIVED }
enum class TransferOutcome { COMPLETED, FAILED, CANCELLED, PARTIAL }
enum class FileOutcome { COMPLETED, FAILED, SKIPPED }
```

### Storage
```kotlin
// Android: Room database — HistoryDao
// Desktop: JSON file at $CONFIG_DIR/fluxsync/history.json (max 500 entries, rotate oldest)

// Write a history entry on every session end — success or failure
fun onSessionEnd(session: Session, outcome: TransferOutcome) {
    val entry = TransferHistoryEntry(
        id = UUID.randomUUID().toString(),
        sessionId = session.id,
        direction = session.direction,
        // ... populate all fields
        completedAtMs = if (outcome == COMPLETED) System.currentTimeMillis() else null,
        outcome = outcome
    )
    historyRepository.insert(entry)
}
```

### History Screen UI

```
┌─────────────────────────────────────────┐
│  Transfer History              [Filter▼] │
│  [All] [Sent] [Received] [Failed]        │
│─────────────────────────────────────────│
│  ↑ Sent to Desktop-PC              ✓    │  Green checkmark = completed
│    13 files · 4.2 GB · 142 MB/s avg    │
│    Wi-Fi + USB  ·  Today 14:32         │
│─────────────────────────────────────────│
│  ↓ Received from Pixel 8          ✓    │
│    3 files · 820 MB · 98 MB/s avg      │
│    Wi-Fi only  ·  Today 11:07          │
│─────────────────────────────────────────│
│  ↑ Sent to Work Laptop            ✗    │  Red X = failed/cancelled
│    1 of 5 files · Transfer cancelled   │
│    Wi-Fi + USB  ·  Yesterday 22:14     │
│─────────────────────────────────────────│
│  [Load more...]                         │
└─────────────────────────────────────────┘
```

**Tapping a history entry expands to show:**
```
Per-file outcomes:
  ✓ vacation_4k.mp4       3.8 GB
  ✓ thumbnail.jpg         2.1 MB
  ✗ project.zip           412 MB  (failed after 3 retries)

Session details:
  Duration: 31 seconds
  Channels: Wi-Fi (71%) + USB (29%)
  Peak speed: 156 MB/s
  Device fingerprint: AB:CD:EF:...
```

**Actions on expanded entry:**

- Retry failed files (if peer is currently available)
- Copy session details to clipboard (for bug reports)
- Delete entry

### History Entry Write Triggers

```
SESSION_COMPLETE received   → outcome = COMPLETED
SESSION_CANCEL received     → outcome = CANCELLED
Max retries exceeded        → outcome = PARTIAL (some files failed)
Heartbeat timeout           → outcome = CANCELLED (reason: timeout)
All files FAILED            → outcome = FAILED
```

---

## 20. Known Pitfalls & Defensive Fixes

### Pitfall 1 — mDNS fails on client-isolated networks
```
Problem: Enterprise Wi-Fi, public hotspots block multicast UDP 5353.
         mDNS never resolves. Discovery appears broken.
Fix:     Always show manual IP + QR code fallback. Never let mDNS be the only path.
         QR format: fluxsync://<ip>:<port>/<certFingerprint>
```

### Pitfall 2 — ADB daemon version conflict (CRITICAL)
```
Problem: Bundled ADB version ≠ Android Studio's ADB version.
         Daemon kills/restarts in a loop → USB tunnel repeatedly severs.
Fix:     Check localhost:5037 before launching bundled ADB.
         If server alive → use system adb binary to talk to existing server.
         Log clearly when this fallback activates.
```

### Pitfall 3 — Port 5002 squatted on Android
```
Problem: Another app on Android listens on 5002. `adb reverse` succeeds silently.
         FluxSync connects to wrong app. USB channel appears dead with no explanation.
Fix:     Send 0x0F probe byte after connecting to 5002.
         Expect 0xF0 pong from FluxSync. Anything else = PORT_COLLISION.
         Surface collision clearly in UI. Never silently degrade to Wi-Fi only.
```

### Pitfall 4 — OEM kills foreground service (OnePlus, Xiaomi, etc.)
```
Problem: Aggressive OEM battery savers ignore foreground service contracts.
         Background TCP sockets killed when screen turns off.
Fix:     WakeLock (PARTIAL_WAKE_LOCK) + aggressive TCP keep-alive (5s/2s/3 probes)
         + pre-emptive battery optimization exemption demand during onboarding.
```

### Pitfall 5 — AppImage: no udev post-install scripts
```
Problem: Linux AppImage has no post-install hook. No udev rules → ADB invisible to app.
Fix:     First-launch detection → setup dialog with copy buttons.
         Optional: pkexec polkit elevation for auto-setup.
         Fallback: USB unavailable, Wi-Fi works, persistent "Fix USB" banner in Settings.
```

### Pitfall 6 — Receiver OOM: fast Wi-Fi + slow disk
```
Problem: Wi-Fi 6 delivers chunks faster than disk can absorb.
         Socket buffers grow unbounded → OOM crash on Android.
Fix:     Receiver-side bounded inbound queue (64MB, same tier as sender).
         Both Wi-Fi and USB socket loops feed the SAME queue.
         When queue fills → both suspend → TCP backpressure → sender slows. Automatic.
```

### Pitfall 7 — Race condition in completion bitmap
```
Problem: Wi-Fi and USB deliver chunks concurrently → both coroutines call assembler.
         Bitmap marked complete before disk write actually finishes → silent corruption.
Fix:     Kotlin Mutex around file writes. AtomicIntegerArray for bitmap.
         INVARIANT: CRC check → write → mark complete. This order cannot be changed.
```

### Pitfall 8 — TLS CPU bottleneck at high speed
```
Problem: Encrypting simultaneous Wi-Fi 6 + USB 3.0 throughput may max out mobile CPU.
Fix:     After handshake, check cipher suite. Assert TLS_AES_*_GCM_* (hardware AES).
         Warn if TLS_CHACHA20_POLY1305 (software — CPU bottleneck at high speeds).
         Profile on mid-range device (e.g. Snapdragon 778G) before over-optimizing.
         Advanced option: AEAD-only chunk stream (TLS for control, raw AES-GCM for data).
```

### Pitfall 9 — Stale mDNS TXT record after port change
```
Problem: Desktop binds to fallback port 5003, re-registers mDNS, but Android resolves
         stale cached service info and connects to 5001 (nothing there).
Fix:     After re-registering mDNS with new port, delay(500) before accepting connections.
         Android must call NsdManager.resolveService() with fresh request, not cache.
         TXT record must include "port=<actual>" — Android reads this field.
```

### Pitfall 10 — Multi-device Desktop: which Android to use?
```
Problem: ADB manager supports multiple Android serials, but UI doesn't model this.
         App silently picks the first device → user sends to wrong phone.
Fix:     If >1 device detected, require explicit device selection before any transfer.
         Never silently pick the first device in the list.
```

### Pitfall 11 — Session state divergence hangs forever
```
Problem: Control packet drops → one side waits for state transition that never comes.
         Session hangs indefinitely, WakeLock held, partial file on disk.
Fix:     10-second heartbeat watchdog. Reset on every state-advancing packet.
         On timeout: SESSION_CANCEL + full cleanup (sockets, WakeLock, .fluxpart flush).
```

### Pitfall 12 — GNOME has no system tray
```
Problem: GNOME removes native system tray. App appears to launch but no icon appears.
Fix:     Detect XDG_CURRENT_DESKTOP=GNOME on startup.
         Show first-run warning: "Install AppIndicator extension for tray support" + link.
```

### Pitfall 13 — lastModified alone is insufficient for resume validation
```
Problem: lastModified has 1-second resolution on most filesystems (FAT32 = 2 seconds).
         File re-saved within the same second passes the check silently.
Fix:     Always check BOTH file size AND lastModified.
         Also validate CRC32 of chunk 0 (cheap — reads only first chunkSize bytes).
         If any of the three checks fail → abort resume, start fresh.
```

### Pitfall 14 — Consent notification actions collapsed on OEM skins
```
Problem: On some API 33+ OEM builds, notification action buttons collapse behind a chevron.
         User may not see [ACCEPT] and [DECLINE] without expanding the notification.
Fix:     Use fullScreenIntent (incoming-call style) for consent notifications.
         This guarantees visibility even on locked screen.
         Requires USE_FULL_SCREEN_INTENT permission (explicit grant needed on API 34+).
```

---

## 21. Port & Network Strategy

```
TCP 5001      Primary Wi-Fi channel + all control packets
TCP 5002      ADB reverse tunnel (USB channel) — Android localhost only
UDP 5353      mDNS multicast
TCP 5037      ADB daemon (used only to detect running server — not used by FluxSync)
Range 5001–5099  Desktop fallback if 5001 occupied
```

### mDNS TXT Record Fields
```
protocolVersion=<int>          Protocol compatibility check
certFingerprint=<SHA-256 hex>  Pre-validate before connecting
port=<actual bound port>       CRITICAL — Android reads this for fallback port detection
```

### Channel Architecture from Android's Perspective
Both transport channels look identical from Android: TCP sockets. The physical transport (Wi-Fi radio vs USB cable) is fully abstracted by the OS and ADB daemon. The DRTLB doesn't need to know the physical medium — it just sees throughput numbers.

---

## 22. Vibe Coding Prompts Cheatsheet

> Copy the opening context for each component. For best results, paste Section 1 alongside the relevant prompt.

---

### FileStreamer
```
Build a Kotlin FileStreamer class (KMP-compatible, targets Android + JVM Desktop).
It reads a file in dynamically-sized chunks (64KB / 256KB / 512KB / 1MB — passed as parameter)
using java.nio.channels.FileChannel and ByteBuffer.allocateDirect().
It emits each chunk as a ChunkPacket (sessionId, fileId, chunkIndex, offset, payloadLength,
CRC32 checksum, payload) into a bounded Kotlin Channel.
Channel capacity = 64MB / chunkSizeBytes (flat memory cap regardless of chunk size).
The coroutine MUST suspend automatically when the channel is full — this IS the backpressure.
Never load the whole file into memory. Always read by absolute offset, not sequential stream.
```

### DRTLB (Pull-Based)
```
Build a Kotlin DRTLB (Dynamic Real-Time Load Balancer) using a pull-based select{} race.
Each active NetworkChannel runs its own channelWorker coroutine that uses kotlinx.coroutines
select{} to pull from two sources: a high-priority retrySlot Channel<ChunkPacket> and
the main chunkSource Channel<ChunkPacket>. retrySlot always wins the race.
On IOException: mark channel DEGRADED, send the failed chunk to retrySlot (not back to main queue),
stop the channelWorker for that channel.
Separate telemetry coroutine (NOT the transfer engine): collects measuredThroughput per channel
and emits ChannelTelemetry via a StateFlow at 5Hz. If telemetry coroutine dies, transfer
must continue at full speed.
"Force Wi-Fi Only" mode is a registration GATE — USB channels are never added to the pool,
not just set to zero weight.
```

### ChunkAssembler
```
Build a Kotlin ChunkAssembler that:
- Receives ChunkPackets from a bounded Channel (capacity = 64MB / chunkSizeBytes).
  Both Wi-Fi and USB socket read loops feed this SAME queue — single consumer loop.
- Pre-populates the completion bitmap from a FluxPartState if resuming (skip completed chunks).
- INVARIANT ORDER: CRC32 check FIRST → write to disk under Kotlin Mutex → mark bit
  in AtomicIntegerArray ONLY after write confirms. This order cannot change.
- Uses RandomAccessFile.seek(chunk.offset) for out-of-order writes.
- After each successful write: triggers FluxPartDebouncer with 2-second debounce.
- Tracks failedChunks in ConcurrentHashSet. After all chunks received: if failures exist,
  triggers retry callback; after 3 retry rounds max, marks file FAILED.
- On completion: triggers atomic rename of .fluxdownload to final filename, deletes .fluxpart.
```

### ADB Lifecycle Manager (Desktop)
```
Build a Kotlin ADB Lifecycle Manager for JVM Desktop using coroutines.
Before launching the bundled ADB binary, check if localhost:5037 is reachable.
If yes: use the system PATH adb binary to avoid ADB daemon version conflicts with Android Studio.
If no: use the bundled platform-specific binary (windows/adb.exe or linux/adb).
Poll "adb devices" every 2 seconds. Track devices in a Map<Serial, DeviceRecord>.
On new authorized device: run "adb -s <serial> reverse tcp:5002 tcp:5002",
then register UsbAdbChannel with DRTLB (ONLY if "Force Wi-Fi Only" setting is false).
On unauthorized device: set state to UNAUTHORISED, show UI banner, retry every 3s for 30s.
On disconnect: run "adb -s <serial> reverse --remove tcp:5002", notify DRTLB.
If adb binary not found: disable USB channel, show Settings callout, log error.
Support multiple simultaneous device serials. Never auto-select a device if multiple are connected.
```

### mDNS Discovery (Desktop)
```
Build a Kotlin mDNS discovery module for JVM Desktop using JmDNS.
Bind JmDNS to the interface with the default gateway (not first interface in list)
to handle developer machines with multiple NICs (VPN, WSL, Ethernet + Wi-Fi).
Register _fluxsync._tcp.local. with TXT record fields: protocolVersion, certFingerprint, port.
Listen for Android devices advertising _fluxsync._tcp. On serviceResolved:
read the "port" TXT field (not just event.info.port) for fallback port support.
If port 5001 is unavailable, iterate 5001–5099, bind to first free port,
unregister old service, re-register with new port, delay(500) before accepting connections.
Expose StateFlow<List<DiscoveredDevice>> for the UI layer.
```

### TOFU Security Module
```
Build a Kotlin TOFU security module (KMP-compatible, Android + JVM Desktop).
On first launch: generate self-signed X.509 cert (Android Keystore on Android,
file-based at $CONFIG_DIR on Desktop). Derive SHA-256 fingerprint.
TLS 1.3 handshake: both sides initially accept unverified self-signed certs.
CORRECT SEQUENCE (do not deviate): TLS first → connection encrypted →
Server generates 4-digit PIN and displays it → Client user enters PIN →
Client sends PIN over encrypted channel → Server verifies.
On success: persist peer cert fingerprint to trust store.
On subsequent connections to known fingerprint: skip PIN, proceed to AWAITING_CONSENT.
After handshake: check cipher suite. If TLS_CHACHA20_POLY1305 detected, emit a warning
(software cipher — potential CPU bottleneck). Prefer TLS_AES_*_GCM_* (hardware AES).
```

### Session State Machine
```
Build a Kotlin session state machine used by both sender and receiver sides.
States: IDLE, CONNECTING, HANDSHAKING, PAIRING, AWAITING_CONSENT (receiver),
PENDING_CONSENT (sender, 60-second timeout), TRANSFERRING, RETRYING, COMPLETED, FAILED, CANCELLED.
Both sides track state independently. State divergence = immediate SESSION_CANCEL + full cleanup.
Heartbeat watchdog: 10-second timeout that resets on every state-advancing packet.
On timeout: send SESSION_CANCEL, close all sockets, release WakeLock, flush .fluxpart,
write TransferHistoryEntry with outcome=CANCELLED.
On COMPLETED: flush .fluxpart, atomic rename .fluxdownload to final filename,
write TransferHistoryEntry with outcome=COMPLETED, release WakeLock.
```

### Transfer ViewModel (Shared)
```
Build a Kotlin ViewModel for the Transfer/Cockpit screen (usable on both Android and Desktop).
Collect from TransferEngine: bytesSent, totalBytes, per-file progress, DRTLB channel telemetry.
MANDATORY: apply .sample(200).conflate() to ALL telemetry flows before updating UI state.
This is non-negotiable — raw engine events will cause recomposition storms.
Expose a single StateFlow<TransferUiState> containing:
  - aggregateSpeedMbs: Float
  - etaSeconds: Int
  - overallProgressFraction: Float
  - fileEntries: List<FileUiEntry> (name, progress, outcome, hasFluxPart)
  - channelStats: List<ChannelTelemetry> (id, weightFraction, throughputMbs, latencyMs, bufferFillPercent)
  - sessionState: SessionState
The Splitter Bar's Blue/Amber widths are derived from channelStats[WIFI].weightFraction
and channelStats[USB].weightFraction directly in the UI layer, not the ViewModel.
```

### Android ForegroundService
```
Build an Android TransferForegroundService in Kotlin.
Call startForeground() within onStartCommand (mandatory within 5 seconds of service creation).
Acquire PARTIAL_WAKE_LOCK with 30-minute max. Release in onDestroy().
Build a consent notification with fullScreenIntent (incoming-call style) and
[ACCEPT] [DECLINE] action buttons via PendingIntent. Requires USE_FULL_SCREEN_INTENT permission.
Build a transfer progress notification using RemoteViews that includes the Splitter Bar
(two horizontal segments weighted by wifiPercent / usbPercent — Blue for Wi-Fi, Amber for USB).
Configure all sockets: SO_KEEPALIVE=true, TCP_KEEPIDLE=5s, TCP_KEEPINTERVAL=2s, TCP_KEEPCOUNT=3.
Return START_STICKY.
```

### Desktop System Tray & Window
```
Build a Desktop entry point using java.awt.SystemTray and Compose Multiplatform.
System tray icon: static when idle, pulses Amber/Blue animation during active transfers.
Tray tooltip: "FluxSync — Ready" (idle), "Syncing... {pct}% at {speed} MB/s" (active).
Tray context menu: [Open FluxSync], [Pause] (active only), separator, [Quit].
Main window: Compose Multiplatform. Closing the window MINIMIZES to tray — never quits.
Global drag-and-drop: onExternalDrag on the entire window surface, handles DragData.FilesList.
Consent window: when a consent request arrives and main window is minimized,
open a small always-on-top Compose window positioned at Alignment.BottomEnd (380dp × 200dp)
showing sender name, file count, size, [Decline] [Accept] buttons.
If main window is open, show consent as a modal dialog instead.
```

### Transfer History
```
Build a TransferHistory module (KMP-compatible) with:
Data model: TransferHistoryEntry (id UUID, sessionId, direction SENT/RECEIVED, peerDeviceName,
peerCertFingerprint, files List<HistoryFileEntry>, totalSizeBytes, startedAtMs, completedAtMs,
outcome COMPLETED/FAILED/CANCELLED/PARTIAL, averageSpeedBytesPerSec, channelsUsed, failedFileCount).
Storage: Android → Room DAO. Desktop → JSON file, max 500 entries (rotate oldest).
Write a history entry on every session end regardless of outcome.
HistoryScreen UI: chronological list, filter chips [All][Sent][Received][Failed].
Each row shows: direction arrow, peer name, file count + total size + avg speed,
channels used (Wi-Fi/USB icons), timestamp, outcome icon (✓ green / ✗ red).
Tapping a row expands to: per-file outcomes, session duration, peak speed, device fingerprint.
Expanded actions: [Retry failed files] (if peer online), [Copy details], [Delete entry].
```

---

*FluxSync Master Reference | Android + Desktop Full Ecosystem*
*Incorporates: Original Blueprint · Desktop Blueprint · Enhancement Set 1 · Defensive Programming Review*
