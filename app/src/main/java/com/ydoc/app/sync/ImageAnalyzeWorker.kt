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
import com.ydoc.app.ai.ImageAnalyzeClient
import com.ydoc.app.appContainer
import com.ydoc.app.logging.AppLogger
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/**
 * 仅负责 **Vision 分析**：调 relay `/api/images/analyze`，把描述 + 关键词 + 可行动项回写到 attachment。
 *
 * OCR 拆到 [ImageOcrWorker] 独立跑（无网络约束），这里保留 NetworkType.CONNECTED，只在有网时启动。
 *
 * 失败分类：
 * - 4xx（除 408/429）→ 永久错误（图太大、鉴权无效等），`Result.failure()` 直接放弃，避免无限 retry 烧资源
 * - 5xx / 408 / 429 / 网络异常 → `Result.retry()` 让 WorkManager 按指数退避重试
 */
class ImageAnalyzeWorker(
    ctx: Context,
    params: WorkerParameters,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val noteId = inputData.getString(KEY_NOTE_ID) ?: return Result.failure()
        val attachmentId = inputData.getString(KEY_ATTACHMENT_ID) ?: return Result.failure()
        val container = applicationContext.appContainer
        val note = container.noteRepository.getNote(noteId) ?: return Result.success()
        val attachment = note.attachments.firstOrNull { it.id == attachmentId }
            ?: return Result.success()
        val imageFile = File(attachment.localPath)
        if (!imageFile.exists()) {
            AppLogger.relay("ImageAnalyzeWorker: local file missing $attachmentId")
            markAnalyzed(noteId, attachmentId, error = "本地图片文件丢失")
            return Result.success()
        }

        val relay = container.settingsStore.settingsFlow.first().relay
        if (!relay.enabled || relay.baseUrl.isBlank() || relay.token.isBlank()) {
            AppLogger.relay("ImageAnalyzeWorker: relay not configured, skip vision for $attachmentId")
            markAnalyzed(noteId, attachmentId, error = "relay 未配置，跳过 Vision 分析")
            return Result.success()
        }

        // 已经分析过 + 没错就不重复烧 token
        if (attachment.aiDescription.isNotBlank() && attachment.analyzeError == null) {
            // 旧数据可能没 analyzedAt，补上以终结 UI 转圈
            if (attachment.analyzedAt == 0L) {
                markAnalyzed(noteId, attachmentId)
            }
            return Result.success()
        }

        val hint = buildString {
            append(note.title)
            if (note.content.isNotBlank() && note.content != note.title) {
                append('\n').append(note.content.take(200))
            }
        }

        val result = runCatching {
            container.imageAnalyzeClient.analyze(
                relay = relay,
                imageFile = imageFile,
                mime = attachment.mime,
                noteId = noteId,
                hint = hint,
            )
        }

        result.onSuccess { resp ->
            val now = System.currentTimeMillis()
            container.noteRepository.updateAttachment(noteId, attachmentId) {
                it.copy(
                    remoteUrl = resp.remoteUrl.ifBlank { it.remoteUrl },
                    aiDescription = resp.description.ifBlank { it.aiDescription },
                    aiStructuredJson = """{"keywords":${jsonArray(resp.keywords)},""" +
                        """"actionable_items":${jsonArray(resp.actionableItems)},""" +
                        """"dates":${jsonArray(resp.dates)}}""",
                    analyzeError = resp.error,
                    analyzedAt = now,
                )
            }
            AppLogger.relay("ImageAnalyzeWorker: ok note=$noteId att=$attachmentId desc_len=${resp.description.length}")
        }

        return result.fold(
            onSuccess = { Result.success() },
            onFailure = { e ->
                val retryable = isRetryable(e)
                AppLogger.error(
                    "YDOC_RELAY",
                    "ImageAnalyzeWorker ${if (retryable) "retry" else "giveup"} $attachmentId: ${e.message}",
                    e,
                )
                container.noteRepository.updateAttachment(noteId, attachmentId) {
                    it.copy(
                        analyzeError = (e.message ?: "unknown").take(160),
                        // retry 时保留 analyzedAt=0 让 UI 继续转圈；永久失败就填，让 UI 停止转圈显示错误
                        analyzedAt = if (retryable) it.analyzedAt else System.currentTimeMillis(),
                    )
                }
                if (retryable) Result.retry() else Result.failure()
            },
        )
    }

    /** 便利方法：在各种 early return 路径把 analyzedAt 标成"跑过了"，让 UI 停止转圈。 */
    private suspend fun markAnalyzed(noteId: String, attachmentId: String, error: String? = null) {
        applicationContext.appContainer.noteRepository.updateAttachment(noteId, attachmentId) {
            it.copy(
                analyzedAt = System.currentTimeMillis(),
                analyzeError = error ?: it.analyzeError,
            )
        }
    }

    /**
     * 只有"下一次可能成功"的错才重试：
     * - 4xx 除 408（请求超时）/ 429（限流）外都是永久问题（图太大、鉴权失败、请求格式错）
     * - 5xx 服务端临时问题，重试
     * - 没有 status 的（网络 IO / DNS / TLS 等）一律重试
     */
    private fun isRetryable(e: Throwable): Boolean {
        if (e is ImageAnalyzeClient.HttpError) {
            return when (e.status) {
                408, 429 -> true
                in 400..499 -> false
                else -> true  // 5xx 或其它
            }
        }
        return true
    }

    private fun jsonArray(items: List<String>): String =
        items.joinToString(prefix = "[", postfix = "]") {
            "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }

    companion object {
        const val KEY_NOTE_ID = "note_id"
        const val KEY_ATTACHMENT_ID = "attachment_id"

        fun schedule(ctx: Context, noteId: String, attachmentId: String) {
            val req = OneTimeWorkRequestBuilder<ImageAnalyzeWorker>()
                .setInputData(
                    workDataOf(
                        KEY_NOTE_ID to noteId,
                        KEY_ATTACHMENT_ID to attachmentId,
                    ),
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 60, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                "image_analyze_${noteId}_$attachmentId",
                ExistingWorkPolicy.KEEP,  // 同一 attachment 重复排队保留第一次
                req,
            )
        }

        /** 便利方法：同时排 OCR + Vision。调用方只需要一行。 */
        fun scheduleBoth(ctx: Context, noteId: String, attachmentId: String) {
            ImageOcrWorker.schedule(ctx, noteId, attachmentId)
            schedule(ctx, noteId, attachmentId)
        }
    }
}
