package com.ydoc.app.ai

import com.ydoc.app.logging.AppLogger
import com.ydoc.app.model.RelayConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Agent 聊天 API 客户端：对接 relay `/api/ai/chat` + `/api/ai/sessions`。
 * 鉴权头沿用 relay 既有的 `Authorization: Bearer <token>`。
 */
class AgentApiClient(
    private val httpClient: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = false },
) {
    suspend fun chat(
        relay: RelayConfig,
        messages: List<WireMessage>,
        relaySessionId: String? = null,
        filter: ChatFilter? = null,
        aiConfig: AiConfigWire? = null,
        inlineNotes: List<WireNote>? = null,
        currentTimeEpochMs: Long? = null,
        currentTimezone: String? = null,
        currentTimeText: String? = null,
    ): ChatResponse {
        require(relay.baseUrl.isNotBlank()) { "Relay base URL is empty" }
        require(relay.token.isNotBlank()) { "Relay token is empty" }
        val payload = ChatRequestBody(
            messages = messages,
            session_id = relaySessionId,
            filter = filter,
            ai_config = aiConfig,
            inline_notes = inlineNotes,
            current_time_epoch_ms = currentTimeEpochMs,
            current_timezone = currentTimezone,
            current_time_text = currentTimeText,
        )
        val bodyJson = json.encodeToString(ChatRequestBody.serializer(), payload)
        val url = relay.baseUrl.trimEnd('/') + "/api/ai/chat"
        val request = Request.Builder()
            .url(url)
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer ${relay.token}")
            .build()

        val started = System.currentTimeMillis()
        httpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val elapsed = System.currentTimeMillis() - started
            AppLogger.agent("POST $url -> ${response.code} in ${elapsed}ms")
            if (!response.isSuccessful) throw relayHttpError("POST", url, response.code, text)
            return json.decodeFromString(ChatResponse.serializer(), text)
        }
    }

    suspend fun listSessions(relay: RelayConfig): List<SessionSummary> {
        require(relay.baseUrl.isNotBlank()) { "Relay base URL is empty" }
        val url = relay.baseUrl.trimEnd('/') + "/api/ai/sessions"
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer ${relay.token}")
            .build()
        httpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            AppLogger.agent("GET $url -> ${response.code}")
            if (!response.isSuccessful) throw relayHttpError("GET", url, response.code, text)
            return json.decodeFromString<List<SessionSummary>>(text)
        }
    }

    suspend fun getSession(relay: RelayConfig, sessionId: String): SessionDetail {
        require(relay.baseUrl.isNotBlank()) { "Relay base URL is empty" }
        val url = relay.baseUrl.trimEnd('/') + "/api/ai/sessions/$sessionId"
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer ${relay.token}")
            .build()
        httpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            AppLogger.agent("GET $url -> ${response.code}")
            if (!response.isSuccessful) throw relayHttpError("GET", url, response.code, text)
            return json.decodeFromString(SessionDetail.serializer(), text)
        }
    }

    suspend fun deleteSession(relay: RelayConfig, sessionId: String) {
        require(relay.baseUrl.isNotBlank()) { "Relay base URL is empty" }
        val url = relay.baseUrl.trimEnd('/') + "/api/ai/sessions/$sessionId"
        val request = Request.Builder()
            .url(url)
            .delete()
            .header("Authorization", "Bearer ${relay.token}")
            .build()
        httpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            AppLogger.agent("DELETE $url -> ${response.code}")
            if (!response.isSuccessful && response.code != 404) {
                throw relayHttpError("DELETE", url, response.code, text)
            }
        }
    }

    private fun relayHttpError(method: String, url: String, code: Int, body: String): IllegalStateException {
        val hint = when (code) {
            404 -> "404 未找到。请检查：(1) relay 地址是否正确（当前: $url），(2) relay_service 是否已部署到最新版本（需要 /api/ai/chat + /api/ai/sessions 端点）。"
            401, 403 -> "$code 鉴权失败。请在设置里确认 Relay 令牌。"
            500, 502, 503 -> "$code 服务器错误：${body.take(160)}"
            else -> "$code: ${body.take(160)}"
        }
        return IllegalStateException(hint)
    }

    @Serializable
    data class WireMessage(val role: String, val content: String)

    @Serializable
    data class ChatFilter(
        val category: String? = null,
        val priority: String? = null,
        val tag: String? = null,
        val from: Long? = null,
        val to: Long? = null,
        @SerialName("include_archived") val includeArchived: Boolean = false,
        @SerialName("max_notes") val maxNotes: Int = 30,
    )

    @Serializable
    data class AiConfigWire(
        @SerialName("base_url") val baseUrl: String,
        val token: String,
        val model: String = "gpt-4o-mini",
        @SerialName("endpoint_mode") val endpointMode: String = "AUTO",
    )

    /** App 端把本地笔记直接随请求发上去，让 relay 不依赖自己 DB 里有没有这些笔记。 */
    @Serializable
    data class WireNote(
        val id: String,
        val title: String = "",
        val content: String = "",
        val category: String = "NOTE",
        val priority: String = "MEDIUM",
        val tags: List<String> = emptyList(),
        @SerialName("created_at") val createdAt: Long = 0,
    )

    @Serializable
    private data class ChatRequestBody(
        val messages: List<WireMessage>,
        @SerialName("session_id") val session_id: String? = null,
        val filter: ChatFilter? = null,
        @SerialName("ai_config") val ai_config: AiConfigWire? = null,
        @SerialName("inline_notes") val inline_notes: List<WireNote>? = null,
        @SerialName("current_time_epoch_ms") val current_time_epoch_ms: Long? = null,
        @SerialName("current_timezone") val current_timezone: String? = null,
        @SerialName("current_time_text") val current_time_text: String? = null,
    )

    @Serializable
    data class ChatResponse(
        val answer: String,
        @SerialName("referenced_note_ids") val referencedNoteIds: List<String> = emptyList(),
        @SerialName("referenced_count") val referencedCount: Int = 0,
        @SerialName("provider_error") val providerError: String? = null,
        @SerialName("used_provider") val usedProvider: Boolean = false,
        @SerialName("session_id") val sessionId: String = "",
        @SerialName("session_title") val sessionTitle: String = "",
    )

    @Serializable
    data class SessionSummary(
        val id: String,
        val title: String = "",
        @SerialName("created_at") val createdAt: Long = 0,
        @SerialName("updated_at") val updatedAt: Long = 0,
        @SerialName("message_count") val messageCount: Int = 0,
    )

    @Serializable
    data class SessionDetail(
        val id: String,
        val title: String = "",
        @SerialName("created_at") val createdAt: Long = 0,
        @SerialName("updated_at") val updatedAt: Long = 0,
        val messages: List<SessionMessage> = emptyList(),
    )

    @Serializable
    data class SessionMessage(
        val role: String,
        val content: String,
        @SerialName("referenced_note_ids") val referencedNoteIds: List<String> = emptyList(),
        @SerialName("provider_error") val providerError: String? = null,
        @SerialName("used_provider") val usedProvider: Boolean = false,
        @SerialName("created_at") val createdAt: Long = 0,
    )
}
