package com.ydoc.app.data

import com.ydoc.app.data.local.ReminderEntryDao
import com.ydoc.app.model.ReminderDeliveryTarget
import com.ydoc.app.model.ReminderEntry
import com.ydoc.app.model.ReminderSource
import com.ydoc.app.model.ReminderStatus
import com.ydoc.app.reminder.SystemAlarmExporter
import com.ydoc.app.reminder.SystemCalendarBridge
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ReminderRepository(
    private val reminderEntryDao: ReminderEntryDao,
    private val systemCalendarBridge: SystemCalendarBridge? = null,
    private val systemAlarmExporter: SystemAlarmExporter? = null,
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
        val baseEntry = ReminderEntry(
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
        // 双写：若 WRITE_CALENDAR 权限已授予，同步一条系统日历事件
        val systemEventId = systemCalendarBridge?.insertEvent(baseEntry)
        // 额外：未来 7 天内的 reminder 同时写一份到系统闹钟 app（AlarmClock）
        systemAlarmExporter?.exportIfApplicable(baseEntry)
        val entry = if (systemEventId != null) {
            baseEntry.copy(
                systemEventId = systemEventId,
                deliveryTargets = deliveryTargets + ReminderDeliveryTarget.SYSTEM_ALARM_EXPORT,
            )
        } else baseEntry
        reminderEntryDao.upsert(entry.toEntity())
        return entry
    }

    suspend fun upsert(entry: ReminderEntry) {
        reminderEntryDao.upsert(entry.toEntity())
    }

    suspend fun markFired(id: String) = updateStatus(id, ReminderStatus.FIRED)

    suspend fun cancel(id: String) {
        val target = reminderEntryDao.getById(id)?.toModel()
        target?.systemEventId?.let { eventId -> systemCalendarBridge?.deleteEvent(eventId) }
        updateStatus(id, ReminderStatus.CANCELLED)
        if (target?.systemEventId != null) {
            reminderEntryDao.updateSystemEventId(id, null, System.currentTimeMillis())
        }
    }

    suspend fun dismiss(id: String) = updateStatus(id, ReminderStatus.DISMISSED)

    suspend fun deleteByNoteId(noteId: String) {
        val affected = reminderEntryDao.getByNoteId(noteId).map { it.toModel() }
        affected.forEach { reminder ->
            reminder.systemEventId?.let { eventId -> systemCalendarBridge?.deleteEvent(eventId) }
        }
        reminderEntryDao.deleteByNoteId(noteId)
    }

    private suspend fun updateStatus(id: String, status: ReminderStatus) {
        reminderEntryDao.updateStatus(id, status.name, System.currentTimeMillis())
    }
}
