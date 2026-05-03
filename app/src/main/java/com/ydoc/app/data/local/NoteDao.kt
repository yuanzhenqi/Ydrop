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

    // AI 助手上下文：活跃 + 归档都要，只排除回收站——用户常问的"归档里 X 相关"之前会因 observeActive 过滤掉而找不到。
    @Query("SELECT * FROM notes WHERE isTrashed = 0 ORDER BY pinned DESC, updatedAt DESC")
    fun observeAgentContextNotes(): Flow<List<NoteEntity>>

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

    // 只改 linkPreviewsJson，不动 updatedAt / status / lastSyncedAt——
    // 链接预览是系统后台产物，不应该把笔记推回 LOCAL_ONLY 触发一次多余的 WebDAV 推送。
    @Query("UPDATE notes SET linkPreviewsJson = :json WHERE id = :id")
    suspend fun updateLinkPreviewsJson(id: String, json: String?)

    // 同理：只改 attachmentsJson。OCR / vision 分析结果回填属于后台副作用，不应影响同步状态。
    @Query("UPDATE notes SET attachmentsJson = :json WHERE id = :id")
    suspend fun updateAttachmentsJson(id: String, json: String?)
}
