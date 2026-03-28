package com.ydoc.app.overlay

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.ydoc.app.MainActivity
import com.ydoc.app.R
import com.ydoc.app.data.AppContainer
import com.ydoc.app.logging.AppLogger
import com.ydoc.app.model.Note
import com.ydoc.app.model.NoteCategory
import com.ydoc.app.model.NotePriority
import com.ydoc.app.model.TranscriptionStatus
import com.ydoc.app.recording.RecordingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class OverlayHandleService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var appContainer: AppContainer
    private lateinit var rootView: View
    private lateinit var handleView: View
    private lateinit var panelView: View
    private lateinit var recentContainer: LinearLayout
    private lateinit var draftInput: EditText
    private lateinit var saveButton: ImageButton
    private lateinit var recordButton: ImageButton
    private lateinit var recordingStatus: TextView
    private lateinit var priorityButtons: List<TextView>
    private lateinit var categoryButtons: Map<NoteCategory, TextView>

    private var overlayState = OverlayUiState()
    private var layoutParams: WindowManager.LayoutParams? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var recordingTimerJob: Job? = null
    private var recordButtonHolding = false

    private val baseWindowFlags: Int
        get() = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(applicationContext)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createOverlay()
        observeNotes()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::rootView.isInitialized) {
            windowManager.removeView(rootView)
        }
    }

    private fun createOverlay() {
        rootView = LayoutInflater.from(this).inflate(R.layout.overlay_handle, null)
        handleView = rootView.findViewById(R.id.overlayHandle)
        panelView = rootView.findViewById(R.id.overlayPanel)
        recentContainer = rootView.findViewById(R.id.overlayRecentNotes)
        draftInput = rootView.findViewById(R.id.overlayDraftInput)
        saveButton = rootView.findViewById(R.id.overlaySaveButton)
        recordButton = rootView.findViewById(R.id.overlayRecordButton)
        recordingStatus = rootView.findViewById(R.id.overlayRecordingStatus)

        categoryButtons = mapOf(
            NoteCategory.TODO to rootView.findViewById(R.id.overlayCategoryTodo),
            NoteCategory.TASK to rootView.findViewById(R.id.overlayCategoryTask),
            NoteCategory.REMINDER to rootView.findViewById(R.id.overlayCategoryReminder),
            NoteCategory.NOTE to rootView.findViewById(R.id.overlayCategoryNote),
        )
        priorityButtons = listOf(
            rootView.findViewById(R.id.overlayPriorityLow),
            rootView.findViewById(R.id.overlayPriorityMedium),
            rootView.findViewById(R.id.overlayPriorityHigh),
            rootView.findViewById(R.id.overlayPriorityUrgent),
        )

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            baseWindowFlags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            x = 0
            y = 0
        }

        windowManager.addView(rootView, layoutParams)
        bindInteractions()
        render()
    }

    private fun bindInteractions() {
        saveButton.setOnClickListener {
            val text = draftInput.text.toString().trim()
            if (text.isNotBlank()) {
                serviceScope.launch {
                    appContainer.noteRepository.createTextNote(text, overlayState.selectedCategory, overlayState.selectedPriority)
                    appContainer.syncOrchestrator.syncPending()
                    AppLogger.overlay("Overlay text note saved with category=${overlayState.selectedCategory}")
                    overlayState = overlayState.copy(draftText = "", mode = OverlayMode.COLLAPSED)
                    draftInput.setText("")
                    exitTextInputMode()
                    render()
                }
            }
        }

        recordButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (!overlayState.isRecording) {
                        recordButtonHolding = true
                        vibrateHandle()
                        startRecordingFromOverlay()
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (recordButtonHolding && overlayState.isRecording) {
                        stopRecordingFromOverlay()
                    }
                    recordButtonHolding = false
                    true
                }

                else -> true
            }
        }

        draftInput.setOnClickListener {
            enableInputFocus()
            draftInput.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(draftInput, InputMethodManager.SHOW_IMPLICIT)
        }
        draftInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                enterTextInputMode()
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(draftInput, InputMethodManager.SHOW_IMPLICIT)
            } else {
                exitTextInputMode()
            }
        }
        draftInput.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_DONE

        categoryButtons.forEach { (category, view) ->
            view.setOnClickListener {
                overlayState = overlayState.copy(selectedCategory = category)
                AppLogger.overlay("Overlay category changed to $category")
                render()
            }
        }

        priorityButtons.forEachIndexed { index, view ->
            view.setOnClickListener {
                val priority = when (index) {
                    0 -> NotePriority.LOW
                    1 -> NotePriority.MEDIUM
                    2 -> NotePriority.HIGH
                    else -> NotePriority.URGENT
                }
                overlayState = overlayState.copy(selectedPriority = priority)
                AppLogger.overlay("Overlay priority changed to $priority")
                render()
            }
        }

        handleView.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var touchX = 0f
            private var touchY = 0f
            private var moved = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val params = layoutParams ?: return false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x
                        startY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (kotlin.math.abs(event.rawX - touchX) > 12 || kotlin.math.abs(event.rawY - touchY) > 12) {
                            moved = true
                        }
                        params.x = (startX - (event.rawX - touchX).toInt()).coerceIn(-32, 32)
                        params.y = startY + (event.rawY - touchY).toInt()
                        windowManager.updateViewLayout(rootView, params)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (!moved) {
                            overlayState = overlayState.copy(
                                mode = if (overlayState.mode == OverlayMode.COLLAPSED) OverlayMode.EXPANDED else OverlayMode.COLLAPSED,
                            )
                            if (overlayState.mode == OverlayMode.COLLAPSED) {
                                exitTextInputMode()
                            }
                            render()
                        }
                    }
                }
                return true
            }
        })
    }

    private fun observeNotes() {
        serviceScope.launch {
            appContainer.noteRepository.observeNotes().collectLatest { notes ->
                overlayState = overlayState.copy(recentNotes = notes.take(5))
                AppLogger.overlay("Overlay recent list refreshed count=${notes.take(5).size}")
                renderRecentNotes(notes.take(5))
            }
        }
    }

    private fun render() {
        panelView.visibility = if (overlayState.mode == OverlayMode.COLLAPSED) View.GONE else View.VISIBLE
        recordButton.setImageResource(if (overlayState.isRecording) android.R.drawable.ic_media_pause else android.R.drawable.ic_btn_speak_now)
        recordingStatus.text = if (overlayState.isRecording) "录音中 ${overlayState.recordingSeconds}s" else "长按把手直接录音"
        serviceScope.launch {
            val overlayConfig = appContainer.settingsStore.settingsFlow.first().overlay
            handleView.alpha = overlayConfig.handleAlpha
            handleView.layoutParams = handleView.layoutParams.apply {
                width = dpToPx(overlayConfig.handleSizeDp)
                height = dpToPx((overlayConfig.handleSizeDp * 3.5f).toInt())
            }
            handleView.requestLayout()
        }
        categoryButtons.forEach { (category, view) ->
            view.isSelected = overlayState.selectedCategory == category
            view.alpha = if (view.isSelected) 1f else 0.55f
        }
        priorityButtons.forEachIndexed { index, view ->
            val priority = when (index) {
                0 -> NotePriority.LOW
                1 -> NotePriority.MEDIUM
                2 -> NotePriority.HIGH
                else -> NotePriority.URGENT
            }
            view.alpha = if (overlayState.selectedPriority == priority) 1f else 0.45f
        }
    }

    private fun renderRecentNotes(notes: List<Note>) {
        recentContainer.removeAllViews()
        notes.forEach { note ->
            val textView = TextView(this).apply {
                val statusText = when (note.transcriptionStatus) {
                    TranscriptionStatus.UPLOADING -> "上传中"
                    TranscriptionStatus.TRANSCRIBING -> "转写中"
                    TranscriptionStatus.DONE -> "转写成功"
                    TranscriptionStatus.FAILED -> "转写失败"
                    else -> ""
                }
                val summary = (note.transcript?.takeIf { it.isNotBlank() } ?: note.content).take(24)
                text = buildString {
                    append(note.category.name)
                    append(" · ")
                    append(summary)
                    if (statusText.isNotBlank()) {
                        append(" · ")
                        append(statusText)
                    }
                }
                setPadding(12, 12, 12, 12)
                setOnClickListener {
                    val intent = Intent(this@OverlayHandleService, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    startActivity(intent)
                }
            }
            recentContainer.addView(textView)
        }
    }

    private fun startRecordingFromOverlay() {
        if (!hasMicrophonePermission()) {
            AppLogger.error("YDOC_OVERLAY", "Overlay recording denied: missing microphone or notification permission")
            recordingStatus.text = "缺少录音权限，请先到主界面授权"
            return
        }
        overlayState = overlayState.copy(mode = OverlayMode.RECORDING, isRecording = true, recordingSeconds = 0)
        AppLogger.overlay("Overlay recording started")
        render()
        startRecordingTicker()
        serviceScope.launch(Dispatchers.IO) {
            runCatching {
                ContextCompat.startForegroundService(this@OverlayHandleService, Intent(this@OverlayHandleService, RecordingService::class.java))
                appContainer.audioRecorder.start()
            }.onFailure {
                AppLogger.error("YDOC_OVERLAY", "Overlay recorder start failed", it)
                stopRecordingTicker()
                overlayState = overlayState.copy(mode = OverlayMode.EXPANDED, isRecording = false)
            }
        }
    }

    private fun stopRecordingFromOverlay() {
        overlayState = overlayState.copy(mode = OverlayMode.EXPANDED, isRecording = false)
        stopRecordingTicker()
        serviceScope.launch(Dispatchers.IO) {
            runCatching {
                val output = appContainer.audioRecorder.stop()
                stopService(Intent(this@OverlayHandleService, RecordingService::class.java))
                AppLogger.overlay("Overlay recording stopped path=${output.path} format=${output.format}")
                val relayConfig = appContainer.settingsStore.settingsFlow.first().relay
                val volcengineConfig = appContainer.settingsStore.settingsFlow.first().volcengine
                var note = appContainer.noteRepository.createVoiceNote(output.path, output.format, overlayState.selectedPriority)
                if (relayConfig.enabled) {
                    val upload = appContainer.relayStorageClient.upload(java.io.File(output.path), relayConfig)
                    note = appContainer.noteRepository.attachRelayInfo(note, upload.fileId, upload.url, upload.expiresAt)
                    AppLogger.overlay("Overlay relay upload success fileId=${upload.fileId}")
                }
                if (relayConfig.enabled && volcengineConfig.enabled && !note.relayUrl.isNullOrBlank()) {
                    appContainer.transcriptionOrchestrator.transcribe(note, volcengineConfig)
                    AppLogger.overlay("Overlay transcription completed noteId=${note.id}")
                }
                val latest = appContainer.noteRepository.getNote(note.id) ?: note
                appContainer.syncOrchestrator.syncNote(latest)
                AppLogger.overlay("Overlay latest note synced noteId=${latest.id}")
            }.onFailure {
                AppLogger.error("YDOC_OVERLAY", "Overlay recording pipeline failed", it)
                stopService(Intent(this@OverlayHandleService, RecordingService::class.java))
            }
        }
        render()
    }

    private fun startRecordingTicker() {
        recordingTimerJob?.cancel()
        recordingTimerJob = serviceScope.launch {
            var elapsed = 0
            while (true) {
                delay(1000)
                elapsed += 1
                overlayState = overlayState.copy(recordingSeconds = elapsed)
                render()
            }
        }
    }

    private fun stopRecordingTicker() {
        recordingTimerJob?.cancel()
        recordingTimerJob = null
        overlayState = overlayState.copy(recordingSeconds = 0)
    }

    private fun enableInputFocus() {
        enterTextInputMode()
    }

    private fun enterTextInputMode() {
        val params = layoutParams ?: return
        params.flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        windowManager.updateViewLayout(rootView, params)
        draftInput.isFocusableInTouchMode = true
        draftInput.isFocusable = true
        draftInput.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                exitTextInputMode()
                true
            } else {
                false
            }
        }
    }

    private fun exitTextInputMode() {
        val params = layoutParams ?: return
        params.flags = baseWindowFlags
        windowManager.updateViewLayout(rootView, params)
        draftInput.clearFocus()
    }

    private fun vibrateHandle() {
        val vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(24, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(24)
        }
    }

    private fun hasMicrophonePermission(): Boolean {
        val audioGranted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return audioGranted && notificationGranted
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
}
