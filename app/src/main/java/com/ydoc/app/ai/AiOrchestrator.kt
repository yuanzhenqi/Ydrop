package com.ydoc.app.ai

import com.ydoc.app.data.AiSuggestionRepository
import com.ydoc.app.data.NoteRepository
import com.ydoc.app.data.SettingsStore
import com.ydoc.app.model.AiAnalyzeRequest
import com.ydoc.app.model.AiConfig
import com.ydoc.app.model.AiRunTrigger
import com.ydoc.app.model.AiSuggestion
import com.ydoc.app.model.AiSuggestionStatus
import java.util.UUID
import kotlinx.coroutines.flow.first

class AiOrchestrator(
    private val noteRepository: NoteRepository,
    private val aiSuggestionRepository: AiSuggestionRepository,
    private val aiClient: AiClient,
    private val settingsStore: SettingsStore,
) {
    suspend fun maybeAnalyze(noteId: String, trigger: AiRunTrigger) {
        val settings = settingsStore.settingsFlow.first().ai
        val shouldRun = when (trigger) {
            AiRunTrigger.TEXT_SAVE -> settings.enabled && settings.autoRunOnTextSave
            AiRunTrigger.VOICE_TRANSCRIBED -> settings.enabled && settings.autoRunOnVoiceTranscribed
            AiRunTrigger.MANUAL -> settings.enabled
        }
        if (!shouldRun) return
        analyze(noteId, settings, trigger)
    }

    suspend fun analyzeNow(noteId: String) {
        val settings = settingsStore.settingsFlow.first().ai
        require(settings.enabled) { "请先启用 AI 整理" }
        analyze(noteId, settings, AiRunTrigger.MANUAL)
    }

    private suspend fun analyze(noteId: String, settings: AiConfig, trigger: AiRunTrigger) {
        val note = noteRepository.getNote(noteId) ?: return
        val now = System.currentTimeMillis()
        val running = aiSuggestionRepository.getByNoteId(noteId)?.copy(
            status = AiSuggestionStatus.RUNNING,
            errorMessage = null,
            updatedAt = now,
        ) ?: AiSuggestion(
            id = UUID.randomUUID().toString(),
            noteId = noteId,
            status = AiSuggestionStatus.RUNNING,
            summary = "",
            suggestedTitle = null,
            suggestedCategory = null,
            suggestedPriority = null,
            todoItems = emptyList(),
            extractedEntities = emptyList(),
            reminderCandidates = emptyList(),
            errorMessage = null,
            createdAt = now,
            updatedAt = now,
        )
        aiSuggestionRepository.upsert(running)

        runCatching {
            aiClient.analyze(
                AiAnalyzeRequest(
                    noteId = note.id,
                    title = note.title,
                    content = note.content,
                    source = note.source.name,
                    category = note.category.name,
                    priority = note.priority.name,
                    transcript = note.transcript,
                    trigger = trigger.name,
                    model = settings.model,
                ),
                settings,
            )
        }.onSuccess { response ->
            aiSuggestionRepository.upsert(
                running.copy(
                    status = AiSuggestionStatus.READY,
                    summary = response.summary,
                    suggestedTitle = response.suggestedTitle,
                    suggestedCategory = response.suggestedCategory
                        ?.takeIf { it.isNotBlank() }
                        ?.let { runCatching { com.ydoc.app.model.NoteCategory.valueOf(it.uppercase()) }.getOrNull() },
                    suggestedPriority = response.suggestedPriority
                        ?.takeIf { it.isNotBlank() }
                        ?.let { runCatching { com.ydoc.app.model.NotePriority.valueOf(it.uppercase()) }.getOrNull() },
                    todoItems = response.todoItems,
                    extractedEntities = response.extractedEntities,
                    reminderCandidates = response.reminderCandidates,
                    errorMessage = null,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }.onFailure { error ->
            aiSuggestionRepository.upsert(
                running.copy(
                    status = AiSuggestionStatus.FAILED,
                    errorMessage = error.message ?: "AI 整理失败",
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }
}
