package com.ydoc.app.data

import com.ydoc.app.data.local.ReminderEntryDao
import com.ydoc.app.model.ReminderDeliveryTarget
import com.ydoc.app.model.ReminderEntry
import com.ydoc.app.model.ReminderSource
import com.ydoc.app.model.ReminderStatus
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ReminderRepository(
    private val reminderEntryDao: ReminderEntryDao,
) {
    fun observeReminders(): Flow<List<ReminderEntry>> =
        reminderEntryDao.observeAll().map { reminders -> reminders.map { it.toModel() } }

    suspend fun getReminder(id: String): ReminderEntry? = reminderEntryDao.getById(id)?.toModel()

    suspend fun getByNoteId(noteId: String): List<ReminderEntry> = reminderEntryDao.getByNoteId(noteId).map { it.toModel() }

    suspend fun getScheduled(): List<ReminderEntry> = reminderEntryDao.getScheduled().map { it.toModel() }

    suspend fun createReminder(
        noteId: String,
        title: String,
        scheduledAt: Long,
        source: ReminderSource,
        deliveryTargets: Set<ReminderDeliveryTarget> = setOf(ReminderDeliveryTarget.LOCAL_NOTIFICATION),
    ): ReminderEntry {
        val now = System.currentTimeMillis()
        val entry = ReminderEntry(
            id = UUID.randomUUID().toString(),
            noteId = noteId,
            title = title,
            scheduledAt = scheduledAt,
            source = source,
            status = ReminderStatus.SCHEDULED,
            deliveryTargets = deliveryTargets,
            createdAt = now,
            updatedAt = now,
        )
        reminderEntryDao.upsert(entry.toEntity())
        return entry
    }

    suspend fun upsert(entry: ReminderEntry) {
        reminderEntryDao.upsert(entry.toEntity())
    }

    suspend fun markFired(id: String) = updateStatus(id, ReminderStatus.FIRED)

    suspend fun cancel(id: String) = updateStatus(id, ReminderStatus.CANCELLED)

    suspend fun dismiss(id: String) = updateStatus(id, ReminderStatus.DISMISSED)

    suspend fun deleteByNoteId(noteId: String) {
        reminderEntryDao.deleteByNoteId(noteId)
    }

    private suspend fun updateStatus(id: String, status: ReminderStatus) {
        reminderEntryDao.updateStatus(id, status.name, System.currentTimeMillis())
    }
}
