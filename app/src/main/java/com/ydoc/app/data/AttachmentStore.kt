package com.ydoc.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.ydoc.app.logging.AppLogger
import com.ydoc.app.model.NoteAttachment
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 处理图片附件的本地落地：把 content:// Uri 拷到 app 私有目录，生成缩略图，返回 NoteAttachment。
 * 不做 WebDAV 同步：附件走 relay 的 /api/images/analyze（返回 remoteUrl），本地就是权威副本。
 *
 * 原图处理策略：**长边超过 MAX_EDGE 就等比缩放并重新 JPEG 编码**。动机：
 * - 现代手机相机原图经常 >8MB，会直接被 relay `/api/images/analyze` 的 413 拒掉，
 *   然后 Worker 无限 retry 烧资源。
 * - Vision 模型对 2000+ 长边已经远超必要分辨率，缩到 2048 画质几乎无感损失但体积掉 80%。
 * - 统一成 JPEG 能让 mime / ext 规范化，也避免 HEIC 这类不被所有 provider 支持的格式送上去。
 *
 * 目录结构：
 *   filesDir/attachments/<uuid>.jpg      — 归一化后的原图（可能已被缩放压缩）
 *   filesDir/attachments/<uuid>-thumb.jpg — 缩略图（长边 <= 480）
 */
class AttachmentStore(private val context: Context) {

    /** 从外部 Uri（相册、分享、拍照）导入成一个本地 attachment。
     *  大图会被压缩到 [MAX_EDGE] 长边 + JPEG quality [JPEG_QUALITY]，mime 固定成 image/jpeg。 */
    suspend fun import(uri: Uri, sourceMime: String? = null): NoteAttachment = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val detectedMime = sourceMime ?: resolver.getType(uri) ?: "image/jpeg"

        val id = UUID.randomUUID().toString()
        val dir = File(context.filesDir, "attachments").apply { mkdirs() }

        // 先把原字节拷到临时文件，decode 有内存保护（inSampleSize）要用到 file path。
        val tempFile = File(dir, "$id.tmp")
        resolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { output -> input.copyTo(output) }
        } ?: error("无法打开图片 Uri")

        val normalized = File(dir, "$id.jpg")
        val normalizedMeta = runCatching { normalizeToJpeg(tempFile, normalized, MAX_EDGE, JPEG_QUALITY) }
            .getOrElse { e ->
                // 归一化失败（bitmap 解不出来等）的极端场景退回直接保留原文件，不让用户丢图
                AppLogger.error("YDOC_ATTACH", "normalize failed, falling back to raw copy: ${e.message}", e)
                val fallback = File(dir, "$id${extFor(detectedMime)}")
                tempFile.copyTo(fallback, overwrite = true)
                val (w, h) = decodeDimensions(fallback)
                FallbackMeta(fallback, detectedMime, w, h)
            }
        tempFile.delete()

        val finalFile = when (normalizedMeta) {
            is NormalizedMeta -> normalized
            is FallbackMeta -> normalizedMeta.file
        }
        val (finalMime, finalW, finalH) = when (normalizedMeta) {
            is NormalizedMeta -> Triple("image/jpeg", normalizedMeta.width, normalizedMeta.height)
            is FallbackMeta -> Triple(normalizedMeta.mime, normalizedMeta.width, normalizedMeta.height)
        }

        val thumb = runCatching { createThumbnail(finalFile, File(dir, "$id-thumb.jpg"), TARGET_THUMB_EDGE) }
            .getOrElse {
                AppLogger.error("YDOC_ATTACH", "thumbnail failed: ${it.message}", it)
                null
            }

        AppLogger.audio(
            "AttachmentStore.import: id=$id size=${finalFile.length()}B dim=${finalW}x${finalH} mime=$finalMime",
        )

        NoteAttachment(
            id = id,
            type = "IMAGE",
            localPath = finalFile.absolutePath,
            mime = finalMime,
            width = finalW,
            height = finalH,
            thumbPath = thumb?.absolutePath,
            createdAt = System.currentTimeMillis(),
        )
    }

    fun delete(attachment: NoteAttachment) {
        runCatching { File(attachment.localPath).delete() }
        attachment.thumbPath?.let { runCatching { File(it).delete() } }
    }

    private fun decodeDimensions(file: File): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        return opts.outWidth to opts.outHeight
    }

    /**
     * 把 src 解码 + 限长边 + 按 EXIF 方向旋转 + JPEG 重编码写到 dest。
     *
     * 关键：**EXIF Orientation 必须在 compress 之前就用 Matrix 把像素摆正**，
     * 否则相机原图会出现「原图靠 EXIF 标着旋转 90°，decodeFile 不读 EXIF 拿到横版像素，
     * compress 成新 JPEG 不带 EXIF → 永远横着显示」的经典 bug。
     */
    private fun normalizeToJpeg(src: File, dest: File, targetEdge: Int, quality: Int): NormalizedMeta {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.absolutePath, bounds)
        val origLong = max(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        var sampleSize = 1
        while (origLong / (sampleSize * 2) >= targetEdge) sampleSize *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = BitmapFactory.decodeFile(src.absolutePath, opts)
            ?: error("image decode failed for ${src.absolutePath}")

        // 先应用 EXIF 旋转，得到"像素摆正"的 Bitmap
        val oriented = applyExifOrientation(src, decoded)

        // 再按长边压到 targetEdge
        val scaledLong = max(oriented.width, oriented.height)
        val finalBmp = if (scaledLong <= targetEdge) {
            oriented
        } else {
            val ratio = targetEdge.toFloat() / scaledLong
            val newW = (oriented.width * ratio).toInt().coerceAtLeast(1)
            val newH = (oriented.height * ratio).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(oriented, newW, newH, true).also { oriented.recycle() }
        }
        FileOutputStream(dest).use { finalBmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        val meta = NormalizedMeta(finalBmp.width, finalBmp.height)
        finalBmp.recycle()
        return meta
    }

    /**
     * 读 src 文件的 EXIF Orientation，生成对应旋转/镜像过的新 Bitmap。
     * 如果原图 orientation 是 NORMAL（1）或读不到 EXIF，直接返回 src Bitmap 不做拷贝。
     * 注意：对非 JPEG（PNG 等）ExifInterface 一般读不出 Orientation，会返回 NORMAL，也走不到旋转分支。
     */
    private fun applyExifOrientation(srcFile: File, src: Bitmap): Bitmap {
        val orientation = runCatching {
            ExifInterface(srcFile.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return src
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
            .also { if (it !== src) src.recycle() }
    }

    private fun createThumbnail(src: File, dest: File, targetEdge: Int): File {
        // 和 normalize 类似但只负责生成缩略图；这里 src 已经是归一化过的 JPEG。
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.absolutePath, bounds)
        val longEdge = max(bounds.outWidth, bounds.outHeight)
        var sampleSize = 1
        while (longEdge / (sampleSize * 2) >= targetEdge) sampleSize *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bmp = BitmapFactory.decodeFile(src.absolutePath, opts) ?: error("thumbnail decode failed")
        FileOutputStream(dest).use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        bmp.recycle()
        return dest
    }

    private fun extFor(mime: String): String = when {
        mime.endsWith("png") -> ".png"
        mime.endsWith("webp") -> ".webp"
        mime.endsWith("heic") || mime.endsWith("heif") -> ".heic"
        else -> ".jpg"
    }

    private sealed interface ImportMeta
    private data class NormalizedMeta(val width: Int, val height: Int) : ImportMeta
    private data class FallbackMeta(val file: File, val mime: String, val width: Int, val height: Int) : ImportMeta

    companion object {
        private const val TARGET_THUMB_EDGE = 480
        private const val MAX_EDGE = 2048      // Vision 模型再高分辨率也没必要
        private const val JPEG_QUALITY = 85    // 实测对截图 / 相机图画质基本无感
    }
}
