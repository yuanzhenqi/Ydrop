package com.ydoc.app.ai

import com.ydoc.app.model.AiAnalyzeRequest
import com.ydoc.app.model.AiAnalyzeResponse
import com.ydoc.app.model.AiConfig
import com.ydoc.app.model.AiEndpointMode
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
import java.io.IOException

class RelayAiClient(
    private val httpClient: OkHttpClient,
) : AiClient {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override suspend fun analyze(request: AiAnalyzeRequest, config: AiConfig): AiAnalyzeResponse {
        require(config.baseUrl.isNotBlank()) { "AI Base URL 未配置" }
        return when (resolveMode(config)) {
            AiEndpointMode.RELAY -> analyzeViaRelay(request, config)
            AiEndpointMode.OPENAI -> analyzeViaOpenAi(request, config)
            AiEndpointMode.ANTHROPIC -> analyzeViaAnthropic(request, config)
            AiEndpointMode.AUTO -> error("AI 模式自动识别失败")
        }
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

    private fun resolveMode(config: AiConfig): AiEndpointMode {
        if (config.endpointMode != AiEndpointMode.AUTO) return config.endpointMode
        detectProviderMode(config)?.let { return it }
        return AiEndpointMode.RELAY
    }

    private fun detectProviderMode(config: AiConfig): AiEndpointMode? {
        if (config.token.isBlank()) return null
        return runCatching {
            val request = Request.Builder()
                .url(buildProviderUrl(config.baseUrl, "models"))
                .get()
                .addHeader("Authorization", "Bearer ${config.token}")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val payload = response.body?.string().orEmpty()
                ensureNotHtml(payload, response.header("Content-Type"))
                val root = json.parseToJsonElement(payload).jsonObject
                val models = root["data"]?.jsonArray ?: return AiEndpointMode.OPENAI
                if (models.isEmpty()) return AiEndpointMode.OPENAI
                val supported = models
                    .firstOrNull { model ->
                        model.jsonObject["id"]?.jsonPrimitive?.contentOrNull == config.model
                    }
                    ?.jsonObject
                    ?.get("supported_endpoint_types")
                    ?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    .orEmpty()
                when {
                    "openai" in supported -> AiEndpointMode.OPENAI
                    "anthropic" in supported -> AiEndpointMode.ANTHROPIC
                    config.model.startsWith("claude", ignoreCase = true) -> AiEndpointMode.ANTHROPIC
                    else -> AiEndpointMode.OPENAI
                }
            }
        }.getOrNull()
    }

    private fun analyzeViaRelay(request: AiAnalyzeRequest, config: AiConfig): AiAnalyzeResponse {
        val endpoint = config.baseUrl.trimEnd('/') + "/ai/analyze-note"
        val body = json.encodeToString(AiAnalyzeRequest.serializer(), request)
            .toRequestBody("application/json".toMediaType())
        val httpRequest = Request.Builder()
            .url(endpoint)
            .post(body)
            .addHeader("Content-Type", "application/json")
            .apply {
                if (config.token.isNotBlank()) {
                    addHeader("Authorization", "Bearer ${config.token}")
                }
            }
            .build()

        httpClient.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("AI 请求失败: HTTP ${response.code}")
            }
            val payload = response.body?.string().orEmpty()
            ensureNotHtml(payload, response.header("Content-Type"))
            return json.decodeFromString(AiAnalyzeResponse.serializer(), payload)
        }
    }

    private fun analyzeViaOpenAi(request: AiAnalyzeRequest, config: AiConfig): AiAnalyzeResponse {
        require(config.token.isNotBlank()) { "AI Token 未配置" }
        val body = buildJsonObject {
            put("model", JsonPrimitive(config.model.ifBlank { request.model }))
            put(
                "messages",
                buildJsonArray {
                    add(buildJsonObject {
                        put("role", JsonPrimitive("system"))
                        put("content", JsonPrimitive(SYSTEM_PROMPT))
                    })
                    add(buildJsonObject {
                        put("role", JsonPrimitive("user"))
                        put("content", JsonPrimitive(json.encodeToString(AiAnalyzeRequest.serializer(), request)))
                    })
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

        httpClient.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("AI 请求失败: HTTP ${response.code}")
            }
            val payload = response.body?.string().orEmpty()
            ensureNotHtml(payload, response.header("Content-Type"))
            val root = json.parseToJsonElement(payload).jsonObject
            val content = root["choices"]
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("message")
                ?.jsonObject
                ?.get("content")
                ?.jsonPrimitive
                ?.contentOrNull
                ?: throw IOException("AI 返回缺少 choices.message.content")
            return decodeStructuredResponse(content)
        }
    }

    private fun analyzeViaAnthropic(request: AiAnalyzeRequest, config: AiConfig): AiAnalyzeResponse {
        require(config.token.isNotBlank()) { "AI Token 未配置" }
        val body = buildJsonObject {
            put("model", JsonPrimitive(config.model.ifBlank { request.model }))
            put("max_tokens", JsonPrimitive(1200))
            put("system", JsonPrimitive(SYSTEM_PROMPT))
            put(
                "messages",
                buildJsonArray {
                    add(buildJsonObject {
                        put("role", JsonPrimitive("user"))
                        put("content", JsonPrimitive(json.encodeToString(AiAnalyzeRequest.serializer(), request)))
                    })
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

        httpClient.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("AI 请求失败: HTTP ${response.code}")
            }
            val payload = response.body?.string().orEmpty()
            ensureNotHtml(payload, response.header("Content-Type"))
            val root = json.parseToJsonElement(payload).jsonObject
            val content = root["content"]
                ?.jsonArray
                ?.mapNotNull { part ->
                    part.jsonObject["text"]?.jsonPrimitive?.contentOrNull
                }
                ?.joinToString("\n")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: throw IOException("AI 返回缺少 content[].text")
            return decodeStructuredResponse(content)
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
                    add(buildJsonObject {
                        put("role", JsonPrimitive("user"))
                        put("content", JsonPrimitive("reply with ok"))
                    })
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

    private fun decodeStructuredResponse(content: String): AiAnalyzeResponse {
        val normalized = content.stripMarkdownCodeFence()
        return json.decodeFromString(AiAnalyzeResponse.serializer(), normalized)
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
        if (contentType?.contains("text/html", ignoreCase = true) == true ||
            normalized.startsWith("<!doctype", ignoreCase = true) ||
            normalized.startsWith("<html", ignoreCase = true)
        ) {
            throw IOException("当前 AI Base URL 返回的是网页，不是 AI JSON 接口。请检查你填的是 Relay 地址，还是模型服务的 API 地址。")
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

    private fun JsonArray?.isNullOrEmpty(): Boolean = this == null || this.isEmpty()

    private fun buildProviderUrl(baseUrl: String, path: String): String {
        val normalized = baseUrl.trimEnd('/')
        val prefix = if (normalized.endsWith("/v1")) normalized else "$normalized/v1"
        return "$prefix/$path"
    }

    companion object {
        private const val SYSTEM_PROMPT =
            "Return only JSON with keys summary, suggestedTitle, suggestedCategory, suggestedPriority, todoItems, extractedEntities, reminderCandidates. suggestedCategory must be NOTE, TODO, TASK, or REMINDER. suggestedPriority must be LOW, MEDIUM, HIGH, or URGENT. reminderCandidates[].scheduledAt must be unix milliseconds."

        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
