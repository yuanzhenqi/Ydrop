package com.ydoc.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "reminders")
data class ReminderEntryEntity(
    @PrimaryKey val id: String,
    val noteId: String,
    val title: String,
    val scheduledAt: Long,
    val source: String,
    val status: String,
    val deliveryTargetsJson: String,
    val createdAt: Long,
    val updatedAt: Long,
)
