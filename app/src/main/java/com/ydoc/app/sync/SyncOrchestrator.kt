package com.ydoc.app.sync

import com.ydoc.app.data.MarkdownFormatter
import com.ydoc.app.data.NoteRepository
import com.ydoc.app.data.SyncTargetRepository
import com.ydoc.app.logging.AppLogger
import com.ydoc.app.model.Note
import com.ydoc.app.model.NoteStatus
import com.ydoc.app.model.SyncTarget
import com.ydoc.app.model.WebDavConfig
import kotlinx.coroutines.flow.first
import java.net.URLEncoder

class SyncOrchestrator(
    private val noteRepository: NoteRepository,
    private val syncTargetRepository: SyncTargetRepository,
    private val formatter: MarkdownFormatter,
    clients: List<SyncClient>,
) {
    private val clientMap = clients.associateBy { it.type }

    suspend fun syncPending(): Result<Int> = runCatching {
        val notes = noteRepository.pendingNotes()
        val targets = syncTargetRepository.getEnabledTargets()
        if (targets.isEmpty()) return@runCatching 0

        var syncedCount = 0
        for (note in notes) {
            if (syncOne(note, targets)) {
                syncedCount += 1
            }
        }
        syncedCount
    }

    suspend fun syncNote(note: Note): Result<Unit> = runCatching {
        val targets = syncTargetRepository.getEnabledTargets()
        if (targets.isEmpty()) return@runCatching Unit
        check(syncOne(note, targets)) { "同步失败" }
    }

    suspend fun deleteRemote(note: Note): Result<Unit> = runCatching {
        val targets = syncTargetRepository.getEnabledTargets()
        if (targets.isEmpty()) return@runCatching Unit
        targets.forEach { target ->
            val client = clientMap[target.type.name] ?: error("Missing client for ${target.type}")
            client.delete(note, target).getOrThrow()
        }
    }

    suspend fun syncBidirectional(): Result<BidirectionalSyncResult> = runCatching {
        val targets = syncTargetRepository.getEnabledTargets()
        if (targets.isEmpty()) return@runCatching BidirectionalSyncResult()

        val result = BidirectionalSyncResult()
        val tombstoneIds = noteRepository.getTombstoneIds()

        for (target in targets) {
            val client = clientMap[target.type.name]
            if (client == null) {
                AppLogger.error("YDOC_SYNC", "No client for target type: ${target.type}", null)
                continue
            }

            val remoteFilesResult = client.listRemote(target)
            if (remoteFilesResult.isFailure) {
                AppLogger.error("YDOC_SYNC", "列出远端文件失败", remoteFilesResult.exceptionOrNull())
                continue
            }
            val remoteFiles = remoteFilesResult.getOrThrow()

            val remoteByNoteId = mutableMapOf<String, Pair<String, RemoteFileInfo>>()
            for (rfi in remoteFiles) {
                val contentResult = client.pull(target, rfi.path)
                if (contentResult.isFailure) {
                    AppLogger.error("YDOC_SYNC", "下载远端文件失败: ${rfi.path}", contentResult.exceptionOrNull())
                    result.failed++
                    continue
                }
                val content = contentResult.getOrThrow()
                val id = formatter.extractId(content)
                if (id != null) {
                    remoteByNoteId[id] = Pair(content, rfi)
                }
            }

            val allLocalNotes = noteRepository.observeNotes().first()
            val localById = allLocalNotes.associateBy { it.id }

            for ((remoteId, pair) in remoteByNoteId) {
                val (content, rfi) = pair
                val local = localById[remoteId]
                if (local != null) {
                    val remoteNote = formatter.parseFromMarkdown(content, rfi.path)
                    if (remoteNote == null) {
                        result.failed++
                        continue
                    }
                    if (remoteNote.updatedAt > local.updatedAt) {
                        val merged = remoteNote.copy(remotePath = rfi.path)
                        noteRepository.upsertFromRemote(merged)
                        result.pulled++
                        AppLogger.overlay("Sync: pulled remote update for id=$remoteId")
                    } else if (local.updatedAt > remoteNote.updatedAt && local.status != NoteStatus.SYNCED) {
                        if (rfi.path != buildRemotePath(local, target)) {
                            client.deleteByPath(target, rfi.path).onFailure {
                                AppLogger.error("YDOC_SYNC", "删除旧远端文件失败: ${rfi.path}", it)
                            }
                        }
                        pushNote(local, client, target, result)
                    }
                } else {
                    if (remoteId !in tombstoneIds) {
                        val remoteNote = formatter.parseFromMarkdown(content, rfi.path)
                        if (remoteNote != null) {
                            noteRepository.upsertFromRemote(remoteNote)
                            result.pulled++
                            AppLogger.overlay("Sync: pulled new remote note id=$remoteId")
                        }
                    }
                }
            }

            for (note in allLocalNotes) {
                if (note.id in tombstoneIds) continue
                if (note.status == NoteStatus.SYNCED && note.id in remoteByNoteId.keys) continue

                if (note.id in remoteByNoteId.keys) {
                    val (_, rfi) = remoteByNoteId[note.id]!!
                    if (note.status != NoteStatus.SYNCED) {
                        if (rfi.path != buildRemotePath(note, target)) {
                            client.deleteByPath(target, rfi.path).onFailure {
                                AppLogger.error("YDOC_SYNC", "删除旧远端文件失败: ${rfi.path}", it)
                            }
                        }
                        pushNote(note, client, target, result)
                    }
                } else {
                    if (note.remotePath != null) {
                        client.deleteByPath(target, note.remotePath).onFailure {
                            AppLogger.error("YDOC_SYNC", "删除旧远端文件失败: ${note.remotePath}", it)
                        }
                    }
                    pushNote(note, client, target, result)
                }
            }

            for (tombstoneId in tombstoneIds) {
                val pair = remoteByNoteId[tombstoneId]
                if (pair != null) {
                    val (_, rfi) = pair
                    client.deleteByPath(target, rfi.path).onFailure {
                        AppLogger.error("YDOC_SYNC", "远端删除墓碑笔记失败: $tombstoneId", it)
                    }
                }
            }
        }
        result
    }

    private suspend fun pushNote(note: Note, client: SyncClient, target: SyncTarget, result: BidirectionalSyncResult) {
        noteRepository.markSyncing(note.id)
        val pushResult = client.push(note, target)
        if (pushResult.isSuccess) {
            val rp = buildRemotePath(note, target)
            noteRepository.markSynced(note.id, rp)
            result.pushed++
        } else {
            noteRepository.markFailed(note.id, pushResult.exceptionOrNull()?.message)
            result.failed++
        }
    }

    private fun buildRemotePath(note: Note, target: SyncTarget): String? {
        val config = target.config as? WebDavConfig ?: return null
        val folder = config.folder.trim('/').ifBlank { "ydoc/inbox" }
        val encoded = URLEncoder.encode(formatter.fileName(note), Charsets.UTF_8.name())
        return "$folder/$encoded"
    }

    private suspend fun syncOne(note: Note, targets: List<SyncTarget>): Boolean {
        noteRepository.markSyncing(note.id)
        val syncResults = targets.map { target ->
            if (note.remotePath != null) {
                val client = clientMap[target.type.name] ?: error("Missing client for ${target.type}")
                val newPath = buildRemotePath(note, target)
                if (newPath != null && note.remotePath != newPath) {
                    client.deleteByPath(target, note.remotePath).onFailure {
                        AppLogger.error("YDOC_SYNC", "删除旧远端文件失败: ${note.remotePath}", it)
                    }
                }
            }
            val client = clientMap[target.type.name] ?: error("Missing client for ${target.type}")
            client.push(note, target)
        }
        return if (syncResults.all { it.isSuccess }) {
            noteRepository.markSynced(note.id)
            true
        } else {
            val errorMessage = syncResults.firstOrNull { it.isFailure }?.exceptionOrNull()?.message
            noteRepository.markFailed(note.id, errorMessage)
            false
        }
    }
}

data class BidirectionalSyncResult(
    var pushed: Int = 0,
    var pulled: Int = 0,
    var failed: Int = 0,
)
