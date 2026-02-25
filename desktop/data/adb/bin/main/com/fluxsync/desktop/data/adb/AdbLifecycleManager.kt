package com.fluxsync.desktop.data.adb

import com.fluxsync.core.protocol.ChannelState
import com.fluxsync.core.protocol.ChannelType
import com.fluxsync.core.protocol.ChunkPacket
import com.fluxsync.core.protocol.ChunkPacketCodec
import com.fluxsync.core.protocol.NetworkChannel
import com.fluxsync.core.protocol.ThroughputTracker
import com.fluxsync.core.transfer.DRTLB
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import jdk.net.ExtendedSocketOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class AdbDeviceState { ABSENT, DETECTED, AUTHORISED, UNAUTHORISED, TUNNEL_ACTIVE, TUNNEL_TORN_DOWN }

data class AdbDevice(val serial: String, var state: AdbDeviceState)

class AdbLifecycleManager(
    private val drtlb: DRTLB,
    private val scope: CoroutineScope,
    private val forceWifiOnly: () -> Boolean,
    private val onUnauthorisedDevice: (serial: String) -> Unit,
    private val onMultipleDevices: (serials: List<String>) -> Unit,
) {
    private val _devices = MutableStateFlow<Map<String, AdbDevice>>(emptyMap())
    val devices: StateFlow<Map<String, AdbDevice>> = _devices.asStateFlow()

    private val tunnelChannels = ConcurrentHashMap<String, UsbNetworkChannel>()
    private val unauthorisedRetryJobs = ConcurrentHashMap<String, Job>()

    private var adbBinary: File? = null
    private var pollingJob: Job? = null

    suspend fun start() {
        adbBinary = resolveAdbBinary()
        pollingJob?.cancel()
        pollingJob = scope.launch {
            devicePollingLoop()
        }
    }

    fun stop() {
        pollingJob?.cancel()
        pollingJob = null

        unauthorisedRetryJobs.values.forEach { it.cancel() }
        unauthorisedRetryJobs.clear()

        val activeSerials = tunnelChannels.keys.toList()
        activeSerials.forEach { serial ->
            scope.launch {
                tearDownTunnel(serial)
            }
        }
    }

    fun resolveAdbBinary(): File {
        val daemonReachable = isPortReachable(host = "127.0.0.1", port = ADB_DAEMON_PORT, timeoutMs = 500)
        if (daemonReachable) {
            return findAdbOnPath() ?: error("ADB daemon is reachable on localhost:5037 but no adb binary was found on PATH")
        }

        return resolveBundledAdbBinary()
    }

    private suspend fun devicePollingLoop() {
        while (scope.isActive) {
            val adb = adbBinary ?: error("ADB lifecycle started without resolved adb binary")
            val output = runAdbCommand(adb, "devices")
            val parsedStates = parseAdbDevices(output)

            val authorised = parsedStates.filterValues { it == "device" }.keys.toList()
            if (authorised.size > 1) {
                onMultipleDevices(authorised)
            }

            parsedStates.forEach { (serial, state) ->
                when (state) {
                    "device" -> onAuthorised(serial)
                    "unauthorized" -> markUnauthorisedAndRetry(serial)
                    "offline" -> updateDeviceState(serial, AdbDeviceState.DETECTED)
                }
            }

            val currentMap = _devices.value.toMutableMap()
            val disappeared = currentMap.keys - parsedStates.keys
            disappeared.forEach { serial ->
                tearDownTunnel(serial)
                currentMap[serial] = AdbDevice(serial, AdbDeviceState.ABSENT)
            }
            _devices.value = currentMap

            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun onAuthorised(serial: String) {
        unauthorisedRetryJobs.remove(serial)?.cancel()
        updateDeviceState(serial, AdbDeviceState.AUTHORISED)
        if (!forceWifiOnly()) {
            setupTunnel(serial)
        }
    }

    private suspend fun setupTunnel(serial: String) {
        val adb = adbBinary ?: error("ADB binary is unavailable while setting up tunnel for $serial")
        val result = runAdbCommand(adb, "-s", serial, "reverse", "tcp:5002", "tcp:5002")
        check(!result.contains("error", ignoreCase = true)) {
            "ADB reverse failed for serial=$serial: $result"
        }

        if (forceWifiOnly()) return

        if (tunnelChannels[serial] == null) {
            val channel = UsbNetworkChannel(serial = serial, scope = scope)
            tunnelChannels[serial] = channel
            drtlb.registerChannel(channel)
        }

        updateDeviceState(serial, AdbDeviceState.TUNNEL_ACTIVE)
    }

    private suspend fun tearDownTunnel(serial: String) {
        unauthorisedRetryJobs.remove(serial)?.cancel()

        val adb = adbBinary
        if (adb != null && adb.exists()) {
            runCatching {
                runAdbCommand(adb, "-s", serial, "reverse", "--remove", "tcp:5002")
            }
        }

        tunnelChannels.remove(serial)?.let { channel ->
            drtlb.removeChannel(channel)
            channel.close()
        }

        updateDeviceState(serial, AdbDeviceState.TUNNEL_TORN_DOWN)
    }

    private fun markUnauthorisedAndRetry(serial: String) {
        updateDeviceState(serial, AdbDeviceState.UNAUTHORISED)
        onUnauthorisedDevice(serial)

        if (unauthorisedRetryJobs.containsKey(serial)) return

        unauthorisedRetryJobs[serial] = scope.launch {
            val startedAt = System.currentTimeMillis()
            while (isActive && (System.currentTimeMillis() - startedAt) < UNAUTHORISED_RETRY_WINDOW_MS) {
                val adb = adbBinary ?: return@launch
                val output = runAdbCommand(adb, "devices")
                val state = parseAdbDevices(output)[serial]
                if (state == "device") {
                    onAuthorised(serial)
                    unauthorisedRetryJobs.remove(serial)
                    return@launch
                }
                delay(UNAUTHORISED_RETRY_INTERVAL_MS)
            }
            unauthorisedRetryJobs.remove(serial)
        }
    }

    private suspend fun runAdbCommand(adb: File, vararg args: String): String = withContext(Dispatchers.IO) {
        val command = listOf(adb.absolutePath) + args
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        check(exitCode == 0) {
            "ADB command failed (exit=$exitCode): ${command.joinToString(" ")}\n$output"
        }

        output
    }

    private fun parseAdbDevices(output: String): Map<String, String> {
        return output
            .lineSequence()
            .dropWhile { !it.startsWith("List of devices attached") }
            .drop(1)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+"))
                if (parts.size < 2) return@mapNotNull null
                parts[0] to parts[1]
            }
            .toMap()
    }

    private fun findAdbOnPath(): File? {
        val executable = if (isWindows()) "adb.exe" else "adb"
        val path = System.getenv("PATH") ?: return null
        val candidates = path
            .split(File.pathSeparator)
            .asSequence()
            .map { File(it, executable) }
            .filter { it.exists() && it.isFile }
            .toList()

        return candidates.firstOrNull()
    }

    private fun resolveBundledAdbBinary(): File {
        val resourcePath = when {
            isWindows() -> "adb/windows/adb.exe"
            isLinux() -> "adb/linux/adb"
            else -> error("Unsupported desktop OS for bundled adb")
        }

        val stream = javaClass.classLoader.getResourceAsStream(resourcePath)
            ?: error("Bundled adb resource not found: $resourcePath")

        val suffix = if (isWindows()) ".exe" else ""
        val tempFile = File.createTempFile("fluxsync-adb-", suffix)
        tempFile.deleteOnExit()

        stream.use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        if (isLinux()) {
            check(tempFile.setExecutable(true)) {
                "Failed to mark bundled adb as executable: ${tempFile.absolutePath}"
            }
        }

        return tempFile
    }

    private fun updateDeviceState(serial: String, newState: AdbDeviceState) {
        val current = _devices.value.toMutableMap()
        val existing = current[serial]
        if (existing == null) {
            current[serial] = AdbDevice(serial, newState)
        } else {
            existing.state = newState
            current[serial] = existing
        }
        _devices.value = current
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase(Locale.ROOT).contains("win")

    private fun isLinux(): Boolean =
        System.getProperty("os.name").lowercase(Locale.ROOT).contains("linux")

    private fun isPortReachable(host: String, port: Int, timeoutMs: Int): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        }.getOrDefault(false)
    }

    private companion object {
        const val ADB_DAEMON_PORT = 5037
        const val POLL_INTERVAL_MS = 2_000L
        const val UNAUTHORISED_RETRY_INTERVAL_MS = 3_000L
        const val UNAUTHORISED_RETRY_WINDOW_MS = 30_000L
    }
}

class UsbNetworkChannel(
    private val serial: String,
    private val scope: CoroutineScope,
) : NetworkChannel {
    override val id: String = "usb-adb:$serial"
    override val type: ChannelType = ChannelType.USB_ADB
    override var state: ChannelState = ChannelState.ACTIVE

    override val measuredThroughput: Long
        get() = throughputTracker.bytesPerSecond

    override val lastLatencyMs: Long = 0L
    override val sendBufferFillFraction: Float = 0f

    private val throughputTracker = ThroughputTracker()
    private val writeMutex = Mutex()
    private val sendBuffer = ByteBuffer.allocateDirect(ChunkPacketCodec.HEADER_SIZE + MAX_CHUNK_SIZE)
    private val socket = Socket().apply {
        connect(InetSocketAddress(LOCALHOST, USB_TUNNEL_PORT), CONNECT_TIMEOUT_MS)
        this@UsbNetworkChannel.configureSocket(this)
    }
    private val writableChannel = Channels.newChannel(socket.getOutputStream())

    override suspend fun send(chunk: ChunkPacket) {
        withContext(scope.coroutineContext + Dispatchers.IO) {
            writeMutex.withLock {
                ChunkPacketCodec.writeTo(chunk, sendBuffer)
                while (sendBuffer.hasRemaining()) {
                    writableChannel.write(sendBuffer)
                }
                recordSuccess((ChunkPacketCodec.HEADER_SIZE + chunk.payloadLength).toLong())
            }
        }
    }

    override fun recordSuccess(bytesSent: Long) {
        throughputTracker.record(bytesSent)
    }

    override fun close() {
        state = ChannelState.OFFLINE
        writableChannel.close()
        socket.close()
    }

    private companion object {
        const val MAX_CHUNK_SIZE: Int = 1_024 * 1_024
        const val LOCALHOST = "127.0.0.1"
        const val USB_TUNNEL_PORT = 5002
        const val CONNECT_TIMEOUT_MS = 1_000
    }
}

fun NetworkChannel.configureSocket(socket: Socket) {
    socket.setOption(StandardSocketOptions.SO_KEEPALIVE, true)
    socket.setOption(ExtendedSocketOptions.TCP_KEEPIDLE, 5)
    socket.setOption(ExtendedSocketOptions.TCP_KEEPINTERVAL, 2)
    socket.setOption(ExtendedSocketOptions.TCP_KEEPCOUNT, 3)
}
