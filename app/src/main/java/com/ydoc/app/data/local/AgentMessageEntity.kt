package com.ydoc.app.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Agent 会话中的单条消息。`pending = true` 表示本地刚插入、尚未收到 relay 回复；
 * `providerError` 非空说明 provider 侧出错但消息已落地。严禁接入 WebDAV 同步。
 */
@Entity(
    tableName = "agent_messages",
    foreignKeys = [
        ForeignKey(
            entity = AgentSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class AgentMessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val role: String,
    val content: String,
    val referencedNoteIdsJson: String,
    val createdAt: Long,
    val providerError: String?,
    val pending: Boolean,
)
