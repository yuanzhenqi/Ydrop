package com.ydoc.app.data

import com.ydoc.app.data.local.NoteEntity
import com.ydoc.app.data.local.SyncTargetEntity
import com.ydoc.app.model.NoteCategory
import com.ydoc.app.model.NoteColorToken
import com.ydoc.app.model.Note
import com.ydoc.app.model.NotePriority
import com.ydoc.app.model.NoteSource
import com.ydoc.app.model.NoteStatus
import com.ydoc.app.model.SyncTarget
import com.ydoc.app.model.SyncType
import com.ydoc.app.model.TranscriptionStatus
import com.ydoc.app.model.WebDavConfig
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

fun NoteEntity.toModel(): Note =
    Note(
        id = id,
        title = title,
        content = content,
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
    )

fun Note.toEntity(): NoteEntity =
    NoteEntity(
        id = id,
        title = title,
        content = content,
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
