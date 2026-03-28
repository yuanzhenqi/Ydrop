package com.ydoc.app.model

data class Note(
    val id: String,
    val title: String,
    val content: String,
    val source: NoteSource,
    val category: NoteCategory,
    val priority: NotePriority,
    val colorToken: NoteColorToken,
    val status: NoteStatus,
    val createdAt: Long,
    val updatedAt: Long,
    val lastSyncedAt: Long?,
    val audioPath: String?,
    val audioFormat: String?,
    val relayFileId: String?,
    val relayUrl: String?,
    val relayExpiresAt: Long?,
    val transcript: String?,
    val transcriptionStatus: TranscriptionStatus,
    val transcriptionError: String?,
    val transcriptionRequestId: String?,
    val transcriptionUpdatedAt: Long?,
    val syncError: String?,
    val pinned: Boolean,
)

enum class NoteSource {
    TEXT,
    VOICE,
}

enum class NoteStatus {
    LOCAL_ONLY,
    SYNCING,
    SYNCED,
    FAILED,
}

enum class NoteCategory {
    NOTE,
    TODO,
    TASK,
    REMINDER,
}

enum class NotePriority {
    LOW,
    MEDIUM,
    HIGH,
    URGENT,
}

enum class NoteColorToken {
    SAGE,
    AMBER,
    SKY,
    ROSE,
}

enum class TranscriptionStatus {
    NOT_STARTED,
    UPLOADING,
    TRANSCRIBING,
    DONE,
    FAILED,
}

fun defaultColorFor(category: NoteCategory, priority: NotePriority): NoteColorToken = when {
    priority == NotePriority.URGENT -> NoteColorToken.ROSE
    category == NoteCategory.TODO -> NoteColorToken.AMBER
    category == NoteCategory.TASK -> NoteColorToken.SKY
    category == NoteCategory.REMINDER -> NoteColorToken.ROSE
    else -> NoteColorToken.SAGE
}
