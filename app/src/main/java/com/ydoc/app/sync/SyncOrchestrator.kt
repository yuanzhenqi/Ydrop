package com.ydoc.app.sync

import android.util.Log
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
            if (syncOne(note, targets)) syncedCount++
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

            // Step 1: list remote files
            val remoteFilesResult = client.listRemote(target)
            if (remoteFilesResult.isFailure) {
                AppLogger.error("YDOC_SYNC", "列出远端文件失败", remoteFilesResult.exceptionOrNull())
                Log.e(TAG, "listRemote failed", remoteFilesResult.exceptionOrNull())
                continue
            }
            val remoteFiles = remoteFilesResult.getOrThrow()
            Log.i(TAG, "Found ${remoteFiles.size} remote files")

            // Step 2: download all remote files, extract id from frontmatter
            val remoteByNoteId = mutableMapOf<String, RemoteFileInfo>()
            val remoteContentById = mutableMapOf<String, String>()
            for (rfi in remoteFiles) {
                val contentResult = client.pull(target, rfi.path)
                if (contentResult.isFailure) {
                    Log.e(TAG, "pull failed for ${rfi.path}: ${contentResult.exceptionOrNull()?.message}")
                    AppLogger.error("YDOC_SYNC", "下载远端文件失败: ${rfi.path}", contentResult.exceptionOrNull())
                    result.failed++
                    continue
                }
                val content = contentResult.getOrThrow()
                val id = formatter.extractId(content)
                if (id != null) {
                    remoteByNoteId[id] = rfi
                    remoteContentById[id] = content
                    Log.d(TAG, "Remote file ${rfi.path} -> id=$id")
                } else {
                    Log.w(TAG, "Remote file ${rfi.path} has no id in frontmatter")
                }
            }
            Log.i(TAG, "Parsed ${remoteByNoteId.size} remote notes by id")

            // Step 3: get all local notes
            val allLocalNotes = noteRepository.observeNotes().first()
            val localById = allLocalNotes.associateBy { it.id }
            Log.i(TAG, "Local notes: ${allLocalNotes.size}, tombstones: ${tombstoneIds.size}")

            // Step 4: pull from remote — remote has, local may or may not have
            for ((remoteId, rfi) in remoteByNoteId) {
                val content = remoteContentById[remoteId]!!
                val local = localById[remoteId]

                if (local != null) {
                    // Both exist — compare updatedAt
                    val remoteNote = formatter.parseFromMarkdown(content, rfi.path)
                    if (remoteNote == null) {
                        Log.e(TAG, "parseFromMarkdown failed for remote id=$remoteId")
                        result.failed++
                        continue
                    }
                    val remoteTime = remoteNote.updatedAt
                    val localTime = local.updatedAt
                    Log.d(TAG, "Compare id=$remoteId: remoteTime=$remoteTime localTime=$localTime localStatus=${local.status}")

                    if (remoteTime > localTime) {
                        // Remote is newer — pull
                        val merged = remoteNote.copy(remotePath = rfi.path)
                        noteRepository.upsertFromRemote(merged)
                        result.pulled++
                        Log.i(TAG, "PULLED remote update for id=$remoteId (remote=$remoteTime > local=$localTime)")
                    } else if (localTime > remoteTime && local.status != NoteStatus.SYNCED) {
                        // Local is newer and not synced — push
                        pushNote(local, client, target, result)
                    } else {
                        Log.d(TAG, "No action for id=$remoteId (equal timestamps or already synced)")
                    }
                } else {
                    // Remote only — pull if not tombstoned
                    if (remoteId !in tombstoneIds) {
                        val remoteNote = formatter.parseFromMarkdown(content, rfi.path)
                        if (remoteNote != null) {
                            noteRepository.upsertFromRemote(remoteNote)
                            result.pulled++
                            Log.i(TAG, "PULLED new remote note id=$remoteId")
                        }
                    } else {
                        Log.d(TAG, "Skipping tombstoned id=$remoteId")
                    }
                }
            }

            // Step 5: push local notes that remote doesn't have
            for (note in allLocalNotes) {
                if (note.id in tombstoneIds) continue
                if (note.status == NoteStatus.SYNCED && note.id in remoteByNoteId.keys) continue
                if (note.id !in remoteByNoteId.keys) {
                    pushNote(note, client, target, result)
                }
            }

            // Step 6: delete remote files for tombstoned notes
            for (tombstoneId in tombstoneIds) {
                val rfi = remoteByNoteId[tombstoneId]
                if (rfi != null) {
                    client.deleteByPath(target, rfi.path).onFailure {
                        AppLogger.error("YDOC_SYNC", "远端删除墓碑笔记失败: $tombstoneId", it)
                    }
                    Log.i(TAG, "Deleted remote tombstone id=$tombstoneId path=${rfi.path}")
                }
            }
        }
        Log.i(TAG, "Sync complete: pushed=${result.pushed} pulled=${result.pulled} failed=${result.failed}")
        result
    }

    private suspend fun pushNote(note: Note, client: SyncClient, target: SyncTarget, result: BidirectionalSyncResult) {
        noteRepository.markSyncing(note.id)
        val pushResult = client.push(note, target)
        if (pushResult.isSuccess) {
            val rp = buildRemotePath(note, target)
            noteRepository.markSynced(note.id, rp)
            result.pushed++
            Log.i(TAG, "PUSHED id=${note.id} to $rp")
        } else {
            val err = pushResult.exceptionOrNull()?.message
            noteRepository.markFailed(note.id, err)
            result.failed++
            Log.e(TAG, "PUSH FAILED id=${note.id}: $err")
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

    companion object {
        private const val TAG = "SyncOrchestrator"
    }
}

data class BidirectionalSyncResult(
    var pushed: Int = 0,
    var pulled: Int = 0,
    var failed: Int = 0,
)
