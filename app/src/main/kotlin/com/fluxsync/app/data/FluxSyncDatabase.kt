package com.fluxsync.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [TransferHistoryEntryEntity::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(HistoryTypeConverters::class)
abstract class FluxSyncDatabase : RoomDatabase() {

    abstract fun historyDao(): TransferHistoryDao

    companion object {
        fun build(context: Context): FluxSyncDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                FluxSyncDatabase::class.java,
                "fluxsync.db",
            )
                .fallbackToDestructiveMigration()
                .build()
    }
}
