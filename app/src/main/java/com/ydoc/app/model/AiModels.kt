package com.ydoc.app.model

import kotlinx.serialization.Serializable

@Serializable
enum class AiEndpointMode {
    AUTO,
    RELAY,
    OPENAI,
    ANTHROPIC,
}

@Serializable
data class AiConfig(
    val enabled: Boolean = false,
    val baseUrl: String = "",
    val token: String = "",
    val model: String = "ydrop-notes-v1",
    val endpointMode: AiEndpointMode = AiEndpointMode.AUTO,
    val autoRunOnTextSave: Boolean = true,
    val autoRunOnVoiceTranscribed: Boolean = true,
)

enum class AiSuggestionStatus {
    RUNNING,
    READY,
    FAILED,
    APPLIED,
    DISMISSED,
}

enum class AiRunTrigger {
    TEXT_SAVE,
    VOICE_TRANSCRIBED,
    MANUAL,
}

@Serializable
data class ExtractedEntity(
    val label: String,
    val value: String,
)

@Serializable
data class ReminderCandidate(
    val title: String,
    val scheduledAt: Long,
    val reason: String? = null,
)

data class AiSuggestion(
    val id: String,
    val noteId: String,
    val status: AiSuggestionStatus,
    val summary: String,
    val suggestedTitle: String?,
    val suggestedCategory: NoteCategory?,
    val suggestedPriority: NotePriority?,
    val todoItems: List<String>,
    val extractedEntities: List<ExtractedEntity>,
    val reminderCandidates: List<ReminderCandidate>,
    val errorMessage: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class AiAnalyzeRequest(
    val noteId: String,
    val title: String,
    val content: String,
    val source: String,
    val category: String,
    val priority: String,
    val transcript: String? = null,
    val trigger: String,
    val model: String,
)

@Serializable
data class AiAnalyzeResponse(
    val summary: String = "",
    val suggestedTitle: String? = null,
    val suggestedCategory: String? = null,
    val suggestedPriority: String? = null,
    val todoItems: List<String> = emptyList(),
    val extractedEntities: List<ExtractedEntity> = emptyList(),
    val reminderCandidates: List<ReminderCandidate> = emptyList(),
)
