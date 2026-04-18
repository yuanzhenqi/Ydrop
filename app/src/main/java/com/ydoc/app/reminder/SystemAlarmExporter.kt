package com.ydoc.app.reminder

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.ydoc.app.logging.AppLogger
import com.ydoc.app.model.ReminderEntry
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 把 Ydrop 提醒写一份到系统闹钟 app（AlarmClock）。
 *
 * 局限：
 * - AlarmClock API 不支持指定具体日期，只能到「本周某几天 HH:mm」。
 * - 因此仅处理未来 7 天内的 reminder，把对应星期映射到 EXTRA_DAYS。
 * - 触发方式是 Activity Intent；即便 SKIP_UI=true，某些 OEM 仍会短暂显示"已添加"Toast。
 * - 无法可靠反向删除闹钟（需知道系统闹钟 id），所以 cancel 时不联动删除。
 */
class SystemAlarmExporter(private val context: Context) {

    /**
     * 若条件满足，发出 AlarmClock.ACTION_SET_ALARM intent。
     * @return true 表示 intent 已发出；false 表示跳过（超 7 天 / 没有可处理的 app / 出错）。
     */
    fun exportIfApplicable(reminder: ReminderEntry): Boolean {
        val now = System.currentTimeMillis()
        val delta = reminder.scheduledAt - now
        if (delta <= 0 || delta > MAX_FUTURE_MS) {
            return false
        }
        return runCatching {
            val calendar = Calendar.getInstance().apply {
                timeInMillis = reminder.scheduledAt
            }
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(AlarmClock.EXTRA_MESSAGE, reminder.title.ifBlank { "Ydrop 提醒" })
                putExtra(AlarmClock.EXTRA_HOUR, calendar.get(Calendar.HOUR_OF_DAY))
                putExtra(AlarmClock.EXTRA_MINUTES, calendar.get(Calendar.MINUTE))
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                putExtra(AlarmClock.EXTRA_VIBRATE, true)
                putExtra(
                    AlarmClock.EXTRA_DAYS,
                    arrayListOf(calendar.get(Calendar.DAY_OF_WEEK)),
                )
            }
            if (intent.resolveActivity(context.packageManager) == null) {
                AppLogger.error(TAG, "未找到可处理 AlarmClock.ACTION_SET_ALARM 的 app")
                return@runCatching false
            }
            context.startActivity(intent)
            true
        }.onFailure { error ->
            AppLogger.error(TAG, "导出系统闹钟失败 id=${reminder.id}", error)
        }.getOrDefault(false)
    }

    companion object {
        private const val TAG = "YdropSystemAlarm"
        private val MAX_FUTURE_MS = TimeUnit.DAYS.toMillis(7)
    }
}
