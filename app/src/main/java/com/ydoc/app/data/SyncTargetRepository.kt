package com.ydoc.app.data

import com.ydoc.app.data.local.SyncTargetDao
import com.ydoc.app.model.SyncTarget
import com.ydoc.app.model.SyncType
import com.ydoc.app.model.WebDavConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SyncTargetRepository(
    private val dao: SyncTargetDao,
) {
    fun observeTargets(): Flow<List<SyncTarget>> = dao.observeAll().map { items -> items.mapNotNull { it.toModelOrNull() } }

    suspend fun getEnabledTargets(): List<SyncTarget> = dao.getAll().mapNotNull { it.toModelOrNull() }.filter { it.enabled }

    suspend fun getTarget(type: SyncType): SyncTarget? = dao.getAll().mapNotNull { it.toModelOrNull() }.firstOrNull { it.type == type }

    suspend fun saveTarget(target: SyncTarget) {
        dao.upsert(target.toEntity())
    }

    suspend fun seedDefaults() {
        val existing = dao.getAll()
        if (existing.isNotEmpty()) return
        val now = System.currentTimeMillis()
        dao.upsert(
            SyncTarget(
                type = SyncType.WEBDAV,
                enabled = false,
                config = WebDavConfig(),
                updatedAt = now,
            ).toEntity(),
        )
    }
}
