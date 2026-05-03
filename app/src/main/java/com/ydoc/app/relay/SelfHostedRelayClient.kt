package com.ydoc.app.relay

import com.ydoc.app.model.RelayConfig
import java.io.File
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody

class SelfHostedRelayClient(
    private val httpClient: OkHttpClient,
) : RelayStorageClient {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun upload(file: File, config: RelayConfig): RelayUploadResult {
        validate(config)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                name = "file",
                filename = file.name,
                body = file.asRequestBody(file.toMediaType().toMediaType()),
            )
            .build()

        val request = Request.Builder()
            .url(config.baseUrl.trimEnd('/') + "/upload")
            .post(body)
            .header("Authorization", "Bearer ${config.token}")
            .build()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            check(response.isSuccessful) { "Relay upload failed: HTTP ${response.code} - $responseBody" }
            val payload = json.decodeFromString(UploadResponse.serializer(), responseBody)
            return RelayUploadResult(
                fileId = payload.fileId,
                url = payload.url,
                expiresAt = (payload.expiresAt ?: payload.expiresAtAlt)?.let { Instant.parse(it).toEpochMilli() },
            )
        }
    }

    override suspend fun delete(fileId: String, config: RelayConfig) {
        validate(config)
        val request = Request.Builder()
            .url(config.baseUrl.trimEnd('/') + "/files/$fileId")
            .delete()
            .header("Authorization", "Bearer ${config.token}")
            .build()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            check(response.isSuccessful || response.code == 404) {
                "Relay delete failed: HTTP ${response.code} - $responseBody"
            }
        }
    }

    override suspend fun test(config: RelayConfig) {
        validate(config)
        val request = Request.Builder()
            .url(config.baseUrl.trimEnd('/') + "/healthz")
            .get()
            .header("Authorization", "Bearer ${config.token}")
            .build()
        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            check(response.isSuccessful) { "Relay health failed: HTTP ${response.code} - $responseBody" }
        }
    }

    private fun validate(config: RelayConfig) {
        require(config.baseUrl.isNotBlank()) { "Relay base URL is empty" }
        require(config.token.isNotBlank()) { "Relay token is empty" }
    }

    private fun File.toMediaType(): String = when (extension.lowercase()) {
        "wav" -> "audio/wav"
        "mp3" -> "audio/mpeg"
        "ogg" -> "audio/ogg"
        else -> "audio/mp4"
    }
}

@Serializable
private data class UploadResponse(
    // relay 新旧实现都用 `file_id`（snake_case）；历史上 Android 端写成 `id`，
    // 导致 kotlinx.serialization 反序列化缺字段抛异常 → VoiceNoteProcessor 捕异常后
    // 把 note 标成 REMOTE_FAILED 并弹"上传失败"。这里两个名都兼容，保旧 relay 也能跑。
    @SerialName("file_id") val fileId: String,
    val url: String,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("expiresAt") val expiresAtAlt: String? = null,
)
