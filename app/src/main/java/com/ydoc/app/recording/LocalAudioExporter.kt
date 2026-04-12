package com.ydoc.app.recording

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LocalAudioExporter(
    private val context: Context,
) {
    fun exportRecording(noteId: String, sourcePath: String): String {
        val sourceFile = File(sourcePath)
        check(sourceFile.exists()) { "找不到刚录好的音频文件。" }
        val fileName = buildFileName(noteId)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            exportToMediaStore(sourceFile, fileName)
        } else {
            exportToExternalFiles(sourceFile, fileName)
        }
    }

    private fun exportToMediaStore(sourceFile: File, fileName: String): String {
        val resolver = context.contentResolver
        val uri = resolver.insert(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/Ydrop")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            },
        ) ?: error("无法创建系统音频文件。")

        return try {
            resolver.openOutputStream(uri)?.use { output ->
                sourceFile.inputStream().use { input -> input.copyTo(output) }
            } ?: error("无法写入系统音频文件。")
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) },
                null,
                null,
            )
            uri.toString()
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    private fun exportToExternalFiles(sourceFile: File, fileName: String): String {
        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?: error("找不到系统音乐目录。")
        val folder = File(baseDir, "Ydrop").apply { mkdirs() }
        check(folder.exists()) { "无法创建系统音频目录。" }
        val targetFile = File(folder, fileName)
        sourceFile.inputStream().use { input ->
            targetFile.outputStream().use { output -> input.copyTo(output) }
        }
        MediaScannerConnection.scanFile(
            context,
            arrayOf(targetFile.absolutePath),
            arrayOf("audio/mp4"),
            null,
        )
        return targetFile.absolutePath
    }

    private fun buildFileName(noteId: String): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "ydrop-${noteId.takeLast(6)}-$stamp.m4a"
    }
}
