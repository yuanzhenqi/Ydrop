package com.ydoc.app.data

import com.ydoc.app.data.local.AiSuggestionEntity
import com.ydoc.app.data.local.NoteEntity
import com.ydoc.app.data.local.ReminderEntryEntity
import com.ydoc.app.data.local.SyncTargetEntity
import com.ydoc.app.model.AiSuggestion
import com.ydoc.app.model.AiSuggestionStatus
import com.ydoc.app.model.ExtractedEntity
import com.ydoc.app.model.NoteCategory
import com.ydoc.app.model.NoteColorToken
import com.ydoc.app.model.Note
import com.ydoc.app.model.NotePriority
import com.ydoc.app.model.NoteSource
import com.ydoc.app.model.NoteStatus
import com.ydoc.app.model.ReminderCandidate
import com.ydoc.app.model.ReminderDeliveryTarget
import com.ydoc.app.model.ReminderEntry
import com.ydoc.app.model.ReminderSource
import com.ydoc.app.model.ReminderStatus
import com.ydoc.app.model.SyncTarget
import com.ydoc.app.model.SyncType
import com.ydoc.app.model.TranscriptionStatus
import com.ydoc.app.model.WebDavConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

private val json = Json { ignoreUnknownKeys = true }

fun NoteEntity.toModel(): Note =
    Note(
        id = id,
        title = title,
        content = content,
        originalContent = originalContent,
        source = NoteSource.valueOf(source),
        category = NoteCategory.valueOf(category),
        priority = NotePriority.valueOf(priority),
        colorToken = NoteColorToken.valueOf(colorToken),
        status = NoteStatus.valueOf(status),
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastSyncedAt = lastSyncedAt,
        audioPath = audioPath,
        audioFormat = audioFormat,
        audioPublicUri = audioPublicUri,
        relayFileId = relayFileId,
        relayUrl = relayUrl,
        relayExpiresAt = relayExpiresAt,
        transcript = transcript,
        transcriptionStatus = TranscriptionStatus.valueOf(transcriptionStatus),
        transcriptionError = transcriptionError,
        transcriptionRequestId = transcriptionRequestId,
        transcriptionUpdatedAt = transcriptionUpdatedAt,
        syncError = syncError,
        pinned = pinned,
        remotePath = remotePath,
        lastPulledAt = lastPulledAt,
        isArchived = isArchived,
        archivedAt = archivedAt,
        isTrashed = isTrashed,
        trashedAt = trashedAt,
        tags = tagsJson?.let { json.decodeFromString(ListSerializer(String.serializer()), it) } ?: emptyList(),
    )

fun Note.toEntity(): NoteEntity =
    NoteEntity(
        id = id,
        title = title,
        content = content,
        originalContent = originalContent,
        source = source.name,
        category = category.name,
        priority = priority.name,
        colorToken = colorToken.name,
        status = status.name,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastSyncedAt = lastSyncedAt,
        audioPath = audioPath,
        audioFormat = audioFormat,
        audioPublicUri = audioPublicUri,
        relayFileId = relayFileId,
        relayUrl = relayUrl,
        relayExpiresAt = relayExpiresAt,
        transcript = transcript,
        transcriptionStatus = transcriptionStatus.name,
        transcriptionError = transcriptionError,
        transcriptionRequestId = transcriptionRequestId,
        transcriptionUpdatedAt = transcriptionUpdatedAt,
        syncError = syncError,
        pinned = pinned,
        remotePath = remotePath,
        lastPulledAt = lastPulledAt,
        isArchived = isArchived,
        archivedAt = archivedAt,
        isTrashed = isTrashed,
        trashedAt = trashedAt,
        tagsJson = if (tags.isEmpty()) null else json.encodeToString(ListSerializer(String.serializer()), tags),
    )

fun SyncTargetEntity.toModelOrNull(): SyncTarget? {
    val syncType = runCatching { SyncType.valueOf(type) }.getOrNull() ?: return null
    return SyncTarget(
        type = syncType,
        enabled = enabled,
        config = json.decodeFromString<WebDavConfig>(configJson),
        updatedAt = updatedAt,
    )
}

fun SyncTarget.toEntity(): SyncTargetEntity =
    SyncTargetEntity(
        type = type.name,
        enabled = enabled,
        configJson = json.encodeToString(WebDavConfig.serializer(), config as WebDavConfig),
        updatedAt = updatedAt,
    )

fun AiSuggestionEntity.toModel(): AiSuggestion =
    AiSuggestion(
        id = id,
        noteId = noteId,
        status = AiSuggestionStatus.valueOf(status),
        summary = summary,
        suggestedTitle = suggestedTitle,
        suggestedCategory = suggestedCategory?.let(NoteCategory::valueOf),
        suggestedPriority = suggestedPriority?.let(NotePriority::valueOf),
        suggestedTags = runCatching { json.decodeFromString<List<String>>(suggestedTagsJson) }.getOrDefault(emptyList()),
        todoItems = json.decodeFromString(todoItemsJson),
        extractedEntities = json.decodeFromString(extractedEntitiesJson),
        reminderCandidates = json.decodeFromString(reminderCandidatesJson),
        errorMessage = errorMessage,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

fun AiSuggestion.toEntity(): AiSuggestionEntity =
    AiSuggestionEntity(
        id = id,
        noteId = noteId,
        status = status.name,
        summary = summary,
        suggestedTitle = suggestedTitle,
        suggestedCategory = suggestedCategory?.name,
        suggestedPriority = suggestedPriority?.name,
        suggestedTagsJson = json.encodeToString(ListSerializer(String.serializer()), suggestedTags),
        todoItemsJson = json.encodeToString(ListSerializer(String.serializer()), todoItems),
        extractedEntitiesJson = json.encodeToString(ListSerializer(ExtractedEntity.serializer()), extractedEntities),
        reminderCandidatesJson = json.encodeToString(ListSerializer(ReminderCandidate.serializer()), reminderCandidates),
        errorMessage = errorMessage,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

fun ReminderEntryEntity.toModel(): ReminderEntry =
    ReminderEntry(
        id = id,
        noteId = noteId,
        title = title,
        scheduledAt = scheduledAt,
        source = ReminderSource.valueOf(source),
        status = ReminderStatus.valueOf(status),
        deliveryTargets = json.decodeFromString<List<String>>(deliveryTargetsJson)
            .map(ReminderDeliveryTarget::valueOf)
            .toSet(),
        createdAt = createdAt,
        updatedAt = updatedAt,
        systemEventId = systemEventId,
    )

fun ReminderEntry.toEntity(): ReminderEntryEntity =
    ReminderEntryEntity(
        id = id,
        noteId = noteId,
        title = title,
        scheduledAt = scheduledAt,
        source = source.name,
        status = status.name,
        deliveryTargetsJson = json.encodeToString(ListSerializer(String.serializer()), deliveryTargets.map { it.name }),
        createdAt = createdAt,
        updatedAt = updatedAt,
        systemEventId = systemEventId,
    )
