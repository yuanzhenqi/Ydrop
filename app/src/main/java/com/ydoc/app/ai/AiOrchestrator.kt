package com.ydoc.app.ai

import com.ydoc.app.data.AiSuggestionRepository
import com.ydoc.app.data.NoteRepository
import com.ydoc.app.data.SettingsStore
import com.ydoc.app.model.AiAnalyzeRequest
import com.ydoc.app.model.AiConfig
import com.ydoc.app.model.AiRunTrigger
import com.ydoc.app.model.AiSuggestion
import com.ydoc.app.model.AiSuggestionStatus
import com.ydoc.app.model.isEffectivelyEmpty
import com.ydoc.app.model.minimalSummaryResponse
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.delay
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
        val previousSuggestion = aiSuggestionRepository.getByNoteId(noteId)
        val rerunAppliedVoiceNote = previousSuggestion?.status == AiSuggestionStatus.APPLIED &&
            trigger == AiRunTrigger.MANUAL

        val running = previousSuggestion?.copy(
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

        val request = AiAnalyzeRequest(
            noteId = note.id,
            title = note.title,
            content = note.content,
            source = note.source.name,
            category = note.category.name,
            priority = note.priority.name,
            transcript = if (rerunAppliedVoiceNote) null else note.transcript,
            trigger = trigger.name,
            model = settings.model,
            currentTimeText = formatCurrentTime(now),
            currentTimezone = TimeZone.getDefault().id,
            currentTimeEpochMs = now,
            prompt = settings.promptSupplement,
        )

        val analysisResult = runCatching { analyzeWithRetry(request, settings) }

        analysisResult.onSuccess { response ->
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

    private fun formatCurrentTime(nowMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).apply {
            timeZone = TimeZone.getDefault()
        }.format(Date(nowMillis))

    private suspend fun analyzeWithRetry(
        request: AiAnalyzeRequest,
        settings: AiConfig,
    ) = runRetryingAiAnalysis(request, settings)

    private suspend fun runRetryingAiAnalysis(
        request: AiAnalyzeRequest,
        settings: AiConfig,
    ): com.ydoc.app.model.AiAnalyzeResponse {
        var retriesPerformed = 0

        while (true) {
            val result = runCatching {
                aiClient.analyze(request, settings).let { response ->
                    if (response.isEffectivelyEmpty()) request.minimalSummaryResponse() else response
                }
            }

            val response = result.getOrNull()
            if (response != null) {
                return response
            }

            val error = result.exceptionOrNull() ?: error("AI analysis failed without an exception")
            val shouldRetry = settings.autoRetryOnTransientFailure &&
                retriesPerformed < MAX_TRANSIENT_AI_RETRIES &&
                error.isTransientAiFailure()

            if (!shouldRetry) {
                throw error.withRetrySummary(retriesPerformed)
            }

            delay(transientRetryDelayMs(retriesPerformed))
            retriesPerformed += 1
        }
    }

    private fun Throwable.isTransientAiFailure(): Boolean {
        val chain = generateSequence(this) { it.cause }.toList()
        if (chain.any { it is SocketTimeoutException || it is ConnectException || it is UnknownHostException || it is InterruptedIOException }) {
            return true
        }

        val messages = chain.mapNotNull { it.message?.lowercase() }
        if (messages.any { message ->
                message.contains("http 408") ||
                    message.contains("http 502") ||
                    message.contains("http 503") ||
                    message.contains("http 504") ||
                    message.contains("timed out") ||
                    message.contains("timeout") ||
                    message.contains("failed to connect") ||
                    message.contains("connection reset") ||
                    message.contains("unexpected end of stream")
            }
        ) {
            return true
        }

        return false
    }

    private fun Throwable.withRetrySummary(retriesPerformed: Int): Throwable {
        if (retriesPerformed == 0) return this
        val message = "${message ?: "AI 整理失败"}（已自动重试 $retriesPerformed 次）"
        return when (this) {
            is IOException -> IOException(message, this)
            is IllegalStateException -> IllegalStateException(message, this)
            else -> IllegalStateException(message, this)
        }
    }

    private fun transientRetryDelayMs(retriesPerformed: Int): Long = when (retriesPerformed) {
        0 -> 3_000L
        1 -> 10_000L
        2 -> 20_000L
        3 -> 40_000L
        else -> 60_000L
    }

    private companion object {
        const val MAX_TRANSIENT_AI_RETRIES = 5
    }
}
