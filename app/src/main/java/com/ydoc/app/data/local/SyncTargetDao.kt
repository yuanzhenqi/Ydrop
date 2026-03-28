package com.ydoc.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncTargetDao {
    @Query("SELECT * FROM sync_targets")
    fun observeAll(): Flow<List<SyncTargetEntity>>

    @Query("SELECT * FROM sync_targets")
    suspend fun getAll(): List<SyncTargetEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(target: SyncTargetEntity)
}
