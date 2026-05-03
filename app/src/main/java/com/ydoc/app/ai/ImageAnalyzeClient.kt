package com.ydoc.app.ai

import com.ydoc.app.logging.AppLogger
import com.ydoc.app.model.RelayConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody

/**
 * multipart 上传图片到 relay `/api/images/analyze`，拿回 description / keywords / actionable_items / dates。
 * relay 端已经处理了 vision provider 的 OpenAI / Anthropic 协议差异，app 端只关心 JSON 结果。
 */
class ImageAnalyzeClient(
    private val httpClient: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    @Serializable
    data class Response(
        @SerialName("remote_url") val remoteUrl: String = "",
        val description: String = "",
        val keywords: List<String> = emptyList(),
        @SerialName("actionable_items") val actionableItems: List<String> = emptyList(),
        val dates: List<String> = emptyList(),
        val error: String? = null,
    )

    /** 非 2xx 响应会抛这个，带着 HTTP status 便于 Worker 层区分"4xx 永久失败"和"5xx/网络 可重试"。 */
    class HttpError(val status: Int, message: String) : IllegalStateException(message)

    /**
     * 根据上传时 relay 返回的 remote_url（形如 https://host/static/images/<32hex>.<ext>）
     * 删除远端图片副本。用户在 EditNoteCard 删附件 / 彻底删除笔记时调用。
     *
     * 幂等：relay 侧对不存在的文件也返回 204。网络失败 / relay 未部署该端点就 throw，
     * 调用方通常用 runCatching 吃掉——本地删除不能被远端失败阻塞。
     */
    suspend fun deleteByRemoteUrl(relay: RelayConfig, remoteUrl: String) = withContext(Dispatchers.IO) {
        require(relay.baseUrl.isNotBlank()) { "Relay base URL is empty" }
        require(relay.token.isNotBlank()) { "Relay token is empty" }
        // 只从 URL 末尾截 basename，不假设 host——relay 地址用户可能配成反代后的不同域名
        val filename = remoteUrl.substringAfterLast('/').substringBefore('?').trim()
        if (filename.isBlank() || filename == remoteUrl) {
            AppLogger.relay("deleteByRemoteUrl: cannot extract filename from $remoteUrl, skip")
            return@withContext
        }
        val url = relay.baseUrl.trimEnd('/') + "/api/images/$filename"
        val req = okhttp3.Request.Builder()
            .url(url)
            .delete()
            .header("Authorization", "Bearer ${relay.token}")
            .build()
        httpClient.newCall(req).execute().use { resp ->
            AppLogger.relay("DELETE $url -> ${resp.code}")
            if (!resp.isSuccessful && resp.code != 404) {
                val text = resp.body?.string().orEmpty()
                throw HttpError(resp.code, "delete remote http ${resp.code}: ${text.take(160)}")
            }
        }
    }

    suspend fun analyze(
        relay: RelayConfig,
        imageFile: File,
        mime: String,
        noteId: String,
        hint: String,
    ): Response = withContext(Dispatchers.IO) {
        require(relay.baseUrl.isNotBlank()) { "Relay base URL is empty" }
        require(relay.token.isNotBlank()) { "Relay token is empty" }
        require(imageFile.exists()) { "image file missing: ${imageFile.absolutePath}" }

        val mediaType = mime.toMediaType()
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("note_id", noteId)
            .addFormDataPart("hint", hint)
            .addFormDataPart("file", imageFile.name, imageFile.asRequestBody(mediaType))
            .build()

        val url = relay.baseUrl.trimEnd('/') + "/api/images/analyze"
        val request = Request.Builder()
            .url(url)
            .post(body)
            .header("Authorization", "Bearer ${relay.token}")
            .build()

        httpClient.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            AppLogger.relay("POST $url -> ${resp.code} (${text.length} bytes)")
            if (!resp.isSuccessful) {
                throw HttpError(resp.code, "image analyze http ${resp.code}: ${text.take(160)}")
            }
            json.decodeFromString(Response.serializer(), text)
        }
    }
}
