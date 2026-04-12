package com.ydoc.app.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.ydoc.app.MainActivity
import com.ydoc.app.R
import com.ydoc.app.data.ReminderRepository
import com.ydoc.app.data.local.YDocDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getStringExtra(EXTRA_REMINDER_ID) ?: return
        val noteId = intent.getStringExtra(EXTRA_NOTE_ID) ?: return
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Ydrop 提醒"
        postNotification(context, noteId, title, reminderId.hashCode())
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val repository = ReminderRepository(YDocDatabase.build(context).reminderEntryDao())
                repository.markFired(reminderId)
            }
            pendingResult.finish()
        }
    }

    private fun postNotification(context: Context, noteId: String, title: String, notificationId: Int) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Ydrop 提醒",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            noteId.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(MainActivity.EXTRA_NOTE_ID, noteId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            notificationId,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText("点击查看对应便签")
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .build(),
        )
    }

    companion object {
        const val ACTION_TRIGGER_REMINDER = "com.ydoc.app.action.TRIGGER_REMINDER"
        const val EXTRA_REMINDER_ID = "REMINDER_ID"
        const val EXTRA_NOTE_ID = "NOTE_ID"
        const val EXTRA_TITLE = "TITLE"
        private const val CHANNEL_ID = "ydrop_reminders"
    }
}
