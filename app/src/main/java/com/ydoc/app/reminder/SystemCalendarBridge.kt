package com.ydoc.app.reminder

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.ydoc.app.logging.AppLogger
import com.ydoc.app.model.ReminderEntry
import java.util.TimeZone

/**
 * 把 Ydrop 的提醒双写到系统日历（CalendarContract）。
 *
 * 设计要点：
 * - 所有操作依赖 WRITE_CALENDAR / READ_CALENDAR 运行时权限；没权限时静默返回 null / false，
 *   上层应该继续跑 AlarmManager 本地链路，不该因此失败。
 * - 只维护一个本地 ACCOUNT_TYPE_LOCAL 日历，命名为 "Ydrop"，避免污染用户已有账号。
 * - 每条提醒对应一个 Events 行 + 一个 METHOD_ALERT Reminders 行。
 */
class SystemCalendarBridge(private val context: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED

    /**
     * 查找或创建 Ydrop 专属本地日历。
     * @return calendarId；无权限 / 失败时返回 null。
     */
    fun ensureLocalCalendar(): Long? {
        if (!hasPermission()) return null
        return runCatching {
            findLocalCalendar() ?: createLocalCalendar()
        }.onFailure { error ->
            AppLogger.error(TAG, "ensureLocalCalendar 失败", error)
        }.getOrNull()
    }

    /**
     * 新增事件，返回 Event id；任何失败（无权限、无日历、系统拒绝）都返回 null。
     */
    fun insertEvent(reminder: ReminderEntry): Long? {
        if (!hasPermission()) return null
        val calendarId = ensureLocalCalendar() ?: return null
        return runCatching {
            val tz = TimeZone.getDefault().id
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, reminder.title.ifBlank { YDROP_EVENT_DEFAULT_TITLE })
                put(CalendarContract.Events.DESCRIPTION, "Ydrop 提醒")
                put(CalendarContract.Events.DTSTART, reminder.scheduledAt)
                put(CalendarContract.Events.DTEND, reminder.scheduledAt + DEFAULT_DURATION_MS)
                put(CalendarContract.Events.EVENT_TIMEZONE, tz)
                put(CalendarContract.Events.HAS_ALARM, 1)
                put(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_BUSY)
                put(CalendarContract.Events.ACCESS_LEVEL, CalendarContract.Events.ACCESS_DEFAULT)
            }
            val eventUri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                ?: return@runCatching null
            val eventId = ContentUris.parseId(eventUri)
            insertReminderRow(eventId)
            eventId
        }.onFailure { error ->
            AppLogger.error(TAG, "insertEvent 失败 id=${reminder.id}", error)
        }.getOrNull()
    }

    fun updateEvent(eventId: Long, reminder: ReminderEntry): Boolean {
        if (!hasPermission()) return false
        return runCatching {
            val tz = TimeZone.getDefault().id
            val values = ContentValues().apply {
                put(CalendarContract.Events.TITLE, reminder.title.ifBlank { YDROP_EVENT_DEFAULT_TITLE })
                put(CalendarContract.Events.DTSTART, reminder.scheduledAt)
                put(CalendarContract.Events.DTEND, reminder.scheduledAt + DEFAULT_DURATION_MS)
                put(CalendarContract.Events.EVENT_TIMEZONE, tz)
            }
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            context.contentResolver.update(uri, values, null, null) > 0
        }.onFailure { error ->
            AppLogger.error(TAG, "updateEvent 失败 eventId=$eventId", error)
        }.getOrDefault(false)
    }

    fun deleteEvent(eventId: Long): Boolean {
        if (!hasPermission()) return false
        return runCatching {
            val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            context.contentResolver.delete(uri, null, null) > 0
        }.onFailure { error ->
            AppLogger.error(TAG, "deleteEvent 失败 eventId=$eventId", error)
        }.getOrDefault(false)
    }

    private fun findLocalCalendar(): Long? {
        val projection = arrayOf(CalendarContract.Calendars._ID)
        val selection = "${CalendarContract.Calendars.ACCOUNT_TYPE} = ? AND ${CalendarContract.Calendars.ACCOUNT_NAME} = ?"
        val args = arrayOf(CalendarContract.ACCOUNT_TYPE_LOCAL, YDROP_ACCOUNT_NAME)
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            selection,
            args,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getLong(0)
            }
        }
        return null
    }

    private fun createLocalCalendar(): Long? {
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, YDROP_ACCOUNT_NAME)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, YDROP_ACCOUNT_NAME)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, YDROP_ACCOUNT_NAME)
            put(CalendarContract.Calendars.CALENDAR_COLOR, YDROP_COLOR)
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, YDROP_ACCOUNT_NAME)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
        }
        val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, YDROP_ACCOUNT_NAME)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            .build()
        return context.contentResolver.insert(uri, values)?.let { ContentUris.parseId(it) }
    }

    private fun insertReminderRow(eventId: Long) {
        val values = ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, eventId)
            // METHOD_ALARM 比 METHOD_ALERT 更偏向"闹钟"语义，AOSP 日历会以闹钟样式弹全屏提醒
            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALARM)
            put(CalendarContract.Reminders.MINUTES, 0)
        }
        context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, values)
    }

    companion object {
        private const val TAG = "YdropCalendarBridge"
        private const val YDROP_ACCOUNT_NAME = "Ydrop"
        private const val YDROP_EVENT_DEFAULT_TITLE = "Ydrop 提醒"
        private const val YDROP_COLOR = 0xFF4F6F52.toInt()
        private const val DEFAULT_DURATION_MS: Long = 10 * 60 * 1000L
    }
}
