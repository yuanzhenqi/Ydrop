package com.ydoc.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderEntryDao {
    @Query("SELECT * FROM reminders ORDER BY scheduledAt ASC")
    fun observeAll(): Flow<List<ReminderEntryEntity>>

    @Query("SELECT * FROM reminders WHERE status = 'SCHEDULED' ORDER BY scheduledAt ASC")
    suspend fun getScheduled(): List<ReminderEntryEntity>

    @Query("SELECT * FROM reminders WHERE noteId = :noteId ORDER BY scheduledAt ASC")
    suspend fun getByNoteId(noteId: String): List<ReminderEntryEntity>

    @Query("SELECT * FROM reminders WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ReminderEntryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(reminder: ReminderEntryEntity)

    @Query("UPDATE reminders SET status = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, updatedAt: Long)

    @Query("DELETE FROM reminders WHERE noteId = :noteId")
    suspend fun deleteByNoteId(noteId: String)
}
