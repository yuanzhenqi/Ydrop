package com.ydoc.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tombstones")
data class TombstoneEntity(
    @PrimaryKey val noteId: String,
    val deletedAt: Long,
)
