package com.ydoc.app.recording

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AudioRecorder(
    private val context: Context,
) {
    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var startedAtMs: Long = 0L

    data class RecordingOutput(
        val path: String,
        val format: String,
    )

    fun start(): RecordingOutput {
        check(recorder == null) { "Recorder already running" }
        val outputFile = createOutputFile()
        val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        mediaRecorder.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(128000)
            setAudioSamplingRate(44100)
            setOutputFile(outputFile.absolutePath)
            prepare()
            start()
        }
        recorder = mediaRecorder
        currentFile = outputFile
        startedAtMs = SystemClock.elapsedRealtime()
        return RecordingOutput(outputFile.absolutePath, "mp4")
    }

    fun stop(minDurationMs: Long = 0L): RecordingOutput {
        val activeRecorder = recorder ?: error("Recorder not running")
        val outputPath = currentFile?.absolutePath ?: error("No output file")
        waitForMinimumDuration(minDurationMs)
        return try {
            activeRecorder.stop()
            activeRecorder.reset()
            activeRecorder.release()
            RecordingOutput(outputPath, "mp4")
        } catch (error: RuntimeException) {
            currentFile?.delete()
            throw IllegalStateException(
                if (elapsedRecordingMs() < 1_000L) {
                    "录音时间太短，请至少按住 1 秒。"
                } else {
                    "录音文件保存失败，请再试一次。"
                },
                error,
            )
        } finally {
            recorder = null
            currentFile = null
            startedAtMs = 0L
            runCatching { activeRecorder.release() }
        }
    }

    fun cancel() {
        recorder?.runCatching { reset() }
        recorder?.runCatching { release() }
        recorder = null
        currentFile?.delete()
        currentFile = null
        startedAtMs = 0L
    }

    private fun createOutputFile(): File {
        val dir = File(context.filesDir, "recordings").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return File(dir, "recording-$stamp.m4a")
    }

    private fun waitForMinimumDuration(minDurationMs: Long) {
        val remainingMs = minDurationMs - elapsedRecordingMs()
        if (remainingMs > 0L) {
            Thread.sleep(remainingMs)
        }
    }

    private fun elapsedRecordingMs(): Long {
        if (startedAtMs == 0L) return 0L
        return (SystemClock.elapsedRealtime() - startedAtMs).coerceAtLeast(0L)
    }
}
