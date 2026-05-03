package com.ydoc.app.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ydoc.app.appContainer
import com.ydoc.app.logging.AppLogger
import com.ydoc.app.model.LinkPreview
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/**
 * 扫描 note 里的 URL，调 relay /api/links/preview 回填预览。
 * - 最多 5 条 URL，避免被单条笔记撑爆。
 * - 单条失败不影响其它；失败的 LinkPreview 也落库，UI 用 `error` 字段降级展示。
 * - 唯一 work 名 = "link_preview_<noteId>"，保证重复保存会 REPLACE 而不是堆队列。
 */
class LinkPreviewWorker(
    ctx: Context,
    params: WorkerParameters,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val noteId = inputData.getString(KEY_NOTE_ID) ?: return Result.failure()
        val container = applicationContext.appContainer
        val note = container.noteRepository.getNote(noteId) ?: return Result.success()
        val relay = container.settingsStore.settingsFlow.first().relay
        if (!relay.enabled || relay.baseUrl.isBlank() || relay.token.isBlank()) {
            AppLogger.relay("LinkPreviewWorker: relay not configured, skip note=$noteId")
            return Result.success()
        }

        // 抽 URL → 归一化（小写 host、去 trailing /、剥常见追踪参数）→ distinct → 截 5 条。
        // 之前出现"两条预览框，一条 404 一条正常"的根因：同一逻辑 URL 被解析成两个不同字符串
        // （比如带 / 不带 trailing slash、带 / 不带 utm），distinct 失效。
        val rawUrls = extractUrls(
            buildString {
                append(note.title).append('\n')
                append(note.content).append('\n')
                note.transcript?.let { append(it) }
            },
        )
        val urls = rawUrls.map { canonicalizeUrl(it) }
            .distinct()
            .take(MAX_URLS)
        AppLogger.relay(
            "LinkPreviewWorker: note=$noteId raw_urls=${rawUrls.size} dedup=${urls.size} list=${urls.joinToString(limit = 5)}",
        )
        if (urls.isEmpty()) {
            // 用户把 URL 删了又保存，旧的预览卡不清空会一直挂在笔记上。
            if (note.linkPreviews.isNotEmpty()) {
                container.noteRepository.setLinkPreviews(noteId, emptyList())
                AppLogger.relay("LinkPreviewWorker: note=$noteId cleared ${note.linkPreviews.size} stale previews")
            }
            return Result.success()
        }

        // 同一条笔记重复保存不必每次重抓：已经成功抓过且没有 error 的直接沿用。
        val existing = note.linkPreviews.associateBy { it.url }
        val now = System.currentTimeMillis()
        val previews = urls.map { url ->
            val cached = existing[url]
            if (cached != null && cached.error == null && cached.fetchedAt > 0 &&
                now - cached.fetchedAt < CACHE_TTL_MS
            ) {
                return@map cached
            }
            runCatching { container.linkPreviewClient.fetchPreview(relay, url) }
                .fold(
                    onSuccess = {
                        LinkPreview(
                            url = it.url.ifBlank { url },
                            title = it.title,
                            description = it.description,
                            imageUrl = it.imageUrl,
                            siteName = it.siteName,
                            summary = it.summary,
                            fetchedAt = now,
                            error = it.error,
                        )
                    },
                    onFailure = { e ->
                        LinkPreview(
                            url = url,
                            fetchedAt = now,
                            error = (e.message ?: "unknown").take(120),
                        )
                    },
                )
        }

        container.noteRepository.setLinkPreviews(noteId, previews)
        AppLogger.relay("LinkPreviewWorker: note=$noteId wrote ${previews.size} previews")
        return Result.success()
    }

    companion object {
        const val KEY_NOTE_ID = "note_id"
        private const val MAX_URLS = 5
        private const val CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000  // 7 天

        fun schedule(ctx: Context, noteId: String) {
            val req = OneTimeWorkRequestBuilder<LinkPreviewWorker>()
                .setInputData(workDataOf(KEY_NOTE_ID to noteId))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                "link_preview_$noteId",
                ExistingWorkPolicy.REPLACE,
                req,
            )
        }
    }
}

// URL 只允许 RFC 3986 合法字符（ASCII 内），这样遇到中文 / 全角标点 / 换行自动终止。
// 之前用 `[^\s]+` 的版本把 "https://x.com/y，这是一个..." 后面的中文都当 URL 吞进去了，
// 送到 relay 的 urllib 再报 'ascii' codec can't encode characters ... 的错。
private val URL_REGEX = Regex(
    """https?://[A-Za-z0-9\-._~:/?#\[\]@!$&'()*+,;=%]+""",
    RegexOption.IGNORE_CASE,
)

/**
 * 从文本里扫 URL。正则只吃 ASCII URL 合法字符，遇到中文立即终止；
 * 再剥一层行尾常见标点（兜底半角 . , 以及用户偶尔输入的全角符号），
 * 例如 "看这个 https://x.com/a." 的末尾句号不应该留在 URL 里。
 */
internal fun extractUrls(text: String): List<String> =
    URL_REGEX.findAll(text)
        .map { it.value.trimEnd('.', ',', ';', ':', '，', '。', '、', '；', '：', '）', ')', ']', '」', '』', '"', '\'') }
        .filter { it.length > 8 }  // http://x.y 以下长度忽略
        .toList()

// 常见的追踪参数：来自不同分享渠道的 utm_*、各家短链平台的 from/spm/share/scene/ref 等等。
// 笔记里同一篇 URL 在两次粘贴时一次带 utm 一次不带，会被算成两条不同 URL，
// 进而出现"一条 404、一条正常"或两个相同预览框的体验。
private val TRACKING_PARAM_PREFIXES = setOf(
    "utm_", "spm", "share", "from", "ref", "ref_", "scene", "src", "sourceid",
    "session_id", "weibo_id", "_t", "fr",
)

/**
 * 归一化 URL，便于跨形态去重：
 * - scheme/host 转小写；
 * - 去掉 trailing 斜杠（path == "/" 的根路径除外）；
 * - 去掉 query 中的常见追踪参数；
 * - fragment 保留（一些站点的 #id 是关键路由）。
 *
 * 解析失败时返回原串，至少不要让笔记保存链路 throw。
 */
internal fun canonicalizeUrl(raw: String): String {
    return runCatching {
        val parsed = java.net.URI(raw)
        val scheme = parsed.scheme?.lowercase() ?: return raw
        val host = parsed.host?.lowercase() ?: return raw
        val port = if (parsed.port == -1) "" else ":${parsed.port}"
        val rawPath = parsed.rawPath ?: ""
        val path = if (rawPath.length > 1 && rawPath.endsWith('/')) rawPath.trimEnd('/') else rawPath
        val cleanedQuery = parsed.rawQuery?.split('&')
            ?.filter { kv ->
                val key = kv.substringBefore('=').lowercase()
                key.isNotBlank() && TRACKING_PARAM_PREFIXES.none { prefix -> key == prefix || key.startsWith(prefix) }
            }
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString("&")
        val frag = parsed.rawFragment?.takeIf { it.isNotBlank() }
        buildString {
            append(scheme).append("://").append(host).append(port).append(path)
            if (!cleanedQuery.isNullOrBlank()) append('?').append(cleanedQuery)
            if (frag != null) append('#').append(frag)
        }
    }.getOrDefault(raw)
}
