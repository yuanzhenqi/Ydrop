package com.ydoc.app.overlay

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.OvershootInterpolator
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.RecyclerView
import com.ydoc.app.model.Note
import com.ydoc.app.model.NoteCategory
import com.ydoc.app.model.NoteColorToken
import com.ydoc.app.model.NotePriority
import com.ydoc.app.model.NoteSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OverlayStripAdapter(
    private val listener: Listener,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    interface Listener {
        fun onOpenComposer()
        fun onStartRecordingFromEntry()
        fun onStopRecordingFromEntry()
        fun onCancelRecordingFromEntry()
        fun onToggleExpanded(noteId: String)
        fun onOpenNote(noteId: String)
        fun onArchive(noteId: String)
        fun onTrash(noteId: String)
        fun onEdit(noteId: String)
        fun onEditingContentChanged(value: String)
        fun onEditingCategoryChanged(category: NoteCategory)
        fun onEditingPriorityChanged(priority: NotePriority)
        fun onEditingTagsChanged(tags: List<String>)
        fun onSaveEditing()
        fun onCancelEditing()
        fun onExpandedContentLayoutChanged()
        fun onQuickAction(noteId: String, action: OverlayNoteQuickAction)
    }

    private val items = mutableListOf<OverlayStripItem>()

    fun submitItems(newItems: List<OverlayStripItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun updateComposerEntry(item: OverlayStripItem.ComposerEntryItem, recyclerView: RecyclerView? = null) {
        if (items.isEmpty() || items.firstOrNull() !is OverlayStripItem.ComposerEntryItem) {
            items.clear()
            items.add(item)
            notifyDataSetChanged()
            return
        }
        items[0] = item
        val holder = recyclerView?.findViewHolderForAdapterPosition(0) as? ComposerViewHolder
        if (holder != null) {
            holder.bind(item, listener)
        } else {
            notifyItemChanged(0, PAYLOAD_COMPOSER_STATE)
        }
    }

    fun getItemOrNull(position: Int): OverlayStripItem? = items.getOrNull(position)

    fun focusEditingInput(recyclerView: RecyclerView, noteId: String) {
        val index = items.indexOfFirst { item ->
            item is OverlayStripItem.EditingNoteItem && item.note.id == noteId
        }
        if (index < 0) return
        val holder = recyclerView.findViewHolderForAdapterPosition(index) as? EditingViewHolder
        holder?.focusInput()
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is OverlayStripItem.ComposerEntryItem -> VIEW_TYPE_COMPOSER
        is OverlayStripItem.NoteStripItem -> VIEW_TYPE_NOTE
        is OverlayStripItem.ExpandedNoteItem -> VIEW_TYPE_EXPANDED
        is OverlayStripItem.EditingNoteItem -> VIEW_TYPE_EDITING
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = when (viewType) {
        VIEW_TYPE_COMPOSER -> ComposerViewHolder(OverlayComposerPressView(parent.context))
        VIEW_TYPE_EDITING -> EditingViewHolder(OverlayEditingNoteView(parent.context))
        VIEW_TYPE_EXPANDED -> ExpandedNoteViewHolder(OverlayExpandedNoteView(parent.context))
        else -> NoteViewHolder(SwipeableNoteView(parent.context))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is OverlayStripItem.ComposerEntryItem -> (holder as ComposerViewHolder).bind(item, listener)
            is OverlayStripItem.NoteStripItem -> (holder as NoteViewHolder).bind(item, listener)
            is OverlayStripItem.ExpandedNoteItem -> (holder as ExpandedNoteViewHolder).bind(item, listener)
            is OverlayStripItem.EditingNoteItem -> (holder as EditingViewHolder).bind(item, listener)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_COMPOSER_STATE)) {
            val item = items.getOrNull(position) as? OverlayStripItem.ComposerEntryItem
            if (holder is ComposerViewHolder && item != null) {
                holder.bind(item, listener)
                return
            }
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    private class ComposerViewHolder(
        private val view: OverlayComposerPressView,
    ) : RecyclerView.ViewHolder(view) {
        fun bind(item: OverlayStripItem.ComposerEntryItem, listener: Listener) {
            view.bind(item, listener)
        }
    }

    private class NoteViewHolder(
        private val view: SwipeableNoteView,
    ) : RecyclerView.ViewHolder(view) {
        fun bind(item: OverlayStripItem.NoteStripItem, listener: Listener) {
            view.bind(
                item.note,
                object : SwipeableNoteView.Listener {
                    override fun onClick(noteId: String) {
                        listener.onOpenNote(noteId)
                    }

                    override fun onToggleExpanded(noteId: String) {
                        listener.onToggleExpanded(noteId)
                    }

                    override fun onQuickAction(noteId: String, action: OverlayNoteQuickAction) {
                        if (action == OverlayNoteQuickAction.EDIT) {
                            listener.onEdit(noteId)
                        } else {
                            listener.onQuickAction(noteId, action)
                        }
                    }
                },
            )
        }
    }

    private class EditingViewHolder(
        private val view: OverlayEditingNoteView,
    ) : RecyclerView.ViewHolder(view) {
        fun bind(item: OverlayStripItem.EditingNoteItem, listener: Listener) {
            view.bind(item, listener)
        }

        fun focusInput() {
            view.focusInput()
        }
    }

    private class ExpandedNoteViewHolder(
        private val view: OverlayExpandedNoteView,
    ) : RecyclerView.ViewHolder(view) {
        fun bind(item: OverlayStripItem.ExpandedNoteItem, listener: Listener) {
            view.bind(item.note, listener)
        }
    }

    private companion object {
        const val VIEW_TYPE_COMPOSER = 0
        const val VIEW_TYPE_NOTE = 1
        const val VIEW_TYPE_EXPANDED = 2
        const val VIEW_TYPE_EDITING = 3
        const val PAYLOAD_COMPOSER_STATE = "payload_composer_state"
    }
}

internal class OverlayComposerPressView(
    context: Context,
) : FrameLayout(context) {

    private val titleText: TextView
    private val subtitleText: TextView
    private val categoryChip: TextView
    private val priorityChip: TextView
    private var currentListener: OverlayStripAdapter.Listener? = null
    private var isRecording = false
    private var longPressTriggered = false
    private var cancelOnRelease = false
    private var downX = 0f
    private var downY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val cancelSwipeThresholdPx = dpToPx(64).toFloat()
    private val longPressRunnable = Runnable {
        if (!isRecording) {
            longPressTriggered = true
            cancelOnRelease = false
            isRecording = true
            renderComposerState()
            Log.d(TAG, "ENTRY_LONG_PRESS_TRIGGERED")
            currentListener?.onStartRecordingFromEntry()
        }
    }

    private val micIcon: ImageView
    private val editIcon: ImageView

    init {
        layoutParams = RecyclerView.LayoutParams(
            RecyclerView.LayoutParams.MATCH_PARENT,
            dpToPx(56),
        ).apply {
            bottomMargin = dpToPx(8)
        }
        foreground = context.getDrawable(android.R.drawable.list_selector_background)
        background = roundedRect(0xFF375D50.toInt(), dpToPx(28).toFloat())
        elevation = dpToPx(4).toFloat()
        setPadding(dpToPx(14), dpToPx(8), dpToPx(14), dpToPx(8))
        isClickable = true

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

        micIcon = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            layoutParams = LinearLayout.LayoutParams(dpToPx(26), dpToPx(26))
            imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
        }

        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dpToPx(12)
                marginEnd = dpToPx(12)
            }
        }

        categoryChip = chip("便签").apply { visibility = View.GONE }
        priorityChip = chip("中").apply { visibility = View.GONE }

        titleText = TextView(context).apply {
            text = "快速记录"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        subtitleText = TextView(context).apply {
            text = "点击输入 · 长按录音"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(0xCCE8DFD1.toInt())
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        textColumn.addView(titleText)
        textColumn.addView(subtitleText)

        editIcon = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_menu_edit)
            layoutParams = LinearLayout.LayoutParams(dpToPx(22), dpToPx(22))
            imageTintList = android.content.res.ColorStateList.valueOf(0xCCFFFFFF.toInt())
        }

        root.addView(micIcon)
        root.addView(textColumn)
        root.addView(editIcon)
        addView(root)

        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    Log.d(TAG, "ENTRY_DOWN")
                    downX = event.x
                    downY = event.y
                    longPressTriggered = false
                    cancelOnRelease = false
                    parent?.requestDisallowInterceptTouchEvent(true)
                    removeCallbacks(longPressRunnable)
                    postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.x - downX
                    val deltaY = event.y - downY
                    if (!longPressTriggered &&
                        !isRecording &&
                        (kotlin.math.abs(deltaX) > touchSlop || kotlin.math.abs(deltaY) > touchSlop)
                    ) {
                        removeCallbacks(longPressRunnable)
                    }
                    if (longPressTriggered || isRecording) {
                        val horizontalDominant =
                            kotlin.math.abs(deltaX) > kotlin.math.abs(deltaY) &&
                                kotlin.math.abs(deltaX) > touchSlop
                        val shouldCancel = horizontalDominant && deltaX <= -cancelSwipeThresholdPx
                        if (cancelOnRelease != shouldCancel) {
                            cancelOnRelease = shouldCancel
                            renderComposerState()
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    removeCallbacks(longPressRunnable)
                    if (longPressTriggered || isRecording) {
                        val shouldCancel = cancelOnRelease
                        Log.d(TAG, if (shouldCancel) "ENTRY_UP_CANCEL" else "ENTRY_UP_STOP")
                        longPressTriggered = false
                        cancelOnRelease = false
                        isRecording = false
                        renderComposerState()
                        if (shouldCancel) {
                            currentListener?.onCancelRecordingFromEntry()
                        } else {
                            currentListener?.onStopRecordingFromEntry()
                        }
                    } else {
                        performClick()
                        currentListener?.onOpenComposer()
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    removeCallbacks(longPressRunnable)
                    if (longPressTriggered || isRecording) {
                        Log.d(TAG, "ENTRY_CANCEL_DISCARD")
                        longPressTriggered = false
                        cancelOnRelease = false
                        isRecording = false
                        renderComposerState()
                        currentListener?.onCancelRecordingFromEntry()
                        true
                    } else {
                        false
                    }
                }

                else -> false
            }
        }
    }

    fun bind(item: OverlayStripItem.ComposerEntryItem, listener: OverlayStripAdapter.Listener) {
        currentListener = listener
        isRecording = item.isRecording
        if (!item.isRecording) {
            longPressTriggered = false
            cancelOnRelease = false
        }
        categoryChip.text = item.category.label()
        priorityChip.text = item.priority.label()
        if (item.isRecording) {
            titleText.text = "正在录音"
            subtitleText.text = "松开结束并保存"
        } else {
            titleText.text = "快速记录"
            subtitleText.text = "点击输入，长按录音"
        }
        renderComposerState()
    }

    private fun renderComposerState() {
        when {
            cancelOnRelease && (longPressTriggered || isRecording) -> {
                background = roundedRect(0xFFC9656C.toInt(), dpToPx(28).toFloat())
                titleText.text = "松手取消"
                subtitleText.text = "左滑后松手丢弃录音"
                titleText.setTextColor(0xFFFFFFFF.toInt())
                subtitleText.setTextColor(0xCCFFE1E1.toInt())
            }

            longPressTriggered || isRecording -> {
                background = roundedRect(0xFF4F86C6.toInt(), dpToPx(28).toFloat())
                titleText.text = "正在录音"
                subtitleText.text = "松手结束并保存 · 左滑取消"
                titleText.setTextColor(0xFFFFFFFF.toInt())
                subtitleText.setTextColor(0xCCDCEAF9.toInt())
            }

            else -> {
                background = roundedRect(0xFF375D50.toInt(), dpToPx(28).toFloat())
                titleText.text = "快速记录"
                subtitleText.text = "点击输入 · 长按录音"
                titleText.setTextColor(0xFFFFFFFF.toInt())
                subtitleText.setTextColor(0xCCE8DFD1.toInt())
            }
        }
    }

    private fun chip(text: String): TextView =
        TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF33404C.toInt())
            background = roundedRect(0xFFFFFFFF.toInt(), dpToPx(10).toFloat())
            setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        }

    private fun roundedRect(color: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(color)
        }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "OverlayEntryCard"
    }
}

private class OverlayExpandedNoteView(
    context: Context,
) : FrameLayout(context) {

    private data class ExpandedPalette(
        val outerBackground: Int,
        val innerBackground: Int,
        val primaryText: Int,
        val secondaryText: Int,
        val subtleButtonBackground: Int,
        val subtleButtonText: Int,
        val filledButtonBackground: Int,
        val filledButtonText: Int,
    )

    private val titleText: TextView
    private val metaText: TextView
    private val bodyContainer: LinearLayout
    private val contentText: TextView
    private val originalToggleButton: TextView
    private val originalPanel: LinearLayout
    private val originalLabelText: TextView
    private val originalText: TextView
    private val collapseButton: TextView
    private val openButton: TextView
    private val editButton: TextView
    private val archiveButton: TextView
    private val trashButton: TextView
    private val scrollView: ScrollView
    private var currentNoteId: String? = null
    private var currentListener: OverlayStripAdapter.Listener? = null
    private var palette: ExpandedPalette = expandedPaletteFor(null)
    private var currentBodyContent: OverlayBodyContent = OverlayBodyContent("暂无更多内容", null)
    private var showOriginalContent = false

    init {
        layoutParams = RecyclerView.LayoutParams(
            RecyclerView.LayoutParams.MATCH_PARENT,
            RecyclerView.LayoutParams.WRAP_CONTENT,
        ).apply {
            bottomMargin = dpToPx(8)
        }
        elevation = dpToPx(3).toFloat()
        setPadding(dpToPx(10), dpToPx(10), dpToPx(10), dpToPx(10))
        addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (currentNoteId != null && (right - left) != (oldRight - oldLeft)) {
                post { applyMeasuredBodyHeight(notifyLayoutChange = false) }
            }
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        val titleColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }

        titleText = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
        }
        metaText = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dpToPx(4)
            }
        }
        collapseButton = actionButton(context, "收起", filled = false)
        openButton = actionButton(context, "打开", filled = true).apply {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = dpToPx(8)
            }
        }

        titleColumn.addView(titleText)
        titleColumn.addView(metaText)
        headerRow.addView(titleColumn)
        headerRow.addView(collapseButton)
        headerRow.addView(openButton)
        scrollView = ScrollView(context).apply {
            isFillViewport = true
            overScrollMode = ScrollView.OVER_SCROLL_IF_CONTENT_SCROLLS
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dpToPx(132)).apply {
                topMargin = dpToPx(10)
            }
            setOnTouchListener { view, event ->
                view.parent?.requestDisallowInterceptTouchEvent(
                    event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL,
                )
                false
            }
        }
        bodyContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        contentText = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setLineSpacing(0f, 1.15f)
            setPadding(dpToPx(10), dpToPx(10), dpToPx(10), dpToPx(10))
        }
        originalPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dpToPx(10)
            }
            setPadding(dpToPx(10), dpToPx(10), dpToPx(10), dpToPx(10))
        }
        originalLabelText = TextView(context).apply {
            text = "原文"
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        originalText = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setLineSpacing(0f, 1.15f)
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dpToPx(6)
            }
        }
        originalPanel.addView(originalLabelText)
        originalPanel.addView(originalText)
        bodyContainer.addView(contentText)
        bodyContainer.addView(originalPanel)
        scrollView.addView(bodyContainer)
        originalToggleButton = actionButton(context, "查看原文", filled = false).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dpToPx(8)
            }
        }

        /*
        val topActions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dpToPx(10)
            }
        }
        openButton = actionButton(context, "在主应用打开", filled = true).apply {
            setOnClickListener { currentNoteId?.let { noteId -> (parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(false); currentListener?.onOpenNote(noteId) } }
        }
        topActions.addView(openButton)
        */

        val bottomActions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dpToPx(8)
            }
        }
        editButton = actionButton(context, "编辑", filled = false)
        archiveButton = actionButton(context, "归档", filled = false)
        trashButton = actionButton(context, "删除", filled = false)
        editButton.layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        archiveButton.layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        trashButton.layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        listOf(editButton, archiveButton, trashButton).forEachIndexed { index, button ->
            if (index > 0) {
                (button.layoutParams as LinearLayout.LayoutParams).marginStart = dpToPx(6)
            }
            bottomActions.addView(button)
        }

        root.addView(headerRow)
        root.addView(scrollView)
        root.addView(originalToggleButton)
        root.addView(bottomActions)
        addView(root)
    }
    fun bind(note: Note, listener: OverlayStripAdapter.Listener) {
        currentListener = listener
        currentNoteId = note.id
        palette = resolvedExpandedPaletteFor(note)
        currentBodyContent = note.overlayBodyContent()
        showOriginalContent = false
        applyPalette()
        titleText.text = note.title.ifBlank { note.category.label() }
        collapseButton.setOnClickListener { listener.onToggleExpanded(note.id) }
        metaText.text = buildString {
            append(note.category.label())
            append(" · ")
            append(note.priority.label())
            if (note.source == NoteSource.VOICE) {
                append(" · 语音")
            }
            append(" · ")
            append(formatOverlayTime(note.overlayDisplayTimestamp()))
        }
        contentText.text = currentBodyContent.primaryText
        originalText.text = currentBodyContent.originalText.orEmpty()
        editButton.setOnClickListener { listener.onEdit(note.id) }
        archiveButton.setOnClickListener { listener.onArchive(note.id) }
        trashButton.setOnClickListener { listener.onTrash(note.id) }
        openButton.setOnClickListener { listener.onOpenNote(note.id) }
        originalToggleButton.setOnClickListener {
            showOriginalContent = !showOriginalContent
            renderOriginalContent()
            post { applyMeasuredBodyHeight() }
        }
        renderOriginalContent()
        post { applyMeasuredBodyHeight() }
        playCardEntranceAnimation()
    }

    private fun applyPalette() {
        background = roundedRect(palette.outerBackground, dpToPx(18).toFloat())
        titleText.setTextColor(palette.primaryText)
        metaText.setTextColor(palette.secondaryText)
        contentText.setTextColor(palette.primaryText)
        originalLabelText.setTextColor(palette.secondaryText)
        originalText.setTextColor(palette.primaryText)
        scrollView.background = roundedRect(palette.innerBackground, dpToPx(14).toFloat())
        contentText.background = roundedRect(palette.innerBackground, dpToPx(14).toFloat())
        originalPanel.background = roundedRect(palette.innerBackground, dpToPx(14).toFloat())
        styleActionButton(collapseButton, filled = false)
        styleActionButton(openButton, filled = true)
        styleActionButton(originalToggleButton, filled = false)
        styleActionButton(editButton, filled = false)
        styleActionButton(archiveButton, filled = false)
        styleActionButton(trashButton, filled = false)
    }

    private fun styleActionButton(button: TextView, filled: Boolean) {
        button.setTextColor(if (filled) palette.filledButtonText else palette.subtleButtonText)
        button.background = roundedRect(
            if (filled) palette.filledButtonBackground else palette.subtleButtonBackground,
            dpToPx(12).toFloat(),
        )
    }

    private fun renderOriginalContent() {
        val hasOriginal = !currentBodyContent.originalText.isNullOrBlank()
        originalToggleButton.visibility = if (hasOriginal) View.VISIBLE else View.GONE
        originalPanel.visibility = if (hasOriginal && showOriginalContent) View.VISIBLE else View.GONE
        originalToggleButton.text = if (showOriginalContent) "隐藏原文" else "查看原文"
    }

    private fun applyMeasuredBodyHeight(notifyLayoutChange: Boolean = true) {
        val availableWidth = (measuredWidth - paddingLeft - paddingRight).takeIf { it > dpToPx(180) }
            ?: (resources.displayMetrics.widthPixels * 0.62f).toInt()
        val targetHeight = context.measureOverlayExpandedBodyHeight(
            primaryText = currentBodyContent.primaryText,
            originalText = if (showOriginalContent) currentBodyContent.originalText else null,
            availableWidthPx = availableWidth,
        )
        val params = scrollView.layoutParams as LinearLayout.LayoutParams
        if (params.height != targetHeight) {
            params.height = targetHeight
            scrollView.layoutParams = params
            scrollView.requestLayout()
            requestLayout()
            if (notifyLayoutChange) {
                post { currentListener?.onExpandedContentLayoutChanged() }
            }
        }
    }

    private fun resolvedExpandedPaletteFor(note: Note?): ExpandedPalette = when (note?.overlayPaletteKey()) {
        null -> ExpandedPalette(
            outerBackground = 0xFFE6ECF3.toInt(),
            innerBackground = 0xFFF7F9FC.toInt(),
            primaryText = 0xFF18212B.toInt(),
            secondaryText = 0xFF61707E.toInt(),
            subtleButtonBackground = 0xFFFFFFFF.toInt(),
            subtleButtonText = 0xFF33404C.toInt(),
            filledButtonBackground = 0xFF4B91DE.toInt(),
            filledButtonText = 0xFFFFFFFF.toInt(),
        )
        OverlayPaletteKey.ROSE -> ExpandedPalette(
            outerBackground = 0xFFF1D9FF.toInt(),
            innerBackground = 0xFFFAF1FF.toInt(),
            primaryText = 0xFF4C2965.toInt(),
            secondaryText = 0xFF7D5B96.toInt(),
            subtleButtonBackground = 0xFFF6EBFF.toInt(),
            subtleButtonText = 0xFF5D3577.toInt(),
            filledButtonBackground = note.category.color(),
            filledButtonText = 0xFFFFFFFF.toInt(),
        )
        OverlayPaletteKey.AMBER -> ExpandedPalette(
            outerBackground = 0xFFFFE7BB.toInt(),
            innerBackground = 0xFFFFF4DB.toInt(),
            primaryText = 0xFF5B3C0B.toInt(),
            secondaryText = 0xFF8B6420.toInt(),
            subtleButtonBackground = 0xFFFFF0CF.toInt(),
            subtleButtonText = 0xFF6A4811.toInt(),
            filledButtonBackground = note.category.color(),
            filledButtonText = 0xFFFFFFFF.toInt(),
        )
        OverlayPaletteKey.SKY -> ExpandedPalette(
            outerBackground = 0xFFD8EBFF.toInt(),
            innerBackground = 0xFFEFF6FF.toInt(),
            primaryText = 0xFF163C66.toInt(),
            secondaryText = 0xFF4B6F98.toInt(),
            subtleButtonBackground = 0xFFE4F1FF.toInt(),
            subtleButtonText = 0xFF1F4E80.toInt(),
            filledButtonBackground = note.category.color(),
            filledButtonText = 0xFFFFFFFF.toInt(),
        )
        OverlayPaletteKey.ARCHIVE -> ExpandedPalette(
            outerBackground = 0xFFD4DEE7.toInt(),
            innerBackground = 0xFFF0F4F8.toInt(),
            primaryText = 0xFF22303D.toInt(),
            secondaryText = 0xFF566A79.toInt(),
            subtleButtonBackground = 0xFFFFFFFF.toInt(),
            subtleButtonText = 0xFF33404C.toInt(),
            filledButtonBackground = note.category.color(),
            filledButtonText = 0xFFFFFFFF.toInt(),
        )
        OverlayPaletteKey.TRASH -> ExpandedPalette(
            outerBackground = 0xFFF3D7D7.toInt(),
            innerBackground = 0xFFFFF1F1.toInt(),
            primaryText = 0xFF642A2A.toInt(),
            secondaryText = 0xFF8D5757.toInt(),
            subtleButtonBackground = 0xFFFFE2E2.toInt(),
            subtleButtonText = 0xFF7C3A3A.toInt(),
            filledButtonBackground = note.category.color(),
            filledButtonText = 0xFFFFFFFF.toInt(),
        )
        OverlayPaletteKey.SAGE -> ExpandedPalette(
            outerBackground = 0xFFDDEFE2.toInt(),
            innerBackground = 0xFFF3FAF5.toInt(),
            primaryText = 0xFF173227.toInt(),
            secondaryText = 0xFF496557.toInt(),
            subtleButtonBackground = 0xFFE8F4EC.toInt(),
            subtleButtonText = 0xFF2B4A3A.toInt(),
            filledButtonBackground = note.category.color(),
            filledButtonText = 0xFFFFFFFF.toInt(),
        )
    }

    private fun expandedPaletteFor(note: Note?): ExpandedPalette = when {
        note == null -> ExpandedPalette(
            outerBackground = 0xFFE6ECF3.toInt(),
            innerBackground = 0xFFF7F9FC.toInt(),
            primaryText = 0xFF18212B.toInt(),
            secondaryText = 0xFF61707E.toInt(),
            subtleButtonBackground = 0xFFFFFFFF.toInt(),
            subtleButtonText = 0xFF33404C.toInt(),
            filledButtonBackground = 0xFF4B91DE.toInt(),
            filledButtonText = 0xFFFFFFFF.toInt(),
        )
        note.source == NoteSource.VOICE -> ExpandedPalette(
            outerBackground = 0xFF6173E8.toInt(),
            innerBackground = 0x26FFFFFF,
            primaryText = 0xFFF9FBFF.toInt(),
            secondaryText = 0xFFD9E0FF.toInt(),
            subtleButtonBackground = 0x24FFFFFF,
            subtleButtonText = 0xFFFFFFFF.toInt(),
            filledButtonBackground = 0xFFFFFFFF.toInt(),
            filledButtonText = 0xFF455AD4.toInt(),
        )
        note.priority == NotePriority.URGENT || note.category == NoteCategory.REMINDER || note.colorToken == NoteColorToken.ROSE -> ExpandedPalette(
            outerBackground = 0xFFF1D9FF.toInt(),
            innerBackground = 0xFFFAF1FF.toInt(),
            primaryText = 0xFF4C2965.toInt(),
            secondaryText = 0xFF7D5B96.toInt(),
            subtleButtonBackground = 0xFFF6EBFF.toInt(),
            subtleButtonText = 0xFF5D3577.toInt(),
            filledButtonBackground = note.category.color(),
            filledButtonText = 0xFFFFFFFF.toInt(),
        )
        note.category == NoteCategory.TODO || note.colorToken == NoteColorToken.AMBER -> ExpandedPalette(
            outerBackground = 0xFFFFE7BB.toInt(),
            innerBackground = 0xFFFFF4DB.toInt(),
            primaryText = 0xFF5B3C0B.toInt(),
            secondaryText = 0xFF8B6420.toInt(),
            subtleButtonBackground = 0xFFFFF0CF.toInt(),
            subtleButtonText = 0xFF6A4811.toInt(),
            filledButtonBackground = note.category.color(),
            filledButtonText = 0xFFFFFFFF.toInt(),
        )
        note.category == NoteCategory.TASK || note.colorToken == NoteColorToken.SKY -> ExpandedPalette(
            outerBackground = 0xFFD8EBFF.toInt(),
            innerBackground = 0xFFEFF6FF.toInt(),
            primaryText = 0xFF163C66.toInt(),
            secondaryText = 0xFF4B6F98.toInt(),
            subtleButtonBackground = 0xFFE4F1FF.toInt(),
            subtleButtonText = 0xFF1F4E80.toInt(),
            filledButtonBackground = note.category.color(),
            filledButtonText = 0xFFFFFFFF.toInt(),
        )
        else -> ExpandedPalette(
            outerBackground = 0xFFDDEFE2.toInt(),
            innerBackground = 0xFFF3FAF5.toInt(),
            primaryText = 0xFF173227.toInt(),
            secondaryText = 0xFF496557.toInt(),
            subtleButtonBackground = 0xFFE8F4EC.toInt(),
            subtleButtonText = 0xFF2B4A3A.toInt(),
            filledButtonBackground = note.category.color(),
            filledButtonText = 0xFFFFFFFF.toInt(),
        )
    }

    private fun actionButton(context: Context, label: String, filled: Boolean): TextView =
        TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dpToPx(10), dpToPx(7), dpToPx(10), dpToPx(7))
            setTextColor(if (filled) 0xFFFFFFFF.toInt() else 0xFF3B4957.toInt())
            background = roundedRect(
                if (filled) 0xFF4B91DE.toInt() else 0xFFE8EDF4.toInt(),
                dpToPx(12).toFloat(),
            )
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        }

    private fun roundedRect(color: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(color)
        }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
}

private class OverlayEditingNoteView(
    context: Context,
) : FrameLayout(context) {

    private data class EditingPalette(
        val outerBackground: Int,
        val inputBackground: Int,
        val primaryText: Int,
        val secondaryText: Int,
        val subtleButtonBackground: Int,
        val subtleButtonText: Int,
        val filledButtonBackground: Int,
        val filledButtonText: Int,
    )

    private val titleText: TextView
    private val contentInput: EditText
    private val tagInput: EditText
    private val typeButtons: Map<NoteCategory, TextView>
    private val priorityButtons: Map<NotePriority, TextView>
    private val cancelButton: TextView
    private val saveButton: TextView
    private var watcher: TextWatcher? = null
    private var tagWatcher: TextWatcher? = null
    private var currentListener: OverlayStripAdapter.Listener? = null
    private var paletteSeedNote: Note? = null
    private var currentCategory: NoteCategory = NoteCategory.NOTE
    private var currentPriority: NotePriority = NotePriority.MEDIUM
    private var palette: EditingPalette = editingPaletteFor(null, NoteCategory.NOTE)

    init {
        layoutParams = RecyclerView.LayoutParams(
            RecyclerView.LayoutParams.MATCH_PARENT,
            RecyclerView.LayoutParams.WRAP_CONTENT,
        ).apply {
            bottomMargin = dpToPx(8)
        }
        background = roundedRect(0xFFF3FAF5.toInt(), dpToPx(16).toFloat())
        elevation = dpToPx(2).toFloat()
        setPadding(dpToPx(10), dpToPx(10), dpToPx(10), dpToPx(10))

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        titleText = TextView(context).apply {
            text = "编辑便签"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF18212B.toInt())
        }

        contentInput = EditText(context).apply {
            minLines = 3
            maxLines = 10
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            hint = "输入便签内容"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(0xFF18212B.toInt())
            background = roundedRect(0xFFF3FAF5.toInt(), dpToPx(12).toFloat())
            setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8))
            isFocusable = true
            isFocusableInTouchMode = true
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dpToPx(8)
            }
            setOnTouchListener { view, event ->
                view.parent?.requestDisallowInterceptTouchEvent(
                    event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL,
                )
                false
            }
        }

        val typeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dpToPx(8)
            }
        }
        typeButtons = NoteCategory.entries.associateWith { category ->
            chip(context, category.label(), selected = category == currentCategory).also { chip ->
                chip.setOnClickListener {
                    currentCategory = category
                    renderCategorySelection()
                    currentListener?.onEditingCategoryChanged(category)
                }
                typeRow.addView(chip)
            }
        }

        val priorityRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dpToPx(8)
            }
        }
        priorityButtons = NotePriority.entries.associateWith { priority ->
            chip(context, priority.label(), selected = priority == currentPriority).also { chip ->
                chip.setOnClickListener {
                    currentPriority = priority
                    renderPrioritySelection()
                    currentListener?.onEditingPriorityChanged(priority)
                }
                priorityRow.addView(chip)
            }
        }

        val tagLabel = TextView(context).apply {
            text = "标签"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(0xFF61707E.toInt())
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dpToPx(8)
            }
        }
        tagInput = EditText(context).apply {
            hint = "逗号分隔，如：工作, 日程"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(0xFF18212B.toInt())
            background = roundedRect(0xFFF3FAF5.toInt(), dpToPx(12).toFloat())
            setPadding(dpToPx(10), dpToPx(6), dpToPx(10), dpToPx(6))
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
            isSingleLine = true
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dpToPx(4)
            }
            setOnTouchListener { view, event ->
                view.parent?.requestDisallowInterceptTouchEvent(
                    event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL,
                )
                false
            }
        }

        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dpToPx(10)
            }
        }
        cancelButton = actionButton(context, "取消", filled = false).apply {
            setOnClickListener { currentListener?.onCancelEditing() }
        }
        saveButton = actionButton(context, "保存", filled = true).apply {
            setOnClickListener { currentListener?.onSaveEditing() }
        }
        cancelButton.layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        saveButton.layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dpToPx(6)
        }
        actionRow.addView(cancelButton)
        actionRow.addView(saveButton)

        root.addView(titleText)
        root.addView(contentInput)
        root.addView(typeRow)
        root.addView(priorityRow)
        root.addView(tagLabel)
        root.addView(tagInput)
        root.addView(actionRow)
        addView(root)
    }

    fun bind(item: OverlayStripItem.EditingNoteItem, listener: OverlayStripAdapter.Listener) {
        currentListener = listener
        currentCategory = item.category
        currentPriority = item.priority
        paletteSeedNote = item.note
        titleText.text = "编辑${item.category.label()}"
        renderCategorySelection()
        renderPrioritySelection()
        applyPalette()

        watcher?.let(contentInput::removeTextChangedListener)
        if (contentInput.text.toString() != item.draft) {
            contentInput.setText(item.draft)
            contentInput.setSelection(contentInput.text.length)
        }
        watcher = contentInput.addTextChangedListener { text ->
            currentListener?.onEditingContentChanged(text?.toString().orEmpty())
        }
        tagWatcher?.let(tagInput::removeTextChangedListener)
        val tagText = item.tags.joinToString(", ")
        if (tagInput.text.toString() != tagText) {
            tagInput.setText(tagText)
        }
        tagWatcher = tagInput.addTextChangedListener { text ->
            val parsed = text?.toString().orEmpty()
                .split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }
            currentListener?.onEditingTagsChanged(parsed)
        }
        playCardEntranceAnimation()
    }

    fun focusInput() {
        contentInput.post {
            contentInput.requestFocus()
            contentInput.setSelection(contentInput.text.length)
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(contentInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun renderCategorySelection() {
        typeButtons.forEach { (category, view) ->
            view.setTextColor(if (category == currentCategory) 0xFFFFFFFF.toInt() else palette.secondaryText)
            view.background = roundedRect(
                if (category == currentCategory) category.color() else palette.inputBackground,
                dpToPx(10).toFloat(),
            )
        }
        applyPalette()
    }

    private fun renderPrioritySelection() {
        priorityButtons.forEach { (priority, view) ->
            view.setTextColor(if (priority == currentPriority) 0xFFFFFFFF.toInt() else palette.secondaryText)
            view.background = roundedRect(
                if (priority == currentPriority) priority.color() else palette.inputBackground,
                dpToPx(10).toFloat(),
            )
        }
    }

    private fun applyPalette() {
        palette = resolvedEditingPaletteFor(paletteSeedNote, currentCategory)
        background = roundedRect(palette.outerBackground, dpToPx(16).toFloat())
        titleText.setTextColor(palette.primaryText)
        contentInput.setTextColor(palette.primaryText)
        contentInput.setHintTextColor(palette.secondaryText)
        contentInput.background = roundedRect(palette.inputBackground, dpToPx(12).toFloat())
        tagInput.setTextColor(palette.primaryText)
        tagInput.setHintTextColor(palette.secondaryText)
        tagInput.background = roundedRect(palette.inputBackground, dpToPx(12).toFloat())
        styleActionButton(cancelButton, filled = false)
        styleActionButton(saveButton, filled = true)
    }

    private fun styleActionButton(button: TextView, filled: Boolean) {
        button.setTextColor(if (filled) palette.filledButtonText else palette.subtleButtonText)
        button.background = roundedRect(
            if (filled) palette.filledButtonBackground else palette.subtleButtonBackground,
            dpToPx(12).toFloat(),
        )
    }

    private fun resolvedEditingPaletteFor(note: Note?, category: NoteCategory): EditingPalette {
        val paletteKey = when {
            note == null -> when (category) {
                NoteCategory.NOTE -> OverlayPaletteKey.SAGE
                NoteCategory.TODO -> OverlayPaletteKey.AMBER
                NoteCategory.TASK -> OverlayPaletteKey.SKY
                NoteCategory.REMINDER -> OverlayPaletteKey.ROSE
            }
            category == NoteCategory.REMINDER -> OverlayPaletteKey.ROSE
            category == NoteCategory.TODO -> OverlayPaletteKey.AMBER
            category == NoteCategory.TASK -> OverlayPaletteKey.SKY
            category == NoteCategory.NOTE && note.overlayPaletteKey() != OverlayPaletteKey.ARCHIVE && note.overlayPaletteKey() != OverlayPaletteKey.TRASH -> OverlayPaletteKey.SAGE
            else -> note.overlayPaletteKey()
        }
        return when (paletteKey) {
            OverlayPaletteKey.ROSE -> EditingPalette(
                outerBackground = 0xFFF1D9FF.toInt(),
                inputBackground = 0xFFFAF1FF.toInt(),
                primaryText = 0xFF4C2965.toInt(),
                secondaryText = 0xFF7D5B96.toInt(),
                subtleButtonBackground = 0xFFF6EBFF.toInt(),
                subtleButtonText = 0xFF5D3577.toInt(),
                filledButtonBackground = category.color(),
                filledButtonText = 0xFFFFFFFF.toInt(),
            )
            OverlayPaletteKey.AMBER -> EditingPalette(
                outerBackground = 0xFFFFE7BB.toInt(),
                inputBackground = 0xFFFFF4DB.toInt(),
                primaryText = 0xFF5B3C0B.toInt(),
                secondaryText = 0xFF8B6420.toInt(),
                subtleButtonBackground = 0xFFFFF0CF.toInt(),
                subtleButtonText = 0xFF6A4811.toInt(),
                filledButtonBackground = category.color(),
                filledButtonText = 0xFFFFFFFF.toInt(),
            )
            OverlayPaletteKey.SKY -> EditingPalette(
                outerBackground = 0xFFD8EBFF.toInt(),
                inputBackground = 0xFFEFF6FF.toInt(),
                primaryText = 0xFF163C66.toInt(),
                secondaryText = 0xFF4B6F98.toInt(),
                subtleButtonBackground = 0xFFE4F1FF.toInt(),
                subtleButtonText = 0xFF1F4E80.toInt(),
                filledButtonBackground = category.color(),
                filledButtonText = 0xFFFFFFFF.toInt(),
            )
            OverlayPaletteKey.ARCHIVE -> EditingPalette(
                outerBackground = 0xFFD4DEE7.toInt(),
                inputBackground = 0xFFF0F4F8.toInt(),
                primaryText = 0xFF22303D.toInt(),
                secondaryText = 0xFF566A79.toInt(),
                subtleButtonBackground = 0xFFFFFFFF.toInt(),
                subtleButtonText = 0xFF33404C.toInt(),
                filledButtonBackground = category.color(),
                filledButtonText = 0xFFFFFFFF.toInt(),
            )
            OverlayPaletteKey.TRASH -> EditingPalette(
                outerBackground = 0xFFF3D7D7.toInt(),
                inputBackground = 0xFFFFF1F1.toInt(),
                primaryText = 0xFF642A2A.toInt(),
                secondaryText = 0xFF8D5757.toInt(),
                subtleButtonBackground = 0xFFFFE2E2.toInt(),
                subtleButtonText = 0xFF7C3A3A.toInt(),
                filledButtonBackground = category.color(),
                filledButtonText = 0xFFFFFFFF.toInt(),
            )
            OverlayPaletteKey.SAGE -> EditingPalette(
                outerBackground = 0xFFDDEFE2.toInt(),
                inputBackground = 0xFFF3FAF5.toInt(),
                primaryText = 0xFF173227.toInt(),
                secondaryText = 0xFF496557.toInt(),
                subtleButtonBackground = 0xFFE8F4EC.toInt(),
                subtleButtonText = 0xFF2B4A3A.toInt(),
                filledButtonBackground = category.color(),
                filledButtonText = 0xFFFFFFFF.toInt(),
            )
        }
    }

    private fun editingPaletteFor(note: Note?, category: NoteCategory): EditingPalette = when {
        note?.source == NoteSource.VOICE -> EditingPalette(
            outerBackground = 0xFF6173E8.toInt(),
            inputBackground = 0x33FFFFFF,
            primaryText = 0xFFF9FBFF.toInt(),
            secondaryText = 0xFFD9E0FF.toInt(),
            subtleButtonBackground = 0x24FFFFFF,
            subtleButtonText = 0xFFFFFFFF.toInt(),
            filledButtonBackground = 0xFFFFFFFF.toInt(),
            filledButtonText = 0xFF455AD4.toInt(),
        )
        note?.priority == NotePriority.URGENT || note?.category == NoteCategory.REMINDER || note?.colorToken == NoteColorToken.ROSE || category == NoteCategory.REMINDER -> EditingPalette(
            outerBackground = 0xFFF1D9FF.toInt(),
            inputBackground = 0xFFFAF1FF.toInt(),
            primaryText = 0xFF4C2965.toInt(),
            secondaryText = 0xFF7D5B96.toInt(),
            subtleButtonBackground = 0xFFF6EBFF.toInt(),
            subtleButtonText = 0xFF5D3577.toInt(),
            filledButtonBackground = category.color(),
            filledButtonText = 0xFFFFFFFF.toInt(),
        )
        note?.category == NoteCategory.TODO || note?.colorToken == NoteColorToken.AMBER || category == NoteCategory.TODO -> EditingPalette(
            outerBackground = 0xFFFFE7BB.toInt(),
            inputBackground = 0xFFFFF4DB.toInt(),
            primaryText = 0xFF5B3C0B.toInt(),
            secondaryText = 0xFF8B6420.toInt(),
            subtleButtonBackground = 0xFFFFF0CF.toInt(),
            subtleButtonText = 0xFF6A4811.toInt(),
            filledButtonBackground = category.color(),
            filledButtonText = 0xFFFFFFFF.toInt(),
        )
        note?.category == NoteCategory.TASK || note?.colorToken == NoteColorToken.SKY || category == NoteCategory.TASK -> EditingPalette(
            outerBackground = 0xFFD8EBFF.toInt(),
            inputBackground = 0xFFEFF6FF.toInt(),
            primaryText = 0xFF163C66.toInt(),
            secondaryText = 0xFF4B6F98.toInt(),
            subtleButtonBackground = 0xFFE4F1FF.toInt(),
            subtleButtonText = 0xFF1F4E80.toInt(),
            filledButtonBackground = category.color(),
            filledButtonText = 0xFFFFFFFF.toInt(),
        )
        else -> EditingPalette(
            outerBackground = 0xFFDDEFE2.toInt(),
            inputBackground = 0xFFF3FAF5.toInt(),
            primaryText = 0xFF173227.toInt(),
            secondaryText = 0xFF496557.toInt(),
            subtleButtonBackground = 0xFFE8F4EC.toInt(),
            subtleButtonText = 0xFF2B4A3A.toInt(),
            filledButtonBackground = category.color(),
            filledButtonText = 0xFFFFFFFF.toInt(),
        )
    }

    private fun chip(context: Context, label: String, selected: Boolean): TextView =
        TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dpToPx(7), dpToPx(5), dpToPx(7), dpToPx(5))
            background = roundedRect(
                if (selected) NoteCategory.NOTE.color() else 0xFFEAF0F6.toInt(),
                dpToPx(10).toFloat(),
            )
            setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFF34404A.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dpToPx(2)
                marginEnd = dpToPx(2)
            }
        }

    private fun actionButton(context: Context, label: String, filled: Boolean): TextView =
        TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dpToPx(12), dpToPx(7), dpToPx(12), dpToPx(7))
            setTextColor(if (filled) 0xFFFFFFFF.toInt() else 0xFF3D4B57.toInt())
            background = roundedRect(
                if (filled) 0xFF4B91DE.toInt() else 0xFFE5EBF2.toInt(),
                dpToPx(12).toFloat(),
            )
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        }

    private fun roundedRect(color: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(color)
        }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
}

private fun NoteCategory.label(): String = when (this) {
    NoteCategory.NOTE -> "便签"
    NoteCategory.TODO -> "待办"
    NoteCategory.TASK -> "任务"
    NoteCategory.REMINDER -> "提醒"
}

private fun NotePriority.label(): String = when (this) {
    NotePriority.LOW -> "低"
    NotePriority.MEDIUM -> "中"
    NotePriority.HIGH -> "高"
    NotePriority.URGENT -> "紧急"
}

private fun formatOverlayTime(timeMillis: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timeMillis))

private fun NoteCategory.color(): Int = when (this) {
    NoteCategory.NOTE -> 0xFF7AA488.toInt()
    NoteCategory.TODO -> 0xFFE0A635.toInt()
    NoteCategory.TASK -> 0xFF4B91DE.toInt()
    NoteCategory.REMINDER -> 0xFF9F61E1.toInt()
}

private fun NotePriority.color(): Int = when (this) {
    NotePriority.LOW -> 0xFF6B7280.toInt()
    NotePriority.MEDIUM -> 0xFF4E7CD5.toInt()
    NotePriority.HIGH -> 0xFFE09C32.toInt()
    NotePriority.URGENT -> 0xFFE05555.toInt()
}

private fun View.playCardEntranceAnimation() {
    animate().cancel()
    alpha = 0f
    translationY = dpFromView(10)
    scaleX = 0.96f
    scaleY = 0.96f
    animate()
        .alpha(1f)
        .translationY(0f)
        .scaleX(1f)
        .scaleY(1f)
        .setDuration(220L)
        .setInterpolator(OvershootInterpolator(0.75f))
        .start()
}

private fun View.dpFromView(dp: Int): Float = dp * resources.displayMetrics.density
