package com.ydoc.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import com.ydoc.app.overlay.OverlayHandleService
import com.ydoc.app.overlay.OverlayPermissionHelper
import com.ydoc.app.quickrecord.QuickRecordShortcuts
import com.ydoc.app.ui.YDocApp
import com.ydoc.app.ui.AppViewModel
import com.ydoc.app.ui.theme.YDocTheme

class MainActivity : ComponentActivity() {
    private val noteLaunchRequest = mutableStateOf<String?>(null)
    private val quickRecordRequestToken = mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        noteLaunchRequest.value = intent?.getStringExtra(EXTRA_NOTE_ID)
        if (intent?.action == ACTION_QUICK_RECORD) {
            quickRecordRequestToken.value = System.currentTimeMillis()
        }
        enableEdgeToEdge()
        setContent {
            val container = remember { application.appContainer }
            val audioPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) { }
            YDocTheme {
                YDocApp(
                    container = container,
                    factory = AppViewModel.factory(application, container),
                    hasOverlayPermission = OverlayPermissionHelper.hasPermission(this),
                    onRequestOverlayPermission = {
                        startActivity(OverlayPermissionHelper.createPermissionIntent(this))
                    },
                    onToggleOverlay = { enabled ->
                        val intent = Intent(this, OverlayHandleService::class.java)
                        if (enabled) startService(intent) else stopService(intent)
                    },
                    quickRecordRequestToken = quickRecordRequestToken.value,
                    onQuickRecordRequestConsumed = { quickRecordRequestToken.value = null },
                    launchNoteId = noteLaunchRequest.value,
                    onLaunchNoteConsumed = { noteLaunchRequest.value = null },
                    onRequestRecordingPermissions = {
                        audioPermissionLauncher.launch(
                            arrayOf(
                                android.Manifest.permission.RECORD_AUDIO,
                                android.Manifest.permission.POST_NOTIFICATIONS,
                            ),
                        )
                    },
                    onPinQuickRecordShortcut = { QuickRecordShortcuts.requestPinnedShortcut(this) },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        noteLaunchRequest.value = intent.getStringExtra(EXTRA_NOTE_ID)
        if (intent.action == ACTION_QUICK_RECORD) {
            quickRecordRequestToken.value = System.currentTimeMillis()
        }
    }

    companion object {
        const val ACTION_QUICK_RECORD = "com.ydoc.app.action.QUICK_RECORD"
        const val EXTRA_NOTE_ID = "NOTE_ID"
    }
}
