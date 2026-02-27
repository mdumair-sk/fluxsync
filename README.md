FluxSync ⚡
A high-performance, cross-platform (Android ↔ JVM Desktop) file transfer ecosystem designed to saturate all available bandwidth.
FluxSync aggregates Wi-Fi and USB/ADB channels simultaneously, dynamically splitting traffic across both mediums in real-time to achieve maximum throughput. Built with Kotlin Multiplatform (KMP), it shares a single robust transfer engine across Android and Desktop.
🚀 Key Features
 * Dynamic Real-Time Load Balancing (DRTLB): A pull-based architecture that races Wi-Fi and USB channels against each other. Each socket pulls chunks as fast as its hardware allows, automatically adapting to network drops or cable disconnects without interrupting the transfer.
 * Zero-Copy Memory Safety: Files are never fully loaded into the heap. FluxSync uses java.nio.channels.FileChannel and direct ByteBuffer allocation to stream chunks (64KB–1MB). A mathematically strictly enforced 64MB global memory cap ensures the app will never OOM, even when transferring 50GB+ files.
 * Hard Resumability: BitTorrent-style resumability using binary .fluxpart files. Transfers survive app kills, reboots, and lost connections. Frankenstein-prevention ensures resumed files haven't been modified at the source.
 * TOFU Security & TLS 1.3: "Trust On First Use" pairing. Handshakes are secured via TLS 1.3. First-time device pairing requires a 4-digit PIN exchange over the encrypted channel.
 * The AWAITING_CONSENT Gatekeeper: Even trusted devices cannot force a file onto your machine. Every inbound transfer triggers a detailed consent prompt before a single byte of payload is written.
 * Zero-Config Discovery: Automatic peer discovery via mDNS (NsdManager on Android, JmDNS on Desktop) with fallback paths for Manual IP and QR Code pairing.
🛠 Tech Stack
 * Core Engine: Kotlin Multiplatform (KMP) targeting Android and JVM.
 * Concurrency: kotlinx.coroutines (Zero raw thread management).
 * Serialization: kotlinx.serialization (Control packets) + Hand-rolled Big-Endian binary codecs (Hot-path data chunks).
 * Android UI: Jetpack Compose + Material 3.
 * Desktop UI: Compose Multiplatform.
🏗 Architecture & Modules
The project is structured into a strict KMP module tree:
├── :core:protocol            # KMP — packet definitions, serialization
├── :core:transfer-engine     # KMP — DRTLB, FileStreamer, ChunkAssembler
├── :core:security            # KMP — TOFU cert gen, trust store
├── :core:resumability        # KMP — .fluxpart read/write, debounce logic
├── :android:data:network     # Android-specific socket/channel impl
├── :android:data:storage     # Android-specific FileChannel + SAF/URI impl
├── :android:service          # ForegroundService, WakeLock, RemoteViews
├── :desktop:app              # Compose Multiplatform UI, system tray, entry point
├── :desktop:data:adb         # ADB lifecycle manager (Desktop-only)
├── :desktop:data:network     # JVM socket/channel impl
└── :desktop:data:storage     # JVM FileChannel impl

⚙️ Installation & Setup
Android
 * Request exemption from aggressive OEM battery optimizers on first launch to prevent background socket death.
 * Uses the Storage Access Framework (SAF) to select a persistent "Drop Zone" directory.
Windows (Desktop)
 * Distributed via .msi or .exe installer.
 * Firewall Requirements: The installer automatically adds inbound Windows Firewall rules for TCP 5001 (Transfer) and UDP 5353 (mDNS).
 * Requires manual authorization of "USB Debugging" on the Android device for the ADB tunnel to activate.
Linux (Desktop)
 * Distributed via .AppImage or .deb.
 * udev Rules: Requires standard Android udev rules (/etc/udev/rules.d/51-android.rules) for non-root ADB access. The app will prompt for polkit elevation to auto-configure this on first launch if missing.
🔨 Building from Source
Ensure you have JDK 17+ installed.
# Clone the repository
git clone https://github.com/yourusername/fluxsync.git
cd fluxsync

# Build the Android APK
./gradlew :app:assembleDebug

# Run the Desktop application (JVM)
./gradlew :desktop:app:run

📜 The Golden Rules of FluxSync Contributors
If you are contributing to the core engine, you must abide by these invariants:
 * Never allocate on the hot path. Use ByteBuffer.allocateDirect() once and reuse it. Never use ByteBuffer.allocate().
 * Strict Chunk Assembly Order: When writing received chunks to disk, the order must strictly be: CRC32 Check → Write under Kotlin Mutex → Mark AtomicIntegerArray → Debounce .fluxpart.
 * Fail loudly, recover gracefully. No silent catches. If a socket dies, mark the channel DEGRADED, re-queue the chunk to the retrySlot, and log it.
 * 
👨‍💻 Author
Developed by Umair

📄 License
Distributed under the Apache 2.0 License. See LICENSE for more information.
