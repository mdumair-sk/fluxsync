package com.fluxsync.core.resumability

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FluxPartDebouncer(
    private val fluxPartFile: File,
    private val scope: CoroutineScope,
    private val debounceMs: Long = 2000L,
    private val writer: (FluxPartState, File) -> Unit = FluxPartSerializer::write
) {
    private val lock = Any()
    private var pendingWriteJob: Job? = null

    fun scheduleWrite(state: FluxPartState) {
        synchronized(lock) {
            pendingWriteJob?.cancel()
            pendingWriteJob = scope.launch {
                delay(debounceMs)
                writeOnIo(state)
            }
        }
    }

    suspend fun flush(state: FluxPartState) {
        val jobToCancel = synchronized(lock) {
            pendingWriteJob.also { pendingWriteJob = null }
        }
        jobToCancel?.cancelAndJoin()
        writeOnIo(state)
    }

    fun cancel() {
        synchronized(lock) {
            pendingWriteJob?.cancel()
            pendingWriteJob = null
        }
    }

    private suspend fun writeOnIo(state: FluxPartState) {
        withContext(Dispatchers.IO) {
            writer(state, fluxPartFile)
        }
    }
}
