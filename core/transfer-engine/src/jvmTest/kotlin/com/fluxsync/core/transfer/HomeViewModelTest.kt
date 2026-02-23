package com.fluxsync.core.transfer

import com.fluxsync.core.security.TrustStore
import com.fluxsync.core.security.TrustedDevice
import java.net.InetAddress
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeViewModelTest {

    @Test
    fun `onDiscoveredDevicesUpdated merges usb and trust state`() = runTest {
        val trustStore = FakeTrustStore(trustedFingerprints = setOf("trusted-fp"))
        val viewModel = HomeViewModel(trustStore = trustStore, scope = backgroundScope)

        viewModel.onUsbDeviceConnected(serial = "ABC123", ipAddress = "192.168.1.20")
        viewModel.onDiscoveredDevicesUpdated(
            listOf(
                DiscoveredDevice(
                    deviceName = "Pixel",
                    ipAddress = InetAddress.getByName("192.168.1.20"),
                    port = 5001,
                    certFingerprint = "trusted-fp",
                    protocolVersion = 1,
                )
            )
        )

        val entry = viewModel.uiState.value.discoveredDevices.single()
        assertTrue(entry.isTrusted)
        assertTrue(entry.hasWifi)
        assertTrue(entry.hasUsb)
        assertEquals("Pixel", entry.deviceName)
    }

    @Test
    fun `usb only devices are included when no wifi discovery exists`() = runTest {
        val viewModel = HomeViewModel(trustStore = FakeTrustStore(), scope = backgroundScope)

        viewModel.onUsbDeviceConnected(serial = "ABC123", ipAddress = "10.0.0.5")

        val entry = viewModel.uiState.value.discoveredDevices.single()
        assertEquals("ABC123", entry.deviceName)
        assertEquals(5002, entry.port)
        assertFalse(entry.hasWifi)
        assertTrue(entry.hasUsb)
    }

    @Test
    fun `onManualIpSubmitted sets error for invalid ip`() = runTest {
        val viewModel = HomeViewModel(trustStore = FakeTrustStore(), scope = backgroundScope)

        viewModel.onManualIpSubmitted(ip = "not-an-ip", port = 5001)

        assertEquals("Invalid IP address format", viewModel.uiState.value.manualIpError)
    }

    @Test
    fun `onManualIpSubmitted emits selected device for valid ip`() = runTest {
        val viewModel = HomeViewModel(trustStore = FakeTrustStore(), scope = backgroundScope)
        val deferred = async { viewModel.selectedDevice.first() }

        viewModel.onManualIpSubmitted(ip = "192.168.1.10", port = 5001)

        val selected = deferred.await()
        assertEquals("192.168.1.10", selected.deviceName)
        assertNull(viewModel.uiState.value.manualIpError)
    }

    @Test
    fun `onDeviceSelected emits one shot event`() = runTest {
        val viewModel = HomeViewModel(trustStore = FakeTrustStore(), scope = backgroundScope)
        val target = DeviceUiEntry(
            deviceName = "Desktop",
            ipAddress = "192.168.1.100",
            port = 5001,
            certFingerprint = "fp",
            isTrusted = true,
            hasWifi = true,
            hasUsb = false,
        )

        val deferred = async { viewModel.selectedDevice.first() }
        viewModel.onDeviceSelected(target)

        assertEquals(target, deferred.await())
    }

    private class FakeTrustStore(
        private val trustedFingerprints: Set<String> = emptySet(),
    ) : TrustStore {
        override fun isTrusted(fingerprint: String): Boolean = fingerprint in trustedFingerprints

        override fun save(device: TrustedDevice) = Unit

        override fun getAll(): List<TrustedDevice> = emptyList()

        override fun revoke(fingerprint: String) = Unit
    }
}
