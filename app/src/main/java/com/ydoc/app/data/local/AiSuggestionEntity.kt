package com.ydoc.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ai_suggestions")
data class AiSuggestionEntity(
    @PrimaryKey val id: String,
    val noteId: String,
    val status: String,
    val summary: String,
    val suggestedTitle: String?,
    val suggestedCategory: String?,
    val suggestedPriority: String?,
    val suggestedTagsJson: String = "[]",
    val todoItemsJson: String,
    val extractedEntitiesJson: String,
    val reminderCandidatesJson: String,
    val errorMessage: String?,
    val createdAt: Long,
    val updatedAt: Long,
)
