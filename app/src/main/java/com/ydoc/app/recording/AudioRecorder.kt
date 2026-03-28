package com.ydoc.app.recording

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AudioRecorder(
    private val context: Context,
) {
    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null

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
        return RecordingOutput(outputFile.absolutePath, "mp4")
    }

    fun stop(): RecordingOutput {
        val activeRecorder = recorder ?: error("Recorder not running")
        val outputPath = currentFile?.absolutePath ?: error("No output file")
        activeRecorder.stop()
        activeRecorder.reset()
        activeRecorder.release()
        recorder = null
        currentFile = null
        return RecordingOutput(outputPath, "mp4")
    }

    fun cancel() {
        recorder?.runCatching {
            stop()
        }
        recorder?.release()
        recorder = null
        currentFile?.delete()
        currentFile = null
    }

    private fun createOutputFile(): File {
        val dir = File(context.filesDir, "recordings").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return File(dir, "recording-$stamp.m4a")
    }
}
