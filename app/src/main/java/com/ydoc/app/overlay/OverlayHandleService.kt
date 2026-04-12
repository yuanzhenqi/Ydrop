package com.ydoc.app.overlay

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.res.ColorStateList
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.util.TypedValue
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ydoc.app.MainActivity
import com.ydoc.app.R
import com.ydoc.app.appContainer
import com.ydoc.app.data.AppContainer
import com.ydoc.app.model.Note
import com.ydoc.app.model.NoteCategory
import com.ydoc.app.model.NotePriority
import com.ydoc.app.model.NoteSource
import com.ydoc.app.model.OverlayDockSide
import com.ydoc.app.model.SyncType
import com.ydoc.app.model.WebDavConfig
import com.ydoc.app.model.defaultColorFor
import com.ydoc.app.recording.RecordingService
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class OverlayHandleService : Service(), OverlayStripAdapter.Listener {
    private lateinit var windowManager: WindowManager
    private lateinit var appContainer: AppContainer
    private lateinit var rootView: FrameLayout
    private lateinit var rootRow: LinearLayout
    private lateinit var railContainer: FrameLayout
    private lateinit var stripList: RecyclerView
    private lateinit var composerPanel: View
    private lateinit var composerTitle: TextView
    private lateinit var handleView: View
    private lateinit var draftInput: EditText
    private lateinit var saveButton: ImageButton
    private lateinit var recordButton: ImageButton
    private lateinit var closeButton: ImageButton
    private lateinit var recordingStatus: TextView
    private lateinit var categoryBar: LinearLayout
    private lateinit var priorityBar: LinearLayout
    private lateinit var categoryButtons: Map<NoteCategory, TextView>
    private lateinit var priorityButtons: List<TextView>
    private lateinit var stripEntryBar: LinearLayout

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private val stripAdapter = OverlayStripAdapter(this)

    private var overlayState = OverlayUiState()
    private var activeNotes: List<Note> = emptyList()
    private var contentLayoutParams: WindowManager.LayoutParams? = null
    private var dismissLayer: View? = null
    private var globalLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var recordingTimerJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null
    private var railWidthAnimator: ValueAnimator? = null
    private var appliedRailWidthPx: Int = 0
    private var handleTopY: Int = 0
    private var handleAlpha: Float = 0.84f
    private var handleSizeDp: Int = 24
    private var suppressImeCollapse = false
    private var composerRecordLongPressTriggered = false
    private val swipePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private lateinit var swipeTextPaint: Paint
    private val composerRecordLongPressRunnable = Runnable {
        composerRecordLongPressTriggered = true
        if (overlayState.surfaceState != OverlaySurfaceState.COMPOSER_ACTIVE || overlayState.isRecording) {
            return@Runnable
        }
        if (overlayState.composerMode != OverlayComposerMode.RECORDING) {
            openRecordingComposer()
        }
        serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
            startRecordingNow(OverlayRecordingOrigin.COMPOSER_BUTTON)
        }
    }

    private val baseWindowFlags: Int
        get() = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onCreate() {
        super.onCreate()
        appContainer = applicationContext.appContainer
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        handleTopY = (resources.displayMetrics.heightPixels * 0.24f).toInt()
        swipeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textAlign = Paint.Align.CENTER
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        createOverlay()
        observeSettings()
        observeNotes()
    }

    override fun onDestroy() {
        super.onDestroy()
        recordingTimerJob?.cancel()
        railWidthAnimator?.cancel()
        dismissLayer?.let { layer ->
            globalLayoutListener?.let { listener ->
                layer.viewTreeObserver.removeOnGlobalLayoutListener(listener)
            }
            runCatching { windowManager.removeView(layer) }
        }
        if (::rootView.isInitialized) {
            runCatching { windowManager.removeView(rootView) }
        }
        serviceScope.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Ydrop 悬浮窗",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
                description = "保持悬浮窗把手常驻。"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ydrop 悬浮窗运行中")
            .setContentText("点击返回应用。")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun createOverlay() {
        rootView = FrameLayout(this)
        val content = LayoutInflater.from(this).inflate(R.layout.overlay_handle, null) as LinearLayout
        rootRow = content
        railContainer = content.findViewById(R.id.overlayRailContainer)
        stripList = content.findViewById(R.id.overlayStripList)
        composerPanel = content.findViewById(R.id.overlayComposerPanel)
        composerTitle = content.findViewById(R.id.overlayComposerTitle)
        handleView = content.findViewById(R.id.overlayHandle)
        draftInput = content.findViewById(R.id.overlayDraftInput)
        saveButton = content.findViewById(R.id.overlaySaveButton)
        recordButton = content.findViewById(R.id.overlayRecordButton)
        closeButton = content.findViewById(R.id.overlayCloseButton)
        recordingStatus = content.findViewById(R.id.overlayRecordingStatus)
        categoryBar = content.findViewById(R.id.overlayTagBar)
        priorityBar = content.findViewById(R.id.overlayPriorityBar)
        stripEntryBar = content.findViewById(R.id.overlayStripEntryBar)

        categoryButtons = mapOf(
            NoteCategory.NOTE to content.findViewById(R.id.overlayCategoryNote),
            NoteCategory.TODO to content.findViewById(R.id.overlayCategoryTodo),
            NoteCategory.TASK to content.findViewById(R.id.overlayCategoryTask),
            NoteCategory.REMINDER to content.findViewById(R.id.overlayCategoryReminder),
        )
        priorityButtons = listOf(
            content.findViewById(R.id.overlayPriorityLow),
            content.findViewById(R.id.overlayPriorityMedium),
            content.findViewById(R.id.overlayPriorityHigh),
            content.findViewById(R.id.overlayPriorityUrgent),
        )

        stripList.layoutManager = LinearLayoutManager(this)
        stripList.adapter = stripAdapter
        stripList.itemAnimator = null
        stripList.clipToPadding = false
        stripList.setPadding(0, 0, 0, dpToPx(92))
        attachStripSwipeHelper()

        rootView.addView(content)

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        contentLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            baseWindowFlags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 0
            y = handleTopY
        }

        val scrim = View(this).apply {
            setBackgroundColor(0x28000000)
            visibility = View.GONE
            setOnClickListener { closeOverlay() }
        }
        val dismissParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener { updateImeInset() }
        scrim.viewTreeObserver.addOnGlobalLayoutListener(globalLayoutListener)
        dismissLayer = scrim

        windowManager.addView(scrim, dismissParams)
        windowManager.addView(rootView, contentLayoutParams)
        bindComposerInteractions()
        bindHandleInteractions()
        refreshStripItems()
        render()
    }

    private fun observeSettings() {
        serviceScope.launch {
            appContainer.settingsStore.settingsFlow.collectLatest { settings ->
                handleAlpha = settings.overlay.handleAlpha
                handleSizeDp = settings.overlay.handleSizeDp
                val dockSide = runCatching {
                    OverlayDockSide.valueOf(settings.overlay.dockSide)
                }.getOrDefault(OverlayDockSide.RIGHT)
                overlayState = overlayState.copy(dockSide = dockSide)
                updateHandleMetrics()
                updateRootOrder()
                updateOverlayPosition()
            }
        }
    }

    private fun observeNotes() {
        serviceScope.launch {
            appContainer.noteRepository.observeActiveNotes().collectLatest { notes ->
                activeNotes = notes
                if (overlayState.editingNoteId != null && notes.none { it.id == overlayState.editingNoteId }) {
                    overlayState = overlayState.clearEditingState()
                }
                if (overlayState.expandedNoteId != null && notes.none { it.id == overlayState.expandedNoteId }) {
                    overlayState = overlayState.copy(expandedNoteId = null)
                }
                refreshStripItems()
                if (overlayState.surfaceState == OverlaySurfaceState.STRIP_EXPANDED && !overlayState.isEntryHoldRecording()) {
                    animateStripIn()
                }
            }
        }
    }

    private fun bindComposerInteractions() {
        closeButton.setOnClickListener { closeOverlay() }

        saveButton.setOnClickListener {
            if (overlayState.isRecording) {
                serviceScope.launch {
                    stopRecordingAndSave(OverlayRecordingStopReason.COMPOSER_BUTTON)
                }
            } else {
                serviceScope.launch { saveTextNote() }
            }
        }

        recordButton.setOnTouchListener { _, event ->
            if (overlayState.surfaceState != OverlaySurfaceState.COMPOSER_ACTIVE) {
                return@setOnTouchListener false
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    composerRecordLongPressTriggered = false
                    recordButton.removeCallbacks(composerRecordLongPressRunnable)
                    recordButton.postDelayed(
                        composerRecordLongPressRunnable,
                        ViewConfiguration.getLongPressTimeout().toLong(),
                    )
                    true
                }

                MotionEvent.ACTION_UP -> {
                    recordButton.removeCallbacks(composerRecordLongPressRunnable)
                    val shouldStop = overlayState.isRecording &&
                        overlayState.recordingOrigin == OverlayRecordingOrigin.COMPOSER_BUTTON
                    when {
                        shouldStop -> {
                            serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
                                stopRecordingAndSave(OverlayRecordingStopReason.COMPOSER_BUTTON)
                            }
                        }

                        composerRecordLongPressTriggered -> Unit
                        overlayState.composerMode != OverlayComposerMode.RECORDING -> openRecordingComposer()
                        else -> toast("长按麦克风开始录音，松开结束。")
                    }
                    composerRecordLongPressTriggered = false
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    recordButton.removeCallbacks(composerRecordLongPressRunnable)
                    if (overlayState.isRecording &&
                        overlayState.recordingOrigin == OverlayRecordingOrigin.COMPOSER_BUTTON
                    ) {
                        serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
                            stopRecordingAndSave(OverlayRecordingStopReason.COMPOSER_BUTTON)
                        }
                    }
                    composerRecordLongPressTriggered = false
                    true
                }

                else -> true
            }
        }

        draftInput.addTextChangedListener { text ->
            overlayState = overlayState.copy(composerDraft = text?.toString().orEmpty())
        }
        draftInput.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_DONE
        draftInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveButton.performClick()
                true
            } else {
                false
            }
        }

        categoryButtons.forEach { (category, view) ->
            view.setOnClickListener {
                overlayState = overlayState.copy(selectedCategory = category)
                refreshStripItems()
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
                refreshStripItems()
                render()
            }
        }
    }

    private fun bindHandleInteractions() {
        handleView.setOnTouchListener(object : View.OnTouchListener {
            private var startRawX = 0f
            private var startRawY = 0f
            private var startTopY = 0
            private var moved = false
            private var dockChanged = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startRawX = event.rawX
                        startRawY = event.rawY
                        startTopY = handleTopY
                        moved = false
                        dockChanged = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaY = (event.rawY - startRawY).toInt()
                        val deltaX = event.rawX - startRawX
                        if (abs(deltaY) > dpToPx(4)) {
                            moved = true
                            handleTopY = (startTopY + deltaY).coerceIn(
                                dpToPx(16),
                                (resources.displayMetrics.heightPixels - dpToPx(120)).coerceAtLeast(dpToPx(16)),
                            )
                            updateOverlayPosition()
                        }
                        val targetDockSide = if (event.rawX < resources.displayMetrics.widthPixels / 2f) {
                            OverlayDockSide.LEFT
                        } else {
                            OverlayDockSide.RIGHT
                        }
                        if (targetDockSide != overlayState.dockSide) {
                            moved = true
                            dockChanged = true
                            overlayState = overlayState.copy(dockSide = targetDockSide)
                            updateRootOrder()
                            updateOverlayPosition()
                        }
                        if (abs(deltaX) > dpToPx(12) && abs(deltaX) > abs(event.rawY - startRawY)) {
                            moved = true
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (dockChanged) {
                            persistDockSide()
                        }
                        val openedBySwipe = isOutwardSwipe(startRawX, event.rawX, overlayState.dockSide)
                        if (!moved) {
                            if (overlayState.surfaceState == OverlaySurfaceState.HANDLE_COLLAPSED) {
                                expandStrip()
                            } else {
                                collapseToHandle()
                            }
                        } else if (overlayState.surfaceState == OverlaySurfaceState.HANDLE_COLLAPSED && openedBySwipe) {
                            expandStrip()
                        } else {
                            updateOverlayPosition()
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun attachStripSwipeHelper() {
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ): Int {
                val item = stripAdapter.getItemOrNull(viewHolder.bindingAdapterPosition)
                return if (item is OverlayStripItem.NoteStripItem) {
                    makeMovementFlags(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT)
                } else {
                    makeMovementFlags(0, 0)
                }
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean = false

            override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float = 0.2f

            override fun getSwipeEscapeVelocity(defaultValue: Float): Float = defaultValue * 0.45f

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val item = stripAdapter.getItemOrNull(viewHolder.bindingAdapterPosition) as? OverlayStripItem.NoteStripItem
                if (item == null) {
                    stripAdapter.notifyItemChanged(viewHolder.bindingAdapterPosition)
                    return
                }
                when (direction) {
                    ItemTouchHelper.RIGHT -> onArchive(item.note.id)
                    ItemTouchHelper.LEFT -> onTrash(item.note.id)
                }
            }

            override fun onChildDraw(
                c: Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean,
            ) {
                val item = stripAdapter.getItemOrNull(viewHolder.bindingAdapterPosition)
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && item is OverlayStripItem.NoteStripItem) {
                    drawSwipeBackground(c, viewHolder.itemView, dX)
                }
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }
        }).attachToRecyclerView(stripList)
    }

    private fun drawSwipeBackground(canvas: Canvas, itemView: View, deltaX: Float) {
        if (deltaX == 0f) return
        val inset = dpToPx(1).toFloat()
        val radius = dpToPx(16).toFloat()
        val bounds = RectF(
            itemView.left.toFloat() + inset,
            itemView.top.toFloat() + inset,
            itemView.right.toFloat() - inset,
            itemView.bottom.toFloat() - inset,
        )
        val color = if (deltaX > 0f) 0xFF2E7D61.toInt() else 0xFFC44545.toInt()
        val label = if (deltaX > 0f) "归档" else "回收站"
        val progress = (abs(deltaX) / itemView.width.toFloat()).coerceIn(0f, 1f)
        swipePaint.color = color
        swipePaint.alpha = (80 + 120 * progress).toInt()
        canvas.drawRoundRect(bounds, radius, radius, swipePaint)

        val textX = if (deltaX > 0f) {
            itemView.left + dpToPx(52)
        } else {
            itemView.right - dpToPx(52)
        }.toFloat()
        val textY = bounds.centerY() - (swipeTextPaint.descent() + swipeTextPaint.ascent()) / 2f
        swipeTextPaint.alpha = (120 + 135 * progress).toInt()
        canvas.drawText(label, textX, textY, swipeTextPaint)
    }

    private fun refreshStripItems() {
        val showEntryHoldRecording = overlayState.isEntryHoldRecording()
        val isStripExpanded = overlayState.surfaceState == OverlaySurfaceState.STRIP_EXPANDED
        val items = buildList {
            if (!isStripExpanded) {
                add(
                    OverlayStripItem.ComposerEntryItem(
                        category = overlayState.selectedCategory,
                        priority = overlayState.selectedPriority,
                        isRecording = showEntryHoldRecording,
                    ),
                )
            }
            activeNotes.forEach { note ->
                if (note.id == overlayState.editingNoteId) {
                    add(
                        OverlayStripItem.EditingNoteItem(
                            note = note,
                            draft = overlayState.editingDraft,
                            category = overlayState.editingCategory,
                            priority = overlayState.editingPriority,
                            tags = overlayState.editingTags,
                        ),
                    )
                } else if (note.id == overlayState.expandedNoteId) {
                    add(OverlayStripItem.ExpandedNoteItem(note))
                } else {
                    add(OverlayStripItem.NoteStripItem(note))
                }
            }
        }
        overlayState = overlayState.copy(stripItems = items)
        if (showEntryHoldRecording && !isStripExpanded) {
            stripAdapter.updateComposerEntry(
                OverlayStripItem.ComposerEntryItem(
                    category = overlayState.selectedCategory,
                    priority = overlayState.selectedPriority,
                    isRecording = true,
                ),
                stripList,
            )
        } else {
            stripAdapter.submitItems(items)
        }
        if (isStripExpanded) {
            renderStripEntryBar()
        }
    }

    private fun expandStrip() {
        overlayState = overlayState.copy(
            surfaceState = OverlaySurfaceState.STRIP_EXPANDED,
            composerMode = OverlayComposerMode.TEXT,
        )
        hideKeyboard()
        updateWindowFocusability(false)
        refreshStripItems()
        render()
        animateStripIn()
    }

    private fun openTextComposer() {
        // 先播放收起动画，再切换到 composer
        val durationMs = 140L
        railContainer.animate().cancel()
        railContainer.animate()
            .alpha(0f)
            .scaleX(0.96f)
            .scaleY(0.96f)
            .setDuration(durationMs)
            .withEndAction {
                overlayState = overlayState.copy(
                    surfaceState = OverlaySurfaceState.COMPOSER_ACTIVE,
                    composerMode = OverlayComposerMode.TEXT,
                    isRecording = false,
                    recordingOrigin = OverlayRecordingOrigin.NONE,
                    entryHoldActive = false,
                    expandedNoteId = null,
                ).clearEditingState()
                suppressImeCollapse = false
                refreshStripItems()
                render()
                updateWindowFocusability(true)
                railContainer.alpha = 0f
                railContainer.scaleX = 0.96f
                railContainer.scaleY = 0.96f
                railContainer.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(durationMs)
                    .start()
                draftInput.post {
                    draftInput.requestFocus()
                    val inputMethodManager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                    inputMethodManager.showSoftInput(draftInput, InputMethodManager.SHOW_IMPLICIT)
                }
            }
            .start()
    }

    private fun openRecordingComposer() {
        overlayState = overlayState.copy(
            surfaceState = OverlaySurfaceState.COMPOSER_ACTIVE,
            composerMode = OverlayComposerMode.RECORDING,
            recordingOrigin = OverlayRecordingOrigin.NONE,
            entryHoldActive = false,
            expandedNoteId = null,
        ).clearEditingState()
        hideKeyboard()
        refreshStripItems()
        updateWindowFocusability(false)
        render()
    }

    private fun closeOverlay() {
        if (overlayState.isRecording) {
            cancelActiveRecording()
        }
        collapseToHandle()
    }

    private fun collapseToHandle() {
        overlayState = overlayState.copy(
            surfaceState = OverlaySurfaceState.HANDLE_COLLAPSED,
            composerMode = OverlayComposerMode.TEXT,
            isRecording = false,
            recordingSeconds = 0,
            recordingOrigin = OverlayRecordingOrigin.NONE,
            entryHoldActive = false,
            imeVisible = false,
            imeInsetBottom = 0,
        ).clearEditingState()
        recordingTimerJob?.cancel()
        updateWindowFocusability(false)
        hideKeyboard()
        refreshStripItems()
        render()
    }

    private fun restoreExpandedStrip(savedNote: Note? = null) {
        savedNote?.let { note ->
            activeNotes = listOf(note) + activeNotes.filterNot { it.id == note.id }
        }
        overlayState = overlayState.copy(
            surfaceState = OverlaySurfaceState.STRIP_EXPANDED,
            composerMode = OverlayComposerMode.TEXT,
            isRecording = false,
            recordingSeconds = 0,
            recordingOrigin = OverlayRecordingOrigin.NONE,
            entryHoldActive = false,
            expandedNoteId = savedNote?.id ?: overlayState.expandedNoteId,
        ).clearEditingState()
        hideKeyboard()
        updateWindowFocusability(false)
        refreshStripItems()
        render()
    }

    private fun render() {
        renderComposerState()
        renderSelectionState()
        updateRootOrder()
        updateHandleMetrics()
        updateRailWidth()

        when (overlayState.surfaceState) {
            OverlaySurfaceState.HANDLE_COLLAPSED -> {
                railContainer.visibility = View.GONE
                stripList.visibility = View.GONE
                composerPanel.visibility = View.GONE
                stripEntryBar.visibility = View.GONE
                handleView.visibility = View.VISIBLE
                dismissLayer?.visibility = View.GONE
            }
            OverlaySurfaceState.STRIP_EXPANDED -> {
                railContainer.visibility = View.VISIBLE
                stripList.visibility = View.VISIBLE
                composerPanel.visibility = View.GONE
                stripEntryBar.visibility = View.VISIBLE
                handleView.visibility = View.VISIBLE
                dismissLayer?.visibility = View.VISIBLE
                updateStripHeight()
                renderStripEntryBar()
            }
            OverlaySurfaceState.COMPOSER_ACTIVE -> {
                railContainer.visibility = View.VISIBLE
                stripList.visibility = View.GONE
                composerPanel.visibility = View.VISIBLE
                stripEntryBar.visibility = View.GONE
                handleView.visibility = View.GONE
                dismissLayer?.visibility = View.VISIBLE
            }
        }

        rootView.post { updateOverlayPosition() }
    }

    private fun renderStripEntryBar() {
        val item = OverlayStripItem.ComposerEntryItem(
            category = overlayState.selectedCategory,
            priority = overlayState.selectedPriority,
            isRecording = overlayState.isEntryHoldRecording(),
        )
        val existing = stripEntryBar.getChildAt(0) as? OverlayComposerPressView
        if (existing != null) {
            existing.bind(item, this)
        } else {
            stripEntryBar.removeAllViews()
            stripEntryBar.addView(OverlayComposerPressView(this).also { it.bind(item, this) })
        }
        val currentWidth = (stripList.layoutParams as? RecyclerView.LayoutParams)?.width ?: railBaseWidthPx()
        stripEntryBar.layoutParams = (stripEntryBar.layoutParams as? FrameLayout.LayoutParams)?.apply {
            width = currentWidth
        } ?: FrameLayout.LayoutParams(currentWidth, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
    }

    private fun renderComposerState() {
        val isTextMode = overlayState.composerMode == OverlayComposerMode.TEXT
        composerTitle.text = if (isTextMode) "快速记录" else "语音记录"
        categoryBar.visibility = if (isTextMode) View.VISIBLE else View.GONE
        draftInput.visibility = if (isTextMode) View.VISIBLE else View.GONE
        saveButton.visibility = if (isTextMode) View.VISIBLE else View.GONE

        if (draftInput.text.toString() != overlayState.composerDraft) {
            draftInput.setText(overlayState.composerDraft)
            draftInput.setSelection(draftInput.text.length)
        }

        when {
            overlayState.isRecording -> {
                recordButton.setImageResource(android.R.drawable.ic_media_pause)
                recordingStatus.text = "录音中 ${overlayState.recordingSeconds} 秒"
                recordingStatus.setTextColor(0xFFCB3A3A.toInt())
            }
            overlayState.composerMode == OverlayComposerMode.RECORDING -> {
                recordButton.setImageResource(android.R.drawable.ic_btn_speak_now)
                recordingStatus.text = "长按麦克风开始录音，松开结束。"
                recordingStatus.setTextColor(0xFF5D6772.toInt())
            }
            else -> {
                recordButton.setImageResource(android.R.drawable.ic_btn_speak_now)
                recordingStatus.text = "点击发送保存，长按第一张卡片录音。"
                recordingStatus.setTextColor(0xFF5D6772.toInt())
            }
        }
    }

    private fun renderSelectionState() {
        categoryButtons.forEach { (category, view) ->
            val selected = overlayState.selectedCategory == category
            view.setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFF34404A.toInt())
            view.backgroundTintList = ColorStateList.valueOf(
                if (selected) categoryColor(category) else 0xFFF1F5F9.toInt(),
            )
        }
        priorityButtons.forEachIndexed { index, view ->
            val priority = when (index) {
                0 -> NotePriority.LOW
                1 -> NotePriority.MEDIUM
                2 -> NotePriority.HIGH
                else -> NotePriority.URGENT
            }
            val selected = overlayState.selectedPriority == priority
            view.setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFF5D6772.toInt())
            view.backgroundTintList = ColorStateList.valueOf(
                if (selected) priorityColor(priority) else 0xFFE7ECF1.toInt(),
            )
        }
    }

    private fun updateHandleMetrics() {
        handleView.alpha = handleAlpha
        handleView.layoutParams = handleView.layoutParams.apply {
            width = dpToPx(handleSizeDp)
            height = dpToPx((handleSizeDp * 3.8f).toInt())
        }
        handleView.requestLayout()
    }

    private fun updateRootOrder() {
        val handleIndex = rootRow.indexOfChild(handleView)
        val railIndex = rootRow.indexOfChild(railContainer)
        val shouldRailLead = overlayState.dockSide == OverlayDockSide.RIGHT
        if (shouldRailLead && railIndex > handleIndex) {
            rootRow.removeView(railContainer)
            rootRow.addView(railContainer, 0)
        } else if (!shouldRailLead && handleIndex > railIndex) {
            rootRow.removeView(handleView)
            rootRow.addView(handleView, 0)
        }
        (railContainer.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
            if (overlayState.dockSide == OverlayDockSide.RIGHT) {
                params.marginStart = 0
                params.marginEnd = dpToPx(8)
            } else {
                params.marginStart = dpToPx(8)
                params.marginEnd = 0
            }
            railContainer.layoutParams = params
        }
    }

    private fun updateStripHeight() {
        val maxHeight = (resources.displayMetrics.heightPixels * 0.73f).toInt()
        stripList.layoutParams = stripList.layoutParams.apply {
            height = maxHeight.coerceAtLeast(dpToPx(320))
        }
        stripList.requestLayout()
    }

    private fun updateRailWidth() {
        val targetWidth = targetRailWidthPx()
        if (targetWidth == appliedRailWidthPx) return

        val currentWidth = appliedRailWidthPx.takeIf { it > 0 } ?: targetWidth
        railWidthAnimator?.cancel()
        if (!rootView.isLaidOut || abs(currentWidth - targetWidth) < dpToPx(8)) {
            applyRailWidthImmediate(targetWidth)
            return
        }

        railWidthAnimator = ValueAnimator.ofInt(currentWidth, targetWidth).apply {
            duration = 180L
            interpolator = OvershootInterpolator(0.65f)
            addUpdateListener { animator ->
                applyRailWidthImmediate(animator.animatedValue as Int)
                updateOverlayPosition()
            }
            start()
        }
    }

    private fun targetRailWidthPx(): Int {
        val baseWidth = railBaseWidthPx()
        if (overlayState.expandedNoteId == null && overlayState.editingNoteId == null) {
            return baseWidth
        }

        val note = activeNotes.firstOrNull {
            it.id == overlayState.editingNoteId || it.id == overlayState.expandedNoteId
        } ?: return baseWidth
        val bodyContent = note.overlayBodyContent()
        val primaryText = if (overlayState.editingNoteId == note.id) {
            overlayState.editingDraft.trim().ifBlank { note.overlayEditingSeedText() }
        } else {
            bodyContent.primaryText
        }
        return measureOverlayRailWidth(
            primaryText = primaryText,
            originalText = if (overlayState.expandedNoteId == note.id) bodyContent.originalText else null,
            maxWidthPx = railExpandedMaxWidthPx(),
        ).coerceAtLeast(baseWidth)
    }

    private fun railBaseWidthPx(): Int {
        val target = dpToPx(260)
        val minimum = dpToPx(236)
        val reserved = dpToPx(handleSizeDp + 72)
        val available = (resources.displayMetrics.widthPixels - reserved).coerceAtLeast(minimum)
        return target.coerceAtMost(available)
    }

    private fun railExpandedMaxWidthPx(): Int {
        val reserved = dpToPx(handleSizeDp + 36)
        val safeByScreen = (resources.displayMetrics.widthPixels * 0.72f).toInt()
        val safeByWorkspace = resources.displayMetrics.widthPixels - reserved
        return minOf(safeByScreen, safeByWorkspace).coerceAtLeast(railBaseWidthPx())
    }

    private fun applyRailWidthImmediate(width: Int) {
        appliedRailWidthPx = width
        stripList.layoutParams = stripList.layoutParams.apply {
            this.width = width
        }
        composerPanel.layoutParams = composerPanel.layoutParams.apply {
            this.width = width
        }
        railContainer.requestLayout()
    }

    private fun updateWindowFocusability(focusable: Boolean) {
        val params = contentLayoutParams ?: return
        val targetFlags = if (focusable) {
            baseWindowFlags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            baseWindowFlags
        }
        if (params.flags != targetFlags) {
            params.flags = targetFlags
            runCatching { windowManager.updateViewLayout(rootView, params) }
        }
    }

    private fun updateOverlayPosition() {
        val params = contentLayoutParams ?: return
        val topGravity = if (overlayState.dockSide == OverlayDockSide.LEFT) {
            Gravity.TOP or Gravity.START
        } else {
            Gravity.TOP or Gravity.END
        }
        params.gravity = topGravity
        params.x = 0

        rootView.post {
            rootView.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            val measuredHeight = rootView.measuredHeight.coerceAtLeast(dpToPx(92))
            val screenHeight = resources.displayMetrics.heightPixels
            val margin = dpToPx(16)
            val shouldAvoidIme = overlayState.imeVisible && (
                (overlayState.surfaceState == OverlaySurfaceState.COMPOSER_ACTIVE &&
                    overlayState.composerMode == OverlayComposerMode.TEXT) ||
                    (overlayState.surfaceState == OverlaySurfaceState.STRIP_EXPANDED &&
                        overlayState.editingNoteId != null)
                )
            val targetY = if (shouldAvoidIme) {
                (screenHeight - overlayState.imeInsetBottom - measuredHeight - margin).coerceAtLeast(margin)
            } else {
                handleTopY.coerceIn(margin, (screenHeight - measuredHeight - margin).coerceAtLeast(margin))
            }
            params.y = targetY
            runCatching { windowManager.updateViewLayout(rootView, params) }
        }
    }

    private fun updateImeInset() {
        val scrim = dismissLayer ?: return
        val rect = Rect()
        scrim.getWindowVisibleDisplayFrame(rect)
        val screenHeight = resources.displayMetrics.heightPixels
        val insetBottom = (screenHeight - rect.bottom).coerceAtLeast(0)
        val imeVisible = insetBottom > dpToPx(96)
        val wasVisible = overlayState.imeVisible
        overlayState = overlayState.copy(
            imeInsetBottom = insetBottom,
            imeVisible = imeVisible,
        )
        if (overlayState.surfaceState == OverlaySurfaceState.COMPOSER_ACTIVE &&
            overlayState.composerMode == OverlayComposerMode.TEXT
        ) {
            updateOverlayPosition()
            if (wasVisible && !imeVisible && !suppressImeCollapse) {
                collapseToHandle()
            }
        }
    }

    private fun animateStripIn() {
        stripList.post {
            repeat(stripList.childCount) { index ->
                val child = stripList.getChildAt(index)
                child.alpha = 0f
                child.translationX = 0f
                child.translationY = dpToPx(10).toFloat()
                child.scaleX = 0.96f
                child.scaleY = 0.96f
                child.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(220L)
                    .setStartDelay(index * 32L)
                    .setInterpolator(OvershootInterpolator(0.75f))
                    .start()
            }
        }
    }

    override fun onOpenComposer() {
        openTextComposer()
    }

    override fun onStartRecordingFromEntry() {
        serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
            startRecordingNow(OverlayRecordingOrigin.ENTRY_HOLD)
        }
    }

    override fun onStopRecordingFromEntry() {
        if (!overlayState.isEntryHoldRecording()) {
            return
        }
        serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
            stopRecordingAndSave(OverlayRecordingStopReason.ENTRY_RELEASE)
        }
    }

    override fun onCancelRecordingFromEntry() {
        serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
            cancelEntryHoldRecording()
        }
    }

    override fun onToggleExpanded(noteId: String) {
        if (overlayState.editingNoteId != null) return
        overlayState = overlayState.copy(
            expandedNoteId = if (overlayState.expandedNoteId == noteId) null else noteId,
        )
        refreshStripItems()
        render()
        stripList.post {
            val index = overlayState.stripItems.indexOfFirst {
                when (it) {
                    is OverlayStripItem.NoteStripItem -> it.note.id == noteId
                    is OverlayStripItem.ExpandedNoteItem -> it.note.id == noteId
                    else -> false
                }
            }
            if (index >= 0) {
                stripList.smoothScrollToPosition(index)
            }
        }
    }

    override fun onOpenNote(noteId: String) {
        openNote(noteId)
    }

    override fun onArchive(noteId: String) {
        serviceScope.launch { archiveNoteFromOverlay(noteId) }
    }

    override fun onTrash(noteId: String) {
        serviceScope.launch { trashNoteFromOverlay(noteId) }
    }

    override fun onEdit(noteId: String) {
        serviceScope.launch { beginInlineEditing(noteId) }
    }

    override fun onEditingContentChanged(value: String) {
        overlayState = overlayState.copy(editingDraft = value)
    }

    override fun onEditingCategoryChanged(category: NoteCategory) {
        overlayState = overlayState.copy(editingCategory = category)
    }

    override fun onEditingPriorityChanged(priority: NotePriority) {
        overlayState = overlayState.copy(editingPriority = priority)
    }

    override fun onEditingTagsChanged(tags: List<String>) {
        overlayState = overlayState.copy(editingTags = tags)
    }

    override fun onSaveEditing() {
        serviceScope.launch { saveInlineEditing() }
    }

    override fun onCancelEditing() {
        overlayState = overlayState.clearEditingState()
        hideKeyboard()
        updateWindowFocusability(false)
        refreshStripItems()
        render()
    }

    override fun onExpandedContentLayoutChanged() {
        rootView.post { updateOverlayPosition() }
    }

    override fun onQuickAction(noteId: String, action: OverlayNoteQuickAction) {
        serviceScope.launch { handleQuickAction(noteId, action) }
    }

    private suspend fun saveTextNote() {
        val content = overlayState.composerDraft.trim()
        if (content.isBlank()) return
        val note = withContext(Dispatchers.IO) {
            appContainer.noteRepository.createTextNote(
                content = content,
                category = overlayState.selectedCategory,
                priority = overlayState.selectedPriority,
            )
        }
        overlayState = overlayState.copy(composerDraft = "")
        draftInput.setText("")
        collapseToHandle()
        syncIfEnabled(note)
    }

    private suspend fun startRecordingNow(origin: OverlayRecordingOrigin) {
        if (overlayState.isRecording) return
        if (!checkPermissions()) {
            overlayState = overlayState.copy(
                recordingOrigin = OverlayRecordingOrigin.NONE,
                entryHoldActive = false,
            )
            refreshStripItems()
            toast("请先授予麦克风和通知权限。")
            return
        }

        vibrateHandle()
        runCatching { appContainer.audioRecorder.start() }.getOrElse {
            overlayState = overlayState.copy(
                isRecording = false,
                recordingSeconds = 0,
                recordingOrigin = OverlayRecordingOrigin.NONE,
                entryHoldActive = false,
            )
            refreshStripItems()
            render()
            toast("无法开始录音。")
            return
        }

        overlayState = overlayState.copy(
            isRecording = true,
            recordingSeconds = 0,
            recordingOrigin = origin,
            entryHoldActive = origin == OverlayRecordingOrigin.ENTRY_HOLD,
        )
        refreshStripItems()
        if (origin == OverlayRecordingOrigin.ENTRY_HOLD) {
            Log.d(TAG, "ENTRY_RECORDING_STARTED")
        } else {
            render()
        }

        ContextCompat.startForegroundService(this, Intent(this, RecordingService::class.java))
        recordingTimerJob?.cancel()
        if (origin == OverlayRecordingOrigin.COMPOSER_BUTTON) {
            recordingTimerJob = serviceScope.launch {
                while (overlayState.isRecording && overlayState.recordingOrigin == OverlayRecordingOrigin.COMPOSER_BUTTON) {
                    delay(1000)
                    overlayState = overlayState.copy(recordingSeconds = overlayState.recordingSeconds + 1)
                    render()
                }
            }
        }
    }

    private suspend fun stopRecordingAndSave(reason: OverlayRecordingStopReason) {
        if (!overlayState.isRecording) return

        val origin = overlayState.recordingOrigin
        val selectedPriority = overlayState.selectedPriority
        recordingTimerJob?.cancel()
        overlayState = overlayState.copy(
            isRecording = false,
            recordingSeconds = 0,
            recordingOrigin = OverlayRecordingOrigin.NONE,
            entryHoldActive = false,
        )
        refreshStripItems()
        if (reason != OverlayRecordingStopReason.ENTRY_RELEASE || overlayState.surfaceState == OverlaySurfaceState.COMPOSER_ACTIVE) {
            render()
        }

        val overlaySaveSettings = appContainer.settingsStore.settingsFlow.first()
        val overlaySaveResult = withContext(Dispatchers.IO) {
            runCatching {
                appContainer.voiceNoteProcessor.stopAndSave(
                    priority = selectedPriority,
                    relayConfig = overlaySaveSettings.relay,
                    volcengineConfig = overlaySaveSettings.volcengine,
                    wifiOnly = currentWebDavWifiOnly(),
                )
            }
        }

        stopService(Intent(this, RecordingService::class.java))
        overlaySaveResult.onSuccess { result ->
            if (origin == OverlayRecordingOrigin.ENTRY_HOLD) {
                Log.d(TAG, "ENTRY_SAVE_SUCCESS")
                restoreExpandedStrip(result.note)
            } else {
                collapseToHandle()
            }
            val syncError = runCatching { syncIfEnabled(result.note) }.exceptionOrNull()?.message
            toast(result.buildUserMessage(syncError))
        }.onFailure { error ->
            if (origin == OverlayRecordingOrigin.ENTRY_HOLD) {
                Log.e(TAG, "ENTRY_SAVE_FAILED", error)
                restoreExpandedStrip()
            } else {
                collapseToHandle()
            }
            toast(error.message ?: "录音保存失败。")
        }
    }

    private fun cancelActiveRecording() {
        if (!overlayState.isRecording) return
        recordingTimerJob?.cancel()
        overlayState = overlayState.copy(
            isRecording = false,
            recordingSeconds = 0,
            recordingOrigin = OverlayRecordingOrigin.NONE,
            entryHoldActive = false,
        )
        runCatching { appContainer.audioRecorder.cancel() }
        stopService(Intent(this, RecordingService::class.java))
        refreshStripItems()
        render()
    }

    private fun cancelEntryHoldRecording() {
        if (!overlayState.entryHoldActive) return
        Log.d(TAG, "ENTRY_CANCEL_DISCARD")
        recordingTimerJob?.cancel()
        if (overlayState.isRecording) {
            runCatching { appContainer.audioRecorder.cancel() }
            stopService(Intent(this, RecordingService::class.java))
        }
        overlayState = overlayState.copy(
            isRecording = false,
            recordingSeconds = 0,
            recordingOrigin = OverlayRecordingOrigin.NONE,
            entryHoldActive = false,
        )
        refreshStripItems()
        render()
    }

    private suspend fun archiveNoteFromOverlay(noteId: String) {
        val note = withContext(Dispatchers.IO) { appContainer.noteRepository.archiveNote(noteId) }
        syncIfEnabled(note, force = true)
    }

    private suspend fun trashNoteFromOverlay(noteId: String) {
        withContext(Dispatchers.IO) { appContainer.noteRepository.trashNote(noteId) }
        syncStateIfEnabled(force = true)
    }

    private suspend fun handleQuickAction(noteId: String, action: OverlayNoteQuickAction) {
        when (action) {
            OverlayNoteQuickAction.EDIT -> beginInlineEditing(noteId)
            OverlayNoteQuickAction.COPY -> copyNoteToClipboard(noteId)
            OverlayNoteQuickAction.CONVERT_TO_TODO -> convertNoteCategory(noteId, NoteCategory.TODO)
            OverlayNoteQuickAction.COMPLETE -> archiveNoteFromOverlay(noteId)
            OverlayNoteQuickAction.REMIND -> convertNoteCategory(noteId, NoteCategory.REMINDER)
            OverlayNoteQuickAction.SORT -> openNote(noteId)
            OverlayNoteQuickAction.SNOOZE -> openNote(noteId)
            OverlayNoteQuickAction.PIN -> togglePinned(noteId)
            OverlayNoteQuickAction.PLAY -> playAudio(noteId)
            OverlayNoteQuickAction.TRANSCRIBE -> retryTranscription(noteId)
            OverlayNoteQuickAction.SHARE -> shareNote(noteId)
        }
    }

    private suspend fun beginInlineEditing(noteId: String) {
        val note = activeNotes.firstOrNull { it.id == noteId }
            ?: withContext(Dispatchers.IO) { appContainer.noteRepository.getNote(noteId) }
            ?: return
        overlayState = overlayState.copy(
            editingNoteId = note.id,
            editingDraft = note.overlayEditingSeedText(),
            editingCategory = note.category,
            editingPriority = note.priority,
            editingTags = note.tags,
            expandedNoteId = null,
        )
        refreshStripItems()
        updateWindowFocusability(true)
        render()
        stripList.post {
            val index = overlayState.stripItems.indexOfFirst {
                it is OverlayStripItem.EditingNoteItem && it.note.id == noteId
            }
            if (index >= 0) {
                stripList.smoothScrollToPosition(index)
                stripList.post {
                    stripAdapter.focusEditingInput(stripList, noteId)
                    updateOverlayPosition()
                }
            }
        }
    }

    private suspend fun saveInlineEditing() {
        val noteId = overlayState.editingNoteId ?: return
        val content = overlayState.editingDraft.trim()
        if (content.isBlank()) {
            toast("便签内容不能为空。")
            return
        }
        val updated = withContext(Dispatchers.IO) {
            val note = appContainer.noteRepository.getNote(noteId) ?: return@withContext null
            appContainer.noteRepository.saveEditedNote(
                note.copy(
                    content = content,
                    category = overlayState.editingCategory,
                    priority = overlayState.editingPriority,
                    tags = overlayState.editingTags,
                    colorToken = defaultColorFor(overlayState.editingCategory, overlayState.editingPriority),
                ),
            )
        } ?: run {
            overlayState = overlayState.clearEditingState()
            hideKeyboard()
            updateWindowFocusability(false)
            refreshStripItems()
            render()
            toast("这条便签已不在当前列表。")
            return
        }

        overlayState = overlayState.clearEditingState()
        hideKeyboard()
        updateWindowFocusability(false)
        refreshStripItems()
        render()
        syncIfEnabled(updated)
    }

    private suspend fun convertNoteCategory(noteId: String, category: NoteCategory) {
        val updated = withContext(Dispatchers.IO) {
            val note = appContainer.noteRepository.getNote(noteId) ?: return@withContext null
            if (note.category == category) return@withContext note
            appContainer.noteRepository.saveEditedNote(
                note.copy(
                    category = category,
                    colorToken = defaultColorFor(category, note.priority),
                ),
            )
        } ?: return
        syncIfEnabled(updated, force = true)
    }

    private suspend fun togglePinned(noteId: String) {
        withContext(Dispatchers.IO) {
            val note = appContainer.noteRepository.getNote(noteId) ?: return@withContext
            appContainer.noteRepository.setPinned(noteId, !note.pinned)
        }
    }

    private suspend fun retryTranscription(noteId: String) {
        val settings = appContainer.settingsStore.settingsFlow.first()
        val note = withContext(Dispatchers.IO) { appContainer.noteRepository.getNote(noteId) } ?: return
        if (note.source != NoteSource.VOICE) return

        val refreshed = withContext(Dispatchers.IO) {
            if (settings.relay.enabled && settings.volcengine.enabled && !note.relayUrl.isNullOrBlank()) {
                runCatching {
                    appContainer.transcriptionOrchestrator.transcribe(note, settings.volcengine, settings.relay)
                }.onFailure {
                    appContainer.transcriptionScheduler.enqueueRetry(note.id, settings.webDav.wifiOnly)
                }
            } else {
                appContainer.transcriptionScheduler.enqueueRetry(note.id, settings.webDav.wifiOnly)
            }
            appContainer.noteRepository.getNote(note.id) ?: note
        }
        syncIfEnabled(refreshed, force = true)
    }

    private suspend fun copyNoteToClipboard(noteId: String) {
        val note = withContext(Dispatchers.IO) { appContainer.noteRepository.getNote(noteId) } ?: return
        val text = (note.transcript ?: note.content).ifBlank { note.title }
        val clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.setPrimaryClip(ClipData.newPlainText(note.title, text))
        toast("已复制")
    }

    private suspend fun shareNote(noteId: String) {
        val note = withContext(Dispatchers.IO) { appContainer.noteRepository.getNote(noteId) } ?: return
        val body = buildString {
            appendLine(note.title)
            appendLine()
            appendLine((note.transcript ?: note.content).ifBlank { "暂无内容" })
            if (!note.relayUrl.isNullOrBlank()) {
                appendLine()
                append(note.relayUrl)
            }
        }.trim()
        startActivity(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, note.title)
                putExtra(Intent.EXTRA_TEXT, body)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    private suspend fun playAudio(noteId: String) {
        val note = withContext(Dispatchers.IO) { appContainer.noteRepository.getNote(noteId) } ?: return
        appContainer.localAudioPlayer.play(note) { message -> toast(message) }
        return
        val path = note.audioPath ?: run {
            toast("没有本地音频文件。")
            return
        }
        if (!File(path).exists()) {
            toast("音频文件不存在。")
            return
        }
        releasePlayer()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(path)
            setOnPreparedListener { it.start() }
            setOnCompletionListener {
                toast("播放完成。")
                releasePlayer()
            }
            setOnErrorListener { _, _, _ ->
                toast("无法播放音频。")
                releasePlayer()
                true
            }
            prepareAsync()
        }
    }

    private fun releasePlayer() {
        mediaPlayer?.runCatching {
            stop()
        }
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private fun openNote(noteId: String) {
        collapseToHandle()
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(MainActivity.EXTRA_NOTE_ID, noteId)
            },
        )
    }

    private suspend fun syncIfEnabled(note: Note, force: Boolean = false) {
        val settings = appContainer.settingsStore.settingsFlow.first()
        if (settings.webDavEnabled && (force || settings.webDav.autoSync)) {
            withContext(Dispatchers.IO) {
                appContainer.syncOrchestrator.syncNote(note).getOrElse {
                    appContainer.syncScheduler.enqueueRetry(settings.webDav.wifiOnly)
                    throw it
                }
            }
        }
    }

    private suspend fun currentWebDavWifiOnly(): Boolean {
        val target = appContainer.syncTargetRepository.getTarget(SyncType.WEBDAV) ?: return false
        val config = target.config as? WebDavConfig ?: return false
        return config.wifiOnly
    }

    private suspend fun syncStateIfEnabled(force: Boolean = false) {
        val settings = appContainer.settingsStore.settingsFlow.first()
        if (settings.webDavEnabled && (force || settings.webDav.autoSync)) {
            withContext(Dispatchers.IO) {
                appContainer.syncOrchestrator.syncBidirectional().getOrElse {
                    appContainer.syncScheduler.enqueueRetry(settings.webDav.wifiOnly)
                    throw it
                }
            }
        }
    }

    private fun hideKeyboard() {
        suppressImeCollapse = true
        val inputMethodManager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val focusedView = rootView.findFocus()
        val windowToken = focusedView?.windowToken ?: draftInput.windowToken
        inputMethodManager.hideSoftInputFromWindow(windowToken, 0)
        focusedView?.clearFocus()
        draftInput.clearFocus()
        rootView.postDelayed({ suppressImeCollapse = false }, 160L)
    }

    private fun persistDockSide() {
        serviceScope.launch {
            val currentOverlay = appContainer.settingsStore.settingsFlow.first().overlay
            appContainer.settingsStore.saveOverlay(currentOverlay.copy(dockSide = overlayState.dockSide.name))
        }
    }

    private fun isOutwardSwipe(startX: Float, endX: Float, dockSide: OverlayDockSide): Boolean =
        when (dockSide) {
            OverlayDockSide.LEFT -> endX - startX > dpToPx(18)
            OverlayDockSide.RIGHT -> startX - endX > dpToPx(18)
        }

    private fun checkPermissions(): Boolean {
        val audioGranted =
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        return audioGranted && notificationGranted
    }

    private fun categoryColor(category: NoteCategory): Int = when (category) {
        NoteCategory.NOTE -> 0xFF7AA488.toInt()
        NoteCategory.TODO -> 0xFFE0A635.toInt()
        NoteCategory.TASK -> 0xFF4B91DE.toInt()
        NoteCategory.REMINDER -> 0xFF9F61E1.toInt()
    }

    private fun priorityColor(priority: NotePriority): Int = when (priority) {
        NotePriority.LOW -> 0xFF6B7280.toInt()
        NotePriority.MEDIUM -> 0xFF4E7CD5.toInt()
        NotePriority.HIGH -> 0xFFE09C32.toInt()
        NotePriority.URGENT -> 0xFFE05555.toInt()
    }

    private fun vibrateHandle() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        } ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(40)
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun OverlayUiState.isEntryHoldRecording(): Boolean =
        isRecording && recordingOrigin == OverlayRecordingOrigin.ENTRY_HOLD && entryHoldActive

    private fun OverlayUiState.clearEditingState(): OverlayUiState =
        copy(
            editingNoteId = null,
            editingDraft = "",
            editingCategory = NoteCategory.NOTE,
            editingPriority = NotePriority.MEDIUM,
            editingTags = emptyList(),
        )

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    companion object {
        private const val CHANNEL_ID = "ydoc_overlay_channel"
        private const val NOTIFICATION_ID = 2001
        private const val TAG = "OverlayHandleService"
    }
}

