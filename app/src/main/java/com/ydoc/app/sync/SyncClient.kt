package com.ydoc.app.sync

import com.ydoc.app.model.Note
import com.ydoc.app.model.SyncTarget

interface SyncClient {
    val type: String
    suspend fun push(note: Note, target: SyncTarget): Result<Unit>
    suspend fun delete(note: Note, target: SyncTarget): Result<Unit>
    suspend fun testConnection(target: SyncTarget): Result<Unit>
    suspend fun listRemote(target: SyncTarget): Result<List<RemoteFileInfo>>
    suspend fun pull(target: SyncTarget, remotePath: String): Result<String>
    suspend fun deleteByPath(target: SyncTarget, remotePath: String): Result<Unit>
}

data class RemoteFileInfo(
    val path: String,
    val lastModified: Long?,
)
