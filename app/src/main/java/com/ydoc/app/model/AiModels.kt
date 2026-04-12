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
    val promptSupplement: String = "",
    val endpointMode: AiEndpointMode = AiEndpointMode.AUTO,
    val autoRunOnTextSave: Boolean = true,
    val autoRunOnVoiceTranscribed: Boolean = true,
    val autoRetryOnTransientFailure: Boolean = true,
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
    val scheduledAtIso: String? = null,
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
    val currentTimeText: String,
    val currentTimezone: String,
    val currentTimeEpochMs: Long,
    val prompt: String? = null,
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

fun AiAnalyzeResponse.isEffectivelyEmpty(): Boolean =
    summary.isBlank() &&
        suggestedTitle.isNullOrBlank() &&
        suggestedCategory.isNullOrBlank() &&
        suggestedPriority.isNullOrBlank() &&
        todoItems.isEmpty() &&
        extractedEntities.isEmpty() &&
        reminderCandidates.isEmpty()

fun AiAnalyzeRequest.minimalSummaryResponse(): AiAnalyzeResponse =
    AiAnalyzeResponse(
        summary = minimalSummaryText(),
        suggestedTitle = null,
        suggestedCategory = null,
        suggestedPriority = null,
        todoItems = emptyList(),
        extractedEntities = emptyList(),
        reminderCandidates = emptyList(),
    )

fun AiSuggestion.hasVisibleContent(): Boolean =
    summary.isNotBlank() ||
        !suggestedTitle.isNullOrBlank() ||
        suggestedCategory != null ||
        suggestedPriority != null ||
        todoItems.isNotEmpty() ||
        extractedEntities.isNotEmpty() ||
        reminderCandidates.isNotEmpty()

fun AiSuggestion.shouldShowPanel(): Boolean = when (status) {
    AiSuggestionStatus.RUNNING,
    AiSuggestionStatus.FAILED,
    -> true

    AiSuggestionStatus.READY -> hasVisibleContent()
    AiSuggestionStatus.APPLIED,
    AiSuggestionStatus.DISMISSED,
    -> false
}

fun defaultAiPromptTemplate(): String =
    """
    You are the structured extraction engine for a personal assistant app.
    Analyze the note, transcript, and current context to produce structured help for reminders, schedules, tasks, and general organization.
    Always write a concise non-empty summary using the user's own perspective and wording style.
    Never describe the note as "the user said", "the user wants", or similar third-person phrasing.
    Use the provided current system time and timezone to resolve relative dates and times.
    The note content may be in Chinese. Recognize Chinese date/time expressions: 明天(+1d), 后天(+2d), 大后天(+3d), 下周一/下周二 etc., 上午(AM), 下午(PM), 晚上(evening), 凌晨(early morning).
    When converting: 上午7:30→07:30, 下午3点→15:00, 晚上8点→20:00, 凌晨2点→02:00, 中午12点→12:00.
    reminderCandidates[].scheduledAt must be the EXACT epoch ms of the resolved future time, computed by adding the date offset to currentTimeEpochMs and adjusting the time-of-day in the user's timezone.
    reminderCandidates[].scheduledAtIso must be the same resolved time in ISO 8601 format "YYYY-MM-DDTHH:mm" in the user's timezone.
    If the note clearly asks for a reminder or contains a concrete future time, prefer suggestedCategory = REMINDER and create reminderCandidates.
    If the note is more like a planned task or schedule but the exact reminder time is not reliable enough, prefer suggestedCategory = TASK and leave reminderCandidates empty.
    If the note is mainly a plain record with no action or time intent, prefer suggestedCategory = NOTE.
    Suggest a clearer title only when the current title is vague.
    Suggest priority only when it is reasonably inferable; otherwise default to MEDIUM.
    Extract concrete todo items from explicit or strongly implied actions.
    Extract useful entities such as intent, people, dates, times, places, organizations, projects, and reference values when relevant.
    Do not guess reminder times when the time expression is too ambiguous to resolve with confidence.
    """.trimIndent()

fun legacyAiPromptTemplate(): String =
    """
    Analyze the note and produce structured assistance for capture, organization, and follow-up.
    Always write a concise non-empty summary, even when there are no todos or reminders to extract.
    Suggest a clearer title only when the current title is vague.
    Suggest category and priority only when they are reasonably inferable from the note.
    Use the provided current system time and timezone to resolve relative dates and times.
    Extract concrete todo items from explicit or strongly implied actions.
    Extract useful entities such as people, dates, times, places, organizations, projects, and reference values when relevant.
    Create reminder candidates only when the note clearly contains a future time, deadline, or explicit reminder intent, and convert reminderCandidates[].scheduledAt into absolute Unix milliseconds.
    """.trimIndent()

private fun AiAnalyzeRequest.minimalSummaryText(): String {
    val primarySource = transcript?.takeIf { it.isNotBlank() }
        ?: content.takeIf { it.isNotBlank() }
        ?: title.takeIf { it.isNotBlank() }

    val normalized = primarySource
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        .orEmpty()

    if (normalized.isBlank()) {
        return "This note was captured and can be reviewed later."
    }

    val sentence = firstSentence(normalized)
    return sentence.take(MAX_FALLBACK_SUMMARY_LENGTH).ifBlank {
        "This note was captured and can be reviewed later."
    }
}

private fun firstSentence(text: String): String {
    val markers = listOf("。", "！", "？", ".", "!", "?", "\n")
    val firstMarkerIndex = markers
        .map { marker -> text.indexOf(marker).takeIf { it >= 0 } }
        .filterNotNull()
        .minOrNull()

    return if (firstMarkerIndex != null) {
        text.substring(0, firstMarkerIndex).trim()
    } else {
        text.trim()
    }
}

private const val MAX_FALLBACK_SUMMARY_LENGTH = 96
