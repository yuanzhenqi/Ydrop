package com.ydoc.app.sync

import com.ydoc.app.data.NoteRepository
import com.ydoc.app.data.SyncTargetRepository
import com.ydoc.app.model.Note

class SyncOrchestrator(
    private val noteRepository: NoteRepository,
    private val syncTargetRepository: SyncTargetRepository,
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

    private suspend fun syncOne(note: Note, targets: List<com.ydoc.app.model.SyncTarget>): Boolean {
        noteRepository.markSyncing(note.id)
        val result = targets.map { target ->
            val client = clientMap[target.type.name] ?: error("Missing client for ${target.type}")
            client.push(note, target)
        }
        return if (result.all { it.isSuccess }) {
            noteRepository.markSynced(note.id)
            true
        } else {
            val errorMessage = result.firstOrNull { it.isFailure }?.exceptionOrNull()?.message
            noteRepository.markFailed(note.id, errorMessage)
            false
        }
    }
}
