package com.ydoc.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey val id: String,
    val title: String,
    val content: String,
    val source: String,
    val category: String,
    val priority: String,
    val colorToken: String,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastSyncedAt: Long?,
    val audioPath: String?,
    val audioFormat: String?,
    val relayFileId: String?,
    val relayUrl: String?,
    val relayExpiresAt: Long?,
    val transcript: String?,
    val transcriptionStatus: String,
    val transcriptionError: String?,
    val transcriptionRequestId: String?,
    val transcriptionUpdatedAt: Long?,
    val syncError: String?,
    val pinned: Boolean,
    val remotePath: String?,
    val lastPulledAt: Long?,
)
