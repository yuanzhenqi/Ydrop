package com.ydoc.app.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.setPadding
import androidx.recyclerview.widget.RecyclerView
import com.ydoc.app.R
import com.ydoc.app.model.Note
import com.ydoc.app.model.NoteCategory
import com.ydoc.app.model.NotePriority
import com.ydoc.app.model.NoteSource
import com.ydoc.app.model.TranscriptionStatus

enum class OverlayNoteQuickAction {
    EDIT,
    COPY,
    CONVERT_TO_TODO,
    COMPLETE,
    REMIND,
    SORT,
    SNOOZE,
    PIN,
    PLAY,
    TRANSCRIBE,
    SHARE,
}

class SwipeableNoteView(
    context: Context,
) : FrameLayout(context) {

    interface Listener {
        fun onClick(noteId: String)
        fun onQuickAction(noteId: String, action: OverlayNoteQuickAction)
    }

    private data class Palette(
        val background: Int,
        val primaryText: Int,
        val secondaryText: Int,
        val chipBackground: Int,
        val chipText: Int,
        val actionBackground: Int,
        val actionTint: Int,
    )

    private var noteId: String = ""
    private var listener: Listener? = null

    private val cardView: LinearLayout
    private val topRow: LinearLayout
    private val typeChip: TextView
    private val pinIcon: AppCompatImageView
    private val timeText: TextView
    private val summaryText: TextView
    private val actionRow: LinearLayout

    init {
        layoutParams = RecyclerView.LayoutParams(
            RecyclerView.LayoutParams.MATCH_PARENT,
            RecyclerView.LayoutParams.WRAP_CONTENT,
        ).apply {
            bottomMargin = dpToPx(8)
        }

        cardView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = true
            clipToPadding = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            elevation = dpToPx(3).toFloat()
            setPadding(dpToPx(10))
            layoutParams = LayoutParams(dpToPx(188), LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
        }

        topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        typeChip = chipView()
        pinIcon = AppCompatImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(14), dpToPx(14)).apply {
                marginEnd = dpToPx(6)
            }
            setImageResource(R.drawable.ic_overlay_pin)
            visibility = View.GONE
        }
        timeText = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.END
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        }
        topRow.addView(typeChip)
        topRow.addView(pinIcon)
        topRow.addView(timeText)

        summaryText = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dpToPx(6)
            }
        }

        actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dpToPx(6)
            }
        }

        cardView.addView(topRow)
        cardView.addView(summaryText)
        cardView.addView(actionRow)
        addView(cardView)
    }

    fun bind(note: Note, listener: Listener) {
        this.noteId = note.id
        this.listener = listener

        val palette = paletteFor(note)
        applyPalette(palette)
        bindHeader(note, palette)
        bindSummary(note)
        bindActions(note, palette)

        setOnClickListener { listener.onClick(note.id) }
    }

    private fun bindHeader(note: Note, palette: Palette) {
        typeChip.text = chipLabel(note)
        timeText.text = DateUtils.getRelativeTimeSpanString(
            note.updatedAt,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE,
        )
        timeText.setTextColor(palette.secondaryText)
        if (note.pinned) {
            pinIcon.visibility = View.VISIBLE
            pinIcon.imageTintList = ColorStateList.valueOf(palette.secondaryText)
        } else {
            pinIcon.visibility = View.GONE
        }
    }

    private fun bindSummary(note: Note) {
        summaryText.text = summaryFor(note)
    }

    private fun bindActions(note: Note, palette: Palette) {
        actionRow.removeAllViews()
        quickActionsFor(note).forEachIndexed { index, action ->
            val button = iconButton(
                iconRes = iconFor(action),
                contentDescription = contentDescriptionFor(action),
                palette = palette,
            ).apply {
                setOnClickListener { listener?.onQuickAction(note.id, action) }
            }
            if (index > 0) {
                (button.layoutParams as LinearLayout.LayoutParams).marginStart = dpToPx(4)
            }
            actionRow.addView(button)
        }
    }

    private fun applyPalette(palette: Palette) {
        cardView.background = roundedRect(palette.background, dpToPx(16).toFloat())
        typeChip.background = roundedRect(palette.chipBackground, dpToPx(10).toFloat())
        typeChip.setTextColor(palette.chipText)
        summaryText.setTextColor(palette.primaryText)
    }

    private fun quickActionsFor(note: Note): List<OverlayNoteQuickAction> =
        if (note.source == NoteSource.VOICE) {
            listOf(
                OverlayNoteQuickAction.PLAY,
                OverlayNoteQuickAction.TRANSCRIBE,
                OverlayNoteQuickAction.SHARE,
            )
        } else {
            when (note.category) {
                NoteCategory.NOTE -> listOf(
                    OverlayNoteQuickAction.EDIT,
                    OverlayNoteQuickAction.COPY,
                    OverlayNoteQuickAction.CONVERT_TO_TODO,
                )
                NoteCategory.TODO,
                NoteCategory.TASK,
                -> listOf(
                    OverlayNoteQuickAction.COMPLETE,
                    OverlayNoteQuickAction.REMIND,
                    OverlayNoteQuickAction.SORT,
                )
                NoteCategory.REMINDER -> listOf(
                    OverlayNoteQuickAction.COMPLETE,
                    OverlayNoteQuickAction.SNOOZE,
                    OverlayNoteQuickAction.PIN,
                )
            }
        }

    private fun chipLabel(note: Note): String = when {
        note.source == NoteSource.VOICE -> "语音"
        note.category == NoteCategory.NOTE -> "便签"
        note.category == NoteCategory.TODO -> "待办"
        note.category == NoteCategory.TASK -> "任务"
        else -> "提醒"
    }

    private fun summaryFor(note: Note): String {
        if (note.source == NoteSource.VOICE) {
            return when (note.transcriptionStatus) {
                TranscriptionStatus.NOT_STARTED -> "等待转写"
                TranscriptionStatus.UPLOADING -> "正在上传音频..."
                TranscriptionStatus.TRANSCRIBING -> "正在转写..."
                TranscriptionStatus.DONE -> compact(note.transcript ?: note.content)
                TranscriptionStatus.FAILED -> note.transcriptionError?.take(40) ?: "转写失败"
            }
        }
        return compact(note.content)
    }

    private fun compact(value: String): String =
        value.replace("\n", "  ").replace(Regex("\\s+"), " ").trim().ifBlank { "暂无内容" }

    private fun iconFor(action: OverlayNoteQuickAction): Int = when (action) {
        OverlayNoteQuickAction.EDIT -> R.drawable.ic_overlay_edit
        OverlayNoteQuickAction.COPY -> R.drawable.ic_overlay_copy
        OverlayNoteQuickAction.CONVERT_TO_TODO -> R.drawable.ic_overlay_todo
        OverlayNoteQuickAction.COMPLETE -> R.drawable.ic_overlay_complete
        OverlayNoteQuickAction.REMIND -> R.drawable.ic_overlay_remind
        OverlayNoteQuickAction.SORT -> R.drawable.ic_overlay_sort
        OverlayNoteQuickAction.SNOOZE -> R.drawable.ic_overlay_later
        OverlayNoteQuickAction.PIN -> R.drawable.ic_overlay_pin
        OverlayNoteQuickAction.PLAY -> R.drawable.ic_overlay_play
        OverlayNoteQuickAction.TRANSCRIBE -> R.drawable.ic_overlay_transcribe
        OverlayNoteQuickAction.SHARE -> R.drawable.ic_overlay_share
    }

    private fun contentDescriptionFor(action: OverlayNoteQuickAction): String = when (action) {
        OverlayNoteQuickAction.EDIT -> "编辑便签"
        OverlayNoteQuickAction.COPY -> "复制便签"
        OverlayNoteQuickAction.CONVERT_TO_TODO -> "转成待办"
        OverlayNoteQuickAction.COMPLETE -> "完成并归档"
        OverlayNoteQuickAction.REMIND -> "转成提醒"
        OverlayNoteQuickAction.SORT -> "整理任务"
        OverlayNoteQuickAction.SNOOZE -> "稍后提醒"
        OverlayNoteQuickAction.PIN -> "固定便签"
        OverlayNoteQuickAction.PLAY -> "播放音频"
        OverlayNoteQuickAction.TRANSCRIBE -> "转写音频"
        OverlayNoteQuickAction.SHARE -> "分享便签"
    }

    private fun iconButton(
        iconRes: Int,
        contentDescription: String,
        palette: Palette,
    ): AppCompatImageButton =
        AppCompatImageButton(context).apply {
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(palette.actionTint)
            background = roundedRect(palette.actionBackground, dpToPx(10).toFloat())
            this.contentDescription = contentDescription
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dpToPx(5))
            layoutParams = LinearLayout.LayoutParams(dpToPx(26), dpToPx(26))
        }

    private fun chipView(): TextView =
        TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dpToPx(6)
            }
        }

    private fun roundedRect(color: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(color)
        }

    private fun paletteFor(note: Note): Palette = when {
        note.source == NoteSource.VOICE -> Palette(
            background = 0xFF6173E8.toInt(),
            primaryText = 0xFFF9FBFF.toInt(),
            secondaryText = 0xFFD9E0FF.toInt(),
            chipBackground = 0x33FFFFFF,
            chipText = 0xFFF7FAFF.toInt(),
            actionBackground = 0x24FFFFFF,
            actionTint = 0xFFFFFFFF.toInt(),
        )
        note.category == NoteCategory.TODO -> Palette(
            background = 0xFFF2BE57.toInt(),
            primaryText = 0xFF50360A.toInt(),
            secondaryText = 0xFF745114.toInt(),
            chipBackground = 0x24FFFFFF,
            chipText = 0xFF50360A.toInt(),
            actionBackground = 0x24FFFFFF,
            actionTint = 0xFF50360A.toInt(),
        )
        note.category == NoteCategory.TASK -> Palette(
            background = 0xFF5BA5F8.toInt(),
            primaryText = 0xFFF9FBFF.toInt(),
            secondaryText = 0xFFD8E7FF.toInt(),
            chipBackground = 0x26FFFFFF,
            chipText = 0xFFFFFFFF.toInt(),
            actionBackground = 0x24FFFFFF,
            actionTint = 0xFFFFFFFF.toInt(),
        )
        note.category == NoteCategory.REMINDER || note.priority == NotePriority.URGENT -> Palette(
            background = 0xFFAF70F0.toInt(),
            primaryText = 0xFFFDFCFF.toInt(),
            secondaryText = 0xFFEBDFFF.toInt(),
            chipBackground = 0x26FFFFFF,
            chipText = 0xFFFFFFFF.toInt(),
            actionBackground = 0x22FFFFFF,
            actionTint = 0xFFFFFFFF.toInt(),
        )
        else -> Palette(
            background = 0xFFA6D3B0.toInt(),
            primaryText = 0xFF173227.toInt(),
            secondaryText = 0xFF355647.toInt(),
            chipBackground = 0x22FFFFFF,
            chipText = 0xFF173227.toInt(),
            actionBackground = 0x24FFFFFF,
            actionTint = 0xFF173227.toInt(),
        )
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
}
