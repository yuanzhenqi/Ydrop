package com.ydoc.app.sync

import com.ydoc.app.data.MarkdownFormatter
import com.ydoc.app.model.Note
import com.ydoc.app.model.SyncTarget
import com.ydoc.app.model.WebDavConfig
import java.io.IOException
import java.io.File
import java.net.URLEncoder
import java.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class WebDavSyncClient(
    private val httpClient: OkHttpClient,
    private val formatter: MarkdownFormatter,
) : SyncClient {
    override val type: String = "WEBDAV"

    override suspend fun testConnection(target: SyncTarget): Result<Unit> = runCatching {
        val config = target.config as? WebDavConfig ?: error("WebDAV config missing")
        require(config.baseUrl.isNotBlank()) { "WebDAV base URL is empty" }
        val folder = config.folder.trim('/').ifBlank { "ydoc/inbox" }
        val authHeader = authHeader(config)

        probeCollection(config.baseUrl.trimEnd('/'), authHeader, "WebDAV 根目录")

        val folderUrl = config.baseUrl.trimEnd('/') + "/" + folder
        val folderCheck = propFind(folderUrl, authHeader)
        when (folderCheck.code) {
            200, 207, 301 -> Unit
            404 -> {
                ensureFolder(config, folder, authHeader)
                probeCollection(folderUrl, authHeader, "WebDAV 目标目录")
            }
            405 -> Unit
            else -> error(responseMessage("WebDAV 目录检测失败", folderCheck.code, folderCheck.body))
        }
    }

    override suspend fun push(note: Note, target: SyncTarget): Result<Unit> = runCatching {
        val config = target.config as? WebDavConfig ?: error("WebDAV config missing")
        require(config.baseUrl.isNotBlank()) { "WebDAV base URL is empty" }

        val folder = config.folder.trim('/').ifBlank { "ydoc/inbox" }
        val encodedFileName = URLEncoder.encode(formatter.fileName(note), Charsets.UTF_8.name())
        val targetUrl = config.baseUrl.trimEnd('/') + "/" + folder + "/" + encodedFileName
        val body = formatter.render(note).toRequestBody("text/markdown; charset=utf-8".toMediaType())
        val authHeader = authHeader(config)

        ensureFolder(config, folder, authHeader)

        val request = Request.Builder()
            .url(targetUrl)
            .put(body)
            .apply {
                authHeader?.let { header("Authorization", it) }
            }
            .build()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            check(response.isSuccessful) {
                responseMessage("WebDAV 上传失败", response.code, responseBody)
            }
        }

        note.audioPath?.let { uploadAudio(config, folder, it, authHeader) }
        Unit
    }.recoverCatching { throwable ->
        throw enrichError(throwable)
    }

    override suspend fun delete(note: Note, target: SyncTarget): Result<Unit> = runCatching {
        val config = target.config as? WebDavConfig ?: error("WebDAV config missing")
        require(config.baseUrl.isNotBlank()) { "WebDAV base URL is empty" }

        val folder = config.folder.trim('/').ifBlank { "ydoc/inbox" }
        val authHeader = authHeader(config)
        deleteRemoteFile(config.baseUrl.trimEnd('/') + "/" + folder + "/" + encodedMarkdownFileName(note), authHeader)
        note.audioPath?.let {
            val audioFolder = "$folder/audio"
            val encodedFileName = URLEncoder.encode(File(it).name, Charsets.UTF_8.name())
            deleteRemoteFile(config.baseUrl.trimEnd('/') + "/" + audioFolder + "/" + encodedFileName, authHeader)
        }
        Unit
    }.recoverCatching { throwable ->
        throw enrichError(throwable)
    }

    private fun authHeader(config: WebDavConfig): String? =
        if (config.username.isNotBlank()) {
            val raw = "${config.username}:${config.password}"
            "Basic ${Base64.getEncoder().encodeToString(raw.toByteArray())}"
        } else {
            null
        }

    private fun ensureFolder(config: WebDavConfig, folder: String, authHeader: String?) {
        val folderUrl = config.baseUrl.trimEnd('/') + "/" + folder
        val request = Request.Builder()
            .url(folderUrl)
            .method("MKCOL", null)
            .apply {
                authHeader?.let { header("Authorization", it) }
            }
            .build()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!(response.isSuccessful || response.code == 405 || response.code == 301)) {
                error(responseMessage("WebDAV 目录准备失败", response.code, responseBody))
            }
        }
    }

    private fun probeCollection(url: String, authHeader: String?, label: String) {
        val response = propFind(url, authHeader)
        if (response.code !in setOf(200, 207, 301, 405)) {
            error(responseMessage("$label 访问失败", response.code, response.body))
        }
    }

    private fun propFind(url: String, authHeader: String?): ProbeResponse {
        val request = Request.Builder()
            .url(url)
            .method("PROPFIND", "".toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .header("Depth", "0")
            .apply {
                authHeader?.let { header("Authorization", it) }
            }
            .build()

        httpClient.newCall(request).execute().use { response ->
            return ProbeResponse(
                code = response.code,
                body = response.body?.string().orEmpty(),
            )
        }
    }

    private fun responseMessage(prefix: String, code: Int, body: String): String {
        val compactBody = body.trim().replace(Regex("\\s+"), " ").take(180)
        return if (compactBody.isBlank()) "$prefix: HTTP $code" else "$prefix: HTTP $code - $compactBody"
    }

    private fun encodedMarkdownFileName(note: Note): String = URLEncoder.encode(formatter.fileName(note), Charsets.UTF_8.name())

    private fun uploadAudio(config: WebDavConfig, folder: String, audioPath: String, authHeader: String?) {
        val file = File(audioPath)
        if (!file.exists()) return

        val audioFolder = "$folder/audio"
        ensureFolder(config, audioFolder, authHeader)
        val encodedFileName = URLEncoder.encode(file.name, Charsets.UTF_8.name())
        val targetUrl = config.baseUrl.trimEnd('/') + "/" + audioFolder + "/" + encodedFileName
        val request = Request.Builder()
            .url(targetUrl)
            .put(file.readBytes().toRequestBody("audio/mp4".toMediaType()))
            .apply { authHeader?.let { header("Authorization", it) } }
            .build()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            check(response.isSuccessful) {
                responseMessage("WebDAV 音频上传失败", response.code, responseBody)
            }
        }
    }

    private fun deleteRemoteFile(url: String, authHeader: String?) {
        val request = Request.Builder()
            .url(url)
            .delete()
            .apply { authHeader?.let { header("Authorization", it) } }
            .build()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!(response.isSuccessful || response.code == 404)) {
                error(responseMessage("WebDAV 删除失败", response.code, responseBody))
            }
        }
    }

    private fun enrichError(throwable: Throwable): Throwable {
        val detail = when (throwable) {
            is javax.net.ssl.SSLHandshakeException -> "TLS/证书握手失败: ${throwable.message}"
            is javax.net.ssl.SSLPeerUnverifiedException -> "证书校验失败: ${throwable.message}"
            is java.net.UnknownHostException -> "域名解析失败: ${throwable.message}"
            is java.net.ConnectException -> "连接失败: ${throwable.message}"
            is java.net.SocketTimeoutException -> "连接超时: ${throwable.message}"
            is IOException -> "网络错误: ${throwable.message}"
            else -> throwable.message ?: throwable.javaClass.simpleName
        }
        return IllegalStateException(detail, throwable)
    }

    private data class ProbeResponse(
        val code: Int,
        val body: String,
    )
}
