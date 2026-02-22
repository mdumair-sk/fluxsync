# FluxSync — Master AI Coding Prompt Plan

---

## 🔒 MASTER COMMON CONTEXT
> **Attach this block to EVERY prompt session, before the specific prompt.**

```
=== FLUXSYNC MASTER CONTEXT ===

You are building FluxSync: a cross-platform (Android ↔ JVM Desktop) zero-copy file transfer ecosystem.
Two apps, one shared core. Every decision is governed by these absolute rules:

--- THE TWO GOLDEN RULES ---
1. Files are NEVER fully loaded into heap. 64KB–1MB chunks only, via FileChannel. Always.
2. Fail loudly, recover gracefully. No silent failures. Every degraded state has a name, a log entry, and a UI representation.

--- ARCHITECTURE ---
Module tree:
  :core:protocol            → KMP (Kotlin Multiplatform) — packet definitions, serialization
  :core:transfer-engine     → KMP — DRTLB, FileStreamer, ChunkAssembler
  :core:security            → KMP — TOFU cert gen, trust store
  :core:resumability        → KMP — .fluxpart read/write, debounce logic
  :android:data:network     → Android-specific socket/channel impl
  :android:data:storage     → Android-specific FileChannel + SAF/URI impl
  :android:service          → ForegroundService, WakeLock, RemoteViews
  :desktop:app              → Compose Multiplatform UI, system tray, entry point
  :desktop:data:adb         → ADB lifecycle manager (Desktop-only)
  :desktop:data:network     → JVM socket/channel impl
  :desktop:data:storage     → JVM FileChannel impl

--- KEY DEPENDENCIES ---
  kotlinx.coroutines        → All async — no raw threads
  kotlinx.serialization     → Control packet serialization
  JmDNS                     → Desktop mDNS (pure Java)
  Android NsdManager        → Android mDNS
  Compose Multiplatform     → Desktop UI
  Jetpack Compose + M3      → Android UI
  java.nio.channels.FileChannel → Shared on both platforms via KMP

--- WIRE FORMAT REFERENCE ---
ChunkPacket (binary, hand-rolled — Big-Endian):
  sessionId(8) | fileId(4) | chunkIndex(4) | offset(8) | payloadLength(4) | checksum(4/CRC32) | payload(N)

Control packets use @Serializable data classes (kotlinx.serialization).
Known control packets:
  HandshakePacket(protocolVersion, deviceName, certFingerprint, maxChunkSizeBytes, availableMemoryMb)
  FileManifest(sessionId, files: List<FileEntry>)
  FileEntry(fileId, name, sizeBytes, totalChunks, negotiatedChunkSizeBytes, resumeValidation?)
  ResumeValidation(fileId, expectedSizeBytes, lastModifiedEpochMs, firstChunkChecksum)
  RetryRequestPacket(sessionId, fileId, failedChunkIndices)
  ConsentRequestPacket(sessionId, manifest)
  ConsentResponsePacket(sessionId, accepted)
  SessionCompletePacket(sessionId)
  SessionCancelPacket(sessionId, reason)

--- CHUNK SIZE TIERS ---
  Small:   64KB  → queue cap 1024 → < 100MB files
  Normal:  256KB → queue cap 256  → 100MB–1GB files
  Big:     512KB → queue cap 128  → 1GB–5GB files
  Massive: 1MB   → queue cap 64   → > 5GB files
  Formula: queueCapacity = 64MB / chunkSizeBytes  (flat 64MB cap always)

--- INVARIANTS (NEVER BREAK) ---
  - ByteBuffer.allocateDirect() always — never allocate()
  - ChunkAssembler write order: CRC32 check → write under Mutex → mark AtomicIntegerArray → debounce .fluxpart
  - UI telemetry: .sample(200).conflate() on every flow before StateFlow update — no exceptions
  - DRTLB retrySlot always wins the select{} race over chunkSource
  - "Force Wi-Fi Only" is a registration GATE — USB channels never enter the pool
  - .fluxpart write is always debounced (2s) — never write on every chunk
  - Atomic rename (.fluxdownload → final name) on completion — never direct write to final name
  - Session state divergence = immediate SESSION_CANCEL + full cleanup

--- SESSION STATES ---
  Receiver: IDLE → CONNECTING → HANDSHAKING → [PAIRING] → AWAITING_CONSENT → TRANSFERRING → RETRYING → COMPLETED/FAILED/CANCELLED
  Sender:   IDLE → CONNECTING → HANDSHAKING → [PAIRING] → PENDING_CONSENT → TRANSFERRING → RETRYING → COMPLETED/FAILED/CANCELLED

--- PORTS ---
  TCP 5001     Primary Wi-Fi + all control packets
  TCP 5002     ADB reverse tunnel (USB channel)
  UDP 5353     mDNS multicast
  TCP 5037     ADB daemon detection only
  5001–5099    Desktop fallback range

--- CHANNEL STATES ---
  ACTIVE    → healthy, in select{} race
  DEGRADED  → IOException caught, excluded from race, chunks re-routed to retrySlot
  OFFLINE   → fully removed from pool

=== END MASTER CONTEXT ===
```

---

## Phase 1: Project Skeleton

### Prompt 1.1 — Gradle Multi-Module Project Setup

> **Target:** KMP + Android + JVM Desktop (all modules)
>
> **Task:** Create the root Gradle project structure for FluxSync.
>
> Set up the following Gradle modules with correct `build.gradle.kts` files (no source code yet — just scaffolding):
> - `:core:protocol` — Kotlin Multiplatform (Android + JVM targets), depends on `kotlinx-serialization`
> - `:core:transfer-engine` — KMP (Android + JVM), no external deps yet
> - `:core:security` — KMP (Android + JVM), no external deps yet
> - `:core:resumability` — KMP (Android + JVM), no external deps yet
> - `:android:data:network` — Android library, depends on `:core:protocol`, `:core:transfer-engine`
> - `:android:data:storage` — Android library, depends on `:core:protocol`
> - `:android:service` — Android library, depends on `:core:transfer-engine`, `:android:data:network`
> - `:app` (Android) — Android application, depends on all `:android:*` and `:core:*` modules
> - `:desktop:data:adb` — JVM library
> - `:desktop:data:network` — JVM library, depends on `:core:protocol`, `:core:transfer-engine`
> - `:desktop:data:storage` — JVM library, depends on `:core:protocol`
> - `:desktop:app` — JVM application, Compose Multiplatform, depends on all `:desktop:*` and `:core:*`
>
> **Requirements:**
> - Root `settings.gradle.kts` must include all modules.
> - Root `build.gradle.kts` must define shared version catalog (`libs.versions.toml`) with: Kotlin 2.x, `kotlinx-coroutines-core`, `kotlinx-serialization-json`, Compose Multiplatform, JmDNS 3.5.x, Material3 for Android.
> - `:core:*` modules must compile for both `androidTarget()` and `jvm()` KMP targets.
> - Android `compileSdk = 35`, `minSdk = 26`.
> - Desktop entry point module (`:desktop:app`) must use `compose.desktop.application` Gradle plugin.
> - No source files — just `build.gradle.kts` and `settings.gradle.kts` with correct dependency wiring.

---

### Prompt 1.2 — Android Manifest & Permissions

> **Target:** Android (`:app` module)
>
> **Task:** Create the `AndroidManifest.xml` for the `:app` module with all required permissions and service declarations for FluxSync.
>
> **Required permissions:**
> ```
> FOREGROUND_SERVICE
> FOREGROUND_SERVICE_DATA_SYNC
> WAKE_LOCK
> CHANGE_NETWORK_STATE
> ACCESS_WIFI_STATE
> CHANGE_WIFI_MULTICAST_STATE
> POST_NOTIFICATIONS
> USE_FULL_SCREEN_INTENT
> REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
> NEARBY_WIFI_DEVICES (android:usesPermissionFlags="neverForLocation") — API 33+
> ACCESS_FINE_LOCATION — API ≤ 32
> ```
>
> **Service declaration:**
> ```xml
> <service
>     android:name=".service.TransferForegroundService"
>     android:foregroundServiceType="dataSync"
>     android:exported="false" />
> ```
>
> **Also include:**
> - A `FileProvider` authority declaration for sharing `.fluxpart` info.
> - `android:usesCleartextTraffic="false"` (TLS only).
> - Application class reference: `android:name=".FluxSyncApp"`.

---

## Phase 2: Core Protocol (`:core:protocol` — KMP)

### Prompt 2.1 — ChunkPacket Data Class + Binary Serializer

> **Target:** KMP (`:core:protocol`)
>
> **Task:** Implement `ChunkPacket` as a plain Kotlin data class plus a hand-rolled binary serializer/deserializer. This is the hot-path data type for all file payload traffic.
>
> **Data class:**
> ```kotlin
> data class ChunkPacket(
>     val sessionId: Long,
>     val fileId: Int,
>     val chunkIndex: Int,
>     val offset: Long,
>     val payloadLength: Int,
>     val checksum: Int,   // CRC32 of payload only
>     val payload: ByteArray
> )
> ```
>
> **Wire format (Big-Endian):**
> ```
> sessionId(8) | fileId(4) | chunkIndex(4) | offset(8) | payloadLength(4) | checksum(4) | payload(N)
> ```
> Header = 32 bytes fixed.
>
> **Implement:**
> - `ChunkPacket.writeTo(buffer: ByteBuffer)` — writes header + payload into a pre-allocated `ByteBuffer.allocateDirect()`. Must NOT allocate a new buffer.
> - `ChunkPacket.readFrom(buffer: ByteBuffer): ChunkPacket` (companion object factory) — reads from a `ByteBuffer` positioned at the start of a packet.
> - `object ChunkPacketCodec` with `val HEADER_SIZE = 32`.
>
> **Constraints:**
> - `ByteBuffer.order(ByteOrder.BIG_ENDIAN)` always.
> - Zero allocation on the hot path — codec must operate on a passed-in `ByteBuffer`, never create its own.
> - `payload` in the data class is a `ByteArray`. The codec copies it out of the `ByteBuffer` using `buffer.get(byteArray)`.
> - Include a `CRC32Helper.compute(payload: ByteArray): Int` utility in the same file using `java.util.zip.CRC32`.
> - Write a unit test class `ChunkPacketCodecTest` verifying round-trip encode/decode for a sample packet.

---

### Prompt 2.2 — Control Packets (@Serializable)

> **Target:** KMP (`:core:protocol`)
>
> **Task:** Define all control packet data classes using `kotlinx.serialization`. These are used for infrequent session control messages (not the hot data path).
>
> **Implement all of the following in a single file `ControlPackets.kt`:**
> ```kotlin
> @Serializable data class HandshakePacket(
>     val protocolVersion: Int,
>     val deviceName: String,
>     val certFingerprint: String,
>     val maxChunkSizeBytes: Int,
>     val availableMemoryMb: Int
> )
> @Serializable data class ResumeValidation(
>     val fileId: String,
>     val expectedSizeBytes: Long,
>     val lastModifiedEpochMs: Long,
>     val firstChunkChecksum: Int
> )
> @Serializable data class FileEntry(
>     val fileId: Int,
>     val name: String,
>     val sizeBytes: Long,
>     val totalChunks: Int,
>     val negotiatedChunkSizeBytes: Int,
>     val resumeValidation: ResumeValidation? = null
> )
> @Serializable data class FileManifest(val sessionId: Long, val files: List<FileEntry>)
> @Serializable data class RetryRequestPacket(val sessionId: Long, val fileId: Int, val failedChunkIndices: List<Int>)
> @Serializable data class ConsentRequestPacket(val sessionId: Long, val manifest: FileManifest)
> @Serializable data class ConsentResponsePacket(val sessionId: Long, val accepted: Boolean)
> @Serializable data class SessionCompletePacket(val sessionId: Long)
> @Serializable data class SessionCancelPacket(val sessionId: Long, val reason: String)
> ```
>
> **Also implement `ControlPacketSerializer`:**
> - A wrapper enum `ControlPacketType` with entries for each type.
> - `fun encodeToBytes(packet: Any): ByteArray` — prepends a 1-byte type tag, then JSON bytes via `Json.encodeToString`.
> - `fun decodeFromBytes(bytes: ByteArray): Any` — reads type tag, decodes correct subtype.
> - Use `kotlinx.serialization.json.Json { ignoreUnknownKeys = true }`.

---

### Prompt 2.3 — Chunk Size Negotiation Logic

> **Target:** KMP (`:core:protocol`)
>
> **Task:** Implement the chunk size negotiation and queue capacity calculation as pure functions.
>
> **Implement in `ChunkSizeNegotiator.kt`:**
> ```kotlin
> object ChunkSizeNegotiator {
>     const val SMALL   = 64   * 1024       // < 100 MB
>     const val NORMAL  = 256  * 1024       // 100 MB – 1 GB
>     const val BIG     = 512  * 1024       // 1 GB – 5 GB
>     const val MASSIVE = 1024 * 1024       // > 5 GB
>     const val GLOBAL_BUFFER_CAP = 64 * 1024 * 1024  // 64MB flat
>
>     fun negotiate(fileSizeBytes: Long, peerMaxChunkBytes: Int): Int
>     fun queueCapacity(chunkSizeBytes: Int): Int  // = 64MB / chunkSizeBytes
>     fun tierName(chunkSizeBytes: Int): String    // "Small" / "Normal" / "Big" / "Massive"
> }
> ```
>
> **Rules:**
> - `negotiate()` returns `minOf(preferred, peerMaxChunkBytes)` — always defensive.
> - `queueCapacity()` must always return `GLOBAL_BUFFER_CAP / chunkSizeBytes`.
> - Write unit tests for all four size tier boundaries and the `minOf` peer-cap behavior.

---

### Prompt 2.4 — NetworkChannel Interface & ChannelTelemetry

> **Target:** KMP (`:core:protocol`)
>
> **Task:** Define the `NetworkChannel` interface and associated types that the DRTLB uses to abstract over Wi-Fi and USB transport channels.
>
> **Implement:**
> ```kotlin
> enum class ChannelType { WIFI, USB_ADB }
>
> enum class ChannelState { ACTIVE, DEGRADED, OFFLINE }
>
> interface NetworkChannel {
>     val id: String
>     val type: ChannelType
>     var state: ChannelState
>     val measuredThroughput: Long         // bytes/sec, rolling average
>     val lastLatencyMs: Long
>     val sendBufferFillFraction: Float    // 0.0–1.0
>
>     suspend fun send(chunk: ChunkPacket)
>     fun recordSuccess(bytesSent: Long)
>     fun close()
> }
>
> data class ChannelTelemetry(
>     val channelId: String,
>     val type: ChannelType,
>     val state: ChannelState,
>     val throughputBytesPerSec: Long,
>     val weightFraction: Float,           // This channel's share of total throughput
>     val bufferFillPercent: Float,
>     val latencyMs: Long
> )
> ```
>
> **Also implement `ThroughputTracker`:**
> - A small class that maintains a rolling 1-second byte count.
> - `fun record(bytes: Long)` — call after each successful send.
> - `val bytesPerSecond: Long` — computed from the rolling window.
> - Uses `System.currentTimeMillis()` — no coroutines needed here.

---

## Phase 3: Storage Engine (`:core:transfer-engine` — KMP)

### Prompt 3.1 — FileStreamer

> **Target:** KMP (`:core:transfer-engine`)
>
> **Dependencies (stubs provided here for context):**
> ```kotlin
> // From :core:protocol
> data class ChunkPacket(val sessionId: Long, val fileId: Int, val chunkIndex: Int,
>     val offset: Long, val payloadLength: Int, val checksum: Int, val payload: ByteArray)
> object CRC32Helper { fun compute(payload: ByteArray): Int }
> object ChunkSizeNegotiator { fun queueCapacity(chunkSizeBytes: Int): Int }
> ```
>
> **Task:** Implement `FileStreamer` — the zero-copy file reader.
>
> ```kotlin
> class FileStreamer(
>     private val file: java.io.File,
>     private val outChannel: kotlinx.coroutines.channels.SendChannel<ChunkPacket>,
>     private val chunkSizeBytes: Int,
>     private val sessionId: Long,
>     private val fileId: Int
> ) {
>     suspend fun stream()
>     // Reads from a specific chunk index onward — for retry support
>     suspend fun streamChunks(chunkIndices: List<Int>)
> }
> ```
>
> **Constraints (non-negotiable):**
> - `ByteBuffer.allocateDirect(chunkSizeBytes)` — allocated ONCE before the loop, reused every iteration. Never `ByteBuffer.allocate()`.
> - Use `FileChannel.open(file.toPath(), StandardOpenOption.READ)` in a `use {}` block.
> - Read by absolute offset: `fc.read(buffer, offset)` — not sequential `fc.read(buffer)`.
> - `outChannel.send(chunk)` — this SUSPENDS when the channel is full, providing automatic backpressure. This is intentional.
> - Channel capacity must be `ChunkSizeNegotiator.queueCapacity(chunkSizeBytes)` — NOT hardcoded.
> - `streamChunks(indices)` re-reads only the specified chunk indices (for retry), calculating `offset = index.toLong() * chunkSizeBytes`.
> - Include a `FileStreamerFactory` that creates both the `Channel<ChunkPacket>` with correct capacity and the `FileStreamer` together.

---

## Phase 4: Resumability Engine (`:core:resumability` — KMP)

### Prompt 4.1 — FluxPart Binary File Format

> **Target:** KMP (`:core:resumability`)
>
> **Task:** Implement the `.fluxpart` binary file format — the BitTorrent-style resume state tracker.
>
> **Binary layout:**
> ```
> magic(4)=0x464C5558 | version(2)=1 | sessionId(8) | fileId(4) | totalChunks(4) |
> chunkSizeBytes(4) | expectedSizeBytes(8) | lastModifiedMs(8) | firstChunkCRC32(4) |
> createdAtMs(8) | completionBitmap(ceil(totalChunks/8) bytes, bit-packed)
> ```
>
> **Implement:**
> ```kotlin
> data class FluxPartState(
>     val version: Int = 1,
>     val sessionId: Long,
>     val fileId: Int,
>     val totalChunks: Int,
>     val chunkSizeBytes: Int,
>     val expectedSizeBytes: Long,
>     val lastModifiedMs: Long,
>     val firstChunkCRC32: Int,
>     val createdAtMs: Long,
>     val completedChunks: Set<Int>   // Decoded from bit-packed bitmap
> )
>
> object FluxPartSerializer {
>     val MAGIC = 0x464C5558
>     fun write(state: FluxPartState, file: java.io.File)
>     fun read(file: java.io.File): FluxPartState   // Throws if magic bytes mismatch
>     fun isValid(file: java.io.File): Boolean      // Checks magic bytes only — cheap
> }
> ```
>
> **Constraints:**
> - All multi-byte integers Big-Endian via `ByteBuffer`.
> - Bitmap is bit-packed: chunk N → byte `N/8`, bit `N%8`.
> - `read()` must throw `IllegalStateException("Invalid .fluxpart magic")` if magic doesn't match.
> - Write a unit test with 100 chunks, mark even indices complete, serialize/deserialize, verify round-trip.

---

### Prompt 4.2 — FluxPartDebouncer

> **Target:** KMP (`:core:resumability`)
>
> **Dependencies:**
> ```kotlin
> data class FluxPartState(...)
> object FluxPartSerializer { fun write(state: FluxPartState, file: java.io.File) }
> ```
>
> **Task:** Implement `FluxPartDebouncer` — debounced persistence of the completion bitmap to protect flash storage.
>
> ```kotlin
> class FluxPartDebouncer(
>     private val fluxPartFile: java.io.File,
>     private val scope: CoroutineScope,
>     private val debounceMs: Long = 2000L
> ) {
>     fun scheduleWrite(state: FluxPartState)
>     suspend fun flush(state: FluxPartState)  // Immediate write, cancels pending debounce
>     fun cancel()                              // Cancel any pending write — use on cleanup
> }
> ```
>
> **Constraints:**
> - Each call to `scheduleWrite()` cancels the previous pending `Job` and schedules a new `delay(debounceMs)` coroutine.
> - `flush()` cancels pending job and writes immediately (no delay).
> - `cancel()` cancels pending job without writing — for when session is cancelled before completion.
> - Disk write happens in `Dispatchers.IO`.
> - Write a test verifying that 10 rapid `scheduleWrite()` calls result in exactly 1 disk write.

---

### Prompt 4.3 — Resume Validation Logic

> **Target:** KMP (`:core:resumability`)
>
> **Dependencies:**
> ```kotlin
> data class FluxPartState(val expectedSizeBytes: Long, val lastModifiedMs: Long,
>     val firstChunkCRC32: Int, val chunkSizeBytes: Int, ...)
> object CRC32Helper { fun compute(payload: ByteArray): Int }
> ```
>
> **Task:** Implement resume validation — verifies the source file hasn't changed since the `.fluxpart` was written.
>
> ```kotlin
> enum class ResumeValidationResult { OK, ABORT_FILE_CHANGED, ABORT_FLUXPART_CORRUPT }
>
> object ResumeValidator {
>     fun validate(fluxPart: FluxPartState, sourceFile: java.io.File): ResumeValidationResult
>     fun computeFirstChunkCRC(file: java.io.File, chunkSizeBytes: Int): Int
> }
> ```
>
> **Three-check sequence (all must pass — fail on first mismatch):**
> 1. `sourceFile.length() == fluxPart.expectedSizeBytes` — exact size match.
> 2. `sourceFile.lastModified() == fluxPart.lastModifiedMs` — timestamp match (1-second resolution, so pair with size check).
> 3. `computeFirstChunkCRC(sourceFile, fluxPart.chunkSizeBytes) == fluxPart.firstChunkCRC32` — reads only first `chunkSizeBytes` bytes.
>
> **Constraints:**
> - `computeFirstChunkCRC` must use `ByteBuffer.allocateDirect()` and `FileChannel` — never load entire file.
> - Any `IOException` during check 3 → return `ABORT_FLUXPART_CORRUPT`.
> - Orphan detection: provide `fun isOrphaned(fluxPart: FluxPartState, maxAgeMs: Long = 7 * 24 * 3600 * 1000L): Boolean` that checks `createdAtMs`.

---

## Phase 5: Transfer Engine (`:core:transfer-engine` — KMP)

### Prompt 5.1 — ChunkAssembler

> **Target:** KMP (`:core:transfer-engine`)
>
> **Dependencies:**
> ```kotlin
> data class ChunkPacket(val sessionId: Long, val fileId: Int, val chunkIndex: Int,
>     val offset: Long, val payloadLength: Int, val checksum: Int, val payload: ByteArray)
> object CRC32Helper { fun compute(payload: ByteArray): Int }
> data class FluxPartState(val completedChunks: Set<Int>, val totalChunks: Int, ...)
> class FluxPartDebouncer { fun scheduleWrite(state: FluxPartState); suspend fun flush(state: FluxPartState) }
> ```
>
> **Task:** Implement `ChunkAssembler` — the receiver-side chunk writer.
>
> ```kotlin
> sealed class WriteResult {
>     object Success : WriteResult()
>     object AlreadyComplete : WriteResult()
>     data class ChecksumFailure(val chunkIndex: Int) : WriteResult()
> }
>
> class ChunkAssembler(
>     private val targetFile: java.io.RandomAccessFile,
>     private val totalChunks: Int,
>     private val resumeState: FluxPartState?,
>     private val fluxPartDebouncer: FluxPartDebouncer,
>     private val onRetryRequired: (List<Int>) -> Unit,
>     private val onFileComplete: () -> Unit,
>     private val onFileFailed: (String) -> Unit
> ) {
>     suspend fun writeChunk(chunk: ChunkPacket): WriteResult
>     fun getFailedChunks(): Set<Int>
>     val isComplete: Boolean
> }
> ```
>
> **Invariants (non-negotiable — this order cannot change):**
> 1. If `completionBitmap.get(chunk.chunkIndex) == 1` → return `AlreadyComplete` immediately (resume skip).
> 2. CRC32 check FIRST (`CRC32Helper.compute(chunk.payload) != chunk.checksum`) → add to `failedChunks`, return `ChecksumFailure`. Do NOT acquire mutex.
> 3. `writeMutex.withLock { targetFile.seek(chunk.offset); targetFile.write(chunk.payload) }`.
> 4. `completionBitmap.set(chunk.chunkIndex, 1)` — ONLY after write returns.
> 5. `fluxPartDebouncer.scheduleWrite(currentState())`.
> 6. Call `checkCompletion()`.
>
> **Other constraints:**
> - `completionBitmap` is `java.util.concurrent.atomic.AtomicIntegerArray(totalChunks)`.
> - Pre-populate from `resumeState?.completedChunks` in init block.
> - `failedChunks` is `ConcurrentHashMap.newKeySet<Int>()`.
> - Max retries = 3. After 3 retry rounds → `onFileFailed()`.
> - `targetFile` must be pre-allocated to full size before assembly (caller's responsibility — document this).

---

### Prompt 5.2 — DRTLB (Dynamic Real-Time Load Balancer)

> **Target:** KMP (`:core:transfer-engine`)
>
> **Dependencies:**
> ```kotlin
> data class ChunkPacket(...)
> interface NetworkChannel {
>     val id: String; val type: ChannelType; var state: ChannelState
>     val measuredThroughput: Long; val lastLatencyMs: Long; val sendBufferFillFraction: Float
>     suspend fun send(chunk: ChunkPacket); fun recordSuccess(bytesSent: Long); fun close()
> }
> enum class ChannelState { ACTIVE, DEGRADED, OFFLINE }
> enum class ChannelType { WIFI, USB_ADB }
> data class ChannelTelemetry(val channelId: String, val type: ChannelType, val state: ChannelState,
>     val throughputBytesPerSec: Long, val weightFraction: Float, val bufferFillPercent: Float, val latencyMs: Long)
> ```
>
> **Task:** Implement `DRTLB` — the pull-based load balancer that distributes chunks across active channels.
>
> ```kotlin
> class DRTLB(
>     private val chunkSource: ReceiveChannel<ChunkPacket>,
>     private val retrySlot: Channel<ChunkPacket> = Channel(capacity = 64)
> ) {
>     private val _telemetryFlow = MutableStateFlow<List<ChannelTelemetry>>(emptyList())
>     val telemetryFlow: StateFlow<List<ChannelTelemetry>> = _telemetryFlow.asStateFlow()
>
>     fun registerChannel(channel: NetworkChannel)
>     fun removeChannel(channel: NetworkChannel)
>     fun onForceWifiOnlyChanged(enabled: Boolean)  // Removes all USB_ADB channels
>     suspend fun run()          // Launches all channelWorker coroutines
>     fun sendToRetry(chunk: ChunkPacket)  // Re-enqueue a failed chunk
> }
> ```
>
> **Core behavior:**
> - `run()` must use `coroutineScope { channels.filter { ACTIVE }.forEach { launch { channelWorker(it) } } }`.
> - Each `channelWorker` loop: `val chunk = select { retrySlot.onReceive { it }; chunkSource.onReceive { it } }` — **retrySlot always listed first** (higher priority).
> - On `IOException` in send: set `channel.state = DEGRADED`, call `retrySlot.send(chunk)` (re-queue for another channel), stop this worker.
> - `onForceWifiOnlyChanged(true)`: remove all `ChannelType.USB_ADB` channels via `channels.removeAll { it.type == USB_ADB }`.
>
> **Telemetry (separate coroutine, MUST NOT affect transfer):**
> - `telemetryLoop()` runs in its own `launch {}` inside `run()`. If it throws, transfer continues at full speed (use `try/catch` around the entire loop body).
> - Emits at 5Hz (`delay(200)`).
> - `weightFraction` = `channel.measuredThroughput.toFloat() / totalThroughput` (guard against divide-by-zero).
>
> **Constraints:**
> - `channels` list must be protected by a `Mutex` for registration/removal — these can happen from other coroutines.
> - Telemetry coroutine crash must NOT cancel the parent `coroutineScope`.

---

### Prompt 5.3 — ChunkReceiver (Inbound Queue + Socket Read Loops)

> **Target:** KMP (`:core:transfer-engine`)
>
> **Dependencies:**
> ```kotlin
> data class ChunkPacket(...)
> class ChunkAssembler { suspend fun writeChunk(chunk: ChunkPacket): WriteResult }
> object ChunkSizeNegotiator { fun queueCapacity(chunkSizeBytes: Int): Int }
> object ChunkPacketCodec { val HEADER_SIZE: Int; fun readFrom(buffer: ByteBuffer): ChunkPacket }
> ```
>
> **Task:** Implement `ChunkReceiver` — manages the bounded inbound queue and socket read loops.
>
> ```kotlin
> class ChunkReceiver(
>     private val assembler: ChunkAssembler,
>     private val chunkSizeBytes: Int
> ) {
>     // Capacity = 64MB / chunkSizeBytes — flat memory cap
>     private val inboundQueue: Channel<ChunkPacket>
>
>     // Call once per active network socket (Wi-Fi and USB each get their own coroutine)
>     suspend fun socketReadLoop(inputStream: java.io.InputStream)
>
>     // Single consumer — serializes all disk writes
>     suspend fun assemblyLoop()
>
>     fun close()
> }
> ```
>
> **Constraints:**
> - Both Wi-Fi and USB `socketReadLoop` coroutines feed the **same** `inboundQueue`.
> - `inboundQueue` capacity = `ChunkSizeNegotiator.queueCapacity(chunkSizeBytes)` — 64MB flat cap. When full, `socketReadLoop` suspends → TCP backpressure → sender slows. This is the entire OOM prevention mechanism.
> - `socketReadLoop` must: read exactly `HEADER_SIZE` bytes first, parse `payloadLength`, then read exactly `payloadLength` more bytes. Use a pre-allocated `ByteBuffer.allocateDirect(HEADER_SIZE + maxChunkSizeBytes)`.
> - `assemblyLoop()` is a single `for (chunk in inboundQueue)` loop — no concurrency inside it.
> - `close()` closes the `inboundQueue` channel.

---

### Prompt 5.4 — Session State Machine

> **Target:** KMP (`:core:transfer-engine`)
>
> **Dependencies:**
> ```kotlin
> data class SessionCancelPacket(val sessionId: Long, val reason: String)
> data class SessionCompletePacket(val sessionId: Long)
> ```
>
> **Task:** Implement `SessionStateMachine` — tracks session lifecycle and enforces the heartbeat watchdog.
>
> ```kotlin
> enum class SessionState {
>     IDLE, CONNECTING, HANDSHAKING, PAIRING, AWAITING_CONSENT, PENDING_CONSENT,
>     TRANSFERRING, RETRYING, COMPLETED, FAILED, CANCELLED
> }
>
> class SessionStateMachine(
>     private val sessionId: Long,
>     private val scope: CoroutineScope,
>     private val onCancel: suspend (reason: String) -> Unit,   // Send SESSION_CANCEL + cleanup
>     private val onComplete: suspend () -> Unit
> ) {
>     val stateFlow: StateFlow<SessionState>
>
>     fun transition(newState: SessionState)
>     fun onStateAdvancingPacketReceived()  // Resets heartbeat watchdog
>     suspend fun cancel(reason: String)
>     suspend fun complete()
> }
> ```
>
> **Constraints:**
> - `stateFlow` is a `MutableStateFlow<SessionState>` exposed as `StateFlow`.
> - Heartbeat watchdog: a coroutine that `delay(10_000)` then calls `onCancel("Heartbeat timeout")`. Must be **cancelled and restarted** on every call to `onStateAdvancingPacketReceived()`.
> - `transition()` to an invalid state (e.g., `IDLE` → `COMPLETED`) must throw `IllegalStateException` with a descriptive message. Define valid transitions explicitly as a map.
> - `cancel()` must: set state to `CANCELLED`, cancel watchdog, call `onCancel(reason)`.
> - `complete()` must: set state to `COMPLETED`, cancel watchdog, call `onComplete()`.
> - Write a test that verifies the watchdog fires after 10 seconds if no packet is received.

---

## Phase 6: Security Module (`:core:security` — KMP)

### Prompt 6.1 — Certificate Generation Interface (KMP Expect/Actual)

> **Target:** KMP (`:core:security`)
>
> **Task:** Define the KMP `expect`/`actual` interface for certificate generation and fingerprinting. The KMP `expect` class must be platform-agnostic; `actual` implementations come in later prompts.
>
> **Implement in `commonMain`:**
> ```kotlin
> data class DeviceCertificate(
>     val alias: String,
>     val sha256Fingerprint: String,   // Hex string, colon-separated: "AB:CD:EF:..."
>     val pemEncoded: String           // For TLS SSLContext construction
> )
>
> expect class CertificateManager {
>     fun getOrCreateCertificate(deviceName: String): DeviceCertificate
>     fun buildSslContext(cert: DeviceCertificate, acceptAllPeers: Boolean): javax.net.ssl.SSLContext
>     fun computeFingerprint(cert: java.security.cert.X509Certificate): String
> }
> ```
>
> Also define:
> ```kotlin
> data class TrustedDevice(
>     val fingerprint: String,
>     val deviceName: String,
>     val lastSeenMs: Long,
>     val trusted: Boolean = true
> )
>
> interface TrustStore {
>     fun isTrusted(fingerprint: String): Boolean
>     fun save(device: TrustedDevice)
>     fun getAll(): List<TrustedDevice>
>     fun revoke(fingerprint: String)
> }
> ```

---

### Prompt 6.2 — Android CertificateManager (actual)

> **Target:** Android (`:core:security` androidMain)
>
> **Dependencies:**
> ```kotlin
> expect class CertificateManager  // from Prompt 6.1
> data class DeviceCertificate(val alias: String, val sha256Fingerprint: String, val pemEncoded: String)
> ```
>
> **Task:** Implement `actual class CertificateManager` for Android using Android Keystore.
>
> **Requirements:**
> - Use `KeyPairGenerator` with `KeyProperties.KEY_ALGORITHM_RSA` and `KeyGenParameterSpec`.
> - Store the key in Android Keystore (provider = `"AndroidKeyStore"`).
> - Generate a self-signed X.509 cert with 10-year validity.
> - `getOrCreateCertificate()` checks Keystore for existing alias before generating.
> - `buildSslContext()` with `acceptAllPeers = true` uses a trust-all `X509TrustManager` (for initial TOFU handshake — peer is verified via PIN, not cert chain).
> - `computeFingerprint()` uses SHA-256 on `cert.encoded`, formatted as colon-separated hex pairs.
> - After TLS handshake: log the negotiated cipher suite. If it contains `"CHACHA20"` → call `onSoftwareCipherDetected()` callback (inject this as a lambda).

---

### Prompt 6.3 — Desktop CertificateManager (actual)

> **Target:** JVM Desktop (`:core:security` jvmMain)
>
> **Dependencies:**
> ```kotlin
> expect class CertificateManager
> data class DeviceCertificate(...)
> ```
>
> **Task:** Implement `actual class CertificateManager` for JVM Desktop using file-based storage.
>
> **Storage paths:**
> ```
> Windows: %APPDATA%\FluxSync\certs\device.p12
> Linux:   $HOME/.config/fluxsync/certs/device.p12
> ```
>
> **Requirements:**
> - Use `KeyPairGenerator` (RSA-2048) + Bouncy Castle or `sun.security.x509` for self-signed cert generation.
> - Store as PKCS12 keystore at the platform path.
> - `getOrCreateCertificate()` loads existing `.p12` if present, generates new one if not.
> - Same `buildSslContext()` trust-all behavior as Android for TOFU.
> - Same `computeFingerprint()` implementation.
> - Include `DesktopConfigPaths` utility: `fun getCertPath(): java.io.File` and `fun getTrustStorePath(): java.io.File` with correct platform-specific resolution.

---

### Prompt 6.4 — TrustStore Implementations

> **Target:** KMP (`:core:security`)
>
> **Dependencies:**
> ```kotlin
> interface TrustStore { fun isTrusted(fp: String): Boolean; fun save(device: TrustedDevice); ... }
> data class TrustedDevice(val fingerprint: String, val deviceName: String, val lastSeenMs: Long, val trusted: Boolean)
> ```
>
> **Task:** Implement two `TrustStore` backends.
>
> **1. `JsonFileTrustStore` (Desktop):**
> - Reads/writes `truststore.json` at the platform config dir.
> - JSON format: `{ "devices": [ { "fingerprint": "...", "deviceName": "...", "lastSeenMs": 0, "trusted": true } ] }`.
> - Use `kotlinx.serialization.json.Json`.
> - Thread-safe: all operations synchronized with a `ReentrantReadWriteLock`.
>
> **2. `InMemoryTrustStore` (for unit tests — KMP commonTest):**
> - Simple `HashMap`-backed implementation.
>
> **Note:** Android's `TrustStore` (using `EncryptedSharedPreferences`) is implemented in `:android:data:network` — leave that for the Android-specific phase.

---

### Prompt 6.5 — TOFU Pairing Flow Coordinator

> **Target:** KMP (`:core:security`)
>
> **Dependencies:**
> ```kotlin
> class CertificateManager { fun buildSslContext(...): SSLContext; fun computeFingerprint(...): String }
> interface TrustStore { fun isTrusted(fp: String): Boolean; fun save(device: TrustedDevice) }
> data class TrustedDevice(...)
> enum class SessionState { ... }
> ```
>
> **Task:** Implement `TofuPairingCoordinator` — orchestrates the full TOFU sequence.
>
> ```kotlin
> sealed class PairingResult {
>     data class Success(val peerFingerprint: String) : PairingResult()
>     object PinMismatch : PairingResult()
>     object Timeout : PairingResult()
>     data class Error(val cause: Exception) : PairingResult()
> }
>
> class TofuPairingCoordinator(
>     private val trustStore: TrustStore,
>     private val onDisplayPin: (pin: String) -> Unit,   // Server side: show PIN to user
>     private val onRequestPin: suspend () -> String,     // Client side: await user input
>     private val onSoftwareCipherWarning: () -> Unit
> ) {
>     // Server role: generate PIN, wait for client to send it back
>     suspend fun runServerPairing(sslSocket: javax.net.ssl.SSLSocket): PairingResult
>
>     // Client role: get PIN from user, send it to server
>     suspend fun runClientPairing(sslSocket: javax.net.ssl.SSLSocket): PairingResult
>
>     fun isKnownPeer(sslSocket: javax.net.ssl.SSLSocket): Boolean
> }
> ```
>
> **TOFU sequence (MUST NOT deviate):**
> 1. TLS handshake is already done (caller's responsibility). Connection is already encrypted.
> 2. Server generates a random 4-digit PIN (0000–9999), calls `onDisplayPin(pin)`.
> 3. Client calls `onRequestPin()` (suspends until user types PIN).
> 4. Client sends PIN as UTF-8 string over the encrypted channel.
> 5. Server reads PIN, compares. Mismatch → return `PinMismatch`.
> 6. On success: persist peer cert fingerprint via `trustStore.save()`.
> 7. After handshake: inspect `sslSocket.session.cipherSuite`. If contains `"CHACHA20"` → call `onSoftwareCipherWarning()`.
> - PIN timeout: 60 seconds on both sides (use `withTimeout`).

---

## Phase 7: Discovery Module

### Prompt 7.1 — Android mDNS Discovery (NsdManager)

> **Target:** Android (`:android:data:network`)
>
> **Task:** Implement `AndroidMdnsDiscovery` using `NsdManager`.
>
> ```kotlin
> data class DiscoveredDevice(
>     val deviceName: String,
>     val ipAddress: java.net.InetAddress,
>     val port: Int,
>     val certFingerprint: String?,
>     val protocolVersion: Int
> )
>
> class AndroidMdnsDiscovery(private val context: android.content.Context) {
>     val discoveredDevices: StateFlow<List<DiscoveredDevice>>
>
>     fun startDiscovery()
>     fun stopDiscovery()
>     fun registerSelf(deviceName: String, port: Int, certFingerprint: String)
>     fun unregisterSelf()
> }
> ```
>
> **Constraints:**
> - Service type: `"_fluxsync._tcp"`.
> - On `serviceResolved`: read `"port"` from TXT attributes — `NsdServiceInfo` may have a different `.port` value if fallback was used. Log both values.
> - `startDiscovery()` must call both `registerService()` AND `discoverServices()`.
> - TXT record must include: `protocolVersion`, `certFingerprint`, `port`.
> - Deduplicate `discoveredDevices` list by `certFingerprint` (or `deviceName` if fingerprint null).
> - Handle `NsdManager.FAILURE_ALREADY_ACTIVE` — stop and restart on failure.

---

### Prompt 7.2 — Android USB Tunnel Detector

> **Target:** Android (`:android:data:network`)
>
> **Dependencies:**
> ```kotlin
> interface NetworkChannel { ... }
> class DRTLB { fun registerChannel(ch: NetworkChannel); fun removeChannel(ch: NetworkChannel) }
> enum class ChannelState { ACTIVE, DEGRADED, OFFLINE }
> enum class TunnelStatus { ACTIVE, PORT_COLLISION, PORT_FREE_NO_TUNNEL }
> ```
>
> **Task:** Implement `UsbTunnelDetector` — polls for the ADB reverse tunnel on port 5002.
>
> ```kotlin
> class UsbTunnelDetector(
>     private val drtlb: DRTLB,
>     private val usbChannel: NetworkChannel,
>     private val onPortCollision: () -> Unit,
>     private val forceWifiOnly: () -> Boolean
> ) {
>     suspend fun pollLoop()   // Runs until cancelled
>
>     private fun isPortReachable(host: String, port: Int, timeoutMs: Int = 500): Boolean
>     private fun verifyTunnelBinding(port: Int): TunnelStatus
>     private fun isFluxSyncResponding(port: Int): Boolean
> }
> ```
>
> **Protocol — FluxSync tunnel handshake:**
> - Probe: write single byte `0x0F` to socket on port 5002.
> - Expected pong: `0xF0`. Any other response = `PORT_COLLISION`.
> - Socket timeout: 1000ms.
>
> **Poll loop behavior:**
> - `delay(1000)` between polls.
> - If `forceWifiOnly()` returns true: do nothing, skip all registration.
> - If port reachable AND `FluxSync responding` AND channel state != `ACTIVE` → `drtlb.registerChannel(usbChannel)`.
> - If port unreachable AND channel state == `ACTIVE` → `drtlb.removeChannel(usbChannel)`.
> - If port reachable but NOT `FluxSync responding` → call `onPortCollision()`, do NOT register.

---

### Prompt 7.3 — Desktop mDNS Discovery (JmDNS)

> **Target:** JVM Desktop (`:desktop:data:network`)
>
> **Task:** Implement `DesktopMdnsDiscovery` using JmDNS.
>
> ```kotlin
> class DesktopMdnsDiscovery(private val scope: CoroutineScope) {
>     val discoveredDevices: StateFlow<List<DiscoveredDevice>>
>
>     suspend fun start()
>     fun stop()
>     suspend fun bindAndAdvertise(
>         deviceName: String,
>         certFingerprint: String,
>         preferredPort: Int = 5001
>     ): Int   // Returns actual bound port
> }
> ```
>
> **Constraints:**
> - Must bind JmDNS to the interface with the **default gateway** (not `InetAddress.getLocalHost()`). Detect via `NetworkInterface.getNetworkInterfaces()`, find the one with a default route. This handles developer machines with VPN/WSL/multiple NICs.
> - TXT record fields: `protocolVersion`, `certFingerprint`, `port` (must be the actual bound port).
> - `serviceResolved`: read `"port"` from `event.info.getPropertyString("port")` — **not** `event.info.port` (stale mDNS cache workaround).
> - `bindAndAdvertise()` port fallback: iterate `preferredPort..5099`, bind to first available. On port change: `jmdns.unregisterService(old)`, `registerService(new)`, `delay(500)` (let mDNS propagate before accepting connections).
> - `serviceAdded()` must call `jmdns.requestServiceInfo(type, name, true)` — force fresh resolve.
> - On `stop()`: call `jmdns.close()` and cancel the scope.

---

## Phase 8: Android Platform — Network & Storage

### Prompt 8.1 — Android NetworkChannel Implementation (Wi-Fi)

> **Target:** Android (`:android:data:network`)
>
> **Dependencies:**
> ```kotlin
> interface NetworkChannel {
>     val id: String; val type: ChannelType; var state: ChannelState
>     val measuredThroughput: Long; val lastLatencyMs: Long; val sendBufferFillFraction: Float
>     suspend fun send(chunk: ChunkPacket); fun recordSuccess(bytesSent: Long); fun close()
> }
> class ThroughputTracker { fun record(bytes: Long); val bytesPerSecond: Long }
> object ChunkPacketCodec { fun writeTo(chunk: ChunkPacket, buffer: ByteBuffer) }
> ```
>
> **Task:** Implement `WifiNetworkChannel` for Android — wraps a `java.net.Socket`.
>
> ```kotlin
> class WifiNetworkChannel(
>     private val socket: java.net.Socket,
>     private val scope: CoroutineScope
> ) : NetworkChannel {
>     override val id: String
>     override val type: ChannelType = ChannelType.WIFI
>     override var state: ChannelState = ChannelState.ACTIVE
>     ...
> }
> ```
>
> **Socket configuration (call immediately after socket creation):**
> ```kotlin
> socket.setOption(StandardSocketOptions.SO_KEEPALIVE, true)
> socket.setOption(ExtendedSocketOptions.TCP_KEEPIDLE, 5)
> socket.setOption(ExtendedSocketOptions.TCP_KEEPINTERVAL, 2)
> socket.setOption(ExtendedSocketOptions.TCP_KEEPCOUNT, 3)
> ```
>
> **Constraints:**
> - `send()` uses a `Dispatchers.IO` coroutine to write. Pre-allocate a `ByteBuffer.allocateDirect(HEADER_SIZE + MAX_CHUNK_SIZE)` once in init.
> - `recordSuccess(bytes)` calls `throughputTracker.record(bytes)`.
> - `measuredThroughput` delegates to `throughputTracker.bytesPerSecond`.
> - `sendBufferFillFraction` is always `0f` for this implementation (TCP has its own buffer — we can't observe it directly; document this limitation).
> - Include a companion `fun configureSocket(socket: Socket)` static helper so the same config can be applied to USB channel too.

---

### Prompt 8.2 — Android Storage — SAF FileAccess

> **Target:** Android (`:android:data:storage`)
>
> **Task:** Implement `AndroidFileAccess` — wraps Android's SAF (Storage Access Framework) for writing received files.
>
> ```kotlin
> class AndroidFileAccess(private val context: android.content.Context) {
>     // Persists the user's chosen drop zone URI
>     fun saveDropZoneUri(uri: android.net.Uri)
>     fun getDropZoneUri(): android.net.Uri?
>
>     // Creates the .fluxdownload temp file and the .fluxpart sidecar in the drop zone
>     fun createTempFile(fileName: String, sizeBytes: Long): AndroidTempFile?
>
>     // Atomically renames .fluxdownload to final file name
>     fun promoteToFinal(tempFile: AndroidTempFile): Boolean
>
>     // Finds orphaned .fluxpart files older than maxAgeMs
>     fun findOrphanedFluxParts(maxAgeMs: Long): List<OrphanedTransfer>
> }
>
> data class AndroidTempFile(
>     val fluxDownloadUri: android.net.Uri,
>     val fluxPartFile: java.io.File,   // .fluxpart lives in app's internal cache dir
>     val randomAccessFile: java.io.RandomAccessFile
> )
> ```
>
> **Constraints:**
> - Use `contentResolver.takePersistableUriPermission()` with `FLAG_GRANT_READ + WRITE`.
> - `createTempFile()` must pre-allocate the `.fluxdownload` file to `sizeBytes` using `RandomAccessFile.setLength(sizeBytes)` — enables out-of-order offset writes.
> - Write a `.nomedia` file to the drop zone directory to prevent media scanners from indexing incomplete files.
> - `.fluxpart` files live in `context.cacheDir` (app-private, not in SAF), named `<fileName>.fluxpart`.

---

## Phase 9: Android Service & Notifications

### Prompt 9.1 — TransferForegroundService

> **Target:** Android (`:android:service`)
>
> **Dependencies:**
> ```kotlin
> // From :core:transfer-engine
> class DRTLB { val telemetryFlow: StateFlow<List<ChannelTelemetry>> }
> data class ChannelTelemetry(val type: ChannelType, val weightFraction: Float, val throughputBytesPerSec: Long, ...)
> enum class ChannelType { WIFI, USB_ADB }
> ```
>
> **Task:** Implement `TransferForegroundService`.
>
> **Requirements:**
> - `startForeground(NOTIFICATION_ID, buildTransferNotification())` must be called in `onStartCommand` — within 5 seconds of service creation.
> - `PARTIAL_WAKE_LOCK` with 30-minute max: `pm.newWakeLock(...).apply { acquire(30 * 60 * 1000L) }`.
> - Release WakeLock in `onDestroy()` with `if (::wakeLock.isInitialized && wakeLock.isHeld) wakeLock.release()`.
> - Return `START_STICKY` from `onStartCommand`.
> - Two notification channels: `TRANSFER_CHANNEL_ID` (ongoing progress) and `CONSENT_CHANNEL_ID` (high priority, heads-up).
>
> **Transfer progress notification (RemoteViews):**
> - Layout file `notification_transfer.xml` with: `TextView` for speed, two horizontal `View`s (`wifi_bar` and `usb_bar`) inside a horizontal `LinearLayout` with weights.
> - `buildTransferNotification(wifiPercent: Float, usbPercent: Float, speedMbs: Float)` updates these weights via `setInt(id, "setLayoutWeight", ...)`.
> - Blue color for Wi-Fi bar (`#3D85F0`), Amber for USB bar (`#F09D3D`).
>
> **Consent notification (fullScreenIntent):**
> - `buildConsentNotification(senderName: String, fileCount: Int, totalSizeFormatted: String)`.
> - `NotificationCompat.PRIORITY_HIGH` + `setFullScreenIntent(pendingIntent, true)`.
> - Action buttons: `[Accept]` and `[Decline]` via `PendingIntent.getBroadcast`.
>
> **Battery optimization:**
> - `requestBatteryOptimizationExemption(context)` helper function — checks `isIgnoringBatteryOptimizations`, opens `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` if not exempt.

---

## Phase 10: Desktop Platform

### Prompt 10.1 — Desktop ADB Lifecycle Manager

> **Target:** JVM Desktop (`:desktop:data:adb`)
>
> **Dependencies:**
> ```kotlin
> interface NetworkChannel { ... }
> class DRTLB { fun registerChannel(ch: NetworkChannel); fun removeChannel(ch: NetworkChannel) }
> fun NetworkChannel.configureSocket(socket: Socket)  // from Prompt 8.1
> ```
>
> **Task:** Implement `AdbLifecycleManager` — manages ADB binary, device polling, and tunnel setup.
>
> ```kotlin
> enum class AdbDeviceState { ABSENT, DETECTED, AUTHORISED, UNAUTHORISED, TUNNEL_ACTIVE, TUNNEL_TORN_DOWN }
>
> data class AdbDevice(val serial: String, var state: AdbDeviceState)
>
> class AdbLifecycleManager(
>     private val drtlb: DRTLB,
>     private val scope: CoroutineScope,
>     private val forceWifiOnly: () -> Boolean,
>     private val onUnauthorisedDevice: (serial: String) -> Unit,
>     private val onMultipleDevices: (serials: List<String>) -> Unit
> ) {
>     val devices: StateFlow<Map<String, AdbDevice>>
>
>     suspend fun start()
>     fun stop()
>
>     fun resolveAdbBinary(): java.io.File
>     private suspend fun devicePollingLoop()
>     private suspend fun onAuthorised(serial: String)
>     private suspend fun setupTunnel(serial: String)
>     private suspend fun tearDownTunnel(serial: String)
> }
> ```
>
> **ADB binary resolution (critical):**
> 1. Check if `localhost:5037` is reachable (500ms timeout).
> 2. If yes: find `adb` on system `PATH` → use it (avoids daemon version conflict).
> 3. If no: use bundled binary at `resources/adb/windows/adb.exe` or `resources/adb/linux/adb`.
> 4. Set executable bit on Linux: `file.setExecutable(true)`.
>
> **Polling loop:**
> - `delay(2000)` between polls.
> - Run `"$adbBinary devices"`, parse output into `Map<Serial, "device"|"unauthorized"|"offline">`.
> - `onAuthorised`: run `"adb -s $serial reverse tcp:5002 tcp:5002"`. Register `UsbNetworkChannel` with DRTLB ONLY if `!forceWifiOnly()`.
> - `onUnauthorised`: set state `UNAUTHORISED`, call `onUnauthorisedDevice(serial)`. Retry every 3s for 30s.
> - On disconnect: run `"adb -s $serial reverse --remove tcp:5002"`, call `drtlb.removeChannel()`.
> - If multiple authorized devices detected: call `onMultipleDevices()` — **never silently auto-select**.

---

### Prompt 10.2 — Desktop System Tray & Window Manager

> **Target:** JVM Desktop (`:desktop:app`)
>
> **Dependencies:**
> ```kotlin
> // ViewModel from Phase 11
> class TransferViewModel { val uiState: StateFlow<TransferUiState> }
> data class TransferUiState(val sessionState: SessionState, val aggregateSpeedMbs: Float, val overallProgressFraction: Float, ...)
> ```
>
> **Task:** Implement `DesktopTrayManager` and the main window controller.
>
> **Tray requirements:**
> - Use `java.awt.SystemTray` + `java.awt.TrayIcon`.
> - Idle tooltip: `"FluxSync — Ready"`.
> - Active tooltip: `"Syncing... ${pct}% at ${speed} MB/s"`.
> - Paused tooltip: `"FluxSync — Paused (${pct}%)"`.
> - Tray icon animation during transfer: pulse between Amber (`#F09D3D`) and Blue (`#3D85F0`) at 500ms interval — use `java.awt.image.BufferedImage` painted programmatically (16×16).
> - Context menu: `[Open FluxSync]`, `[Send File]`, separator, `[Pause]` (visible only during `TRANSFERRING`), separator, `[Quit]`.
>
> **Window management:**
> - Compose `application {}` entry point.
> - Main window: `onCloseRequest = { windowState.isMinimized = true }` — **never quit on close**.
> - GNOME detection: on startup check `System.getenv("XDG_CURRENT_DESKTOP")`. If GNOME, show first-run dialog linking to AppIndicator extension.
>
> **Consent window (floating overlay):**
> - When consent arrives and main window is minimized: open a new `Window` with `alwaysOnTop = true`, `position = WindowPosition(Alignment.BottomEnd)`, size `380.dp × 200.dp`.
> - If main window is open: show as a `Dialog` inside it instead.
> - `onCloseRequest` of consent window → `viewModel.declineConsent()`.
>
> **Global drag-and-drop:**
> - Wrap the main window content in a `Box(Modifier.fillMaxSize().onExternalDrag { ... })`.
> - Handle `DragData.FilesList` → `viewModel.onFilesDropped(files.map { File(URI(it)) })`.

---

### Prompt 10.3 — Desktop Platform Setup Utilities

> **Target:** JVM Desktop (`:desktop:app`)
>
> **Task:** Implement platform setup utilities for Windows and Linux first-run experience.
>
> ```kotlin
> object DesktopPlatformSetup {
>     fun isWindows(): Boolean
>     fun isLinux(): Boolean
>
>     // Windows: configure firewall rules via netsh
>     // Returns: stdout + stderr combined, and exit code
>     fun configureWindowsFirewall(): PlatformSetupResult
>
>     // Windows: register app in HKCU\...\Run (no elevation needed)
>     fun registerWindowsStartup(appPath: String): Boolean
>
>     // Linux: write $HOME/.config/autostart/fluxsync.desktop
>     fun registerLinuxStartup(appPath: String): Boolean
>
>     // Linux: check if udev rules exist for ADB
>     fun checkLinuxUdevRules(): Boolean
>
>     // Linux: attempt auto-setup via pkexec (polkit elevation)
>     fun setupLinuxUdevRules(): PlatformSetupResult
> }
>
> data class PlatformSetupResult(val success: Boolean, val output: String, val exitCode: Int)
> ```
>
> **Windows firewall commands:**
> ```
> netsh advfirewall firewall add rule name="FluxSync TCP In" protocol=TCP dir=in localport=5001 action=allow
> netsh advfirewall firewall add rule name="FluxSync mDNS" protocol=UDP dir=in localport=5353 action=allow
> ```
> Run via `ProcessBuilder` with a 10-second timeout. Capture stdout and stderr.
>
> **Linux udev rule:**
> ```
> SUBSYSTEM=="usb", ATTR{idVendor}=="*", MODE="0666", GROUP="plugdev"
> ```
> File: `/etc/udev/rules.d/51-android.rules`
> Auto-setup via: `pkexec sh -c "echo '...' > /etc/udev/rules.d/51-android.rules && udevadm control --reload-rules && udevadm trigger"`

---

## Phase 11: ViewModels

### Prompt 11.1 — TransferViewModel (Shared)

> **Target:** KMP (`:core:transfer-engine`) — usable on both Android and Desktop
>
> **Dependencies:**
> ```kotlin
> class DRTLB { val telemetryFlow: StateFlow<List<ChannelTelemetry>> }
> class SessionStateMachine { val stateFlow: StateFlow<SessionState> }
> data class ChannelTelemetry(val channelId: String, val type: ChannelType, val state: ChannelState,
>     val throughputBytesPerSec: Long, val weightFraction: Float, val bufferFillPercent: Float, val latencyMs: Long)
> enum class SessionState { IDLE, CONNECTING, HANDSHAKING, PAIRING, AWAITING_CONSENT, PENDING_CONSENT,
>     TRANSFERRING, RETRYING, COMPLETED, FAILED, CANCELLED }
> ```
>
> **Task:** Implement `TransferViewModel` — the single source of truth for the Transfer/Cockpit UI.
>
> ```kotlin
> data class FileUiEntry(
>     val fileId: Int,
>     val name: String,
>     val progressFraction: Float,
>     val outcome: FileOutcome?,   // null = in progress
>     val hasFluxPart: Boolean     // Show resume badge
> )
>
> enum class FileOutcome { COMPLETED, FAILED, PARTIAL }
>
> data class TransferUiState(
>     val sessionState: SessionState = SessionState.IDLE,
>     val aggregateSpeedMbs: Float = 0f,
>     val etaSeconds: Int = 0,
>     val overallProgressFraction: Float = 0f,
>     val fileEntries: List<FileUiEntry> = emptyList(),
>     val channelStats: List<ChannelTelemetry> = emptyList(),
>     val pendingConsentTimeoutSeconds: Int = 60   // Countdown for PENDING_CONSENT state
> )
>
> class TransferViewModel(
>     private val drtlb: DRTLB,
>     private val sessionMachine: SessionStateMachine,
>     private val scope: CoroutineScope
> ) {
>     val uiState: StateFlow<TransferUiState>
>
>     fun onFilesDropped(files: List<java.io.File>)
>     fun onPauseResume()
>     fun onCancel()
>     fun onConsentAccepted()
>     fun onConsentDeclined()
>     fun updateFileProgress(fileId: Int, bytesWritten: Long, totalBytes: Long)
>     fun markFileComplete(fileId: Int)
>     fun markFileFailed(fileId: Int)
> }
> ```
>
> **MANDATORY telemetry throttling (non-negotiable):**
> ```kotlin
> drtlb.telemetryFlow
>     .sample(200)   // 5Hz
>     .conflate()    // Drop intermediate if UI busy
>     .onEach { telemetry -> _uiState.update { it.copy(channelStats = telemetry) } }
>     .launchIn(scope)
> ```
> Apply `.sample(200).conflate()` to ALL flows before updating `_uiState`. No exceptions.
>
> **ETA calculation:** `etaSeconds = ((totalBytes - bytesSent) / speedBytesPerSec).toInt()` — clamp to 0 minimum.
>
> **NOTE:** The Splitter Bar's Blue/Amber widths are computed in the UI layer from `channelStats[WIFI].weightFraction` and `channelStats[USB].weightFraction` — NOT in this ViewModel.

---

### Prompt 11.2 — HomeViewModel

> **Target:** KMP (`:core:transfer-engine`) — usable on both platforms
>
> **Dependencies:**
> ```kotlin
> data class DiscoveredDevice(val deviceName: String, val ipAddress: InetAddress,
>     val port: Int, val certFingerprint: String?, val protocolVersion: Int)
> interface TrustStore { fun isTrusted(fingerprint: String): Boolean }
> ```
>
> **Task:** Implement `HomeViewModel` — manages the discovery hub screen.
>
> ```kotlin
> data class DeviceUiEntry(
>     val deviceName: String,
>     val ipAddress: String,
>     val port: Int,
>     val certFingerprint: String?,
>     val isTrusted: Boolean,          // In trust store — no PIN needed
>     val hasWifi: Boolean,
>     val hasUsb: Boolean              // ADB tunnel active for this device
> )
>
> data class HomeUiState(
>     val discoveredDevices: List<DeviceUiEntry> = emptyList(),
>     val isDiscovering: Boolean = false,
>     val manualIpError: String? = null
> )
>
> class HomeViewModel(
>     private val trustStore: TrustStore,
>     private val scope: CoroutineScope
> ) {
>     val uiState: StateFlow<HomeUiState>
>
>     fun onDiscoveredDevicesUpdated(devices: List<DiscoveredDevice>)
>     fun onUsbDeviceConnected(serial: String, ipAddress: String)
>     fun onUsbDeviceDisconnected(serial: String)
>     fun onManualIpSubmitted(ip: String, port: Int)
>     fun onDeviceSelected(entry: DeviceUiEntry)
> }
> ```
>
> **Constraints:**
> - `onManualIpSubmitted`: validate IP format (`InetAddress.getByName()` in try/catch), set `manualIpError` if invalid.
> - `onDiscoveredDevicesUpdated`: merge with USB device info, set `isTrusted` from `trustStore`.
> - `onDeviceSelected`: emit a one-shot `selectedDevice` event (use a `SharedFlow<DeviceUiEntry>` with `replay=0`).

---

## Phase 12: Transfer History

### Prompt 12.1 — TransferHistory Data Model & KMP Interface

> **Target:** KMP (`:core:transfer-engine`)
>
> **Task:** Define the transfer history data model and the platform-agnostic `TransferHistoryRepository` interface.
>
> ```kotlin
> enum class TransferDirection { SENT, RECEIVED }
> enum class TransferOutcome { COMPLETED, FAILED, CANCELLED, PARTIAL }
>
> @Serializable
> data class HistoryFileEntry(
>     val name: String,
>     val sizeBytes: Long,
>     val outcome: TransferOutcome
> )
>
> @Serializable
> data class TransferHistoryEntry(
>     val id: String = java.util.UUID.randomUUID().toString(),
>     val sessionId: Long,
>     val direction: TransferDirection,
>     val peerDeviceName: String,
>     val peerCertFingerprint: String,
>     val files: List<HistoryFileEntry>,
>     val totalSizeBytes: Long,
>     val startedAtMs: Long,
>     val completedAtMs: Long,
>     val outcome: TransferOutcome,
>     val averageSpeedBytesPerSec: Long,
>     val channelsUsed: List<ChannelType>,
>     val failedFileCount: Int
> )
>
> interface TransferHistoryRepository {
>     suspend fun insert(entry: TransferHistoryEntry)
>     suspend fun getAll(): List<TransferHistoryEntry>
>     suspend fun getFiltered(direction: TransferDirection): List<TransferHistoryEntry>
>     suspend fun delete(id: String)
>     suspend fun getById(id: String): TransferHistoryEntry?
> }
> ```
>
> Also implement `TransferHistoryManager` that triggers `insert()` on every session end regardless of outcome — wire it to the `SessionStateMachine`'s `onComplete` and `onCancel` callbacks.

---

### Prompt 12.2 — Desktop JSON History Repository

> **Target:** JVM Desktop (`:desktop:app`)
>
> **Dependencies:**
> ```kotlin
> interface TransferHistoryRepository { ... }
> data class TransferHistoryEntry(...)
> ```
>
> **Task:** Implement `JsonFileHistoryRepository` — stores history as a rotating JSON file on Desktop.
>
> ```kotlin
> class JsonFileHistoryRepository(
>     private val historyFile: java.io.File,   // $CONFIG_DIR/fluxsync/history.json
>     private val maxEntries: Int = 500
> ) : TransferHistoryRepository
> ```
>
> **Constraints:**
> - JSON format: `{ "entries": [ ... ] }`.
> - On `insert()`: if `entries.size >= maxEntries`, remove the oldest entry (by `startedAtMs`) before inserting new one.
> - All reads/writes on `Dispatchers.IO`.
> - Use `ReentrantReadWriteLock` — reads concurrent, writes exclusive.
> - Handle corrupt JSON gracefully: if `historyFile` cannot be parsed, log the error and start fresh (do not crash).

---

### Prompt 12.3 — HistoryViewModel

> **Target:** KMP
>
> **Dependencies:**
> ```kotlin
> interface TransferHistoryRepository { suspend fun getAll(): List<TransferHistoryEntry>; ... }
> data class TransferHistoryEntry(...)
> enum class TransferDirection { SENT, RECEIVED }
> enum class TransferOutcome { COMPLETED, FAILED, CANCELLED, PARTIAL }
> ```
>
> **Task:** Implement `HistoryViewModel`.
>
> ```kotlin
> enum class HistoryFilter { ALL, SENT, RECEIVED, FAILED }
>
> data class HistoryUiState(
>     val entries: List<TransferHistoryEntry> = emptyList(),
>     val activeFilter: HistoryFilter = HistoryFilter.ALL,
>     val expandedEntryId: String? = null,
>     val isLoading: Boolean = true
> )
>
> class HistoryViewModel(
>     private val repository: TransferHistoryRepository,
>     private val scope: CoroutineScope
> ) {
>     val uiState: StateFlow<HistoryUiState>
>
>     fun onFilterChanged(filter: HistoryFilter)
>     fun onEntryTapped(id: String)       // Toggle expand/collapse
>     fun onDeleteEntry(id: String)
>     fun onRetryFailed(entry: TransferHistoryEntry)  // Emits retry event via SharedFlow
>     fun onCopyDetails(entry: TransferHistoryEntry): String  // Returns formatted string for clipboard
>     fun refresh()
> }
> ```
>
> **`onCopyDetails` format:**
> ```
> FluxSync Transfer — [date]
> Direction: Sent / Received
> Peer: [deviceName] ([fingerprint])
> Files: [count] · [totalSize] · [outcome]
> Duration: [Xm Ys] · Peak: [speed] MB/s
> Channels: Wi-Fi [X%] + USB [Y%]
> ```

---

## Phase 13: Android UI Screens

### Prompt 13.1 — Android Onboarding Screen

> **Target:** Android (`:app`) — Jetpack Compose + Material 3
>
> **Task:** Implement the multi-step `OnboardingScreen` composable.
>
> **Steps:**
> 1. **Welcome**: title, tagline `"Need to share a movie? Lemme FluxSync it to you!"`, `[Get Started]` button.
> 2. **Drop Zone Picker**: explanation text, `[Choose Folder]` button using `ActivityResultContracts.OpenDocumentTree`. Show chosen path once selected. `[Continue]` becomes active.
> 3. **Battery Optimization**: full-screen yellow warning card. Text: `"Your phone's battery saver will kill high-speed transfers."` `[Exempt FluxSync]` button → `requestBatteryOptimizationExemption()`. Show checkmark if already exempt.
> 4. **Notifications**: request `POST_NOTIFICATIONS`. Show rationale. `[Allow]` and `[Skip]` options.
>
> **Constraints:**
> - Use a `HorizontalPager` (or manual step state) with step indicator dots.
> - Persist completion state in `DataStore` — never show onboarding twice.
> - Battery optimization step must use `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` deep link.
> - All text must use Material 3 Typography tokens — no hardcoded font sizes.

---

### Prompt 13.2 — Android Home / Discovery Screen

> **Target:** Android (`:app`) — Jetpack Compose + Material 3
>
> **Dependencies:**
> ```kotlin
> data class DeviceUiEntry(val deviceName: String, val ipAddress: String, val port: Int,
>     val certFingerprint: String?, val isTrusted: Boolean, val hasWifi: Boolean, val hasUsb: Boolean)
> data class HomeUiState(val discoveredDevices: List<DeviceUiEntry>, val isDiscovering: Boolean, ...)
> class HomeViewModel { val uiState: StateFlow<HomeUiState>; fun onDeviceSelected(...); ... }
> ```
>
> **Task:** Implement `HomeScreen` composable.
>
> **Layout:**
> - Top: `"FluxSync"` title. FAB: `"Send Files"` (triggers file picker).
> - Peer list: `LazyColumn` of `DeviceCard` composables.
> - Footer: `"Device not appearing?"` row with `[Manual IP]` and `[Show QR Code]` text buttons.
>
> **`DeviceCard` composable:**
> - Device name (large), IP address (small, muted).
> - Badges: `[WiFi]` blue chip if `hasWifi`, `[USB]` amber chip if `hasUsb`, `[★]` star badge if `isTrusted`.
> - Tap → navigate to Transfer screen.
>
> **Manual IP dialog:** `AlertDialog` with a `TextField` for IP, port defaults to 5001.
>
> **QR Code:** Generate using `zxing-android-embedded` or `androidx.camera`. QR encodes `fluxsync://<ip>:<port>/<certFingerprint>`.
>
> **Color semantics:** Wi-Fi blue = `Color(0xFF3D85F0)`, USB amber = `Color(0xFFF09D3D)`.

---

### Prompt 13.3 — Android Cockpit / Transfer Screen

> **Target:** Android (`:app`) — Jetpack Compose + Material 3
>
> **Dependencies:**
> ```kotlin
> data class TransferUiState(val sessionState: SessionState, val aggregateSpeedMbs: Float,
>     val etaSeconds: Int, val overallProgressFraction: Float,
>     val fileEntries: List<FileUiEntry>, val channelStats: List<ChannelTelemetry>, ...)
> data class ChannelTelemetry(val type: ChannelType, val weightFraction: Float, val throughputBytesPerSec: Long, val latencyMs: Long, val bufferFillPercent: Float, ...)
> data class FileUiEntry(val fileId: Int, val name: String, val progressFraction: Float, val outcome: FileOutcome?, val hasFluxPart: Boolean)
> ```
>
> **Task:** Implement `CockpitScreen` composable — the main transfer view.
>
> **Components:**
>
> 1. **Speed Gauge**: Large typography (`48.sp+`) showing `aggregateSpeedMbs` with `"MB/s"` label. Animate number changes with `animateFloatAsState`.
>
> 2. **Splitter Bar**: A custom `Canvas` composable. Blue segment width = `channelStats.firstOrNull { it.type == WIFI }?.weightFraction ?: 0f`. Amber = USB fraction. Grey = degraded (animate transitions with `animateFloatAsState(animationSpec = tween(400))`). Show percentage text inside each segment if wide enough.
>
> 3. **Channel Detail Chips**: Horizontal row. Each chip shows: `[WiFi: 45ms · -62dBm]` or `[USB: 1ms · USB 3.0]`. Tappable for detail sheet.
>
> 4. **ETA**: `"ETA: ${formatEta(etaSeconds)}"`.
>
> 5. **File List**: `LazyColumn`. Each row: file name + linear progress bar + percentage. If `hasFluxPart` → small `"↺"` resume badge. If `outcome == FAILED` → red X icon.
>
> 6. **Bottom bar**: `[Pause / Resume]` and `[Cancel]` buttons.
>
> **Constraints:**
> - All animation specs: `tween(durationMillis = 400)`.
> - Splitter bar color transitions must animate smoothly when a channel degrades (grey), not snap.
> - Use `key(fileEntry.fileId)` in the file list for stable recomposition.

---

### Prompt 13.4 — Android Pairing (PIN) Screen

> **Target:** Android (`:app`) — Jetpack Compose + Material 3
>
> **Task:** Implement `PairingScreen` composable.
>
> **Layout:**
> - Title: `"Enter PIN"`.
> - 4 large digit boxes (monospaced, `48.sp+`), each showing one digit or `_` placeholder. Styled as outlined boxes.
> - Numeric keypad: 3×4 grid buttons (1–9, 0, backspace). Use `FilledTonalButton`.
> - Auto-submits when all 4 digits are entered.
>
> **State:**
> - `digits: List<Int?>` of length 4, managed in the composable's local state.
> - On submit: call `viewModel.onPinEntered(digits.joinToString(""))`.
> - On incorrect PIN: shake animation on the digit row (`animateFloatAsState` with spring), clear digits.
>
> **Server display variant:**
> - When this device IS the server: show `"Your PIN: XXXX"` in the same 4-box layout (read-only, non-interactive). No keypad shown. Include a `"Waiting for other device..."` subtitle.

---

### Prompt 13.5 — Android Consent (AWAITING_CONSENT) Screen

> **Target:** Android (`:app`) — Jetpack Compose + Material 3
>
> **Task:** Implement the incoming transfer consent UI — both BottomSheet and notification variants.
>
> **BottomSheet (app in foreground):**
> - `ModalBottomSheet` with: sender device name (title), file list summary (`"vacation_4k.mp4, project_files.tar, +11 more (4.2 GB)"`), two full-width buttons: `[DECLINE]` (outlined) and `[ACCEPT]` (filled, primary).
> - Swipe to dismiss = decline.
>
> **Sender's PENDING_CONSENT view (same screen, different state):**
> - Pulsing progress animation (animated `LinearProgressIndicator` in indeterminate mode).
> - `"Waiting for [Device Name] to accept..."`.
> - Countdown: `"Cancels in: ${pendingConsentTimeoutSeconds}s"` — update every second.
> - `[Cancel]` button.
>
> **Constraints:**
> - `ModalBottomSheet` must be shown from the current `NavBackStack` screen — do not navigate away.
> - The 60-second countdown must start when `sessionState == PENDING_CONSENT`. Use `LaunchedEffect(sessionState)`.

---

### Prompt 13.6 — Android Settings Screen

> **Target:** Android (`:app`) — Jetpack Compose + Material 3
>
> **Task:** Implement `SettingsScreen` composable.
>
> **Sections:**
>
> **STORAGE:**
> - Download directory path + `[Change]` button (opens SAF picker).
> - Orphaned `.fluxpart` files: count + total size + `[View & Clean]` button → navigates to orphan list.
>
> **NETWORK:**
> - Routing mode radio group: `[● Opportunistic (Auto)]` / `[○ Force Wi-Fi Only]`.
> - Port override text field (default `5001`).
> - Interface selector (Desktop only — skip on Android).
>
> **DISCOVERY:**
> - My fingerprint: formatted hex + `[Copy]` icon button.
> - Trusted devices: `[Manage →]` → navigates to `TrustedDevicesScreen`.
>
> **ADVANCED:**
> - Hardware AES chip: shows current cipher suite string.
> - Debug log: `[View →]` + `[Export]` — writes `DebugLog.exportToFile()` to a user-chosen location.
> - Protocol version label.
>
> **`TrustedDevicesScreen`:**
> - List of `TrustedDevice` entries: device name, fingerprint (truncated), last seen date.
> - Each row has `[Revoke]` action — removes from `TrustStore`.

---

### Prompt 13.7 — Android Transfer History Screen

> **Target:** Android (`:app`) — Jetpack Compose + Material 3
>
> **Dependencies:**
> ```kotlin
> data class HistoryUiState(val entries: List<TransferHistoryEntry>, val activeFilter: HistoryFilter, ...)
> class HistoryViewModel { val uiState: StateFlow<HistoryUiState>; fun onFilterChanged(...); ... }
> ```
>
> **Task:** Implement `HistoryScreen` composable.
>
> **Layout:**
> - Filter chips row: `[All] [Sent] [Received] [Failed]`.
> - `LazyColumn` of `HistoryRow` composables.
>
> **`HistoryRow` (collapsed):**
> - Direction arrow icon (↑ sent, ↓ received) + peer name.
> - File count + total size + avg speed on second line.
> - Channel icons (Wi-Fi / USB) + timestamp + outcome icon (✓ green / ✗ red).
> - Tap → expand.
>
> **`HistoryRow` (expanded):**
> - Per-file outcomes list: `✓` or `✗` + file name + size.
> - Session details: duration, peak speed, device fingerprint.
> - Action buttons: `[Retry failed files]` (if peer online), `[Copy details]`, `[Delete]`.
>
> **Constraints:**
> - Use `AnimatedVisibility` for expand/collapse transition.
> - `key(entry.id)` for stable recomposition.

---

## Phase 14: Desktop UI Screens

### Prompt 14.1 — Desktop Home / Discovery Screen

> **Target:** JVM Desktop (`:desktop:app`) — Compose Multiplatform
>
> **Dependencies:**
> ```kotlin
> data class HomeUiState(val discoveredDevices: List<DeviceUiEntry>, val isDiscovering: Boolean, ...)
> class HomeViewModel { ... }
> ```
>
> **Task:** Implement `DesktopHomeScreen` composable.
>
> **Layout:**
> - Center: large dashed-border `Box` drop zone — `"Drop files here to send"`. On hover: glow animation (`animateFloatAsState` opacity pulse). Handle `onExternalDrag` here AND on the outer window surface.
> - Right panel: `LazyColumn` of `DeviceCard`s (same badge design as Android).
> - Footer: `[Manual IP]` + `[Show QR Code]` buttons.
> - QR code display: render as a Canvas drawing from the ZXing `BitMatrix` API or a QR library.
>
> **DeviceCard:** same badge semantics as Android. On click → start transfer flow.
>
> **Note:** The outer drag-and-drop (entire window surface) is set up in the main window composition — this screen's drop zone is the visual affordance only.

---

### Prompt 14.2 — Desktop Cockpit / Transfer Screen

> **Target:** JVM Desktop (`:desktop:app`) — Compose Multiplatform
>
> **Task:** Implement `DesktopCockpitScreen` composable — reuses same state types as Android Cockpit.
>
> **Layout:** Same component hierarchy as `Prompt 13.3`, adapted for a wider desktop layout:
> - Speed gauge and Splitter Bar in a top card (full width).
> - Channel chips in a row below.
> - File list on the left 60% of the remaining space.
> - ETA + action buttons on the right 40%.
>
> **Splitter Bar:** Same `Canvas` implementation as Android — extract `SplitterBar` into a shared composable in `:core:protocol` or a shared UI module if KMP UI is available; otherwise duplicate with a code comment.
>
> **Constraints:**
> - Same `.sample(200).conflate()` rule enforced by the ViewModel — the composable just observes `uiState`.
> - Same animation specs as Android.

---

### Prompt 14.3 — Desktop Settings Screen

> **Target:** JVM Desktop (`:desktop:app`) — Compose Multiplatform
>
> **Task:** Implement `DesktopSettingsScreen` — same sections as `Prompt 13.6` plus Desktop-specific items.
>
> **Additional Desktop-only sections:**
>
> **NETWORK → Interface selector:**
> - Dropdown of all non-loopback `NetworkInterface` entries with their IP addresses.
> - Selecting an interface rebinds JmDNS.
>
> **PLATFORM:**
> - `[Auto-Configure Firewall]` button (Windows only) — calls `DesktopPlatformSetup.configureWindowsFirewall()`. Show result inline.
> - `[Launch at Login]` toggle — calls `registerWindowsStartup` / `registerLinuxStartup`.
> - ADB binary path display + `[Browse]` override.
>
> **USB STATUS:**
> - List of connected ADB devices with their serial and state (`AUTHORISED` / `UNAUTHORISED` / `TUNNEL_ACTIVE`).
> - If any `UNAUTHORISED`: show yellow banner `"[Device] needs USB debugging trust. Follow the prompt on your phone."`.
> - If multiple authorized: show `"Multiple devices connected — select a device before transferring."`.

---

## Phase 15: Debug Utilities

### Prompt 15.1 — In-Memory Debug Log

> **Target:** KMP (`:core:transfer-engine`)
>
> **Task:** Implement `DebugLog` — a thread-safe circular buffer for runtime diagnostics.
>
> ```kotlin
> enum class LogLevel { DEBUG, INFO, WARN, ERROR }
>
> data class LogEntry(val timestampMs: Long, val level: LogLevel, val tag: String, val message: String) {
>     fun format(): String = "[${formatTime(timestampMs)}] ${level.name} $tag: $message"
> }
>
> object DebugLog {
>     private const val MAX_SIZE = 500
>
>     fun log(level: LogLevel, tag: String, message: String)
>     fun getEntries(): List<LogEntry>
>     fun exportToFile(file: java.io.File)
>     fun clear()
> }
> ```
>
> **Constraints:**
> - Backed by `ArrayDeque<LogEntry>(MAX_SIZE)`.
> - When full: `removeFirst()` before `addLast()` — circular behavior.
> - **NEVER write to disk during transfer** — `exportToFile` is only called from the Settings screen.
> - Thread-safe: use `@Synchronized` or a `ReentrantLock` around all mutations.
> - Provide convenience extension: `fun String.logD(tag: String)`, `fun String.logE(tag: String)` etc.

---

*FluxSync Master Prompt Plan — 38 Prompts across 15 Phases*
*Covers: KMP Core · Storage Engine · Resumability · Transfer Engine · Security · Discovery · Android Service · Desktop ADB · System Tray · All UI Screens · ViewModels · History*
