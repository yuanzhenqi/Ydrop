package com.ydoc.app.transcription

import com.ydoc.app.data.NoteRepository
import com.ydoc.app.model.Note
import com.ydoc.app.model.VolcengineConfig
import com.ydoc.app.sync.SyncOrchestrator
import kotlinx.coroutines.delay

class TranscriptionOrchestrator(
    private val noteRepository: NoteRepository,
    private val transcriptionClient: VolcengineTranscriptionClient,
    private val syncOrchestrator: SyncOrchestrator,
) {
    suspend fun transcribe(note: Note, config: VolcengineConfig) {
        val audioUrl = note.relayUrl ?: error("Relay URL missing for transcription")
        val audioFormat = note.audioFormat ?: error("Audio format missing for transcription")
        noteRepository.markTranscribing(note.id, null)
        val submit = transcriptionClient.submit(audioUrl, audioFormat, config)
        noteRepository.markTranscribing(note.id, submit.requestId)

        repeat(12) {
            delay(3000)
            val query = transcriptionClient.query(submit.requestId, config)
            if (query.ready) {
                noteRepository.saveTranscript(note.id, query.text.orEmpty())
                noteRepository.getNote(note.id)?.let { updated ->
                    syncOrchestrator.syncNote(updated)
                }
                return
            }
        }

        noteRepository.markTranscriptionFailed(note.id, "Volcengine transcription timed out")
    }
}
