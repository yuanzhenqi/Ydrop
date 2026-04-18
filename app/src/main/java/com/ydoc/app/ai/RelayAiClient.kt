package com.ydoc.app.ai

import com.ydoc.app.model.AiAnalyzeRequest
import com.ydoc.app.model.AiAnalyzeResponse
import com.ydoc.app.model.AiConfig
import com.ydoc.app.model.AiEndpointMode
import com.ydoc.app.model.defaultAiPromptTemplate
import com.ydoc.app.model.isEffectivelyEmpty
import com.ydoc.app.model.minimalSummaryResponse
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class RelayAiClient(
    private val httpClient: OkHttpClient,
) : AiClient {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override suspend fun analyze(request: AiAnalyzeRequest, config: AiConfig): AiAnalyzeResponse {
        require(config.baseUrl.isNotBlank()) { "AI Base URL 未配置" }
        val response = when (resolveMode(config)) {
            AiEndpointMode.RELAY -> analyzeViaRelay(request, config)
            AiEndpointMode.OPENAI -> analyzeViaOpenAi(request, config)
            AiEndpointMode.ANTHROPIC -> analyzeViaAnthropic(request, config)
            AiEndpointMode.AUTO -> error("AI 模式自动识别失败")
        }
        return if (response.isEffectivelyEmpty()) request.minimalSummaryResponse() else response
    }

    override suspend fun test(config: AiConfig) {
        require(config.baseUrl.isNotBlank()) { "AI Base URL 未配置" }
        when (resolveMode(config)) {
            AiEndpointMode.RELAY -> testRelay(config)
            AiEndpointMode.OPENAI -> testOpenAi(config)
            AiEndpointMode.ANTHROPIC -> testAnthropic(config)
            AiEndpointMode.AUTO -> error("AI 模式自动识别失败")
        }
    }

    override suspend fun batchOrganize(
        request: com.ydoc.app.model.BatchOrganizeRequest,
        config: AiConfig,
    ): com.ydoc.app.model.BatchOrganizeResponse {
        require(config.baseUrl.isNotBlank()) { "AI Base URL 未配置" }
        if (request.notes.isEmpty()) {
            return com.ydoc.app.model.BatchOrganizeResponse(total_analyzed = 0, clusters = emptyList())
        }
        val mode = resolveMode(config)
        val clusters = when (mode) {
            AiEndpointMode.OPENAI -> batchViaOpenAi(request, config)
            AiEndpointMode.ANTHROPIC -> batchViaAnthropic(request, config)
            AiEndpointMode.RELAY -> batchViaRelay(request, config)
            AiEndpointMode.AUTO -> error("AI 模式自动识别失败")
        }
        return com.ydoc.app.model.BatchOrganizeResponse(
            total_analyzed = request.notes.size,
            clusters = clusters,
        )
    }

    private fun batchViaOpenAi(
        request: com.ydoc.app.model.BatchOrganizeRequest,
        config: AiConfig,
    ): List<com.ydoc.app.model.ClusterSuggestion> {
        require(config.token.isNotBlank()) { "AI Token 未配置" }
        val body = buildJsonObject {
            put("model", JsonPrimitive(config.model.ifBlank { "gpt-4o-mini" }))
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", JsonPrimitive("system"))
                            put("content", JsonPrimitive(BATCH_ORGANIZE_SYSTEM_PROMPT))
                        },
                    )
                    add(
                        buildJsonObject {
                            put("role", JsonPrimitive("user"))
                            put("content", JsonPrimitive(encodeBatchPrompt(request.notes)))
                        },
                    )
                },
            )
            put(
                "response_format",
                buildJsonObject { put("type", JsonPrimitive("json_object")) },
            )
        }
        val httpRequest = Request.Builder()
            .url(buildProviderUrl(config.baseUrl, "chat/completions"))
            .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA_TYPE))
            .addHeader("Authorization", "Bearer ${config.token}")
            .addHeader("Content-Type", "application/json")
            .build()
        return httpClient.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("批量整理请求失败: HTTP ${response.code}")
            }
            val payload = response.body?.string().orEmpty()
            ensureNotHtml(payload, response.header("Content-Type"))
            val root = parseEnvelope(payload, AiEndpointMode.OPENAI)
            val content = root["choices"]
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("message")
                ?.jsonObject
                ?.get("content")
                ?.jsonPrimitive
                ?.contentOrNull
                ?: throw buildStructuredOutputError(
                    mode = AiEndpointMode.OPENAI,
                    message = "批量整理返回缺少 choices.message.content",
                    rawContent = payload,
                )
            decodeBatchClusters(content, AiEndpointMode.OPENAI)
        }
    }

    private fun batchViaAnthropic(
        request: com.ydoc.app.model.BatchOrganizeRequest,
        config: AiConfig,
    ): List<com.ydoc.app.model.ClusterSuggestion> {
        require(config.token.isNotBlank()) { "AI Token 未配置" }
        val body = buildJsonObject {
            put("model", JsonPrimitive(config.model.ifBlank { "claude-3-5-sonnet-latest" }))
            put("max_tokens", JsonPrimitive(1600))
            put("system", JsonPrimitive(BATCH_ORGANIZE_SYSTEM_PROMPT))
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", JsonPrimitive("user"))
                            put("content", JsonPrimitive(encodeBatchPrompt(request.notes)))
                        },
                    )
                },
            )
        }
        val httpRequest = Request.Builder()
            .url(buildProviderUrl(config.baseUrl, "messages"))
            .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA_TYPE))
            .addHeader("x-api-key", config.token)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("Content-Type", "application/json")
            .build()
        return httpClient.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("批量整理请求失败: HTTP ${response.code}")
            }
            val payload = response.body?.string().orEmpty()
            ensureNotHtml(payload, response.header("Content-Type"))
            val root = parseEnvelope(payload, AiEndpointMode.ANTHROPIC)
            val content = root["content"]
                ?.jsonArray
                ?.firstOrNull { block -> block.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "text" }
                ?.jsonObject
                ?.get("text")
                ?.jsonPrimitive
                ?.contentOrNull
                ?: throw buildStructuredOutputError(
                    mode = AiEndpointMode.ANTHROPIC,
                    message = "批量整理返回缺少 content[].text",
                    rawContent = payload,
                )
            decodeBatchClusters(content, AiEndpointMode.ANTHROPIC)
        }
    }

    /** RELAY 模式：继续打 relay 的 `/api/ai/batch-organize`，用 relay 数据库里的笔记。 */
    private fun batchViaRelay(
        request: com.ydoc.app.model.BatchOrganizeRequest,
        config: AiConfig,
    ): List<com.ydoc.app.model.ClusterSuggestion> {
        val endpoint = config.baseUrl.trimEnd('/') + "/api/ai/batch-organize"
        val legacyBody = buildJsonObject {
            put("note_ids", buildJsonArray { request.notes.forEach { add(JsonPrimitive(it.id)) } })
            put("max_notes", JsonPrimitive(request.max_notes))
        }
        val httpRequest = Request.Builder()
            .url(endpoint)
            .post(json.encodeToString(JsonObject.serializer(), legacyBody).toRequestBody(JSON_MEDIA_TYPE))
            .addHeader("Content-Type", "application/json")
            .apply {
                if (config.token.isNotBlank()) addHeader("Authorization", "Bearer ${config.token}")
            }
            .build()
        return httpClient.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                val preview = runCatching { response.body?.string().orEmpty().take(200) }.getOrDefault("")
                throw IOException("批量整理请求失败: HTTP ${response.code}${if (preview.isNotBlank()) "，$preview" else ""}")
            }
            val payload = response.body?.string().orEmpty()
            ensureNotHtml(payload, response.header("Content-Type"))
            json.decodeFromString(
                com.ydoc.app.model.BatchOrganizeResponse.serializer(),
                payload,
            ).clusters
        }
    }

    private fun encodeBatchPrompt(notes: List<com.ydoc.app.model.BatchOrganizeNoteInput>): String {
        val compact = notes.map { note ->
            buildJsonObject {
                put("id", JsonPrimitive(note.id))
                put("title", JsonPrimitive(note.title))
                put("content", JsonPrimitive(note.content.take(300)))
                put("category", JsonPrimitive(note.category))
                put(
                    "tags",
                    buildJsonArray { note.tags.forEach { add(JsonPrimitive(it)) } },
                )
                put("created_at", JsonPrimitive(note.created_at))
            }
        }
        val arr = buildJsonArray { compact.forEach { add(it) } }
        return "用户笔记（JSON 数组）：\n" + json.encodeToString(JsonArray.serializer(), arr)
    }

    private fun decodeBatchClusters(
        content: String,
        mode: AiEndpointMode,
    ): List<com.ydoc.app.model.ClusterSuggestion> {
        val normalized = normalizeStructuredResponse(content, mode)
        val root = try {
            json.parseToJsonElement(normalized).jsonObject
        } catch (error: Exception) {
            throw buildStructuredOutputError(
                mode = mode,
                message = "批量整理返回不是合法 JSON",
                rawContent = normalized,
                cause = error,
            )
        }
        val clustersArr = root["clusters"]?.jsonArray ?: return emptyList()
        return clustersArr.mapNotNull { element ->
            runCatching {
                json.decodeFromString(
                    com.ydoc.app.model.ClusterSuggestion.serializer(),
                    json.encodeToString(JsonObject.serializer(), element.jsonObject),
                )
            }.getOrNull()
        }
    }

    private fun resolveMode(config: AiConfig): AiEndpointMode {
        if (config.endpointMode != AiEndpointMode.AUTO) return config.endpointMode
        detectProviderMode(config)?.let { return it }
        return AiEndpointMode.RELAY
    }

    private fun detectProviderMode(config: AiConfig): AiEndpointMode? {
        if (config.token.isBlank()) return null
        val normalizedBase = config.baseUrl.lowercase()
        if (normalizedBase.contains("anthropic")) return AiEndpointMode.ANTHROPIC

        val request = Request.Builder()
            .url(buildProviderUrl(config.baseUrl, "models"))
            .get()
            .addHeader("Authorization", "Bearer ${config.token}")
            .build()

        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val payload = response.body?.string().orEmpty()
            ensureNotHtml(payload, response.header("Content-Type"))
            val root = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null
            if (root["data"] != null) AiEndpointMode.OPENAI else null
        }
    }

    private fun analyzeViaRelay(request: AiAnalyzeRequest, config: AiConfig): AiAnalyzeResponse {
        val endpoint = config.baseUrl.trimEnd('/') + "/ai/analyze-note"
        val httpRequest = Request.Builder()
            .url(endpoint)
            .post(json.encodeToString(AiAnalyzeRequest.serializer(), request).toRequestBody(JSON_MEDIA_TYPE))
            .addHeader("Content-Type", "application/json")
            .apply {
                if (config.token.isNotBlank()) {
                    addHeader("Authorization", "Bearer ${config.token}")
                }
            }
            .build()

        return httpClient.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("AI 请求失败: HTTP ${response.code}")
            }
            val payload = response.body?.string().orEmpty()
            ensureNotHtml(payload, response.header("Content-Type"))
            decodeStructuredResponse(payload, AiEndpointMode.RELAY)
        }
    }

    private fun analyzeViaOpenAi(request: AiAnalyzeRequest, config: AiConfig): AiAnalyzeResponse {
        require(config.token.isNotBlank()) { "AI Token 未配置" }
        val systemPrompt = buildSystemPrompt(request.prompt ?: config.promptSupplement, request)
        val body = buildJsonObject {
            put("model", JsonPrimitive(config.model.ifBlank { request.model }))
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", JsonPrimitive("system"))
                            put("content", JsonPrimitive(systemPrompt))
                        },
                    )
                    add(
                        buildJsonObject {
                            put("role", JsonPrimitive("user"))
                            put("content", JsonPrimitive(encodeProviderRequest(request)))
                        },
                    )
                },
            )
            put(
                "response_format",
                buildJsonObject {
                    put("type", JsonPrimitive("json_object"))
                },
            )
        }

        val httpRequest = Request.Builder()
            .url(buildProviderUrl(config.baseUrl, "chat/completions"))
            .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA_TYPE))
            .addHeader("Authorization", "Bearer ${config.token}")
            .addHeader("Content-Type", "application/json")
            .build()

        return httpClient.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("AI 请求失败: HTTP ${response.code}")
            }
            val payload = response.body?.string().orEmpty()
            ensureNotHtml(payload, response.header("Content-Type"))
            val root = parseEnvelope(payload, AiEndpointMode.OPENAI)
            val content = root["choices"]
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("message")
                ?.jsonObject
                ?.get("content")
                ?.jsonPrimitive
                ?.contentOrNull
                ?: throw buildStructuredOutputError(
                    mode = AiEndpointMode.OPENAI,
                    message = "接口返回缺少 choices.message.content 字段",
                    rawContent = payload,
                )
            decodeStructuredResponse(content, AiEndpointMode.OPENAI)
        }
    }

    private fun analyzeViaAnthropic(request: AiAnalyzeRequest, config: AiConfig): AiAnalyzeResponse {
        require(config.token.isNotBlank()) { "AI Token 未配置" }
        val systemPrompt = buildSystemPrompt(request.prompt ?: config.promptSupplement, request)
        val body = buildJsonObject {
            put("model", JsonPrimitive(config.model.ifBlank { request.model }))
            put("max_tokens", JsonPrimitive(1200))
            put("system", JsonPrimitive(systemPrompt))
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", JsonPrimitive("user"))
                            put("content", JsonPrimitive(encodeProviderRequest(request)))
                        },
                    )
                },
            )
        }

        val httpRequest = Request.Builder()
            .url(buildProviderUrl(config.baseUrl, "messages"))
            .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA_TYPE))
            .addHeader("x-api-key", config.token)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("Content-Type", "application/json")
            .build()

        return httpClient.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("AI 请求失败: HTTP ${response.code}")
            }
            val payload = response.body?.string().orEmpty()
            ensureNotHtml(payload, response.header("Content-Type"))
            val root = parseEnvelope(payload, AiEndpointMode.ANTHROPIC)
            val content = root["content"]
                ?.jsonArray
                ?.firstOrNull { block ->
                    block.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "text"
                }
                ?.jsonObject
                ?.get("text")
                ?.jsonPrimitive
                ?.contentOrNull
                ?: root["content"]
                    ?.jsonArray
                    ?.firstOrNull()
                    ?.jsonObject
                    ?.get("text")
                    ?.jsonPrimitive
                    ?.contentOrNull
                ?: throw buildStructuredOutputError(
                    mode = AiEndpointMode.ANTHROPIC,
                    message = "接口返回缺少 content[].text 字段",
                    rawContent = payload,
                )
            decodeStructuredResponse(content, AiEndpointMode.ANTHROPIC)
        }
    }

    private fun testRelay(config: AiConfig) {
        val endpoint = config.baseUrl.trimEnd('/') + "/healthz"
        val httpRequest = Request.Builder()
            .url(endpoint)
            .get()
            .build()
        httpClient.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("AI 服务不可用: HTTP ${response.code}")
            }
            val payload = response.body?.string().orEmpty()
            ensureNotHtml(payload, response.header("Content-Type"))
            if (!payload.contains("\"ok\"", ignoreCase = true)) {
                throw IOException("AI Relay 健康检查返回了非预期内容")
            }
        }
    }

    private fun testOpenAi(config: AiConfig) {
        require(config.token.isNotBlank()) { "AI Token 未配置" }
        val request = Request.Builder()
            .url(buildProviderUrl(config.baseUrl, "models"))
            .get()
            .addHeader("Authorization", "Bearer ${config.token}")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("AI 服务不可用: HTTP ${response.code}")
            }
            val payload = response.body?.string().orEmpty()
            ensureNotHtml(payload, response.header("Content-Type"))
            validateModelAvailability(payload, config.model)
        }
    }

    private fun testAnthropic(config: AiConfig) {
        require(config.token.isNotBlank()) { "AI Token 未配置" }
        val body = buildJsonObject {
            put("model", JsonPrimitive(config.model))
            put("max_tokens", JsonPrimitive(16))
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", JsonPrimitive("user"))
                            put("content", JsonPrimitive("reply with ok"))
                        },
                    )
                },
            )
        }
        val request = Request.Builder()
            .url(buildProviderUrl(config.baseUrl, "messages"))
            .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA_TYPE))
            .addHeader("x-api-key", config.token)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("Content-Type", "application/json")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("AI 服务不可用: HTTP ${response.code}")
            }
            val payload = response.body?.string().orEmpty()
            ensureNotHtml(payload, response.header("Content-Type"))
            val root = json.parseToJsonElement(payload).jsonObject
            if (root["content"]?.jsonArray.isNullOrEmpty()) {
                throw IOException("Anthropic 返回内容为空")
            }
        }
    }

    private fun decodeStructuredResponse(content: String, mode: AiEndpointMode): AiAnalyzeResponse {
        val normalized = normalizeStructuredResponse(content, mode)
        return try {
            json.decodeFromString(AiAnalyzeResponse.serializer(), normalized)
        } catch (error: Exception) {
            if (error is IOException) throw error
            throw buildStructuredOutputError(
                mode = mode,
                message = "返回的 JSON 格式损坏或字段不匹配",
                rawContent = normalized,
                cause = error,
            )
        }
    }

    private fun parseEnvelope(payload: String, mode: AiEndpointMode): JsonObject =
        try {
            json.parseToJsonElement(payload).jsonObject
        } catch (error: Exception) {
            throw buildStructuredOutputError(
                mode = mode,
                message = "接口返回了非 JSON 响应",
                rawContent = payload,
                cause = error,
            )
        }

    private fun normalizeStructuredResponse(content: String, mode: AiEndpointMode): String {
        val stripped = content.stripMarkdownCodeFence()
        val candidate = when {
            stripped.looksLikeJsonObject() -> stripped
            else -> stripped.extractFirstJsonObject()
        }
        return candidate?.trim()
            ?: throw buildStructuredOutputError(
                mode = mode,
                message = "返回了非 JSON 文本，请检查模型输出约束",
                rawContent = content,
            )
    }

    private fun validateModelAvailability(payload: String, configuredModel: String) {
        if (configuredModel.isBlank()) return
        val data = json.parseToJsonElement(payload).jsonObject["data"]?.jsonArray ?: return
        val exists = data.any { model ->
            model.jsonObject["id"]?.jsonPrimitive?.contentOrNull == configuredModel
        }
        if (!exists) {
            throw IOException("模型 $configuredModel 不在当前服务的模型列表中")
        }
    }

    private fun ensureNotHtml(payload: String, contentType: String?) {
        val normalized = payload.trimStart()
        if (
            contentType?.contains("text/html", ignoreCase = true) == true ||
            normalized.startsWith("<!doctype", ignoreCase = true) ||
            normalized.startsWith("<html", ignoreCase = true)
        ) {
            throw IOException("当前 AI Base URL 返回的是网页，不是 AI JSON 接口。请检查你填写的是 Relay 地址，还是模型服务的 API 地址。")
        }
    }

    private fun String.stripMarkdownCodeFence(): String {
        val trimmed = trim()
        val fenced = Regex("^```(?:json)?\\s*(.*?)\\s*```$", RegexOption.DOT_MATCHES_ALL)
            .find(trimmed)
            ?.groupValues
            ?.getOrNull(1)
        return fenced?.trim() ?: trimmed
    }

    private fun String.looksLikeJsonObject(): Boolean {
        val trimmed = trim()
        return trimmed.startsWith("{") && trimmed.endsWith("}")
    }

    private fun String.extractFirstJsonObject(): String? {
        var searchStart = indexOf('{')
        while (searchStart >= 0) {
            var depth = 0
            var inString = false
            var escaping = false

            for (index in searchStart until length) {
                val ch = this[index]
                if (inString) {
                    when {
                        escaping -> escaping = false
                        ch == '\\' -> escaping = true
                        ch == '"' -> inString = false
                    }
                    continue
                }

                when (ch) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            return substring(searchStart, index + 1)
                        }
                    }
                }
            }

            searchStart = indexOf('{', startIndex = searchStart + 1)
        }
        return null
    }

    private fun buildStructuredOutputError(
        mode: AiEndpointMode,
        message: String,
        rawContent: String,
        cause: Throwable? = null,
    ): IOException {
        val preview = rawContent.safePreview()
        val detail = buildString {
            append(mode.displayName())
            append(' ')
            append(message)
            append("。响应预览：")
            append(preview)
        }
        return IOException(detail, cause)
    }

    private fun String.safePreview(maxLength: Int = 220): String {
        val compact = replace(Regex("\\s+"), " ").trim()
        if (compact.isBlank()) return "(empty)"
        return if (compact.length <= maxLength) compact else compact.take(maxLength) + "..."
    }

    private fun AiEndpointMode.displayName(): String = when (this) {
        AiEndpointMode.RELAY -> "Relay"
        AiEndpointMode.OPENAI -> "OpenAI"
        AiEndpointMode.ANTHROPIC -> "Anthropic"
        AiEndpointMode.AUTO -> "AI"
    }

    private fun encodeProviderRequest(request: AiAnalyzeRequest): String =
        json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("noteId", JsonPrimitive(request.noteId))
                put("title", JsonPrimitive(request.title))
                put("content", JsonPrimitive(request.content))
                put("source", JsonPrimitive(request.source))
                put("category", JsonPrimitive(request.category))
                put("priority", JsonPrimitive(request.priority))
                request.transcript?.let { put("transcript", JsonPrimitive(it)) }
                put("trigger", JsonPrimitive(request.trigger))
                put("model", JsonPrimitive(request.model))
                put("currentTimeText", JsonPrimitive(request.currentTimeText))
                put("currentTimezone", JsonPrimitive(request.currentTimezone))
                put("currentTimeEpochMs", JsonPrimitive(request.currentTimeEpochMs))
                if (request.existingTags.isNotEmpty()) {
                    put(
                        "existingTags",
                        buildJsonArray { request.existingTags.forEach { add(JsonPrimitive(it)) } },
                    )
                }
                if (request.currentTags.isNotEmpty()) {
                    put(
                        "currentTags",
                        buildJsonArray { request.currentTags.forEach { add(JsonPrimitive(it)) } },
                    )
                }
            },
        )

    private fun buildSystemPrompt(prompt: String, request: AiAnalyzeRequest): String {
        val basePrompt = buildBasePrompt(
            supplement = prompt,
            request = request,
        )
        return buildString {
            appendLine("Current system time: ${request.currentTimeText}")
            appendLine("Current system timezone: ${request.currentTimezone}")
            appendLine("Current system Unix milliseconds: ${request.currentTimeEpochMs}")
            appendLine()
            appendLine("TIME RESOLUTION RULES:")
            appendLine("- For reminderCandidates, you MUST provide a \"scheduledAtIso\" field with the resolved future time in ISO 8601 format: \"YYYY-MM-DDTHH:mm\".")
            appendLine("- Example: if currentTimeEpochMs corresponds to 2024-04-11 14:30 in the user's timezone and user says \"明天上午7:30\", scheduledAtIso should be \"2024-04-12T07:30\".")
            appendLine("- Common Chinese relative expressions: 明天=+1 day, 后天=+2 days, 大后天=+3 days, 下周一=next Monday, 下个月=next month.")
            appendLine("- Common time expressions: 上午7:30=07:30, 下午3点=15:00, 晚上8点=20:00, 凌晨2点=02:00, 中午12点=12:00, 半夜=00:00.")
            appendLine("- If only a time-of-day is given without a date qualifier, assume today if that time is still in the future, otherwise tomorrow.")
            appendLine("- If the time expression is too ambiguous to resolve confidently, do NOT create a reminderCandidate.")
            appendLine("- Also provide scheduledAt as epoch milliseconds (best effort); the app will prefer scheduledAtIso if present.")
            appendLine("Resolve all relative dates and times against this context.")
            appendLine()
            if (request.existingTags.isNotEmpty()) {
                appendLine("EXISTING_TAGS (the user's current tag pool, preserve casing and language exactly):")
                appendLine(request.existingTags.joinToString(", "))
                appendLine("TAG REUSE RULE: when suggesting tags, you MUST first try to pick from EXISTING_TAGS verbatim.")
                appendLine("Only invent a new tag when NONE of the existing tags matches the note's theme semantically.")
                appendLine("New tags allowed: at most 1-2 per note; each new tag should be short (2-8 Chinese chars or 1-20 English chars), general, and reusable across future notes.")
                appendLine()
            }
            if (request.currentTags.isNotEmpty()) {
                appendLine("CURRENT_TAGS (tags already on this note — do NOT re-suggest these):")
                appendLine(request.currentTags.joinToString(", "))
                appendLine()
            }
            append(basePrompt)
            append("\n\n")
            append(STRUCTURED_OUTPUT_REQUIREMENTS)
        }
    }

    private fun buildBasePrompt(supplement: String, request: AiAnalyzeRequest): String {
        val renderedSupplement = renderPromptVariables(
            prompt = supplement.trim(),
            request = request,
        )
        return buildString {
            append(defaultAiPromptTemplate())
            if (renderedSupplement.isNotBlank()) {
                append("\n\n")
                appendLine("Additional instructions from the user:")
                append(renderedSupplement)
            }
        }
    }

    private fun renderPromptVariables(prompt: String, request: AiAnalyzeRequest): String =
        prompt
            .replace("{{current_time}}", request.currentTimeText)
            .replace("{{current_timezone}}", request.currentTimezone)
            .replace("{{current_time_ms}}", request.currentTimeEpochMs.toString())

    private fun JsonArray?.isNullOrEmpty(): Boolean = this == null || this.isEmpty()

    private fun buildProviderUrl(baseUrl: String, path: String): String {
        val normalized = baseUrl.trimEnd('/')
        val prefix = if (normalized.endsWith("/v1")) normalized else "$normalized/v1"
        return "$prefix/$path"
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private val BATCH_ORGANIZE_SYSTEM_PROMPT =
            """
            你是用户笔记整理助手。用户会给你一批 inbox 笔记的 JSON 数组。
            请分析它们的主题、标签、分类，识别出以下三类聚类：
            - merge：内容高度相似或互补、可以合并为一条
            - convert_to_task：多条零散笔记可以汇总成一个可执行任务
            - keep：保持独立不动（一般不必返回这类，除非解释某主题为什么不合并）
            只返回一个 raw JSON 对象，不要 Markdown、不要解释性文字，schema 如下：
            {"clusters":[{"cluster_id":"c1","theme":"短主题","note_ids":["id1","id2"],"suggested_action":"merge","suggested_title":"建议标题","reason":"中文理由"}]}
            要求：
            - cluster_id 自取短字符串，唯一即可
            - note_ids 中每个元素必须是输入笔记的 id，不要编造
            - suggested_action 取值：merge / convert_to_task / keep
            - theme、reason、suggested_title 全部用中文
            - 如果没有可聚类的，返回 {"clusters":[]}
            """.trimIndent()

        private val STRUCTURED_OUTPUT_REQUIREMENTS =
            """
            Return exactly one raw JSON object and nothing else.
            Do not use markdown fences.
            Do not add explanations, headings, notes, comments, or trailing text.
            Always provide a non-empty summary, even if there are no todo items or reminder candidates.
            The JSON object may only contain these keys: summary, suggestedTitle, suggestedCategory, suggestedPriority, suggestedTags, todoItems, extractedEntities, reminderCandidates.
            Use null for missing suggestedTitle, suggestedCategory, and suggestedPriority.
            Use [] for empty suggestedTags, todoItems, extractedEntities, and reminderCandidates.
            suggestedCategory must be NOTE, TODO, TASK, or REMINDER.
            suggestedPriority must be LOW, MEDIUM, HIGH, or URGENT.
            suggestedTags must be an array of strings (0-4 items).
            extractedEntities items must be objects with keys label and value.
            reminderCandidates items must be objects with keys title, scheduledAt, reason, and scheduledAtIso.
            reminderCandidates[].scheduledAt must be unix milliseconds (best effort).
            reminderCandidates[].scheduledAtIso must be an ISO 8601 datetime string in the user's timezone: "YYYY-MM-DDTHH:mm".
            Valid example JSON object: {"summary":"The user wants a reminder for tomorrow at 9 AM to submit the weekly report.","suggestedTitle":"Submit weekly report","suggestedCategory":"REMINDER","suggestedPriority":"HIGH","suggestedTags":["工作","周报"],"todoItems":["Submit weekly report"],"extractedEntities":[{"label":"time","value":"tomorrow 9 AM"},{"label":"task","value":"weekly report"}],"reminderCandidates":[{"title":"Submit weekly report","scheduledAt":1736384400000,"scheduledAtIso":"2024-09-09T09:00","reason":"The user explicitly asked to be reminded tomorrow at 9 AM."}]}
            """
                .trimIndent()
                .replace('\n', ' ')
    }
}
