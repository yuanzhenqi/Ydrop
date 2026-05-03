package com.ydoc.app.ai

import com.ydoc.app.logging.AppLogger
import com.ydoc.app.model.RelayConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 调 relay 的 /api/links/preview 拉链接预览。鉴权头与其它 relay client 保持一致。
 * 失败（4xx/5xx/拉不到页面）不抛到 worker 外层，直接在 caller 里降级成带 error 的 LinkPreview。
 */
class LinkPreviewClient(
    private val httpClient: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = false },
) {
    @Serializable
    private data class RequestBody(
        val url: String,
        @SerialName("want_summary") val wantSummary: Boolean = true,
    )

    @Serializable
    data class Response(
        val url: String,
        val title: String = "",
        val description: String = "",
        @SerialName("image_url") val imageUrl: String = "",
        @SerialName("site_name") val siteName: String = "",
        val summary: String = "",
        val error: String? = null,
    )

    suspend fun fetchPreview(
        relay: RelayConfig,
        url: String,
        wantSummary: Boolean = true,
    ): Response = withContext(Dispatchers.IO) {
        require(relay.baseUrl.isNotBlank()) { "Relay base URL is empty" }
        require(relay.token.isNotBlank()) { "Relay token is empty" }
        val bodyJson = json.encodeToString(
            RequestBody.serializer(),
            RequestBody(url = url, wantSummary = wantSummary),
        )
        val endpoint = relay.baseUrl.trimEnd('/') + "/api/links/preview"
        val req = Request.Builder()
            .url(endpoint)
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer ${relay.token}")
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            AppLogger.relay("POST $endpoint -> ${resp.code}")
            if (!resp.isSuccessful) {
                error("link preview http ${resp.code}: ${text.take(160)}")
            }
            json.decodeFromString(Response.serializer(), text)
        }
    }
}
