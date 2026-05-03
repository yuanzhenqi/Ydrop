package com.ydoc.app.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ydoc.app.appContainer
import com.ydoc.app.logging.AppLogger
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 纯本地 OCR：用 ML Kit bundled 模型扫一遍图片文字，写回 attachment.ocrText。
 *
 * 关键：**没有网络约束**。用户保存一张图，即使飞行模式下 OCR 也能立刻跑完；
 * Vision 分析（需要 relay + AI provider）交给 [ImageAnalyzeWorker]。
 *
 * 幂等保护：attachment.ocrText 已非空就直接跳过，避免重复跑 ML Kit 推理。
 */
class ImageOcrWorker(
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
        if (attachment.ocrText.isNotBlank()) {
            // 已经跑过，幂等跳过
            return Result.success()
        }
        if (!File(attachment.localPath).exists()) {
            AppLogger.audio("ImageOcrWorker: local file missing $attachmentId")
            return Result.success()
        }

        val ocr = runCatching { container.imageOcrService.recognize(attachment.localPath) }
            .getOrElse { e ->
                AppLogger.error("YDOC_OCR", "ocr worker failed $attachmentId: ${e.message}", e)
                // ML Kit 推理失败一般是图损坏 / OOM，重试无用，直接 success（留空 ocrText，UI 降级）
                return Result.success()
            }

        if (ocr.isNotBlank()) {
            container.noteRepository.updateAttachment(noteId, attachmentId) { it.copy(ocrText = ocr) }
            AppLogger.audio("ImageOcrWorker: note=$noteId att=$attachmentId ocr_len=${ocr.length}")
        }
        return Result.success()
    }

    companion object {
        const val KEY_NOTE_ID = "note_id"
        const val KEY_ATTACHMENT_ID = "attachment_id"

        fun schedule(ctx: Context, noteId: String, attachmentId: String) {
            val req = OneTimeWorkRequestBuilder<ImageOcrWorker>()
                .setInputData(
                    workDataOf(
                        KEY_NOTE_ID to noteId,
                        KEY_ATTACHMENT_ID to attachmentId,
                    ),
                )
                // **故意不加 NetworkType 约束**：OCR 纯本地，断网也要跑。
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                "image_ocr_${noteId}_$attachmentId",
                ExistingWorkPolicy.KEEP,  // KEEP：同 attachment 重复排队保留第一次，省资源
                req,
            )
        }
    }
}
