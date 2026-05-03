package com.ydoc.app.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.ydoc.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * 本地 ML Kit OCR 服务。
 *
 * 关键修改（v1.0.3）：
 * - 不再"取较长一路"——latin 识别中文时常返回乱码长串，长但糟糕。改为按行 union：
 *   两路结果各自切行去空白，进 LinkedHashSet 合并去重，保留首次出现顺序。
 * - 短边 < 1024 的小图先 2x 放大喂 ML Kit，手机截图小字识别率明显提升。
 * - 读取 EXIF rotation 传给 InputImage——横拍图被识别成"侧着"是常见漏识别原因。
 * - 任一路抛异常都不让整体失败：用 runCatching 兜底，最差降级成空串。
 *
 * 为啥不懒加载 recognizer：ML Kit 底层 client 是轻量句柄；多次调用复用同一个 client 开销低。
 */
class ImageOcrService {

    private val latin by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val chinese by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    suspend fun recognize(imagePath: String): String = withContext(Dispatchers.Default) {
        val bmp = BitmapFactory.decodeFile(imagePath) ?: run {
            AppLogger.error("YDOC_OCR", "decode failed: $imagePath")
            return@withContext ""
        }
        val rotation = readExifRotation(imagePath)
        val processed = preprocess(bmp)
        val input = InputImage.fromBitmap(processed, rotation)
        val latinText = runCatching { latin.process(input).await().text }.getOrDefault("")
        val chineseText = runCatching { chinese.process(input).await().text }.getOrDefault("")
        if (processed !== bmp) processed.recycle()
        bmp.recycle()

        val merged = mergeByLines(latinText, chineseText)
        AppLogger.audio(
            "ocr: rot=$rotation latin_len=${latinText.length} zh_len=${chineseText.length} merged_len=${merged.length}",
        )
        merged
    }

    /** 短边 < 1024 时按 2x 放大；ML Kit 对小字识别率明显提升。返回的 bitmap 调用方负责回收。 */
    private fun preprocess(src: Bitmap): Bitmap {
        val short = minOf(src.width, src.height)
        if (short >= 1024) return src
        val scale = if (short < 512) 3f else 2f
        return runCatching {
            Bitmap.createScaledBitmap(
                src,
                (src.width * scale).toInt(),
                (src.height * scale).toInt(),
                true,
            )
        }.getOrDefault(src)
    }

    /** 把两路 OCR 输出按行合并去重，保留首次出现顺序。空行直接跳。 */
    private fun mergeByLines(a: String, b: String): String {
        val lines = LinkedHashSet<String>()
        sequenceOf(a, b).forEach { text ->
            text.lineSequence().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotBlank()) lines.add(trimmed)
            }
        }
        return lines.joinToString("\n")
    }

    /** 从 EXIF 读 rotation。读不到或抛异常时回 0；ML Kit 接受 0/90/180/270。 */
    private fun readExifRotation(path: String): Int = runCatching {
        val orientation = ExifInterface(path).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)
}
