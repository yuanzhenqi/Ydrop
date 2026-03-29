package com.ydoc.app.data

import com.ydoc.app.model.Note
import com.ydoc.app.model.NoteCategory
import com.ydoc.app.model.NotePriority
import com.ydoc.app.model.NoteSource
import com.ydoc.app.model.TranscriptionStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MarkdownFormatter {
    private val fileDateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.CHINA).apply {
        timeZone = TimeZone.getDefault()
    }

    private val frontmatterDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private val displayDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).apply {
        timeZone = TimeZone.getDefault()
    }

    /** Human-readable filename: 2026-03-28_14-35_待办_标题摘要_a1b2c3.md */
    fun fileName(note: Note): String {
        val date = fileDateFormat.format(Date(note.createdAt))
        val category = categoryLabel(note.category)
        val titleSlug = note.title
            .replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
            .take(20)
            .trimEnd('_')
        val shortId = note.id.takeLast(6)
        return "${date}_${category}_${titleSlug}_${shortId}.md"
    }

    fun render(note: Note): String = buildString {
        val createdDisplay = displayDateFormat.format(Date(note.createdAt))
        val updatedDisplay = displayDateFormat.format(Date(note.updatedAt))
        val categoryLabel = categoryLabel(note.category)
        val priorityLabel = priorityLabel(note.priority)

        // --- Frontmatter ---
        appendLine("---")
        appendLine("id: ${note.id}")
        appendLine("createdAt: ${frontmatterDateFormat.format(Date(note.createdAt))}")
        appendLine("updatedAt: ${frontmatterDateFormat.format(Date(note.updatedAt))}")
        appendLine("source: ${sourceLabel(note.source)}")
        appendLine("category: $categoryLabel")
        appendLine("priority: $priorityLabel")
        appendLine("status: ${note.status.name.lowercase()}")
        appendLine("transcriptionStatus: ${note.transcriptionStatus.name.lowercase()}")
        note.audioPath?.let { appendLine("audioPath: \"${it.replace("\\\\", "/")}\"") }
        note.relayUrl?.let { appendLine("relayUrl: \"$it\"") }
        note.syncError?.let { appendLine("syncError: \"${it.take(120)}\"") }
        appendLine("---")
        appendLine()

        // --- Title ---
        appendLine("# ${note.title}")
        appendLine()

        // --- Meta ---
        appendLine("> 类型：$categoryLabel　优先级：$priorityLabel　来源：${sourceLabel(note.source)}")
        appendLine("> 创建：$createdDisplay　最后更新：$updatedDisplay")
        appendLine()

        // --- Content ---
        appendLine("## 记录内容")
        appendLine()
        if (note.source == NoteSource.VOICE && !note.transcript.isNullOrBlank()) {
            appendLine("**AI 转写文本：**")
            appendLine()
            appendLine(note.transcript.trim())
            appendLine()
            if (note.content.isNotBlank() && note.content != "语音记录，等待后续转写。") {
                appendLine("**原始内容：**")
                appendLine()
                appendLine(note.content.trim())
            }
        } else {
            appendLine(note.content.trim())
        }
    }

    private fun categoryLabel(category: NoteCategory): String = when (category) {
        NoteCategory.NOTE -> "普通"
        NoteCategory.TODO -> "待办"
        NoteCategory.TASK -> "任务"
        NoteCategory.REMINDER -> "提醒"
    }

    private fun priorityLabel(priority: NotePriority): String = when (priority) {
        NotePriority.LOW -> "低"
        NotePriority.MEDIUM -> "中"
        NotePriority.HIGH -> "高"
        NotePriority.URGENT -> "紧急"
    }

    private fun sourceLabel(source: NoteSource): String = when (source) {
        NoteSource.TEXT -> "文字"
        NoteSource.VOICE -> "语音"
    }
}
