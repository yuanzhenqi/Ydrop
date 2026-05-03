package com.ydoc.app.data

import com.ydoc.app.ai.AgentApiClient
import com.ydoc.app.data.local.AgentDao
import com.ydoc.app.data.local.AgentMessageEntity
import com.ydoc.app.data.local.AgentSessionEntity
import com.ydoc.app.logging.AppLogger
import com.ydoc.app.model.AgentMessage
import com.ydoc.app.model.AgentRole
import com.ydoc.app.model.AgentSession
import com.ydoc.app.model.RelayConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Agent 会话本地仓库。relay 端是权威来源：发送时先乐观写本地再调 relay；
 * 切会话/进入 tab 时按需 hydrate。严禁把这两张表接入 WebDAV / SyncOrchestrator。
 */
class AgentRepository(
    private val dao: AgentDao,
    private val api: AgentApiClient,
    private val settingsStore: SettingsStore,
    private val noteRepository: NoteRepository,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = false },
) {
    private val sendLocks = mutableMapOf<String, Mutex>()
    private val stringListSerializer = ListSerializer(String.serializer())

    fun observeSessions(): Flow<List<AgentSession>> =
        dao.observeSessions().map { list -> list.map { it.toDomain() } }

    fun observeMessages(sessionId: String): Flow<List<AgentMessage>> =
        dao.observeMessages(sessionId).map { list -> list.map { it.toDomain() } }

    /** 把老于 [ageMillis] 的 pending 占位气泡全部打成 ERROR 避免 UI 卡死。 */
    suspend fun expireStalePendingOlderThan(ageMillis: Long) {
        val threshold = System.currentTimeMillis() - ageMillis
        val affected = dao.expireStalePending(threshold, "发送中断，请重新发送。")
        if (affected > 0) {
            AppLogger.agent("expireStalePending cleared $affected stale pending bubbles")
        }
    }

    suspend fun createLocalSession(title: String = "新会话"): String {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        dao.upsertSession(
            AgentSessionEntity(
                id = id,
                relaySessionId = null,
                title = title,
                createdAt = now,
                updatedAt = now,
                lastMessagePreview = null,
            ),
        )
        return id
    }

    suspend fun renameSession(id: String, title: String) {
        val existing = dao.getSession(id) ?: return
        dao.upsertSession(existing.copy(title = title, updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteSession(id: String): Result<Unit> = runCatching {
        val existing = dao.getSession(id) ?: return@runCatching
        val relayId = existing.relaySessionId
        val relay = relayConfigOrNull()
        if (relayId != null && relay != null) {
            runCatching { api.deleteSession(relay, relayId) }
                .onFailure { AppLogger.agent("deleteSession relay failed: ${it.message}") }
        }
        dao.deleteSession(id)
    }

    suspend fun sendMessage(localSessionId: String, content: String): Result<Unit> {
        val mutex = synchronized(sendLocks) { sendLocks.getOrPut(localSessionId) { Mutex() } }
        return mutex.withLock { sendMessageInternal(localSessionId, content) }
    }

    private suspend fun sendMessageInternal(localSessionId: String, content: String): Result<Unit> = runCatching {
        AppLogger.agent("sendMessage start session=$localSessionId content_len=${content.length}")
        // 先把 session 补出来（即便是 ephemeral 的，确保后续 upsertMessages 的外键约束通得过）。
        val sessionAtStart = dao.getSession(localSessionId)
            ?: run {
                val now = System.currentTimeMillis()
                val seeded = AgentSessionEntity(
                    id = localSessionId,
                    relaySessionId = null,
                    title = content.take(30).ifBlank { "新会话" },
                    createdAt = now,
                    updatedAt = now,
                    lastMessagePreview = content.take(40),
                )
                dao.upsertSession(seeded)
                seeded
            }

        val now = System.currentTimeMillis()
        val userMessage = AgentMessageEntity(
            id = UUID.randomUUID().toString(),
            sessionId = localSessionId,
            role = AgentRole.USER.wireName(),
            content = content,
            referencedNoteIdsJson = "[]",
            createdAt = now,
            providerError = null,
            pending = false,
        )
        val placeholder = AgentMessageEntity(
            id = UUID.randomUUID().toString(),
            sessionId = localSessionId,
            role = AgentRole.ASSISTANT.wireName(),
            content = "",
            referencedNoteIdsJson = "[]",
            createdAt = now + 1,
            providerError = null,
            pending = true,
        )

        // 关键：无论 relay 可达与否，先把用户消息气泡和占位气泡落到本地 DB —— 这样即使
        // 后续任何一步抛异常，用户都能看到自己发出的内容和一条 ERROR bubble，不会出现
        // "按了发送啥都没发生"的体验。
        dao.upsertMessages(listOf(userMessage, placeholder))
        dao.upsertSession(
            sessionAtStart.copy(
                updatedAt = now,
                lastMessagePreview = content.take(40),
            ),
        )

        // 只要后续任何一步失败，都走这个闭包把 placeholder 变成 ERROR 气泡再抛
        val failPlaceholder: suspend (Throwable) -> Unit = { e ->
            AppLogger.agent("chat failed: ${e.message}")
            val errored = placeholder.copy(
                role = AgentRole.ERROR.wireName(),
                content = e.message ?: "发送失败。",
                providerError = e.message,
                pending = false,
            )
            dao.upsertMessage(errored)
        }

        val relay = relayConfigOrNull()
        if (relay == null) {
            val e = IllegalStateException("先在设置中配置中转服务地址和令牌。")
            failPlaceholder(e)
            throw e
        }

        val settings = runCatching { settingsStore.settingsFlow.first() }.getOrNull()
        val aiConfig = settings?.ai?.takeIf { it.baseUrl.isNotBlank() && it.token.isNotBlank() }?.let { cfg ->
            AgentApiClient.AiConfigWire(
                baseUrl = cfg.baseUrl,
                token = cfg.token,
                model = cfg.model.ifBlank { "gpt-4o-mini" },
                endpointMode = cfg.endpointMode.name,
            )
        }
        AppLogger.agent(
            "sendMessage ai_config_present=${aiConfig != null} base=${aiConfig?.baseUrl ?: "-"} model=${aiConfig?.model ?: "-"} mode=${aiConfig?.endpointMode ?: "-"}",
        )

        val history = dao.getMessages(localSessionId)
            .filter { it.id != placeholder.id && !it.pending && it.role != AgentRole.ERROR.wireName() }
            .map { AgentApiClient.WireMessage(role = it.role, content = it.content) }

        // 兜底：确保发给 relay 的 messages 至少含当前这条 user 输入。即使 Room 的
        // upsert→read 一致性出问题让 history 读空，也不能让 relay 收到 "role 里没 user"
        // 的空请求（provider 会直接拒：400 do not contain elements with the role of user）。
        val ensuredHistory = if (history.none { it.role == AgentRole.USER.wireName() }) {
            history + AgentApiClient.WireMessage(
                role = AgentRole.USER.wireName(),
                content = content,
            )
        } else {
            history
        }
        AppLogger.agent("sendMessage history_size=${history.size} ensured_size=${ensuredHistory.size}")

        // 把本地活跃 + 归档笔记都 inline 带过去——Android 端笔记不同步到 relay DB，
        // 不传的话 LLM 会看到空的 context，回答会变成"没找到相关笔记"。
        // 之前的 observeActiveNotes 只含活跃笔记，归档笔记对 AI 全程不可见；
        // 改走 observeAgentContextNotes 把归档也带上，只排回收站。
        val inlineNotes = runCatching {
            noteRepository.observeAgentContextNotes().first()
                .sortedByDescending { it.updatedAt }
                .take(80)
                .map { n ->
                    AgentApiClient.WireNote(
                        id = n.id,
                        title = n.title,
                        content = n.content,
                        category = n.category.name,
                        priority = n.priority.name,
                        tags = n.tags,
                        createdAt = n.createdAt,
                    )
                }
        }.getOrElse { e ->
            AppLogger.agent("collect inline notes failed: ${e.message}")
            emptyList()
        }
        val previewIds = inlineNotes.take(3).joinToString {
            "${it.id.take(6)}:${(it.title.ifBlank { it.content }).take(12)}"
        }
        AppLogger.agent("sendMessage inline_notes_size=${inlineNotes.size} preview=[$previewIds]")

        // 客户端当前时间锚点：让 LLM 能解析"今天/本周/最近 N 天"这类相对时间。
        // server 端没传时只能用 UTC 兜底，会让中文相对时间解析偏一天甚至更多。
        val nowMs = System.currentTimeMillis()
        val tzId = java.util.TimeZone.getDefault().id
        val nowText = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getDefault()
        }.format(java.util.Date(nowMs))

        val response = try {
            api.chat(
                relay = relay,
                messages = ensuredHistory,
                relaySessionId = sessionAtStart.relaySessionId,
                filter = AgentApiClient.ChatFilter(
                    includeArchived = true,
                    maxNotes = 80,
                ),
                aiConfig = aiConfig,
                inlineNotes = inlineNotes.takeIf { it.isNotEmpty() },
                currentTimeEpochMs = nowMs,
                currentTimezone = tzId,
                currentTimeText = nowText,
            )
        } catch (e: Throwable) {
            failPlaceholder(e)
            throw e
        }
        AppLogger.agent(
            "chat ok relay_session=${response.sessionId} used_provider=${response.usedProvider} answer_len=${response.answer.length}" +
                (response.providerError?.let { " provider_error=${it.take(120)}" } ?: ""),
        )

        // api.chat 已经成功拿到 answer——这一步以后任何异常都不能让占位气泡卡在 pending=true。
        // 否则底部"思考中…"消失（sending=false）而气泡仍在转圈，用户会看到"短暂思考后什么都没返回"。
        // 先把 assistant 消息落库（关键路径），再 best-effort 更新 session 元信息。
        val answerText = response.answer.ifBlank { "（空回复）" }
        try {
            val refJson = runCatching {
                json.encodeToString(stringListSerializer, response.referencedNoteIds)
            }.getOrDefault("[]")
            val assistant = placeholder.copy(
                content = answerText,
                referencedNoteIdsJson = refJson,
                providerError = response.providerError,
                pending = false,
            )
            dao.upsertMessage(assistant)
            AppLogger.agent("placeholder resolved (len=${assistant.content.length})")
        } catch (e: Throwable) {
            AppLogger.agent("persist assistant failed: ${e.message}; trying minimal fallback")
            // 退化到最小写入：只改 content + pending，不碰 refJson/providerError。
            // 再失败就 failPlaceholder 兜底，至少让用户看到 ERROR 气泡而不是永远转圈。
            val fallbackOk = runCatching {
                dao.upsertMessage(
                    placeholder.copy(
                        content = answerText,
                        pending = false,
                    ),
                )
            }.isSuccess
            if (!fallbackOk) {
                failPlaceholder(
                    IllegalStateException("回答已收到但本地保存失败：${e.message}。回答内容：${answerText.take(300)}"),
                )
            }
        }

        // session 元信息更新失败不影响用户看到回答，降级成 best-effort。
        runCatching {
            dao.upsertSession(
                sessionAtStart.copy(
                    relaySessionId = response.sessionId.ifBlank { sessionAtStart.relaySessionId },
                    title = when {
                        sessionAtStart.relaySessionId != null -> sessionAtStart.title
                        response.sessionTitle.isNotBlank() -> response.sessionTitle
                        else -> content.take(30).ifBlank { sessionAtStart.title }
                    },
                    updatedAt = System.currentTimeMillis(),
                    lastMessagePreview = response.answer.take(40).ifBlank { content.take(40) },
                ),
            )
        }.onFailure { AppLogger.agent("session metadata update after chat failed: ${it.message}") }
    }

    suspend fun refreshSessions(): Result<Unit> = runCatching {
        val relay = relayConfigOrNull() ?: return@runCatching
        val remote = api.listSessions(relay)
        if (remote.isEmpty()) return@runCatching
        // 关键：不再为 relay 返回但本地没映射的 session 新建本地条目——这会制造
        // 两条指向同一 relaySessionId 的本地 session，observeSessions 排序后可能
        // 切到空的那条，导致用户看到"回复一闪而过然后消失"。只更新已有 session。
        var updated = 0
        remote.forEach { summary ->
            val localId = dao.findLocalIdByRelayId(summary.id) ?: return@forEach
            val local = dao.getSession(localId) ?: return@forEach
            val merged = local.copy(
                title = summary.title.ifBlank { local.title },
                updatedAt = summary.updatedAt.takeIf { it > 0 } ?: local.updatedAt,
            )
            if (merged != local) {
                dao.upsertSession(merged)
                updated += 1
            }
        }
        AppLogger.agent("refreshSessions: scanned ${remote.size} remote, updated=$updated (no new creation)")
    }

    suspend fun hydrateSession(localSessionId: String): Result<Unit> = runCatching {
        val session = dao.getSession(localSessionId) ?: return@runCatching
        val relayId = session.relaySessionId ?: return@runCatching
        val relay = relayConfigOrNull() ?: return@runCatching
        AppLogger.agent("hydrateSession start session=$localSessionId relay=$relayId")
        val detail = api.getSession(relay, relayId)
        // 只在 relay 真的有新消息时才删旧的。否则 relay 返回空 detail 会把本地刚落的
        // user/assistant 气泡全删掉（用户看到"回复一闪而过然后空"）。
        if (detail.messages.isEmpty()) {
            AppLogger.agent("hydrateSession: relay returned 0 messages, keeping local intact")
            return@runCatching
        }
        dao.deleteNonPendingMessages(localSessionId)
        val inserted = detail.messages.map { wire ->
            val refJson = try {
                json.encodeToString(stringListSerializer, wire.referencedNoteIds)
            } catch (_: Throwable) {
                "[]"
            }
            AgentMessageEntity(
                id = UUID.randomUUID().toString(),
                sessionId = localSessionId,
                role = wire.role,
                content = wire.content,
                referencedNoteIdsJson = refJson,
                createdAt = wire.createdAt,
                providerError = wire.providerError,
                pending = false,
            )
        }
        if (inserted.isNotEmpty()) dao.upsertMessages(inserted)
        dao.upsertSession(
            session.copy(
                title = detail.title.ifBlank { session.title },
                updatedAt = detail.updatedAt.takeIf { it > 0 } ?: session.updatedAt,
                lastMessagePreview = detail.messages.lastOrNull()?.content?.take(40) ?: session.lastMessagePreview,
            ),
        )
    }

    private suspend fun relayConfigOrNull(): RelayConfig? {
        val settings = settingsStore.settingsFlow.first()
        val relay = settings.relay
        return if (relay.baseUrl.isNotBlank() && relay.token.isNotBlank()) relay else null
    }

    private fun AgentSessionEntity.toDomain() = AgentSession(
        id = id,
        relaySessionId = relaySessionId,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastMessagePreview = lastMessagePreview,
    )

    private fun AgentMessageEntity.toDomain(): AgentMessage {
        val noteIds = runCatching { json.decodeFromString(stringListSerializer, referencedNoteIdsJson) }
            .getOrDefault(emptyList())
        return AgentMessage(
            id = id,
            sessionId = sessionId,
            role = AgentRole.fromWire(role),
            content = content,
            referencedNoteIds = noteIds,
            createdAt = createdAt,
            providerError = providerError,
            pending = pending,
        )
    }
}
