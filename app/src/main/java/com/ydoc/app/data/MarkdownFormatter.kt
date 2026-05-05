package com.ydoc.app.data

import com.ydoc.app.model.Note
import com.ydoc.app.model.NoteAttachment
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

class MarkdownFormatter {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
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
        val source = sourceLabel(note.source)
        val titleSlug = note.title
            .replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
            .take(20)
            .trimEnd('_')
        val shortId = note.id.takeLast(6)
        return "${date}_${source}_${titleSlug}_${shortId}.md"
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
        appendLine("archived: ${note.isArchived}")
        if (note.tags.isNotEmpty()) appendLine("tags: ${note.tags.joinToString(",")}")
        appendLine("status: ${note.status.name.lowercase()}")
        appendLine("transcriptionStatus: ${note.transcriptionStatus.name.lowercase()}")
        note.audioPath?.let { appendLine("audioPath: \"${it.replace("\\\\", "/")}\"") }
        note.relayUrl?.let { appendLine("relayUrl: \"$it\"") }
        note.syncError?.let { appendLine("syncError: \"${it.take(120)}\"") }
        // attachments 跨端同步（A+ 方案）：仅带 remoteUrl 非空的项；ocrText 太大不带；
        // description 截 600 字。Web 端从 frontmatter 解出来后能直接看到 Android 上传的图。
        val attachmentsLine = serializeAttachmentsForFrontmatter(note.attachments)
        if (attachmentsLine.isNotEmpty()) appendLine("attachments: $attachmentsLine")
        appendLine("---")
        appendLine()

        appendLine("# ${note.title}")
        appendLine()

        appendLine("> 类型：$categoryLabel　优先级：$priorityLabel　来源：${sourceLabel(note.source)}")
        appendLine("> 创建：$createdDisplay　最后更新：$updatedDisplay")
        appendLine()

        appendLine("## 记录内容")
        appendLine()
        if (note.source == NoteSource.VOICE && !note.transcript.isNullOrBlank() && note.content != note.transcript) {
            appendLine(note.content.trim())
        } else if (note.source == NoteSource.VOICE && !note.transcript.isNullOrBlank()) {
            appendLine(note.transcript.trim())
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
        val isArchived = parseArchived(frontmatter["archived"], remotePath)
        val tags = frontmatter["tags"]?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
        val attachments = parseAttachmentsFromFrontmatter(frontmatter["attachments"])
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
            isArchived = isArchived,
            archivedAt = if (isArchived) updatedAt else null,
            isTrashed = false,
            trashedAt = null,
            tags = tags,
            attachments = attachments,
        )
    }

    /** 把 attachments 序列化成单行 JSON 字符串，可直接拼到 frontmatter 行。
     *  跳过没 remoteUrl 的项（纯本地附件跨同步意义不大）。 */
    private fun serializeAttachmentsForFrontmatter(attachments: List<NoteAttachment>): String {
        val items = attachments.mapNotNull { att ->
            val url = att.remoteUrl?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val parsedStruct = runCatching { json.parseToJsonElement(att.aiStructuredJson).jsonObject }.getOrNull()
            val keywords = parsedStruct?.get("keywords")?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
            val actionable = parsedStruct?.get("actionable_items")?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
            val dates = parsedStruct?.get("dates")?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
            buildJsonObject {
                put("id", JsonPrimitive(att.id))
                put("type", JsonPrimitive(att.type))
                put("remoteUrl", JsonPrimitive(url))
                if (att.aiDescription.isNotBlank()) put("description", JsonPrimitive(att.aiDescription.take(600)))
                if (keywords.isNotEmpty()) put("keywords", buildJsonArray { keywords.take(10).forEach { add(JsonPrimitive(it)) } })
                if (actionable.isNotEmpty()) put("actionableItems", buildJsonArray { actionable.take(10).forEach { add(JsonPrimitive(it)) } })
                if (dates.isNotEmpty()) put("dates", buildJsonArray { dates.take(10).forEach { add(JsonPrimitive(it)) } })
                put("createdAt", JsonPrimitive(att.createdAt))
            }
        }
        if (items.isEmpty()) return ""
        return json.encodeToString(JsonArray.serializer(), JsonArray(items))
    }

    /** 从 frontmatter 单行 JSON 解出 attachments；localPath 留空（拉下来的图本地没文件）。 */
    private fun parseAttachmentsFromFrontmatter(value: String?): List<NoteAttachment> {
        if (value.isNullOrBlank()) return emptyList()
        val arr = runCatching { json.parseToJsonElement(value).jsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { el ->
            val obj = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val remoteUrl = obj["remoteUrl"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: "IMAGE"
            val description = obj["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val keywords = obj["keywords"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
            val actionable = obj["actionableItems"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
            val dates = obj["dates"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
            val createdAt = obj["createdAt"]?.jsonPrimitive?.longOrNull ?: 0L
            // 重新装回 aiStructuredJson 字段，保持本地数据形态一致
            val structJson = buildJsonObject {
                put("keywords", buildJsonArray { keywords.forEach { add(JsonPrimitive(it)) } })
                put("actionable_items", buildJsonArray { actionable.forEach { add(JsonPrimitive(it)) } })
                put("dates", buildJsonArray { dates.forEach { add(JsonPrimitive(it)) } })
            }
            NoteAttachment(
                id = id,
                type = type,
                localPath = "",  // 远端拉下来的没有本地文件，UI 用 remoteUrl 加载（NoteAttachmentRow 已处理 fallback）
                publicUri = null,
                remoteUrl = remoteUrl,
                aiDescription = description,
                aiStructuredJson = json.encodeToString(JsonObject.serializer(), structJson),
                analyzedAt = if (description.isNotBlank() || keywords.isNotEmpty()) System.currentTimeMillis() else 0L,
                createdAt = createdAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
            )
        }
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

    private fun parseArchived(value: String?, remotePath: String): Boolean {
        val fromFrontmatter = when (value?.trim()?.lowercase()) {
            "true", "1", "yes" -> true
            "false", "0", "no" -> false
            else -> null
        }
        if (fromFrontmatter != null) return fromFrontmatter
        return remotePath.split('/').any { it.equals("archive", ignoreCase = true) }
    }

    private fun extractTitle(content: String): String {
        val afterFrontmatter = skipFrontmatter(content)
        val lines = afterFrontmatter.trimStart().lines()
        val titleLine = lines.firstOrNull { it.startsWith("# ") } ?: return "Imported note"
        return titleLine.removePrefix("# ").trim()
    }

    private fun extractBody(content: String): String {
        val afterFrontmatter = skipFrontmatter(content)
        val lines = afterFrontmatter.trimStart().lines()
        val bodyLines = mutableListOf<String>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("# ") || line.startsWith("## ")) { i++; continue }
            if (line.startsWith("> ")) { i++; continue }
            if (line.startsWith("**AI") || line.startsWith("**原始内容")) { i++; continue }
            if (line.trim() == "---") { i++; continue }
            if (line.isBlank() && bodyLines.isEmpty()) { i++; continue }
            break
        }
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("# ") || line.startsWith("## ")) { i++; continue }
            if (line.startsWith("> ")) { i++; continue }
            if (line.startsWith("**AI") || line.startsWith("**原始内容")) { i++; continue }
            if (line.trim() == "---") { i++; continue }
            if (line.isBlank() && bodyLines.isEmpty()) { i++; continue }
            bodyLines.add(line)
            i++
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
