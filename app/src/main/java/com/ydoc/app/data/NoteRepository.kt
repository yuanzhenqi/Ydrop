package com.ydoc.app.data

import com.ydoc.app.data.local.NoteDao
import com.ydoc.app.data.local.TombstoneDao
import com.ydoc.app.data.local.TombstoneEntity
import com.ydoc.app.model.Note
import com.ydoc.app.model.NoteCategory
import com.ydoc.app.model.NotePriority
import com.ydoc.app.model.NoteSource
import com.ydoc.app.model.NoteStatus
import com.ydoc.app.model.TranscriptionStatus
import com.ydoc.app.model.defaultColorFor
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
        tags: List<String> = emptyList(),
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
            audioPublicUri = null,
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
            isArchived = false,
            archivedAt = null,
            isTrashed = false,
            trashedAt = null,
            tags = tags,
        )
        noteDao.upsert(note.toEntity())
        return note
    }

    suspend fun createVoiceNote(
        noteId: String,
        audioPath: String,
        audioFormat: String,
        priority: NotePriority = NotePriority.HIGH,
        audioPublicUri: String? = null,
    ): Note {
        val now = System.currentTimeMillis()
        val note = Note(
            id = noteId,
            title = "语音记录 ${noteId.takeLast(6)}",
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
            audioPublicUri = audioPublicUri,
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
            isArchived = false,
            archivedAt = null,
            isTrashed = false,
            trashedAt = null,
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
            noteDao.upsert(
                entity.copy(
                    status = NoteStatus.SYNCED.name,
                    lastSyncedAt = System.currentTimeMillis(),
                    syncError = null,
                    remotePath = remotePath,
                ),
            )
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

    suspend fun saveNote(note: Note): Note {
        val updated = note.copy(
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
        // 转写完成时同步更新 title：仅当当前 title 还是初始占位符（"语音记录 xxxxxx"）才替换，
        // 避免覆盖用户已经手动改过的标题。修掉 WebDAV 拉回后标题一直是占位符的问题。
        val newTitle = if (isVoicePlaceholderTitle(note.title)) {
            extractVoiceTitle(transcript) ?: note.title
        } else {
            note.title
        }
        noteDao.update(
            note.copy(
                title = newTitle,
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

    /** 扫描存量 VOICE note，把仍为占位符的 title 从 content/transcript 抽一个新标题。只修占位符。 */
    suspend fun fixupVoiceTitles(): Int {
        val candidates = noteDao.getAll()
            .map { it.toModel() }
            .filter {
                it.source == NoteSource.VOICE &&
                    isVoicePlaceholderTitle(it.title) &&
                    it.content.isNotBlank() &&
                    it.content != "语音记录，等待后续转写。"
            }
        var fixed = 0
        candidates.forEach { note ->
            val newTitle = extractVoiceTitle(note.transcript?.takeIf { it.isNotBlank() } ?: note.content)
                ?: return@forEach
            if (newTitle == note.title) return@forEach
            noteDao.update(
                note.copy(
                    title = newTitle,
                    updatedAt = System.currentTimeMillis(),
                    status = NoteStatus.LOCAL_ONLY,
                    lastSyncedAt = null,
                ).toEntity(),
            )
            fixed += 1
        }
        return fixed
    }

    private fun isVoicePlaceholderTitle(title: String): Boolean {
        val trimmed = title.trim()
        if (trimmed == "语音记录") return true
        // "语音记录 <6 位 id 后缀>"
        return trimmed.startsWith("语音记录 ") &&
            trimmed.removePrefix("语音记录 ").length in 1..10
    }

    private fun extractVoiceTitle(text: String): String? {
        val firstLine = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            ?: return null
        return firstLine.take(36)
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
        tombstoneDao.insert(TombstoneEntity(noteId, System.currentTimeMillis()))
    }

    suspend fun pendingNotes(): List<Note> = noteDao.getPendingSync().map { it.toModel() }

    suspend fun getNoteByRemotePath(remotePath: String): Note? = noteDao.getByRemotePath(remotePath)?.toModel()

    suspend fun upsertFromRemote(note: Note) {
        val existing = noteDao.getById(note.id)?.toModel()
        if (existing != null) {
            val merged = existing.copy(
                title = note.title,
                content = note.content,
                originalContent = existing.originalContent,
                source = note.source,
                category = note.category,
                priority = note.priority,
                colorToken = note.colorToken,
                status = NoteStatus.SYNCED,
                updatedAt = note.updatedAt,
                lastSyncedAt = System.currentTimeMillis(),
                syncError = null,
                audioPublicUri = existing.audioPublicUri,
                transcript = note.transcript ?: existing.transcript,
                transcriptionStatus = note.transcriptionStatus,
                transcriptionError = note.transcriptionError,
                remotePath = note.remotePath,
                lastPulledAt = System.currentTimeMillis(),
                relayUrl = note.relayUrl ?: existing.relayUrl,
                tags = note.tags.ifEmpty { existing.tags },
                isArchived = note.isArchived,
                archivedAt = note.archivedAt,
                isTrashed = false,
                trashedAt = null,
            )
            noteDao.upsert(merged.toEntity())
        } else {
            noteDao.upsert(note.toEntity())
        }
        tombstoneDao.deleteById(note.id)
    }

    fun observeActiveNotes(): Flow<List<Note>> = noteDao.observeActive().map { it.map { entity -> entity.toModel() } }
    fun observeArchivedNotes(): Flow<List<Note>> = noteDao.observeArchived().map { it.map { entity -> entity.toModel() } }
    fun observeTrashedNotes(): Flow<List<Note>> = noteDao.observeTrashed().map { it.map { entity -> entity.toModel() } }

    suspend fun archiveNote(noteId: String): Note {
        val now = System.currentTimeMillis()
        noteDao.archiveById(noteId, now, now)
        return getNote(noteId) ?: error("找不到这条记录。")
    }

    suspend fun unarchiveNote(noteId: String): Note {
        val now = System.currentTimeMillis()
        noteDao.unarchiveById(noteId, now)
        return getNote(noteId) ?: error("找不到这条记录。")
    }

    suspend fun trashNote(noteId: String): Note {
        val now = System.currentTimeMillis()
        noteDao.trashById(noteId, now, now)
        return getNote(noteId) ?: error("找不到这条记录。")
    }

    suspend fun restoreNote(noteId: String): Note {
        val now = System.currentTimeMillis()
        noteDao.restoreById(noteId, now)
        return getNote(noteId) ?: error("找不到这条记录。")
    }

    suspend fun setPinned(noteId: String, pinned: Boolean): Note {
        noteDao.setPinned(noteId, pinned, System.currentTimeMillis())
        return getNote(noteId) ?: error("Note not found")
    }

    /**
     * 把多条笔记合并为一条新的 TEXT note：
     * - 按 createdAt 升序拼接 content（每段前加 "### 原标题" 作为分隔）
     * - tags 取并集
     * - category 取出现次数最多的；并列时取 ids 中第一条的 category
     * - priority 取最高（URGENT > HIGH > MEDIUM > LOW）
     * - 原 N 条全部移入回收站
     * 返回新 note；若 ids 少于 2 条或查不到，抛错。
     */
    suspend fun mergeNotes(ids: List<String>, overrideTitle: String? = null): Note {
        require(ids.size >= 2) { "至少选择 2 条笔记才能合并。" }
        val sources = ids.mapNotNull { noteDao.getById(it)?.toModel() }
            .sortedBy { it.createdAt }
        require(sources.size >= 2) { "所选笔记不存在或已被删除。" }

        val mergedContent = buildString {
            if (!overrideTitle.isNullOrBlank()) {
                appendLine("# ${overrideTitle.trim()}")
                appendLine()
            }
            sources.forEachIndexed { index, note ->
                val heading = note.title.trim().ifBlank { "片段 ${index + 1}" }
                appendLine("### $heading")
                appendLine(note.content.trim())
                if (index != sources.lastIndex) {
                    appendLine()
                }
            }
        }.trimEnd()

        val mergedTags = sources.flatMap { it.tags }.distinct()
        val mergedCategory = sources.groupingBy { it.category }.eachCount()
            .maxByOrNull { it.value }?.key ?: sources.first().category
        val mergedPriority = sources.maxByOrNull { it.priority.ordinal }?.priority
            ?: NotePriority.MEDIUM

        val newNote = createTextNote(
            content = mergedContent,
            category = mergedCategory,
            priority = mergedPriority,
            tags = mergedTags,
        )
        sources.forEach { runCatching { trashNote(it.id) } }
        return newNote
    }

    suspend fun emptyTrash() {
        val trashed = noteDao.getTrashed()
        trashed.forEach { entity ->
            noteDao.deleteById(entity.id)
            tombstoneDao.insert(TombstoneEntity(entity.id, System.currentTimeMillis()))
        }
    }

    suspend fun getTombstoneIds(): List<String> = tombstoneDao.getAllIds()
}
