package com.fluxsync.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface TransferHistoryDao {

    @Insert
    suspend fun insert(entry: TransferHistoryEntryEntity)

    @Query("SELECT * FROM history ORDER BY startedAtMs DESC")
    suspend fun getAll(): List<TransferHistoryEntryEntity>

    @Query("SELECT * FROM history WHERE direction = :dir ORDER BY startedAtMs DESC")
    suspend fun getByDirection(dir: String): List<TransferHistoryEntryEntity>

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM history WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TransferHistoryEntryEntity?
}
