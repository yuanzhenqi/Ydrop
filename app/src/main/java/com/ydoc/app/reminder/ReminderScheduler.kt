package com.ydoc.app.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.ydoc.app.model.ReminderEntry

enum class ReminderScheduleMode {
    EXACT,
    INEXACT,
}

data class ReminderScheduleResult(
    val mode: ReminderScheduleMode,
)

class ReminderScheduler(
    private val context: Context,
) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun schedule(reminder: ReminderEntry): ReminderScheduleResult {
        val pendingIntent = buildPendingIntent(reminder.id, reminder.noteId, reminder.title)
        if (canUseExactAlarms()) {
            return runCatching {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    reminder.scheduledAt,
                    pendingIntent,
                )
                ReminderScheduleResult(ReminderScheduleMode.EXACT)
            }.getOrElse {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    reminder.scheduledAt,
                    pendingIntent,
                )
                ReminderScheduleResult(ReminderScheduleMode.INEXACT)
            }
        }
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            reminder.scheduledAt,
            pendingIntent,
        )
        return ReminderScheduleResult(ReminderScheduleMode.INEXACT)
    }

    fun cancel(reminderId: String, noteId: String, title: String) {
        alarmManager.cancel(buildPendingIntent(reminderId, noteId, title))
    }

    private fun buildPendingIntent(reminderId: String, noteId: String, title: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            reminderId.hashCode(),
            Intent(context, ReminderReceiver::class.java).apply {
                action = ReminderReceiver.ACTION_TRIGGER_REMINDER
                putExtra(ReminderReceiver.EXTRA_REMINDER_ID, reminderId)
                putExtra(ReminderReceiver.EXTRA_NOTE_ID, noteId)
                putExtra(ReminderReceiver.EXTRA_TITLE, title)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun canUseExactAlarms(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
}
