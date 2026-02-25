package com.fluxsync.desktop.data.network

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.Collections
import java.util.logging.Level
import java.util.logging.Logger
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class DiscoveredDevice(
    val deviceName: String,
    val ipAddress: InetAddress,
    val port: Int,
    val certFingerprint: String?,
    val protocolVersion: Int,
)

class DesktopMdnsDiscovery(private val scope: CoroutineScope) {
    private val lifecycleMutex = Mutex()

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    private var jmdns: JmDNS? = null
    private var listener: ServiceListener? = null
    private var advertisedService: ServiceInfo? = null

    suspend fun start() {
        lifecycleMutex.withLock {
            startInternal()
        }
    }

    fun stop() {
        val currentMdns = jmdns
        val currentListener = listener
        val currentService = advertisedService

        try {
            if (currentMdns != null && currentListener != null) {
                currentMdns.removeServiceListener(SERVICE_TYPE, currentListener)
            }
            if (currentMdns != null && currentService != null) {
                currentMdns.unregisterService(currentService)
            }
            currentMdns?.close()
            logger.info("Stopped desktop mDNS discovery")
        } catch (t: Throwable) {
            logger.log(Level.WARNING, "Error while stopping desktop mDNS discovery", t)
        } finally {
            advertisedService = null
            listener = null
            jmdns = null
            scope.cancel()
        }
    }

    suspend fun bindAndAdvertise(
        deviceName: String,
        certFingerprint: String,
        preferredPort: Int = 5001,
    ): Int {
        lifecycleMutex.withLock {
            if (jmdns == null) {
                startInternal()
            }

            val mdns = jmdns ?: error("mDNS unavailable after start()")
            val actualPort = withContext(Dispatchers.IO) { findAvailablePort(preferredPort) }
            val previousService = advertisedService
            val previousPort = previousService?.getPropertyString(TXT_PORT_KEY)?.toIntOrNull()

            val txtRecord = mapOf(
                TXT_PROTOCOL_VERSION_KEY to DEFAULT_PROTOCOL_VERSION.toString(),
                TXT_CERT_FINGERPRINT_KEY to certFingerprint,
                TXT_PORT_KEY to actualPort.toString(),
            )

            val nextService = ServiceInfo.create(
                FULL_SERVICE_TYPE,
                deviceName,
                actualPort,
                0,
                0,
                txtRecord,
            )

            if (previousService != null) {
                mdns.unregisterService(previousService)
            }

            mdns.registerService(nextService)
            advertisedService = nextService

            if (previousPort != null && previousPort != actualPort) {
                delay(MDNS_REANNOUNCE_DELAY_MS)
            }

            logger.info(
                "Advertised desktop service '$deviceName' on port $actualPort (previous=$previousPort)",
            )
            return actualPort
        }
    }


    private suspend fun startInternal() {
        if (jmdns != null) {
            return
        }

        val bindAddress = resolveDefaultRouteAddress()
        val instance = withContext(Dispatchers.IO) { JmDNS.create(bindAddress) }
        val serviceListener = buildServiceListener(instance)
        instance.addServiceListener(SERVICE_TYPE, serviceListener)

        jmdns = instance
        listener = serviceListener
        logger.info("Started desktop mDNS discovery on ${bindAddress.hostAddress}")
    }

    private fun buildServiceListener(mdns: JmDNS): ServiceListener {
        return object : ServiceListener {
            override fun serviceAdded(event: ServiceEvent) {
                mdns.requestServiceInfo(event.type, event.name, true)
            }

            override fun serviceRemoved(event: ServiceEvent) {
                _discoveredDevices.value = _discoveredDevices.value.filterNot { it.deviceName == event.name }
            }

            override fun serviceResolved(event: ServiceEvent) {
                val info = event.info
                val ip = info.inet4Addresses.firstOrNull() ?: return
                val txtPort = info.getPropertyString(TXT_PORT_KEY)?.toIntOrNull()
                if (txtPort == null) {
                    logger.warning("Skipping service ${event.name}: missing/invalid TXT 'port'")
                    return
                }

                val protocolVersion = info.getPropertyString(TXT_PROTOCOL_VERSION_KEY)?.toIntOrNull()
                    ?: DEFAULT_PROTOCOL_VERSION
                val certFingerprint = info.getPropertyString(TXT_CERT_FINGERPRINT_KEY)

                val discovered = DiscoveredDevice(
                    deviceName = event.name,
                    ipAddress = ip,
                    port = txtPort,
                    certFingerprint = certFingerprint,
                    protocolVersion = protocolVersion,
                )
                upsertDiscovered(discovered)
            }
        }
    }

    private fun upsertDiscovered(device: DiscoveredDevice) {
        val current = _discoveredDevices.value.toMutableList()
        val existing = current.indexOfFirst {
            it.certFingerprint != null && it.certFingerprint == device.certFingerprint
        }

        if (existing >= 0) {
            current[existing] = device
        } else {
            val byName = current.indexOfFirst { it.deviceName == device.deviceName }
            if (byName >= 0) {
                current[byName] = device
            } else {
                current += device
            }
        }

        _discoveredDevices.value = current
    }

    private fun resolveDefaultRouteAddress(): InetAddress {
        val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces()).filter {
            it.isUp && !it.isLoopback
        }

        val route = detectDefaultRoute()

        val byInterfaceName = route.interfaceName?.let { ifaceName ->
            interfaces.firstOrNull { it.name == ifaceName || it.displayName == ifaceName }
        }

        val byLocalAddress = route.localAddress?.let { localAddress ->
            interfaces.firstOrNull { networkInterface ->
                Collections.list(networkInterface.inetAddresses).any { it.hostAddress == localAddress }
            }
        }

        val selectedInterface = byInterfaceName
            ?: byLocalAddress
            ?: interfaces.firstOrNull { networkInterface ->
                Collections.list(networkInterface.inetAddresses).any {
                    it is Inet4Address && !it.isLoopbackAddress
                }
            }
            ?: error("No active non-loopback interface available for JmDNS binding")

        return Collections.list(selectedInterface.inetAddresses)
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?: error("Selected interface ${selectedInterface.name} has no IPv4 address")
    }

    private fun detectDefaultRoute(): DefaultRoute {
        return if (isWindows()) {
            parseWindowsDefaultRoute(runCommand("route", "print", "-4"))
        } else {
            parseUnixDefaultRoute(runCommand("ip", "route", "show", "default"))
        }
    }

    private fun findAvailablePort(preferredPort: Int): Int {
        require(preferredPort in MIN_PORT..MAX_PORT) {
            "Preferred port must be in [$MIN_PORT, $MAX_PORT], got $preferredPort"
        }

        for (port in preferredPort..MAX_PORT) {
            if (isPortAvailable(port)) {
                return port
            }
        }
        throw IllegalStateException("No available port found in range $preferredPort..$MAX_PORT")
    }

    private fun isPortAvailable(port: Int): Boolean {
        return try {
            ServerSocket(port).use { true }
        } catch (_: Throwable) {
            false
        }
    }

    private fun runCommand(vararg command: String): List<String> {
        return try {
            val process = ProcessBuilder(*command)
                .redirectErrorStream(true)
                .start()

            process.inputStream.use { input ->
                BufferedReader(InputStreamReader(input)).readLines()
            }
        } catch (t: Throwable) {
            logger.log(Level.WARNING, "Failed to execute command: ${command.joinToString(" ")}", t)
            emptyList()
        }
    }

    private fun parseUnixDefaultRoute(lines: List<String>): DefaultRoute {
        val defaultLine = lines.firstOrNull { it.trim().startsWith("default") } ?: return DefaultRoute()
        val interfaceName = Regex("\\bdev\\s+(\\S+)").find(defaultLine)?.groupValues?.get(1)
        val localAddress = Regex("\\bsrc\\s+(\\S+)").find(defaultLine)?.groupValues?.get(1)
        return DefaultRoute(interfaceName = interfaceName, localAddress = localAddress)
    }

    private fun parseWindowsDefaultRoute(lines: List<String>): DefaultRoute {
        val defaultLine = lines.firstOrNull {
            val trimmed = it.trim()
            trimmed.startsWith("0.0.0.0") && trimmed.contains("0.0.0.0")
        } ?: return DefaultRoute()

        val columns = defaultLine.trim().split(Regex("\\s+"))
        val localAddress = columns.getOrNull(3)
        return DefaultRoute(localAddress = localAddress)
    }

    private fun isWindows(): Boolean {
        return System.getProperty("os.name").contains("win", ignoreCase = true)
    }

    private data class DefaultRoute(
        val interfaceName: String? = null,
        val localAddress: String? = null,
    )

    private companion object {
        private val logger = Logger.getLogger(DesktopMdnsDiscovery::class.java.name)

        private const val SERVICE_TYPE = "_fluxsync._tcp.local."
        private const val FULL_SERVICE_TYPE = "_fluxsync._tcp.local."

        private const val TXT_PROTOCOL_VERSION_KEY = "protocolVersion"
        private const val TXT_CERT_FINGERPRINT_KEY = "certFingerprint"
        private const val TXT_PORT_KEY = "port"

        private const val DEFAULT_PROTOCOL_VERSION = 1
        private const val MIN_PORT = 5001
        private const val MAX_PORT = 5099
        private const val MDNS_REANNOUNCE_DELAY_MS = 500L
    }
}
