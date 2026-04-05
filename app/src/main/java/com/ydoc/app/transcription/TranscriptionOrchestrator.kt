package com.ydoc.app.transcription

import android.util.Log
import com.ydoc.app.ai.AiOrchestrator
import com.ydoc.app.data.NoteRepository
import com.ydoc.app.logging.AppLogger
import com.ydoc.app.model.Note
import com.ydoc.app.model.RelayConfig
import com.ydoc.app.model.VolcengineConfig
import com.ydoc.app.relay.RelayStorageClient
import com.ydoc.app.sync.SyncOrchestrator
import kotlinx.coroutines.delay

class TranscriptionOrchestrator(
    private val noteRepository: NoteRepository,
    private val transcriptionClient: VolcengineTranscriptionClient,
    private val syncOrchestrator: SyncOrchestrator,
    private val relayStorageClient: RelayStorageClient,
    private val aiOrchestrator: AiOrchestrator,
) {
    suspend fun transcribe(note: Note, config: VolcengineConfig, relayConfig: RelayConfig? = null) {
        val audioUrl = note.relayUrl ?: error("Relay URL missing for transcription")
        val audioFormat = note.audioFormat ?: error("Audio format missing for transcription")

        noteRepository.markTranscribing(note.id, null)
        val submit = try {
            transcriptionClient.submit(audioUrl, audioFormat, config)
        } catch (e: Exception) {
            noteRepository.markTranscriptionFailed(note.id, "Submit failed: ${e.message}")
            cleanupRelayFile(note.relayFileId, relayConfig)
            throw e
        }
        noteRepository.markTranscribing(note.id, submit.requestId)

        repeat(MAX_POLL_ATTEMPTS) {
            delay(POLL_INTERVAL_MS)
            val query = transcriptionClient.query(submit.requestId, config)
            if (query.ready) {
                noteRepository.saveTranscript(note.id, query.text.orEmpty())
                noteRepository.getNote(note.id)?.let { updated ->
                    syncOrchestrator.syncNote(updated)
                }
                aiOrchestrator.maybeAnalyze(note.id, com.ydoc.app.model.AiRunTrigger.VOICE_TRANSCRIBED)
                cleanupRelayFile(note.relayFileId, relayConfig)
                return
            }
        }

        noteRepository.markTranscriptionFailed(note.id, "Transcription timed out after ${MAX_POLL_ATTEMPTS * POLL_INTERVAL_MS / 1000}s")
        cleanupRelayFile(note.relayFileId, relayConfig)
    }

    private suspend fun cleanupRelayFile(relayFileId: String?, relayConfig: RelayConfig?) {
        if (relayFileId.isNullOrBlank() || relayConfig == null) return
        try {
            relayStorageClient.delete(relayFileId, relayConfig)
            Log.i(TAG, "Cleaned up relay temp file: $relayFileId")
        } catch (e: Exception) {
            AppLogger.error("YDOC_TRANSCRIBE", "Failed to clean relay file: $relayFileId", e)
        }
    }

    companion object {
        private const val TAG = "TranscriptionOrchestrator"
        private const val POLL_INTERVAL_MS = 3000L
        private const val MAX_POLL_ATTEMPTS = 20
    }
}
