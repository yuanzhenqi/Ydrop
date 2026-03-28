package com.ydoc.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.remember
import com.ydoc.app.data.AppContainer
import com.ydoc.app.overlay.OverlayHandleService
import com.ydoc.app.overlay.OverlayPermissionHelper
import com.ydoc.app.ui.YDocApp
import com.ydoc.app.ui.AppViewModel
import com.ydoc.app.ui.theme.YDocTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val quickRecordRequested = intent?.action == ACTION_QUICK_RECORD
        enableEdgeToEdge()
        setContent {
            val container = remember { AppContainer(applicationContext) }
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
                    quickRecordRequested = quickRecordRequested,
                    onRequestRecordingPermissions = {
                        audioPermissionLauncher.launch(
                            arrayOf(
                                android.Manifest.permission.RECORD_AUDIO,
                                android.Manifest.permission.POST_NOTIFICATIONS,
                            ),
                        )
                    },
                )
            }
        }
    }

    companion object {
        const val ACTION_QUICK_RECORD = "com.ydoc.app.action.QUICK_RECORD"
    }
}
