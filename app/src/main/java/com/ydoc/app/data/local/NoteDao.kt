package com.ydoc.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE isTrashed = 0 AND isArchived = 0 ORDER BY pinned DESC, updatedAt DESC")
    fun observeActive(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE isArchived = 1 AND isTrashed = 0 ORDER BY archivedAt DESC")
    fun observeArchived(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE isTrashed = 1 ORDER BY trashedAt DESC")
    fun observeTrashed(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE status != 'SYNCED' AND isTrashed = 0 ORDER BY updatedAt ASC")
    suspend fun getPendingSync(): List<NoteEntity>

    @Query("SELECT * FROM notes")
    suspend fun getAll(): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): NoteEntity?

    @Query("UPDATE notes SET status = :status, lastSyncedAt = :lastSyncedAt, syncError = :syncError WHERE id = :id")
    suspend fun updateSyncMetadata(id: String, status: String, lastSyncedAt: Long?, syncError: String?)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: NoteEntity)

    @Update
    suspend fun update(note: NoteEntity)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM notes WHERE remotePath = :remotePath LIMIT 1")
    suspend fun getByRemotePath(remotePath: String): NoteEntity?

    @Query("UPDATE notes SET isArchived = 1, archivedAt = :archivedAt, updatedAt = :updatedAt, status = 'LOCAL_ONLY', lastSyncedAt = NULL, syncError = NULL WHERE id = :id")
    suspend fun archiveById(id: String, archivedAt: Long, updatedAt: Long)

    @Query("UPDATE notes SET isArchived = 0, archivedAt = NULL, updatedAt = :updatedAt, status = 'LOCAL_ONLY', lastSyncedAt = NULL, syncError = NULL WHERE id = :id")
    suspend fun unarchiveById(id: String, updatedAt: Long)

    @Query("UPDATE notes SET isTrashed = 1, trashedAt = :trashedAt, updatedAt = :updatedAt, status = 'LOCAL_ONLY', lastSyncedAt = NULL, syncError = NULL WHERE id = :id")
    suspend fun trashById(id: String, trashedAt: Long, updatedAt: Long)

    @Query("UPDATE notes SET isTrashed = 0, trashedAt = NULL, updatedAt = :updatedAt, status = 'LOCAL_ONLY', lastSyncedAt = NULL, syncError = NULL WHERE id = :id")
    suspend fun restoreById(id: String, updatedAt: Long)

    @Query("UPDATE notes SET pinned = :pinned, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean, updatedAt: Long)

    @Query("SELECT * FROM notes WHERE isTrashed = 1")
    suspend fun getTrashed(): List<NoteEntity>
}
