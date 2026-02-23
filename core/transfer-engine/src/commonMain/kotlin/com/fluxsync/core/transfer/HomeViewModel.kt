package com.fluxsync.core.transfer

import com.fluxsync.core.security.TrustStore
import java.net.InetAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DiscoveredDevice(
    val deviceName: String,
    val ipAddress: InetAddress,
    val port: Int,
    val certFingerprint: String?,
    val protocolVersion: Int,
)

data class DeviceUiEntry(
    val deviceName: String,
    val ipAddress: String,
    val port: Int,
    val certFingerprint: String?,
    val isTrusted: Boolean,
    val hasWifi: Boolean,
    val hasUsb: Boolean,
)

data class HomeUiState(
    val discoveredDevices: List<DeviceUiEntry> = emptyList(),
    val isDiscovering: Boolean = false,
    val manualIpError: String? = null,
)

class HomeViewModel(
    private val trustStore: TrustStore,
    private val scope: CoroutineScope,
) {
    private val usbConnectionsBySerial = linkedMapOf<String, String>()
    private var discoveredDevices: List<DiscoveredDevice> = emptyList()

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _selectedDevice = MutableSharedFlow<DeviceUiEntry>(
        replay = 0,
        extraBufferCapacity = 1,
    )
    val selectedDevice: SharedFlow<DeviceUiEntry> = _selectedDevice.asSharedFlow()

    fun onDiscoveredDevicesUpdated(devices: List<DiscoveredDevice>) {
        discoveredDevices = devices
        _uiState.update { current ->
            current.copy(
                discoveredDevices = mergeDeviceEntries(devices, usbConnectionsBySerial),
                isDiscovering = false,
            )
        }
    }

    fun onUsbDeviceConnected(serial: String, ipAddress: String) {
        usbConnectionsBySerial[serial] = ipAddress
        refreshMergedDevices()
    }

    fun onUsbDeviceDisconnected(serial: String) {
        usbConnectionsBySerial.remove(serial)
        refreshMergedDevices()
    }

    fun onManualIpSubmitted(ip: String, port: Int) {
        val parsedAddress = try {
            InetAddress.getByName(ip)
        } catch (error: Exception) {
            _uiState.update { it.copy(manualIpError = "Invalid IP address format") }
            return
        }

        _uiState.update { it.copy(manualIpError = null) }
        val manualEntry = DeviceUiEntry(
            deviceName = ip,
            ipAddress = parsedAddress.hostAddress ?: ip,
            port = port,
            certFingerprint = null,
            isTrusted = false,
            hasWifi = true,
            hasUsb = false,
        )

        onDeviceSelected(manualEntry)
    }

    fun onDeviceSelected(entry: DeviceUiEntry) {
        scope.launch {
            _selectedDevice.emit(entry)
        }
    }

    private fun refreshMergedDevices() {
        _uiState.update { current ->
            current.copy(discoveredDevices = mergeDeviceEntries(discoveredDevices, usbConnectionsBySerial))
        }
    }

    private fun mergeDeviceEntries(
        discovered: List<DiscoveredDevice>,
        usbBySerial: Map<String, String>,
    ): List<DeviceUiEntry> {
        val usbIps = usbBySerial.values.toSet()
        val wifiEntriesByIp = discovered.associateBy { it.ipAddress.hostAddress ?: it.ipAddress.hostName }

        val wifiEntries = discovered.map { device ->
            val host = device.ipAddress.hostAddress ?: device.ipAddress.hostName
            DeviceUiEntry(
                deviceName = device.deviceName,
                ipAddress = host,
                port = device.port,
                certFingerprint = device.certFingerprint,
                isTrusted = device.certFingerprint?.let(trustStore::isTrusted) == true,
                hasWifi = true,
                hasUsb = host in usbIps,
            )
        }

        val usbOnlyEntries = usbBySerial.mapNotNull { (serial, ip) ->
            if (ip in wifiEntriesByIp) {
                null
            } else {
                DeviceUiEntry(
                    deviceName = serial,
                    ipAddress = ip,
                    port = USB_DEFAULT_PORT,
                    certFingerprint = null,
                    isTrusted = false,
                    hasWifi = false,
                    hasUsb = true,
                )
            }
        }

        return (wifiEntries + usbOnlyEntries).sortedBy { it.deviceName.lowercase() }
    }

    private companion object {
        private const val USB_DEFAULT_PORT = 5002
    }
}
