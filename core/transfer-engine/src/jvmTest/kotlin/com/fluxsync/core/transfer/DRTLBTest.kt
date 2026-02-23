package com.fluxsync.core.transfer

import com.fluxsync.core.protocol.ChannelState
import com.fluxsync.core.protocol.ChannelType
import com.fluxsync.core.protocol.ChunkPacket
import com.fluxsync.core.protocol.NetworkChannel
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DRTLBTest {

    @Test
    fun `worker prioritizes retrySlot over chunkSource`() = runTest {
        val chunkSource = Channel<ChunkPacket>(capacity = 4)
        val retrySlot = Channel<ChunkPacket>(capacity = 64)
        val drtlb = DRTLB(chunkSource = chunkSource, retrySlot = retrySlot)

        val sentChunks = CopyOnWriteArrayList<ChunkPacket>()
        val channel = FakeNetworkChannel(onSend = { sentChunks += it })
        drtlb.registerChannel(channel)

        val sourceChunk = chunkPacket(chunkIndex = 1)
        val retryChunk = chunkPacket(chunkIndex = 99)

        chunkSource.send(sourceChunk)
        retrySlot.send(retryChunk)

        val runJob = launch(start = CoroutineStart.UNDISPATCHED) { drtlb.run() }

        while (sentChunks.size < 2) {
            testScheduler.runCurrent()
        }

        runJob.cancel()

        assertEquals(99, sentChunks[0].chunkIndex)
        assertEquals(1, sentChunks[1].chunkIndex)
    }

    @Test
    fun `IOException marks channel degraded and retries failed chunk`() = runTest {
        val chunkSource = Channel<ChunkPacket>(capacity = 4)
        val retrySlot = Channel<ChunkPacket>(capacity = 64)
        val drtlb = DRTLB(chunkSource = chunkSource, retrySlot = retrySlot)

        val channel = FakeNetworkChannel(onSend = { throw IOException("boom") })
        drtlb.registerChannel(channel)

        val failedChunk = chunkPacket(chunkIndex = 7)
        chunkSource.send(failedChunk)

        val runJob = launch(start = CoroutineStart.UNDISPATCHED) { drtlb.run() }
        testScheduler.runCurrent()

        assertEquals(ChannelState.DEGRADED, channel.state)
        assertEquals(failedChunk, retrySlot.receive())

        runJob.cancel()
    }

    @Test
    fun `force wifi only removes USB channels`() = runTest {
        val chunkSource = Channel<ChunkPacket>(capacity = 4)
        val retrySlot = Channel<ChunkPacket>(capacity = 64)
        val drtlb = DRTLB(chunkSource = chunkSource, retrySlot = retrySlot)

        val wifiSends = CopyOnWriteArrayList<ChunkPacket>()
        val usbSends = CopyOnWriteArrayList<ChunkPacket>()

        drtlb.registerChannel(FakeNetworkChannel(id = "wifi-1", type = ChannelType.WIFI, onSend = { wifiSends += it }))
        drtlb.registerChannel(FakeNetworkChannel(id = "usb-1", type = ChannelType.USB_ADB, onSend = { usbSends += it }))
        drtlb.onForceWifiOnlyChanged(enabled = true)

        chunkSource.send(chunkPacket(chunkIndex = 3))

        val runJob = launch(start = CoroutineStart.UNDISPATCHED) { drtlb.run() }
        testScheduler.runCurrent()

        runJob.cancel()

        assertEquals(1, wifiSends.size)
        assertTrue(usbSends.isEmpty())
    }

    private fun chunkPacket(chunkIndex: Int): ChunkPacket = ChunkPacket(
        sessionId = 1L,
        fileId = 10,
        chunkIndex = chunkIndex,
        offset = chunkIndex.toLong(),
        payloadLength = 1,
        checksum = 0,
        payload = byteArrayOf(chunkIndex.toByte()),
    )

    private class FakeNetworkChannel(
        override val id: String = "channel",
        override val type: ChannelType = ChannelType.WIFI,
        private val onSend: suspend (ChunkPacket) -> Unit,
    ) : NetworkChannel {
        override var state: ChannelState = ChannelState.ACTIVE
        override val measuredThroughput: Long = 1_024L
        override val lastLatencyMs: Long = 5L
        override val sendBufferFillFraction: Float = 0.25f

        override suspend fun send(chunk: ChunkPacket) {
            onSend(chunk)
        }

        override fun recordSuccess(bytesSent: Long) = Unit

        override fun close() = Unit
    }
}
