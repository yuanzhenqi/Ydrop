package com.ydoc.app.transcription

import com.ydoc.app.model.VolcengineConfig
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class VolcengineTranscriptionClient(
    private val httpClient: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun test(config: VolcengineConfig) {
        validate(config)
    }

    suspend fun submit(audioUrl: String, audioFormat: String, config: VolcengineConfig): SubmitResult {
        validate(config)
        val requestId = UUID.randomUUID().toString()
        val body = json.encodeToString(
            SubmitRequest.serializer(),
            SubmitRequest(
                user = SubmitUser(uid = requestId),
                audio = SubmitAudio(
                    url = audioUrl,
                    language = config.language,
                    format = audioFormat,
                ),
                request = SubmitOptions(
                    modelName = "bigmodel",
                    enableItn = true,
                    showUtterances = true,
                ),
            ),
        ).toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(SUBMIT_URL)
            .post(body)
            .header("X-Api-App-Key", config.appId)
            .header("X-Api-Access-Key", config.accessToken)
            .header("X-Api-Resource-Id", config.resourceId)
            .header("X-Api-Request-Id", requestId)
            .header("X-Api-Sequence", "-1")
            .build()

        httpClient.newCall(request).execute().use { response ->
            val statusCode = response.header("X-Api-Status-Code")
            val message = response.header("X-Api-Message")
            check(response.isSuccessful) { "Volcengine submit failed: HTTP ${response.code}" }
            check(statusCode == "20000000") { "Volcengine submit failed: $statusCode $message" }
            return SubmitResult(requestId = requestId)
        }
    }

    suspend fun query(requestId: String, config: VolcengineConfig): QueryResult {
        validate(config)
        val body = "{}".toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(QUERY_URL)
            .post(body)
            .header("X-Api-App-Key", config.appId)
            .header("X-Api-Access-Key", config.accessToken)
            .header("X-Api-Resource-Id", config.resourceId)
            .header("X-Api-Request-Id", requestId)
            .header("X-Api-Sequence", "-1")
            .build()

        httpClient.newCall(request).execute().use { response ->
            val statusCode = response.header("X-Api-Status-Code")
            val message = response.header("X-Api-Message")
            check(response.isSuccessful) { "Volcengine query failed: HTTP ${response.code}" }
            val responseBody = response.body?.string().orEmpty()
            if (statusCode == "20000000") {
                val payload = json.decodeFromString(QueryResponse.serializer(), responseBody)
                val text = payload.result?.text.orEmpty()
                check(text.isNotBlank()) { "Volcengine query returned empty text" }
                return QueryResult(ready = true, text = text)
            }
            if (message == "PROCESSING" || statusCode == "20000001") {
                return QueryResult(ready = false, text = null)
            }
            error("Volcengine query failed: $statusCode $message")
        }
    }

    private fun validate(config: VolcengineConfig) {
        require(config.appId.isNotBlank()) { "Volcengine appId is empty" }
        require(config.accessToken.isNotBlank()) { "Volcengine access token is empty" }
        require(config.resourceId.isNotBlank()) { "Volcengine resourceId is empty" }
    }

    companion object {
        private const val SUBMIT_URL = "https://openspeech.bytedance.com/api/v3/auc/bigmodel/submit"
        private const val QUERY_URL = "https://openspeech.bytedance.com/api/v3/auc/bigmodel/query"
    }
}

data class SubmitResult(
    val requestId: String,
)

data class QueryResult(
    val ready: Boolean,
    val text: String?,
)

@Serializable
private data class SubmitRequest(
    val user: SubmitUser,
    val audio: SubmitAudio,
    val request: SubmitOptions,
)

@Serializable
private data class SubmitUser(
    val uid: String,
)

@Serializable
private data class SubmitAudio(
    val url: String,
    val language: String,
    val format: String,
)

@Serializable
private data class SubmitOptions(
    @SerialName("model_name") val modelName: String,
    @SerialName("enable_itn") val enableItn: Boolean = true,
    @SerialName("show_utterances") val showUtterances: Boolean = true,
)

@Serializable
private data class QueryResponse(
    val result: QueryResultPayload? = null,
)

@Serializable
private data class QueryResultPayload(
    val text: String? = null,
)
