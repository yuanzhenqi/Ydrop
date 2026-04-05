package com.ydoc.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AiSuggestionDao {
    @Query("SELECT * FROM ai_suggestions ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<AiSuggestionEntity>>

    @Query("SELECT * FROM ai_suggestions WHERE noteId = :noteId LIMIT 1")
    suspend fun getByNoteId(noteId: String): AiSuggestionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(suggestion: AiSuggestionEntity)

    @Query("DELETE FROM ai_suggestions WHERE noteId = :noteId")
    suspend fun deleteByNoteId(noteId: String)
}
