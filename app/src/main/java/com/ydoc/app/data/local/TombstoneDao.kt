package com.ydoc.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TombstoneDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tombstone: TombstoneEntity)

    @Query("SELECT * FROM tombstones WHERE noteId = :noteId LIMIT 1")
    suspend fun getById(noteId: String): TombstoneEntity?

    @Query("SELECT noteId FROM tombstones")
    suspend fun getAllIds(): List<String>

    @Query("DELETE FROM tombstones WHERE noteId = :noteId")
    suspend fun deleteById(noteId: String)
}
