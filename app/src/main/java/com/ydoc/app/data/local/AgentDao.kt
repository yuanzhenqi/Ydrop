package com.ydoc.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentDao {
    @Query("SELECT * FROM agent_sessions ORDER BY updatedAt DESC")
    fun observeSessions(): Flow<List<AgentSessionEntity>>

    @Query("SELECT * FROM agent_messages WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    fun observeMessages(sessionId: String): Flow<List<AgentMessageEntity>>

    @Query("SELECT * FROM agent_sessions WHERE id = :id LIMIT 1")
    suspend fun getSession(id: String): AgentSessionEntity?

    @Query("SELECT id FROM agent_sessions WHERE relaySessionId = :relayId LIMIT 1")
    suspend fun findLocalIdByRelayId(relayId: String): String?

    @Query("SELECT * FROM agent_messages WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    suspend fun getMessages(sessionId: String): List<AgentMessageEntity>

    // 关键：session 的 upsert 必须用 @Upsert（走 UPDATE），不能用 @Insert(REPLACE)。
    // REPLACE 在 SQLite 里展开成 DELETE+INSERT，会触发 agent_messages 的 ON DELETE CASCADE，
    // 把这个 session 下的所有消息一并删光（曾经的 bug：发送后什么都不显示，title 却正确更新）。
    @Upsert
    suspend fun upsertSession(entity: AgentSessionEntity)

    @Upsert
    suspend fun upsertSessions(entities: List<AgentSessionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(entity: AgentMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessages(entities: List<AgentMessageEntity>)

    @Query("DELETE FROM agent_sessions WHERE id = :id")
    suspend fun deleteSession(id: String)

    @Query("DELETE FROM agent_messages WHERE sessionId = :sessionId AND pending = 0")
    suspend fun deleteNonPendingMessages(sessionId: String)

    /** 把超过 [thresholdMs] 以前仍在 pending=1 的 assistant 占位气泡翻成 ERROR，避免停留 UI 上永远转圈。 */
    @Query(
        "UPDATE agent_messages SET pending = 0, role = 'error', content = :errorMessage, providerError = :errorMessage " +
            "WHERE pending = 1 AND createdAt < :thresholdMs",
    )
    suspend fun expireStalePending(thresholdMs: Long, errorMessage: String): Int
}
