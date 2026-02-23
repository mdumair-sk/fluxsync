package com.fluxsync.android.data.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DiscoveredDevice(
    val deviceName: String,
    val ipAddress: InetAddress,
    val port: Int,
    val certFingerprint: String?,
    val protocolVersion: Int,
)

class AndroidMdnsDiscovery(private val context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    private var isStarted = false
    private var registeredService: RegisteredService? = null

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    fun startDiscovery() {
        isStarted = true
        registerServiceInternal()
        discoverServicesInternal()
    }

    fun stopDiscovery() {
        isStarted = false
        stopDiscoveryInternal()
        stopRegistrationInternal()
    }

    fun registerSelf(deviceName: String, port: Int, certFingerprint: String) {
        registeredService = RegisteredService(
            deviceName = deviceName,
            port = port,
            certFingerprint = certFingerprint,
        )

        if (isStarted) {
            registerServiceInternal()
        }
    }

    fun unregisterSelf() {
        registeredService = null
        stopRegistrationInternal()
    }

    private fun discoverServicesInternal() {
        stopDiscoveryInternal()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "startDiscovery failed for type=$serviceType, error=$errorCode")
                handleAlreadyActive(errorCode) { discoverServicesInternal() }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "stopDiscovery failed for type=$serviceType, error=$errorCode")
                handleAlreadyActive(errorCode) { discoverServicesInternal() }
            }

            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "mDNS discovery started for type=$serviceType")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "mDNS discovery stopped for type=$serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType != SERVICE_TYPE) {
                    return
                }
                resolveService(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val name = serviceInfo.serviceName
                _discoveredDevices.value = _discoveredDevices.value.filterNot { it.deviceName == name }
                Log.i(TAG, "mDNS service lost: $name")
            }
        }

        discoveryListener = listener
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        nsdManager.resolveService(
            serviceInfo,
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "resolveService failed for ${serviceInfo.serviceName}, error=$errorCode")
                    handleAlreadyActive(errorCode) { resolveService(serviceInfo) }
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val txtAttributes = serviceInfo.attributes.mapValues { (_, value) ->
                        value?.toString(StandardCharsets.UTF_8)
                    }

                    val txtPort = txtAttributes[TXT_PORT_KEY]?.toIntOrNull()
                    val resolvedPort = txtPort ?: serviceInfo.port
                    Log.i(
                        TAG,
                        "Resolved ${serviceInfo.serviceName}: nsdPort=${serviceInfo.port}, txtPort=$txtPort, using=$resolvedPort",
                    )

                    val host = serviceInfo.host
                    if (host == null) {
                        Log.w(TAG, "Resolved service ${serviceInfo.serviceName} has null host; skipping")
                        return
                    }

                    val protocolVersion = txtAttributes[TXT_PROTOCOL_VERSION_KEY]?.toIntOrNull() ?: DEFAULT_PROTOCOL_VERSION
                    val certFingerprint = txtAttributes[TXT_CERT_FINGERPRINT_KEY]

                    upsertDiscovered(
                        DiscoveredDevice(
                            deviceName = serviceInfo.serviceName,
                            ipAddress = host,
                            port = resolvedPort,
                            certFingerprint = certFingerprint,
                            protocolVersion = protocolVersion,
                        ),
                    )
                }
            },
        )
    }

    private fun upsertDiscovered(device: DiscoveredDevice) {
        val next = _discoveredDevices.value.toMutableList()
        val key = dedupeKey(device)
        val existingIndex = next.indexOfFirst { dedupeKey(it) == key }

        if (existingIndex >= 0) {
            next[existingIndex] = device
        } else {
            next += device
        }

        _discoveredDevices.value = next
    }

    private fun registerServiceInternal() {
        val service = registeredService ?: return

        stopRegistrationInternal()

        val nsdServiceInfo = NsdServiceInfo().apply {
            serviceName = service.deviceName
            serviceType = SERVICE_TYPE
            port = service.port
            setAttribute(TXT_PROTOCOL_VERSION_KEY, DEFAULT_PROTOCOL_VERSION.toString())
            setAttribute(TXT_CERT_FINGERPRINT_KEY, service.certFingerprint)
            setAttribute(TXT_PORT_KEY, service.port.toString())
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "registerService failed for ${serviceInfo.serviceName}, error=$errorCode")
                handleAlreadyActive(errorCode) { registerServiceInternal() }
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "unregisterService failed for ${serviceInfo.serviceName}, error=$errorCode")
                handleAlreadyActive(errorCode) { registerServiceInternal() }
            }

            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "mDNS service registered as ${serviceInfo.serviceName}")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "mDNS service unregistered: ${serviceInfo.serviceName}")
            }
        }

        registrationListener = listener
        nsdManager.registerService(nsdServiceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun stopDiscoveryInternal() {
        val listener = discoveryListener ?: return
        try {
            nsdManager.stopServiceDiscovery(listener)
        } catch (t: Throwable) {
            Log.w(TAG, "Error stopping discovery", t)
        } finally {
            discoveryListener = null
        }
    }

    private fun stopRegistrationInternal() {
        val listener = registrationListener ?: return
        try {
            nsdManager.unregisterService(listener)
        } catch (t: Throwable) {
            Log.w(TAG, "Error unregistering service", t)
        } finally {
            registrationListener = null
        }
    }

    private fun handleAlreadyActive(errorCode: Int, retry: () -> Unit) {
        if (errorCode != NsdManager.FAILURE_ALREADY_ACTIVE || !isStarted) {
            return
        }

        Log.w(TAG, "NsdManager already active; restarting discovery and registration")
        stopDiscoveryInternal()
        stopRegistrationInternal()
        registerServiceInternal()
        retry()
    }

    private fun dedupeKey(device: DiscoveredDevice): String {
        return device.certFingerprint?.takeIf { it.isNotBlank() }
            ?: "name:${device.deviceName}"
    }

    private data class RegisteredService(
        val deviceName: String,
        val port: Int,
        val certFingerprint: String,
    )

    private companion object {
        private const val TAG = "AndroidMdnsDiscovery"
        private const val SERVICE_TYPE = "_fluxsync._tcp"
        private const val TXT_PROTOCOL_VERSION_KEY = "protocolVersion"
        private const val TXT_CERT_FINGERPRINT_KEY = "certFingerprint"
        private const val TXT_PORT_KEY = "port"
        private const val DEFAULT_PROTOCOL_VERSION = 1
    }
}
