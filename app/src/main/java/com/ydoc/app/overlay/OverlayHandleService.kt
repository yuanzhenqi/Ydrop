package com.ydoc.app.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ydoc.app.MainActivity
import com.ydoc.app.R
import com.ydoc.app.data.AppContainer
import com.ydoc.app.model.Note
import com.ydoc.app.model.NoteCategory
import com.ydoc.app.model.NotePriority
import com.ydoc.app.model.OverlayDockSide
import com.ydoc.app.recording.RecordingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

class OverlayHandleService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var appContainer: AppContainer
    private lateinit var rootView: FrameLayout
    private lateinit var handleView: View
    private lateinit var panelView: View
    private lateinit var recentContainer: LinearLayout
    private lateinit var draftInput: EditText
    private lateinit var saveButton: ImageButton
    private lateinit var recordButton: ImageButton
    private lateinit var closeButton: ImageButton
    private lateinit var recordingStatus: TextView
    private lateinit var togglePriority: TextView
    private lateinit var priorityBar: LinearLayout
    private lateinit var priorityButtons: List<TextView>
    private lateinit var categoryButtons: Map<NoteCategory, TextView>

    private var overlayState = OverlayUiState()
    private var layoutParams: WindowManager.LayoutParams? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var recordingTimerJob: Job? = null
    private var recordButtonHolding = false
    private var dockSide: OverlayDockSide = OverlayDockSide.RIGHT
    private var dismissLayer: View? = null
    private var priorityVisible = false

    private val baseWindowFlags: Int
        get() = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(applicationContext)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        serviceScope.launch {
            val cfg = appContainer.settingsStore.settingsFlow.first().overlay
            dockSide = runCatching { OverlayDockSide.valueOf(cfg.dockSide) }.getOrDefault(OverlayDockSide.RIGHT)
        }
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        createOverlay()
        observeNotes()
    }

    override fun onDestroy() {
        super.onDestroy()
        dismissLayer?.let { runCatching { windowManager.removeView(it) } }
        if (::rootView.isInitialized) runCatching { windowManager.removeView(rootView) }
        serviceScope.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Ydrop 悬浮助手", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                description = "保持悬浮把手运行"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ydrop 悬浮助手正在运行中")
            .setContentText("点击回到主界面")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pending)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun createOverlay() {
        rootView = FrameLayout(this)
        val inflated = LayoutInflater.from(this).inflate(R.layout.overlay_handle, null) as LinearLayout

        handleView = inflated.findViewById(R.id.overlayHandle)
        panelView = inflated.findViewById(R.id.overlayPanel)
        recentContainer = inflated.findViewById(R.id.overlayRecentNotes)
        draftInput = inflated.findViewById(R.id.overlayDraftInput)
        saveButton = inflated.findViewById(R.id.overlaySaveButton)
        recordButton = inflated.findViewById(R.id.overlayRecordButton)
        closeButton = inflated.findViewById(R.id.overlayCloseButton)
        recordingStatus = inflated.findViewById(R.id.overlayRecordingStatus)
        togglePriority = inflated.findViewById(R.id.overlayTogglePriority)
        priorityBar = inflated.findViewById(R.id.overlayPriorityBar)

        categoryButtons = mapOf(
            NoteCategory.TODO to inflated.findViewById(R.id.overlayCategoryTodo),
            NoteCategory.TASK to inflated.findViewById(R.id.overlayCategoryTask),
            NoteCategory.REMINDER to inflated.findViewById(R.id.overlayCategoryReminder),
            NoteCategory.NOTE to inflated.findViewById(R.id.overlayCategoryNote),
        )
        priorityButtons = listOf(
            inflated.findViewById(R.id.overlayPriorityLow),
            inflated.findViewById(R.id.overlayPriorityMedium),
            inflated.findViewById(R.id.overlayPriorityHigh),
            inflated.findViewById(R.id.overlayPriorityUrgent),
        )

        inflated.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        )
        rootView.addView(inflated)

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType, baseWindowFlags, PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END; x = 0; y = 0 }

        val dl = View(this)
        dl.setBackgroundColor(0x55000000)
        dl.visibility = View.GONE
        dl.setOnClickListener { collapsePanel() }
        val dismissParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        windowManager.addView(dl, dismissParams)
        dismissLayer = dl

        windowManager.addView(rootView, layoutParams)
        bindInteractions()
        render()
    }

    private fun bindInteractions() {
        closeButton.setOnClickListener { collapsePanel() }

        saveButton.setOnClickListener {
            val text = draftInput.text.toString().trim()
            if (text.isNotBlank()) {
                serviceScope.launch {
                    val note = appContainer.noteRepository.createTextNote(text, overlayState.selectedCategory, overlayState.selectedPriority)
                    draftInput.setText("")
                    exitTextInputMode()
                    overlayState = overlayState.copy(draftText = "")
                    render()
                    syncIfEnabled(note)
                }
            }
        }

        recordButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (!overlayState.isRecording) { recordButtonHolding = true; vibrateHandle(); startRecordingFromOverlay() }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (recordButtonHolding && overlayState.isRecording) stopRecordingFromOverlay()
                    recordButtonHolding = false; true
                }
                else -> true
            }
        }

        draftInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                enterTextInputMode()
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(draftInput, InputMethodManager.SHOW_IMPLICIT)
            } else exitTextInputMode()
        }
        draftInput.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_DONE
        draftInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { saveButton.performClick(); true } else false
        }

        togglePriority.setOnClickListener {
            priorityVisible = !priorityVisible
            priorityBar.visibility = if (priorityVisible) View.VISIBLE else View.GONE
            togglePriority.text = if (priorityVisible) "优先级 ▴" else "优先级 ▾"
        }

        categoryButtons.forEach { (category, view) ->
            view.setOnClickListener { overlayState = overlayState.copy(selectedCategory = category); render() }
        }
        priorityButtons.forEachIndexed { index, view ->
            view.setOnClickListener {
                val p = when (index) { 0 -> NotePriority.LOW; 1 -> NotePriority.MEDIUM; 2 -> NotePriority.HIGH; else -> NotePriority.URGENT }
                overlayState = overlayState.copy(selectedPriority = p); render()
            }
        }

        handleView.setOnTouchListener(object : View.OnTouchListener {
            private var startY = 0; private var touchX = 0f; private var touchY = 0f; private var moved = false
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val params = layoutParams ?: return false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> { startY = params.y; touchX = event.rawX; touchY = event.rawY; moved = false }
                    MotionEvent.ACTION_MOVE -> {
                        if (abs(event.rawX - touchX) > 12 || abs(event.rawY - touchY) > 12) moved = true
                        dockSide = if (event.rawX < resources.displayMetrics.widthPixels / 2f) OverlayDockSide.LEFT else OverlayDockSide.RIGHT
                        params.y = startY + (event.rawY - touchY).toInt()
                        applyGravityAndLayout(params)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        dockSide = if (event.rawX < resources.displayMetrics.widthPixels / 2f) OverlayDockSide.LEFT else OverlayDockSide.RIGHT
                        params.x = 0; applyGravityAndLayout(params)
                        serviceScope.launch {
                            val cfg = appContainer.settingsStore.settingsFlow.first().overlay
                            appContainer.settingsStore.saveOverlay(cfg.copy(dockSide = dockSide.name))
                        }
                        if (!moved) {
                            if (overlayState.mode == OverlayMode.COLLAPSED) {
                                overlayState = overlayState.copy(mode = OverlayMode.EXPANDED); render()
                            } else collapsePanel()
                        }
                    }
                }
                return true
            }
        })
    }

    private fun applyGravityAndLayout(params: WindowManager.LayoutParams) {
        val child = rootView.getChildAt(0) as? LinearLayout ?: return
        val handle = child.findViewById<View>(R.id.overlayHandle)
        val panel = child.findViewById<View>(R.id.overlayPanel)
        if (dockSide == OverlayDockSide.LEFT) {
            params.gravity = Gravity.CENTER_VERTICAL or Gravity.START
            if (child.indexOfChild(handle) < child.indexOfChild(panel)) { child.removeView(handle); child.addView(handle) }
        } else {
            params.gravity = Gravity.CENTER_VERTICAL or Gravity.END
            if (child.indexOfChild(handle) > child.indexOfChild(panel)) { child.removeView(handle); child.addView(handle, 0) }
        }
        runCatching { windowManager.updateViewLayout(rootView, params) }
    }

    private fun observeNotes() {
        serviceScope.launch {
            appContainer.noteRepository.observeNotes().collectLatest { notes ->
                overlayState = overlayState.copy(recentNotes = notes.take(2))
                renderRecentNotes(notes.take(2))
            }
        }
    }

    private fun collapsePanel() {
        overlayState = overlayState.copy(mode = OverlayMode.COLLAPSED)
        exitTextInputMode()
        render()
    }

    private fun enterTextInputMode() {
        if (overlayState.mode == OverlayMode.COLLAPSED) overlayState = overlayState.copy(mode = OverlayMode.EXPANDED)
        layoutParams?.let { p ->
            p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            runCatching { windowManager.updateViewLayout(rootView, p) }
        }
    }

    private fun exitTextInputMode() {
        layoutParams?.let { p ->
            p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            runCatching { windowManager.updateViewLayout(rootView, p) }
        }
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(draftInput.windowToken, 0)
    }

    private fun render() {
        val expanded = overlayState.mode != OverlayMode.COLLAPSED
        panelView.visibility = if (expanded) View.VISIBLE else View.GONE
        dismissLayer?.visibility = if (expanded) View.VISIBLE else View.GONE
        layoutParams?.let { applyGravityAndLayout(it) }

        if (overlayState.isRecording) {
            recordButton.setImageResource(android.R.drawable.ic_media_pause)
            recordButton.setBackgroundColor(0xFFEF4444.toInt())
            recordingStatus.text = "录音中 ${overlayState.recordingSeconds}s"
            recordingStatus.setTextColor(0xFFEF4444.toInt())
        } else {
            recordButton.setImageResource(android.R.drawable.ic_btn_speak_now)
            recordButton.setBackgroundColor(0x00000000)
            recordingStatus.text = "按住录音"
            recordingStatus.setTextColor(0xFF6B7280.toInt())
        }

        categoryButtons.forEach { (category, view) ->
            val selected = overlayState.selectedCategory == category
            view.setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFF374151.toInt())
            view.setBackgroundColor(if (selected) categoryColor(category) else 0xFFF3F4F6.toInt())
        }
        priorityButtons.forEachIndexed { index, view ->
            val p = when (index) { 0 -> NotePriority.LOW; 1 -> NotePriority.MEDIUM; 2 -> NotePriority.HIGH; else -> NotePriority.URGENT }
            val selected = overlayState.selectedPriority == p
            view.setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFF6B7280.toInt())
            view.setBackgroundColor(if (selected) priorityColor(p) else 0xFFE5E7EB.toInt())
        }

        serviceScope.launch {
            val cfg = appContainer.settingsStore.settingsFlow.first().overlay
            handleView.alpha = cfg.handleAlpha
        }
    }

    private fun renderRecentNotes(notes: List<Note>) {
        recentContainer.removeAllViews()
        notes.forEach { note ->
            val summary = (note.transcript?.takeIf { it.isNotBlank() } ?: note.content).take(40)
            val tv = TextView(this).apply {
                text = summary
                setPadding(8, 6, 8, 6)
                textSize = 12f
                setTextColor(0xFF374151.toInt())
                setOnClickListener {
                    startActivity(Intent(this@OverlayHandleService, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    })
                }
            }
            recentContainer.addView(tv)
        }
    }

    private fun startRecordingFromOverlay() {
        if (!checkPermissions()) return
        vibrateHandle()
        overlayState = overlayState.copy(isRecording = true, recordingSeconds = 0)
        render()
        ContextCompat.startForegroundService(this, Intent(this, RecordingService::class.java))
        recordingTimerJob = serviceScope.launch {
            while (overlayState.isRecording) {
                delay(1000); overlayState = overlayState.copy(recordingSeconds = overlayState.recordingSeconds + 1); render()
            }
        }
    }

    private fun stopRecordingFromOverlay() {
        recordingTimerJob?.cancel()
        overlayState = overlayState.copy(isRecording = false, recordingSeconds = 0)
        render()
        serviceScope.launch {
            val output = appContainer.audioRecorder.stop()
            stopService(Intent(this@OverlayHandleService, RecordingService::class.java))
            if (output != null) {
                val settings = appContainer.settingsStore.settingsFlow.first()
                val relayConfig = settings.relay
                val volcengineConfig = settings.volcengine
                var note = appContainer.noteRepository.createVoiceNote(output.path, output.format, overlayState.selectedPriority)
                if (relayConfig.enabled) {
                    try {
                        val info = appContainer.relayStorageClient.upload(java.io.File(output.path), relayConfig)
                        note = appContainer.noteRepository.attachRelayInfo(note, info.fileId, info.url, info.expiresAt)
                        if (volcengineConfig.enabled) {
                            appContainer.transcriptionScheduler.enqueueRetry(note.id, false)
                        }
                    } catch (_: Exception) { }
                }
                syncIfEnabled(note)
            }
        }
    }

    private suspend fun syncIfEnabled(note: Note) {
        val settings = appContainer.settingsStore.settingsFlow.first()
        if (settings.webDavEnabled && settings.webDav.autoSync) {
            appContainer.syncOrchestrator.syncBidirectional()
        }
    }

    private fun vibrateHandle() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            (getSystemService(VIBRATOR_SERVICE) as? Vibrator)?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            (getSystemService(VIBRATOR_SERVICE) as? Vibrator)?.vibrate(40)
        }
    }

    private fun categoryColor(category: NoteCategory): Int = when (category) {
        NoteCategory.TODO -> 0xFFD89A2B.toInt()
        NoteCategory.TASK -> 0xFF4F86C6.toInt()
        NoteCategory.REMINDER -> 0xFFC9656C.toInt()
        NoteCategory.NOTE -> 0xFF6C8E7B.toInt()
    }

    private fun priorityColor(priority: NotePriority): Int = when (priority) {
        NotePriority.LOW -> 0xFF6B7280.toInt()
        NotePriority.MEDIUM -> 0xFF4F86C6.toInt()
        NotePriority.HIGH -> 0xFFD89A2B.toInt()
        NotePriority.URGENT -> 0xFFC9656C.toInt()
    }

    private fun checkPermissions(): Boolean {
        val audioGranted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
        return audioGranted && notificationGranted
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    companion object {
        private const val CHANNEL_ID = "ydoc_overlay_channel"
        private const val NOTIFICATION_ID = 2001
    }
}