package com.ydoc.app.data

import com.ydoc.app.model.Note
import com.ydoc.app.model.NoteCategory
import com.ydoc.app.model.NoteColorToken
import com.ydoc.app.model.NotePriority
import com.ydoc.app.model.NoteSource
import com.ydoc.app.model.NoteStatus
import com.ydoc.app.model.TranscriptionStatus
import com.ydoc.app.model.defaultColorFor
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

    fun extractIdFromFileName(fileName: String): String? {
        val name = fileName.removeSuffix(".md")
        val lastUnderscore = name.lastIndexOf('_')
        if (lastUnderscore < 0) return null
        val shortId = name.substring(lastUnderscore + 1)
        return if (shortId.length == 6) shortId else null
    }

    fun render(note: Note): String = buildString {
        val createdDisplay = displayDateFormat.format(Date(note.createdAt))
        val updatedDisplay = displayDateFormat.format(Date(note.updatedAt))
        val categoryLabel = categoryLabel(note.category)
        val priorityLabel = priorityLabel(note.priority)

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

        appendLine("# ${note.title}")
        appendLine()

        appendLine("> 类型：$categoryLabel　优先级：$priorityLabel　来源：${sourceLabel(note.source)}")
        appendLine("> 创建：$createdDisplay　最后更新：$updatedDisplay")
        appendLine()

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

    fun extractId(content: String): String? {
        val frontmatter = parseFrontmatter(content) ?: return null
        return frontmatter["id"]
    }

    fun parseFromMarkdown(content: String, remotePath: String): Note? {
        val frontmatter = parseFrontmatter(content) ?: return null
        val id = frontmatter["id"] ?: return null
        val createdAt = parseTimestamp(frontmatter["createdAt"]) ?: return null
        val updatedAt = parseTimestamp(frontmatter["updatedAt"]) ?: return null
        val source = parseSource(frontmatter["source"])
        val category = parseCategory(frontmatter["category"])
        val priority = parsePriority(frontmatter["priority"])
        val title = extractTitle(content)
        val body = extractBody(content)
        val isVoice = source == NoteSource.VOICE
        val transcript = if (isVoice) extractTranscript(body) else null
        val noteContent = if (isVoice && transcript != null) transcript else body

        return Note(
            id = id,
            title = title,
            content = noteContent,
            source = source,
            category = category,
            priority = priority,
            colorToken = defaultColorFor(category, priority),
            status = NoteStatus.SYNCED,
            createdAt = createdAt,
            updatedAt = updatedAt,
            lastSyncedAt = System.currentTimeMillis(),
            audioPath = frontmatter["audioPath"],
            audioFormat = if (frontmatter["audioPath"] != null) "mp4" else null,
            relayFileId = null,
            relayUrl = frontmatter["relayUrl"],
            relayExpiresAt = null,
            transcript = transcript,
            transcriptionStatus = parseTranscriptionStatus(frontmatter["transcriptionStatus"]),
            transcriptionError = null,
            transcriptionRequestId = null,
            transcriptionUpdatedAt = null,
            syncError = null,
            pinned = false,
            remotePath = remotePath,
            lastPulledAt = System.currentTimeMillis(),
        )
    }

    private fun parseFrontmatter(content: String): Map<String, String>? {
        val trimmed = content.trimStart()
        if (!trimmed.startsWith("---")) return null
        val end = trimmed.indexOf("---", 3)
        if (end < 0) return null
        val body = trimmed.substring(3, end).trim()
        val map = mutableMapOf<String, String>()
        body.lines().forEach { line ->
            val idx = line.indexOf(':')
            if (idx > 0) {
                val key = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim().removeSurrounding("\"")
                if (key.isNotBlank()) map[key] = value
            }
        }
        return map.ifEmpty { null }
    }

    private fun parseTimestamp(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return runCatching { frontmatterDateFormat.parse(value)?.time }.getOrNull()
    }

    private fun parseSource(value: String?): NoteSource = when (value?.trim()?.lowercase()) {
        "语音" -> NoteSource.VOICE
        else -> NoteSource.TEXT
    }

    private fun parseCategory(value: String?): NoteCategory = when (value?.trim()) {
        "待办" -> NoteCategory.TODO
        "任务" -> NoteCategory.TASK
        "提醒" -> NoteCategory.REMINDER
        else -> NoteCategory.NOTE
    }

    private fun parsePriority(value: String?): NotePriority = when (value?.trim()) {
        "低" -> NotePriority.LOW
        "高" -> NotePriority.HIGH
        "紧急" -> NotePriority.URGENT
        else -> NotePriority.MEDIUM
    }

    private fun parseTranscriptionStatus(value: String?): TranscriptionStatus = runCatching {
        TranscriptionStatus.valueOf(value?.trim()?.uppercase() ?: "NOT_STARTED")
    }.getOrDefault(TranscriptionStatus.NOT_STARTED)

    private fun extractTitle(content: String): String {
        val afterFrontmatter = skipFrontmatter(content)
        val lines = afterFrontmatter.trimStart().lines()
        val titleLine = lines.firstOrNull { it.startsWith("# ") } ?: return "Imported note"
        return titleLine.removePrefix("# ").trim()
    }

    private fun extractBody(content: String): String {
        val afterFrontmatter = skipFrontmatter(content)
        val lines = afterFrontmatter.trimStart().lines()
        var pastTitle = false
        var pastMeta = false
        val bodyLines = mutableListOf<String>()
        for (line in lines) {
            if (!pastTitle) {
                if (line.startsWith("# ")) pastTitle = true
                continue
            }
            if (!pastMeta) {
                if (line.startsWith("> ")) continue
                if (line.isBlank() && !pastMeta) { pastMeta = true; continue }
                pastMeta = true
            }
            if (line.startsWith("## 记录内容") || line.isBlank() && bodyLines.isEmpty()) continue
            bodyLines.add(line)
        }
        return bodyLines.joinToString("\n").trim()
    }

    private fun extractTranscript(body: String): String? {
        val marker = "**AI 转写文本：**"
        val idx = body.indexOf(marker)
        if (idx < 0) return null
        val after = body.substring(idx + marker.length).trim()
        val endMarkers = listOf("**原始内容：**", "## ")
        var end = after.length
        for (m in endMarkers) {
            val i = after.indexOf(m)
            if (i > 0 && i < end) end = i
        }
        return after.substring(0, end).trim().ifBlank { null }
    }

    private fun skipFrontmatter(content: String): String {
        val trimmed = content.trimStart()
        if (!trimmed.startsWith("---")) return content
        val end = trimmed.indexOf("---", 3)
        return if (end < 0) content else trimmed.substring(end + 3)
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
