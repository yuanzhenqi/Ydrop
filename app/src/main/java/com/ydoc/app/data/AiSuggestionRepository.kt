package com.ydoc.app.data

import com.ydoc.app.data.local.AiSuggestionDao
import com.ydoc.app.model.AiSuggestion
import com.ydoc.app.model.AiSuggestionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AiSuggestionRepository(
    private val aiSuggestionDao: AiSuggestionDao,
) {
    fun observeSuggestions(): Flow<List<AiSuggestion>> =
        aiSuggestionDao.observeAll().map { items -> items.map { it.toModel() } }

    suspend fun getByNoteId(noteId: String): AiSuggestion? = aiSuggestionDao.getByNoteId(noteId)?.toModel()

    suspend fun upsert(suggestion: AiSuggestion) {
        aiSuggestionDao.upsert(suggestion.toEntity())
    }

    suspend fun deleteByNoteId(noteId: String) {
        aiSuggestionDao.deleteByNoteId(noteId)
    }

    suspend fun markStatus(noteId: String, status: AiSuggestionStatus, errorMessage: String? = null) {
        val current = getByNoteId(noteId) ?: return
        upsert(
            current.copy(
                status = status,
                errorMessage = errorMessage,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }
}
