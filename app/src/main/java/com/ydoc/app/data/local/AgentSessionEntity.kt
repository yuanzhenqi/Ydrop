package com.ydoc.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Agent 会话的本地缓存。relay 端 (`/api/ai/chat`, `/api/ai/sessions`) 才是权威来源，
 * 本地只做离线翻阅和乐观写。严禁接入 WebDAV 同步。
 */
@Entity(tableName = "agent_sessions")
data class AgentSessionEntity(
    @PrimaryKey val id: String,
    val relaySessionId: String?,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastMessagePreview: String?,
)
