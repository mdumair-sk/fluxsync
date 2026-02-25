package com.fluxsync.desktop.app.history

import com.fluxsync.core.transfer.TransferDirection
import com.fluxsync.core.transfer.TransferHistoryEntry
import com.fluxsync.core.transfer.TransferHistoryRepository
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class JsonFileHistoryRepository(
    private val historyFile: File,
    private val maxEntries: Int = 500,
) : TransferHistoryRepository {

    override suspend fun insert(entry: TransferHistoryEntry): Unit = withContext(Dispatchers.IO) {
        lock.write {
            val entries = loadEntriesLocked().toMutableList()
            if (maxEntries <= 0) {
                writeEntriesLocked(emptyList())
                return@write
            }

            if (entries.size >= maxEntries) {
                val oldestEntry = entries.minByOrNull { it.startedAtMs }
                if (oldestEntry != null) {
                    entries.remove(oldestEntry)
                }
            }
            entries.add(entry)
            writeEntriesLocked(entries)
        }
    }

    override suspend fun getAll(): List<TransferHistoryEntry> = withContext(Dispatchers.IO) {
        lock.read {
            loadEntriesLocked()
        }
    }

    override suspend fun getFiltered(direction: TransferDirection): List<TransferHistoryEntry> = withContext(Dispatchers.IO) {
        lock.read {
            loadEntriesLocked().filter { it.direction == direction }
        }
    }

    override suspend fun delete(id: String): Unit = withContext(Dispatchers.IO) {
        lock.write {
            val updatedEntries = loadEntriesLocked().filterNot { it.id == id }
            writeEntriesLocked(updatedEntries)
        }
    }

    override suspend fun getById(id: String): TransferHistoryEntry? = withContext(Dispatchers.IO) {
        lock.read {
            loadEntriesLocked().firstOrNull { it.id == id }
        }
    }

    private fun loadEntriesLocked(): List<TransferHistoryEntry> {
        if (!historyFile.exists()) {
            return emptyList()
        }

        return runCatching {
            val serialized = historyFile.readText()
            json.decodeFromString<HistoryFileContent>(serialized).entries
        }.onFailure { error ->
            logger.log(Level.WARNING, "Failed to parse transfer history JSON. Starting fresh.", error)
        }.getOrDefault(emptyList())
    }

    private fun writeEntriesLocked(entries: List<TransferHistoryEntry>) {
        historyFile.parentFile?.mkdirs()
        val payload = json.encodeToString(HistoryFileContent(entries = entries))
        historyFile.writeText(payload)
    }

    @Serializable
    private data class HistoryFileContent(
        val entries: List<TransferHistoryEntry> = emptyList(),
    )

    private val lock = ReentrantReadWriteLock()

    private companion object {
        private val logger: Logger = Logger.getLogger(JsonFileHistoryRepository::class.java.name)
        private val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }
    }
}
