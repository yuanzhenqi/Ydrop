package com.ydoc.app.quickrecord

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import com.ydoc.app.R

object QuickRecordShortcuts {
    private const val SHORTCUT_ID = "quick_record"

    fun publishDynamicShortcut(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        val shortcut = buildShortcut(context)
        manager.dynamicShortcuts = listOf(shortcut)
    }

    fun requestPinnedShortcut(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return false
        if (!manager.isRequestPinShortcutSupported) return false
        return manager.requestPinShortcut(buildShortcut(context), null)
    }

    private fun buildShortcut(context: Context): ShortcutInfo =
        ShortcutInfo.Builder(context, SHORTCUT_ID)
            .setShortLabel("快速录音")
            .setLongLabel("Ydrop 一键录音")
            .setIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(
                Intent(context, QuickRecordEntryActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                },
            )
            .build()
}
