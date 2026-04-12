package com.ydoc.app.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ydoc.app.appContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val container = context.appContainer
                container.reminderRepository.getScheduled()
                    .filter { it.scheduledAt > System.currentTimeMillis() }
                    .forEach(container.reminderScheduler::schedule)
            }
            pendingResult.finish()
        }
    }
}
