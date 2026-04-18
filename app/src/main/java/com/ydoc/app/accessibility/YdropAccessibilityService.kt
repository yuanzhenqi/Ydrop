package com.ydoc.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.ydoc.app.logging.AppLogger
import com.ydoc.app.quickrecord.QuickRecordEntryActivity

/**
 * 监听锁屏/前台下的硬件按键，实现「双击音量下键」快速启动录音。
 *
 * 触发后只负责拉起 QuickRecordEntryActivity，录音链路完全复用既有逻辑。
 * 事件不消费（onKeyEvent 返回 false），保持音量键原有行为。
 */
class YdropAccessibilityService : AccessibilityService() {

    private var lastVolumeDownDownAt: Long = 0L

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && event.action == KeyEvent.ACTION_DOWN) {
            val now = SystemClock.elapsedRealtime()
            val delta = now - lastVolumeDownDownAt
            if (lastVolumeDownDownAt != 0L && delta in MIN_DOUBLE_TAP_GAP_MS..MAX_DOUBLE_TAP_GAP_MS) {
                lastVolumeDownDownAt = 0L
                triggerQuickRecord()
            } else {
                lastVolumeDownDownAt = now
            }
        }
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不处理窗口事件；本服务只为按键过滤，保留空实现满足系统约束
    }

    override fun onInterrupt() {
        // noop
    }

    private fun triggerQuickRecord() {
        runCatching {
            val intent = Intent(this, QuickRecordEntryActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
        }.onFailure { error ->
            AppLogger.error(TAG, "启动 QuickRecordEntryActivity 失败", error)
        }
    }

    companion object {
        private const val TAG = "YdropA11y"

        // 双击窗口：50ms 内判为误触，500ms 内判为一次双击
        private const val MIN_DOUBLE_TAP_GAP_MS = 50L
        private const val MAX_DOUBLE_TAP_GAP_MS = 500L
    }
}
