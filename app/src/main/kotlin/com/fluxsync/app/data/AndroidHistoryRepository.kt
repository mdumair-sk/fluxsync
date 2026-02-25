package com.fluxsync.app.data

import com.fluxsync.core.transfer.TransferDirection
import com.fluxsync.core.transfer.TransferHistoryEntry
import com.fluxsync.core.transfer.TransferHistoryRepository

/**
 * Bridges the Room [TransferHistoryDao] to the core [TransferHistoryRepository] interface.
 * All mapping between Room entities and domain models happens here.
 */
class AndroidHistoryRepository(
    private val dao: TransferHistoryDao,
) : TransferHistoryRepository {

    override suspend fun insert(entry: TransferHistoryEntry) {
        dao.insert(entry.toEntity())
    }

    override suspend fun getAll(): List<TransferHistoryEntry> {
        return dao.getAll().map { it.toDomain() }
    }

    override suspend fun getFiltered(direction: TransferDirection): List<TransferHistoryEntry> {
        return dao.getByDirection(direction.name).map { it.toDomain() }
    }

    override suspend fun delete(id: String) {
        dao.delete(id)
    }

    override suspend fun getById(id: String): TransferHistoryEntry? {
        return dao.getById(id)?.toDomain()
    }
}
