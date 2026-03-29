package com.ydoc.app.data

import com.ydoc.app.data.local.NoteDao
import com.ydoc.app.data.local.TombstoneDao
import com.ydoc.app.model.NoteCategory
import com.ydoc.app.model.NoteColorToken
import com.ydoc.app.model.Note
import com.ydoc.app.model.NotePriority
import com.ydoc.app.model.NoteSource
import com.ydoc.app.model.NoteStatus
import com.ydoc.app.model.TranscriptionStatus
import com.ydoc.app.model.defaultColorFor
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class NoteRepository(
    private val noteDao: NoteDao,
    private val tombstoneDao: TombstoneDao,
) {
    fun observeNotes(): Flow<List<Note>> = noteDao.observeAll().map { notes -> notes.map { it.toModel() } }

    suspend fun getNote(noteId: String): Note? = noteDao.getById(noteId)?.toModel()

    suspend fun createTextNote(
        content: String,
        category: NoteCategory,
        priority: NotePriority,
    ): Note {
        val now = System.currentTimeMillis()
        val normalized = content.trim()
        val note = Note(
            id = UUID.randomUUID().toString(),
            title = normalized.lineSequence().firstOrNull()?.take(36).orEmpty().ifBlank { "Quick note" },
            content = normalized,
            source = NoteSource.TEXT,
            category = category,
            priority = priority,
            colorToken = defaultColorFor(category, priority),
            status = NoteStatus.LOCAL_ONLY,
            createdAt = now,
            updatedAt = now,
            lastSyncedAt = null,
            audioPath = null,
            audioFormat = null,
            relayFileId = null,
            relayUrl = null,
            relayExpiresAt = null,
            transcript = null,
            transcriptionStatus = TranscriptionStatus.NOT_STARTED,
            transcriptionError = null,
            transcriptionRequestId = null,
            transcriptionUpdatedAt = null,
            syncError = null,
            pinned = false,
        )
        noteDao.upsert(note.toEntity())
        return note
    }

    suspend fun createVoiceNote(
        audioPath: String,
        audioFormat: String,
        priority: NotePriority = NotePriority.HIGH,
    ): Note {
        val now = System.currentTimeMillis()
        val fileName = File(audioPath).nameWithoutExtension
        val note = Note(
            id = UUID.randomUUID().toString(),
            title = "语音记录 ${fileName.takeLast(6)}",
            content = "语音记录，等待后续转写。",
            source = NoteSource.VOICE,
            category = NoteCategory.REMINDER,
            priority = priority,
            colorToken = defaultColorFor(NoteCategory.REMINDER, priority),
            status = NoteStatus.LOCAL_ONLY,
            createdAt = now,
            updatedAt = now,
            lastSyncedAt = null,
            audioPath = audioPath,
            audioFormat = audioFormat,
            relayFileId = null,
            relayUrl = null,
            relayExpiresAt = null,
            transcript = null,
            transcriptionStatus = TranscriptionStatus.NOT_STARTED,
            transcriptionError = null,
            transcriptionRequestId = null,
            transcriptionUpdatedAt = null,
            syncError = null,
            pinned = false,
        )
        noteDao.upsert(note.toEntity())
        return note
    }

    suspend fun markSyncing(noteId: String) {
        noteDao.updateSyncMetadata(noteId, NoteStatus.SYNCING.name, null, null)
    }

    suspend fun markSynced(noteId: String, remotePath: String? = null) {
        val entity = noteDao.getById(noteId)
        if (remotePath != null && entity != null) {
            noteDao.upsert(entity.copy(status = NoteStatus.SYNCED.name, lastSyncedAt = System.currentTimeMillis(), syncError = null, remotePath = remotePath))
        } else {
            noteDao.updateSyncMetadata(noteId, NoteStatus.SYNCED.name, System.currentTimeMillis(), null)
        }
    }

    suspend fun markFailed(noteId: String, error: String?) {
        noteDao.updateSyncMetadata(noteId, NoteStatus.FAILED.name, null, error)
    }

    suspend fun saveEditedNote(note: Note): Note {
        val updated = note.copy(
            title = note.content.lineSequence().firstOrNull()?.take(36).orEmpty().ifBlank { note.title },
            updatedAt = System.currentTimeMillis(),
            status = NoteStatus.LOCAL_ONLY,
            lastSyncedAt = null,
            syncError = null,
        )
        noteDao.update(updated.toEntity())
        return updated
    }

    suspend fun attachRelayInfo(
        note: Note,
        relayFileId: String,
        relayUrl: String,
        relayExpiresAt: Long?,
    ): Note {
        val updated = note.copy(
            transcriptionStatus = TranscriptionStatus.UPLOADING,
            transcriptionError = null,
            transcriptionUpdatedAt = System.currentTimeMillis(),
            relayFileId = relayFileId,
            relayUrl = relayUrl,
            relayExpiresAt = relayExpiresAt,
            updatedAt = System.currentTimeMillis(),
        )
        noteDao.update(updated.toEntity())
        return updated
    }

    suspend fun markTranscribing(noteId: String, requestId: String?) {
        val note = getNote(noteId) ?: return
        noteDao.update(
            note.copy(
                transcriptionStatus = TranscriptionStatus.TRANSCRIBING,
                transcriptionError = null,
                transcriptionRequestId = requestId,
                transcriptionUpdatedAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            ).toEntity(),
        )
    }

    suspend fun saveTranscript(noteId: String, transcript: String) {
        val note = getNote(noteId) ?: return
        noteDao.update(
            note.copy(
                content = transcript,
                transcript = transcript,
                transcriptionStatus = TranscriptionStatus.DONE,
                transcriptionError = null,
                transcriptionUpdatedAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                status = NoteStatus.LOCAL_ONLY,
                syncError = null,
                lastSyncedAt = null,
            ).toEntity(),
        )
    }

    suspend fun markTranscriptionFailed(noteId: String, error: String) {
        val note = getNote(noteId) ?: return
        noteDao.update(
            note.copy(
                transcriptionStatus = TranscriptionStatus.FAILED,
                transcriptionError = error,
                transcriptionUpdatedAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            ).toEntity(),
        )
    }

    suspend fun deleteNote(noteId: String) {
        noteDao.deleteById(noteId)
        tombstoneDao.insert(com.ydoc.app.data.local.TombstoneEntity(noteId, System.currentTimeMillis()))
    }

    suspend fun pendingNotes(): List<Note> = noteDao.getPendingSync().map { it.toModel() }

    suspend fun getNoteByRemotePath(remotePath: String): Note? = noteDao.getByRemotePath(remotePath)?.toModel()

    suspend fun upsertFromRemote(note: Note) {
        noteDao.upsert(note.toEntity())
        tombstoneDao.deleteById(note.id)
    }

    suspend fun getTombstoneIds(): List<String> = tombstoneDao.getAllIds()
}
