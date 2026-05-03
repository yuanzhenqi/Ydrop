package com.ydoc.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import com.ydoc.app.logging.AppLogger
import com.ydoc.app.overlay.OverlayHandleService
import com.ydoc.app.overlay.OverlayPermissionHelper
import com.ydoc.app.quickrecord.QuickRecordShortcuts
import com.ydoc.app.sync.ImageAnalyzeWorker
import com.ydoc.app.ui.YDocApp
import com.ydoc.app.ui.AppViewModel
import com.ydoc.app.ui.theme.YDocTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val noteLaunchRequest = mutableStateOf<String?>(null)
    private val quickRecordRequestToken = mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        noteLaunchRequest.value = intent?.getStringExtra(EXTRA_NOTE_ID)
        if (intent?.action == ACTION_QUICK_RECORD) {
            quickRecordRequestToken.value = System.currentTimeMillis()
        }
        handleShareIntent(intent)
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
        handleShareIntent(intent)
    }

    /**
     * 处理系统分享进来的图片：一次分享一张或多张都接住，拷贝到私有目录，建成一条图片笔记。
     * 建完立刻排 OCR + Vision 分析。
     *
     * 关键：**一进来就消 intent**。Activity 因旋转 / 配置变更 / 内存压力被重建时，
     * `getIntent()` 依旧是这个 ACTION_SEND——不消会反复建重复笔记。
     * 清 action + 拿走 EXTRA_STREAM 就够：后续 `handleShareIntent` 的 guard 会直接 return。
     */
    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
        val type = intent.type ?: return
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return
        if (!type.startsWith("image/")) return

        @Suppress("DEPRECATION")
        val uris: List<Uri> = when (action) {
            Intent.ACTION_SEND ->
                (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)?.let { listOf(it) }.orEmpty()
            Intent.ACTION_SEND_MULTIPLE ->
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.toList().orEmpty()
            else -> emptyList()
        }
        // 备注要在 removeExtra 之前读出来——上一版写反了顺序，分享里带的文字永远读不到。
        val sharedHint = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()

        // 不管成功失败都清，避免重建重放。Activity 启动后 intent 的使命已经完成。
        intent.action = null
        intent.removeExtra(Intent.EXTRA_STREAM)
        intent.removeExtra(Intent.EXTRA_TEXT)

        if (uris.isEmpty()) return

        lifecycleScope.launch {
            val container = application.appContainer
            val attachments = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    runCatching { container.attachmentStore.import(uri) }
                        .onFailure { AppLogger.error("YDOC_ATTACH", "import failed for $uri", it) }
                        .getOrNull()
                }
            }
            if (attachments.isEmpty()) return@launch

            val note = withContext(Dispatchers.IO) {
                container.noteRepository.createAttachmentNote(attachments = attachments, hint = sharedHint)
            }
            // 一并排 OCR + vision 分析
            attachments.forEach { ImageAnalyzeWorker.scheduleBoth(applicationContext, note.id, it.id) }
            noteLaunchRequest.value = note.id
        }
    }

    companion object {
        const val ACTION_QUICK_RECORD = "com.ydoc.app.action.QUICK_RECORD"
        const val EXTRA_NOTE_ID = "NOTE_ID"
    }
}
