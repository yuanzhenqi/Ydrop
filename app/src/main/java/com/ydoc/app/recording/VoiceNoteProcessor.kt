package com.ydoc.app.recording

import com.ydoc.app.data.NoteRepository
import com.ydoc.app.logging.AppLogger
import com.ydoc.app.model.Note
import com.ydoc.app.model.NotePriority
import com.ydoc.app.model.RelayConfig
import com.ydoc.app.model.VolcengineConfig
import com.ydoc.app.relay.RelayStorageClient
import com.ydoc.app.transcription.TranscriptionOrchestrator
import com.ydoc.app.transcription.TranscriptionScheduler
import java.io.File
import java.util.UUID

class VoiceNoteProcessor(
    private val audioRecorder: AudioRecorder,
    private val localAudioExporter: LocalAudioExporter,
    private val noteRepository: NoteRepository,
    private val relayStorageClient: RelayStorageClient,
    private val transcriptionOrchestrator: TranscriptionOrchestrator,
    private val transcriptionScheduler: TranscriptionScheduler,
) {
    data class SaveResult(
        val note: Note,
        val remoteStatus: RemoteStatus,
        val exportError: String? = null,
        val remoteError: String? = null,
    ) {
        fun buildUserMessage(syncError: String? = null): String {
            val parts = mutableListOf<String>()
            parts += when (remoteStatus) {
                RemoteStatus.LOCAL_ONLY -> "录音已保存，本地可播放。"
                RemoteStatus.RELAY_UPLOADED -> "录音已保存，已上传到中转服务，本地可播放。"
                RemoteStatus.TRANSCRIPTION_REQUESTED -> "录音已保存，已上传并提交转写，本地可播放。"
                RemoteStatus.REMOTE_FAILED -> "录音已保存，本地可播放。"
            }
            exportError?.let(parts::add)
            remoteError?.let(parts::add)
            syncError?.takeIf { it.isNotBlank() }?.let { parts += "WebDAV 同步失败：$it" }
            return parts.joinToString(" ")
        }
    }

    enum class RemoteStatus {
        LOCAL_ONLY,
        RELAY_UPLOADED,
        TRANSCRIPTION_REQUESTED,
        REMOTE_FAILED,
    }

    /**
     * 快速路径：停录音 + 导出到系统媒体库 + 写本地 note。几百毫秒返回，
     * 供 UI 尽快把 SAVING 态切回 IDLE。不碰 relay / 豆包 / AI。
     */
    suspend fun stopAndCreateNote(priority: NotePriority): SaveResult {
        val output = audioRecorder.stop(MIN_RECORDING_MS)
        val noteId = UUID.randomUUID().toString()
        var exportError: String? = null
        val publicUri = runCatching {
            localAudioExporter.exportRecording(noteId, output.path)
        }.getOrElse {
            exportError = "系统媒体库导出失败。"
            null
        }

        val note = noteRepository.createVoiceNote(
            noteId = noteId,
            audioPath = output.path,
            audioFormat = output.format,
            priority = priority,
            audioPublicUri = publicUri,
        )
        return SaveResult(note, RemoteStatus.LOCAL_ONLY, exportError = exportError)
    }

    /**
     * 慢速路径：relay upload + 豆包 submit/query + AI 分析。调用方必须在
     * 后台 scope 里跑，不应阻塞 UI。返回最终的 SaveResult 用于 snackbar 文案。
     */
    suspend fun processNoteInBackground(
        noteId: String,
        relayConfig: RelayConfig,
        volcengineConfig: VolcengineConfig,
        wifiOnly: Boolean,
    ): SaveResult {
        val startNote = noteRepository.getNote(noteId)
            ?: throw IllegalStateException("note $noteId 不存在，无法继续后台处理。")

        if (!relayConfig.enabled) {
            return SaveResult(startNote, RemoteStatus.LOCAL_ONLY)
        }

        val audioPath = startNote.audioPath
            ?: return SaveResult(startNote, RemoteStatus.LOCAL_ONLY)

        var note = startNote
        try {
            val upload = relayStorageClient.upload(File(audioPath), relayConfig)
            note = noteRepository.attachRelayInfo(note, upload.fileId, upload.url, upload.expiresAt)
        } catch (error: Exception) {
            val detail = (error.message ?: "unknown").take(160)
            noteRepository.markTranscriptionFailed(note.id, "Relay upload failed: $detail")
            AppLogger.error("YDOC_RELAY", "upload failed for note=${note.id}", error)
            val refreshed = noteRepository.getNote(note.id) ?: note
            return SaveResult(
                note = refreshed,
                remoteStatus = RemoteStatus.REMOTE_FAILED,
                remoteError = "上传失败：$detail",
            )
        }

        if (!volcengineConfig.enabled || note.relayUrl.isNullOrBlank()) {
            return SaveResult(note, RemoteStatus.RELAY_UPLOADED)
        }

        var remoteStatus = RemoteStatus.TRANSCRIPTION_REQUESTED
        var remoteError: String? = null
        runCatching {
            transcriptionOrchestrator.transcribe(note, volcengineConfig, relayConfig)
        }.onFailure { e ->
            transcriptionScheduler.enqueueRetry(note.id, wifiOnly)
            remoteStatus = RemoteStatus.REMOTE_FAILED
            val detail = (e.message ?: "unknown error").take(200)
            val audioUrlLine = note.relayUrl?.takeIf { it.isNotBlank() }?.let { "\n音频URL：$it" }.orEmpty()
            remoteError = "转写失败：$detail$audioUrlLine\n已加入重试队列。"
            AppLogger.error("YDOC_VOLC", "transcribe failed for note=${note.id} url=${note.relayUrl}", e)
        }
        val refreshed = noteRepository.getNote(note.id) ?: note
        return SaveResult(
            note = refreshed,
            remoteStatus = remoteStatus,
            remoteError = remoteError,
        )
    }

    /**
     * 保留给仍然想串行完整处理的调用方（比如悬浮窗以外）。
     * 本质就是快速路径 + 慢速路径串联。
     */
    suspend fun stopAndSave(
        priority: NotePriority,
        relayConfig: RelayConfig,
        volcengineConfig: VolcengineConfig,
        wifiOnly: Boolean,
    ): SaveResult {
        val created = stopAndCreateNote(priority)
        if (created.remoteStatus == RemoteStatus.REMOTE_FAILED || !relayConfig.enabled) {
            return created
        }
        val processed = processNoteInBackground(
            noteId = created.note.id,
            relayConfig = relayConfig,
            volcengineConfig = volcengineConfig,
            wifiOnly = wifiOnly,
        )
        // 合并两阶段的 exportError（来自 stopAndCreateNote）
        return processed.copy(exportError = created.exportError)
    }

    companion object {
        const val MIN_RECORDING_MS = 800L
    }
}
