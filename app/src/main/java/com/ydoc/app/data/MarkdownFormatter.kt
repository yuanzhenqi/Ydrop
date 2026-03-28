package com.ydoc.app.data

import com.ydoc.app.model.Note
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MarkdownFormatter {
    private val fileDateFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private val frontmatterDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun fileName(note: Note): String = "${fileDateFormat.format(Date(note.createdAt))}-${note.id}.md"

    fun render(note: Note): String = buildString {
        appendLine("---")
        appendLine("id: ${note.id}")
        appendLine("createdAt: ${frontmatterDateFormat.format(Date(note.createdAt))}")
        appendLine("updatedAt: ${frontmatterDateFormat.format(Date(note.updatedAt))}")
        appendLine("source: ${note.source.name.lowercase()}")
        appendLine("category: ${note.category.name.lowercase()}")
        appendLine("priority: ${note.priority.name.lowercase()}")
        appendLine("color: ${note.colorToken.name.lowercase()}")
        appendLine("status: ${note.status.name.lowercase()}")
        note.audioPath?.let { appendLine("audioPath: \"${it.replace("\\", "/")}\"") }
        appendLine("title: \"${note.title.replace("\"", "\\\"")}\"")
        appendLine("---")
        appendLine()
        appendLine(note.content.trim())
    }
}
