package com.ydoc.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_targets")
data class SyncTargetEntity(
    @PrimaryKey val type: String,
    val enabled: Boolean,
    val configJson: String,
    val updatedAt: Long,
)
