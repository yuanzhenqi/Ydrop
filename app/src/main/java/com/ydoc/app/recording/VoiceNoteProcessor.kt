package com.ydoc.app.recording

import com.ydoc.app.data.NoteRepository
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

    suspend fun stopAndSave(
        priority: NotePriority,
        relayConfig: RelayConfig,
        volcengineConfig: VolcengineConfig,
        wifiOnly: Boolean,
    ): SaveResult {
        val output = audioRecorder.stop(MIN_RECORDING_MS)
        val noteId = UUID.randomUUID().toString()
        var exportError: String? = null
        val publicUri = runCatching {
            localAudioExporter.exportRecording(noteId, output.path)
        }.getOrElse {
            exportError = "系统媒体库导出失败。"
            null
        }

        var note = noteRepository.createVoiceNote(
            noteId = noteId,
            audioPath = output.path,
            audioFormat = output.format,
            priority = priority,
            audioPublicUri = publicUri,
        )

        if (!relayConfig.enabled) {
            return SaveResult(note, RemoteStatus.LOCAL_ONLY, exportError = exportError)
        }

        try {
            val upload = relayStorageClient.upload(File(output.path), relayConfig)
            note = noteRepository.attachRelayInfo(note, upload.fileId, upload.url, upload.expiresAt)
        } catch (error: Exception) {
            noteRepository.markTranscriptionFailed(note.id, "Relay upload failed: ${error.message ?: "unknown"}")
            val refreshed = noteRepository.getNote(note.id) ?: note
            return SaveResult(
                note = refreshed,
                remoteStatus = RemoteStatus.REMOTE_FAILED,
                exportError = exportError,
                remoteError = "上传失败，请稍后重试。",
            )
        }

        if (!volcengineConfig.enabled || note.relayUrl.isNullOrBlank()) {
            return SaveResult(note, RemoteStatus.RELAY_UPLOADED, exportError = exportError)
        }

        var remoteStatus = RemoteStatus.TRANSCRIPTION_REQUESTED
        var remoteError: String? = null
        runCatching {
            transcriptionOrchestrator.transcribe(note, volcengineConfig, relayConfig)
        }.onFailure {
            transcriptionScheduler.enqueueRetry(note.id, wifiOnly)
            remoteStatus = RemoteStatus.REMOTE_FAILED
            remoteError = "转写失败，已加入重试队列。"
        }
        val refreshed = noteRepository.getNote(note.id) ?: note
        return SaveResult(
            note = refreshed,
            remoteStatus = remoteStatus,
            exportError = exportError,
            remoteError = remoteError,
        )
    }

    companion object {
        const val MIN_RECORDING_MS = 800L
    }
}
