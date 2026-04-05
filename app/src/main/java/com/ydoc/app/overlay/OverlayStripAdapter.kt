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
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.RecyclerView
import com.ydoc.app.model.NoteCategory
import com.ydoc.app.model.NotePriority

class OverlayStripAdapter(
    private val listener: Listener,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    interface Listener {
        fun onOpenComposer()
        fun onStartRecordingFromEntry()
        fun onStopRecordingFromEntry()
        fun onCancelRecordingFromEntry()
        fun onOpenNote(noteId: String)
        fun onArchive(noteId: String)
        fun onTrash(noteId: String)
        fun onEdit(noteId: String)
        fun onEditingContentChanged(value: String)
        fun onEditingCategoryChanged(category: NoteCategory)
        fun onEditingPriorityChanged(priority: NotePriority)
        fun onSaveEditing()
        fun onCancelEditing()
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

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is OverlayStripItem.ComposerEntryItem -> VIEW_TYPE_COMPOSER
        is OverlayStripItem.NoteStripItem -> VIEW_TYPE_NOTE
        is OverlayStripItem.EditingNoteItem -> VIEW_TYPE_EDITING
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = when (viewType) {
        VIEW_TYPE_COMPOSER -> ComposerViewHolder(OverlayComposerPressView(parent.context))
        VIEW_TYPE_EDITING -> EditingViewHolder(OverlayEditingNoteView(parent.context))
        else -> NoteViewHolder(SwipeableNoteView(parent.context))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is OverlayStripItem.ComposerEntryItem -> (holder as ComposerViewHolder).bind(item, listener)
            is OverlayStripItem.NoteStripItem -> (holder as NoteViewHolder).bind(item, listener)
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
    }

    private companion object {
        const val VIEW_TYPE_COMPOSER = 0
        const val VIEW_TYPE_NOTE = 1
        const val VIEW_TYPE_EDITING = 2
        const val PAYLOAD_COMPOSER_STATE = "payload_composer_state"
    }
}

private class OverlayComposerPressView(
    context: Context,
) : FrameLayout(context) {

    private val titleText: TextView
    private val subtitleText: TextView
    private val categoryChip: TextView
    private val priorityChip: TextView
    private var currentListener: OverlayStripAdapter.Listener? = null
    private var isRecording = false
    private var longPressTriggered = false
    private var downX = 0f
    private var downY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressRunnable = Runnable {
        if (!isRecording) {
            longPressTriggered = true
            Log.d(TAG, "ENTRY_LONG_PRESS_TRIGGERED")
            currentListener?.onStartRecordingFromEntry()
        }
    }

    init {
        layoutParams = RecyclerView.LayoutParams(
            RecyclerView.LayoutParams.MATCH_PARENT,
            RecyclerView.LayoutParams.WRAP_CONTENT,
        ).apply {
            bottomMargin = dpToPx(8)
        }
        foreground = context.getDrawable(android.R.drawable.list_selector_background)
        background = roundedRect(0xFFF2F4F8.toInt(), dpToPx(16).toFloat())
        elevation = dpToPx(3).toFloat()
        setPadding(dpToPx(10), dpToPx(10), dpToPx(10), dpToPx(10))
        isClickable = true

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(dpToPx(188), LayoutParams.WRAP_CONTENT)
        }

        val chipRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        categoryChip = chip("便签")
        priorityChip = chip("中").apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = dpToPx(6)
        }
        chipRow.addView(categoryChip)
        chipRow.addView(priorityChip)

        titleText = TextView(context).apply {
            text = "快速记录"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF17212B.toInt())
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dpToPx(6)
            }
        }

        subtitleText = TextView(context).apply {
            text = "点击输入，长按录音"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(0xFF5E6B78.toInt())
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dpToPx(3)
            }
        }

        root.addView(chipRow)
        root.addView(titleText)
        root.addView(subtitleText)
        addView(root)

        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    Log.d(TAG, "ENTRY_DOWN")
                    downX = event.x
                    downY = event.y
                    longPressTriggered = false
                    parent?.requestDisallowInterceptTouchEvent(true)
                    removeCallbacks(longPressRunnable)
                    postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!longPressTriggered &&
                        (kotlin.math.abs(event.x - downX) > touchSlop || kotlin.math.abs(event.y - downY) > touchSlop)
                    ) {
                        removeCallbacks(longPressRunnable)
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    removeCallbacks(longPressRunnable)
                    if (longPressTriggered || isRecording) {
                        Log.d(TAG, "ENTRY_UP_STOP")
                        longPressTriggered = false
                        currentListener?.onStopRecordingFromEntry()
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
        categoryChip.text = item.category.label()
        priorityChip.text = item.priority.label()
        if (item.isRecording) {
            titleText.text = "正在录音"
            subtitleText.text = "松开结束并保存"
        } else {
            titleText.text = "快速记录"
            subtitleText.text = "点击输入，长按录音"
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

private class OverlayEditingNoteView(
    context: Context,
) : FrameLayout(context) {

    private val titleText: TextView
    private val contentInput: EditText
    private val typeButtons: Map<NoteCategory, TextView>
    private val priorityButtons: Map<NotePriority, TextView>
    private val cancelButton: TextView
    private val saveButton: TextView
    private var watcher: TextWatcher? = null
    private var currentListener: OverlayStripAdapter.Listener? = null
    private var currentCategory: NoteCategory = NoteCategory.NOTE
    private var currentPriority: NotePriority = NotePriority.MEDIUM

    init {
        layoutParams = RecyclerView.LayoutParams(
            RecyclerView.LayoutParams.MATCH_PARENT,
            RecyclerView.LayoutParams.WRAP_CONTENT,
        ).apply {
            bottomMargin = dpToPx(8)
        }
        background = roundedRect(0xFFF7F9FC.toInt(), dpToPx(16).toFloat())
        elevation = dpToPx(4).toFloat()
        setPadding(dpToPx(10), dpToPx(10), dpToPx(10), dpToPx(10))

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(dpToPx(188), LayoutParams.WRAP_CONTENT)
        }

        titleText = TextView(context).apply {
            text = "编辑便签"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF18212B.toInt())
        }

        contentInput = EditText(context).apply {
            minLines = 3
            maxLines = 5
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            hint = "输入便签内容"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(0xFF18212B.toInt())
            background = roundedRect(0xFFFFFFFF.toInt(), dpToPx(12).toFloat())
            setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8))
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

        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
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
        actionRow.addView(cancelButton)
        actionRow.addView(saveButton.apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = dpToPx(6)
        })

        root.addView(titleText)
        root.addView(contentInput)
        root.addView(typeRow)
        root.addView(priorityRow)
        root.addView(actionRow)
        addView(root)
    }

    fun bind(item: OverlayStripItem.EditingNoteItem, listener: OverlayStripAdapter.Listener) {
        currentListener = listener
        currentCategory = item.category
        currentPriority = item.priority
        titleText.text = "编辑${item.category.label()}"
        renderCategorySelection()
        renderPrioritySelection()

        watcher?.let(contentInput::removeTextChangedListener)
        if (contentInput.text.toString() != item.draft) {
            contentInput.setText(item.draft)
            contentInput.setSelection(contentInput.text.length)
        }
        watcher = contentInput.addTextChangedListener { text ->
            currentListener?.onEditingContentChanged(text?.toString().orEmpty())
        }
    }

    private fun renderCategorySelection() {
        typeButtons.forEach { (category, view) ->
            view.setTextColor(if (category == currentCategory) 0xFFFFFFFF.toInt() else 0xFF34404A.toInt())
            view.background = roundedRect(
                if (category == currentCategory) category.color() else 0xFFEAF0F6.toInt(),
                dpToPx(10).toFloat(),
            )
        }
    }

    private fun renderPrioritySelection() {
        priorityButtons.forEach { (priority, view) ->
            view.setTextColor(if (priority == currentPriority) 0xFFFFFFFF.toInt() else 0xFF4F5E6B.toInt())
            view.background = roundedRect(
                if (priority == currentPriority) priority.color() else 0xFFEAF0F6.toInt(),
                dpToPx(10).toFloat(),
            )
        }
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
