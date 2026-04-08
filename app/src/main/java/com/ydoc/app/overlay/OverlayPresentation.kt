package com.ydoc.app.overlay

import android.content.Context
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import com.ydoc.app.model.Note
import com.ydoc.app.model.NoteSource
import kotlin.math.roundToInt

data class OverlayBodyContent(
    val primaryText: String,
    val originalText: String?,
)

enum class OverlayPaletteKey {
    SAGE,
    AMBER,
    SKY,
    ROSE,
    ARCHIVE,
    TRASH,
}

fun Note.overlayBodyContent(): OverlayBodyContent {
    val normalizedContent = content.trim().ifBlank { title.trim() }.ifBlank { "暂无更多内容" }
    val normalizedOriginal = originalContent
        ?.trim()
        ?.takeIf { it.isNotBlank() && it != normalizedContent }

    if (normalizedOriginal != null) {
        return OverlayBodyContent(
            primaryText = normalizedContent,
            originalText = normalizedOriginal,
        )
    }

    if (source == NoteSource.VOICE) {
        val spokenText = transcript?.trim().takeIf { !it.isNullOrBlank() } ?: normalizedContent
        return OverlayBodyContent(
            primaryText = spokenText,
            originalText = null,
        )
    }

    return OverlayBodyContent(
        primaryText = normalizedContent,
        originalText = null,
    )
}

fun Note.overlayEditingSeedText(): String = overlayBodyContent().primaryText

fun Note.overlayPaletteKey(): OverlayPaletteKey = when {
    isTrashed -> OverlayPaletteKey.TRASH
    isArchived -> OverlayPaletteKey.ARCHIVE
    priority.name == "URGENT" || category.name == "REMINDER" || colorToken.name == "ROSE" -> OverlayPaletteKey.ROSE
    category.name == "TODO" || colorToken.name == "AMBER" -> OverlayPaletteKey.AMBER
    category.name == "TASK" || colorToken.name == "SKY" -> OverlayPaletteKey.SKY
    else -> OverlayPaletteKey.SAGE
}

fun Note.overlayDisplayTimestamp(): Long = createdAt

fun Context.measureOverlayRailWidth(
    primaryText: String,
    originalText: String? = null,
    minWidthDp: Int = 248,
    maxWidthPx: Int,
    targetLineCount: Int = 8,
    candidateStepDp: Int = 12,
    textSizeSp: Float = 13f,
): Int {
    val density = resources.displayMetrics.density
    val minWidthPx = (minWidthDp * density).roundToInt()
    if (maxWidthPx <= minWidthPx) return maxWidthPx.coerceAtLeast(minWidthPx)

    val measuringText = primaryText
        .trim()
        .ifBlank { originalText.orEmpty().trim() }
        .ifBlank { return minWidthPx }

    val textPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            textSizeSp,
            resources.displayMetrics,
        )
    }

    val stepPx = (candidateStepDp * density).roundToInt().coerceAtLeast(1)
    val textHorizontalPaddingPx = (40 * density).roundToInt()
    val minimumTextWidthPx = (120 * density).roundToInt()

    var candidate = minWidthPx
    while (candidate <= maxWidthPx) {
        val textWidth = (candidate - textHorizontalPaddingPx).coerceAtLeast(minimumTextWidthPx)
        val layout = StaticLayout.Builder
            .obtain(measuringText, 0, measuringText.length, textPaint, textWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(0f, 1.15f)
            .build()
        if (layout.lineCount <= targetLineCount) {
            return candidate
        }
        candidate += stepPx
    }

    return maxWidthPx
}

fun Context.measureOverlayExpandedBodyHeight(
    primaryText: String,
    originalText: String? = null,
    availableWidthPx: Int,
    minBodyHeightDp: Int = 104,
    maxBodyHeightDp: Int = 320,
    textSizeSp: Float = 13f,
    labelTextSizeSp: Float = 11f,
): Int {
    val density = resources.displayMetrics.density
    val minBodyHeightPx = (minBodyHeightDp * density).roundToInt()
    val maxBodyHeightPx = minOf(
        (maxBodyHeightDp * density).roundToInt(),
        (resources.displayMetrics.heightPixels * 0.36f).roundToInt(),
    ).coerceAtLeast(minBodyHeightPx)
    val horizontalPaddingPx = (40 * density).roundToInt()
    val minimumTextWidthPx = (120 * density).roundToInt()
    val textWidth = (availableWidthPx - horizontalPaddingPx).coerceAtLeast(minimumTextWidthPx)

    val bodyPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            textSizeSp,
            resources.displayMetrics,
        )
    }
    val labelPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            labelTextSizeSp,
            resources.displayMetrics,
        )
    }

    val normalizedPrimary = primaryText.trim().ifBlank { "暂无更多内容" }
    val contentHeight = measureOverlayTextHeight(
        text = normalizedPrimary,
        textPaint = bodyPaint,
        widthPx = textWidth,
    ) + (20 * density).roundToInt()

    val originalHeight = originalText
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { original ->
            measureOverlayTextHeight(
                text = original,
                textPaint = labelPaint,
                widthPx = textWidth,
            ) +
                (6 * density).roundToInt() +
                measureOverlayTextHeight(
                    text = original,
                    textPaint = bodyPaint,
                    widthPx = textWidth,
                ) +
                (30 * density).roundToInt()
        } ?: 0

    return (contentHeight + originalHeight).coerceIn(minBodyHeightPx, maxBodyHeightPx)
}

private fun measureOverlayTextHeight(
    text: String,
    textPaint: TextPaint,
    widthPx: Int,
): Int {
    val normalized = text.trim().ifBlank { " " }
    return StaticLayout.Builder
        .obtain(normalized, 0, normalized.length, textPaint, widthPx)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setIncludePad(false)
        .setLineSpacing(0f, 1.15f)
        .build()
        .height
}
