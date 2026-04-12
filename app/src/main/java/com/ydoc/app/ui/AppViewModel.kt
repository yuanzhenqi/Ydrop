package com.ydoc.app.ui

import android.Manifest
import android.provider.AlarmClock
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ydoc.app.data.AppContainer
import com.ydoc.app.model.AiConfig
import com.ydoc.app.model.AiEndpointMode
import com.ydoc.app.model.AiRunTrigger
import com.ydoc.app.model.AiSuggestion
import com.ydoc.app.model.AudioPlaybackUiState
import com.ydoc.app.model.Note
import com.ydoc.app.model.NoteCategory
import com.ydoc.app.model.NotePriority
import com.ydoc.app.model.OverlayConfig
import com.ydoc.app.model.ReminderCandidate
import com.ydoc.app.model.ReminderDeliveryTarget
import com.ydoc.app.model.ReminderEntry
import com.ydoc.app.model.ReminderSource
import com.ydoc.app.model.RecordingState
import com.ydoc.app.model.RecordingUiState
import com.ydoc.app.model.SyncSettingsState
import com.ydoc.app.model.SyncTarget
import com.ydoc.app.model.SyncType
import com.ydoc.app.model.VolcengineConfig
import com.ydoc.app.model.WebDavConfig
import com.ydoc.app.model.defaultColorFor
import com.ydoc.app.recording.RecordingService
import com.ydoc.app.reminder.ReminderScheduleMode
import com.ydoc.app.sync.BidirectionalSyncResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar

data class CaptureDraft(
    val content: String = "",
    val category: NoteCategory = NoteCategory.NOTE,
    val priority: NotePriority = NotePriority.MEDIUM,
    val tags: List<String> = emptyList(),
)

data class EditDraft(
    val noteId: String,
    val content: String,
    val category: NoteCategory,
    val priority: NotePriority,
    val tags: List<String> = emptyList(),
)

enum class NoteListSection {
    INBOX,
    CALENDAR,
    ARCHIVE,
    TRASH,
}

data class AppUiState(
    val draft: CaptureDraft = CaptureDraft(),
    val captureExpanded: Boolean = false,
    val notes: List<Note> = emptyList(),
    val archivedNotes: List<Note> = emptyList(),
    val trashedNotes: List<Note> = emptyList(),
    val reminders: List<ReminderEntry> = emptyList(),
    val aiSuggestions: Map<String, AiSuggestion> = emptyMap(),
    val currentSection: NoteListSection = NoteListSection.INBOX,
    val syncTargets: List<SyncTarget> = emptyList(),
    val isSaving: Boolean = false,
    val isSyncing: Boolean = false,
    val message: String? = null,
    val syncHint: String = "启用 WebDAV 自动同步后，新的记录会实时推送到你的 NAS。",
    val settings: SyncSettingsState = SyncSettingsState(),
    val recording: RecordingUiState = RecordingUiState(),
    val requiresMicrophonePermission: Boolean = false,
    val editingNote: EditDraft? = null,
    val pendingQuickRecord: Boolean = false,
    val audioPlayback: AudioPlaybackUiState = AudioPlaybackUiState(),
    val tagFilter: Set<String> = emptySet(),
    val searchQuery: String = "",
    val suggestedTags: List<String> = emptyList(),
)

class AppViewModel(
    application: Application,
    private val container: AppContainer,
) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppUiState(),
    )

    private var recordingTimerJob: Job? = null
    private var pendingRecordingCompletionMessage: String? = null

    init {
        viewModelScope.launch {
            container.syncTargetRepository.seedDefaults()
            launch {
                container.noteRepository.observeActiveNotes().collect { notes ->
                    val suggested = notes
                        .flatMap { it.tags }
                        .groupingBy { it }
                        .eachCount()
                        .entries
                        .sortedByDescending { it.value }
                        .take(8)
                        .map { it.key }
                    _uiState.value = _uiState.value.copy(notes = notes, suggestedTags = suggested)
                }
            }
            launch {
                container.noteRepository.observeArchivedNotes().collect { notes ->
                    _uiState.value = _uiState.value.copy(archivedNotes = notes)
                }
            }
            launch {
                container.noteRepository.observeTrashedNotes().collect { notes ->
                    _uiState.value = _uiState.value.copy(trashedNotes = notes)
                }
            }
            launch {
                container.reminderRepository.observeReminders().collect { reminders ->
                    _uiState.value = _uiState.value.copy(reminders = reminders)
                }
            }
            launch {
                container.aiSuggestionRepository.observeSuggestions().collect { suggestions ->
                    _uiState.value = _uiState.value.copy(
                        aiSuggestions = suggestions.associateBy { it.noteId },
                    )
                }
            }
            launch {
                container.syncTargetRepository.observeTargets().collect { targets ->
                    _uiState.value = _uiState.value.copy(syncTargets = targets, syncHint = buildSyncHint(targets))
                }
            }
            launch {
                container.localAudioPlayer.playbackState.collect { playback ->
                    _uiState.value = _uiState.value.copy(audioPlayback = playback)
                }
            }
            launch {
                container.settingsStore.settingsFlow.collect { stored ->
                    _uiState.value = _uiState.value.copy(
                        settings = _uiState.value.settings.copy(
                            overlay = stored.overlay,
                            relay = stored.relay,
                            volcengine = stored.volcengine,
                            ai = stored.ai,
                            hasUnsavedChanges = false,
                        ),
                    )
                }
            }
            refreshSettings()
            launch { autoSyncOnStart() }
        }
    }

    fun updateDraftContent(value: String) { _uiState.value = _uiState.value.copy(draft = _uiState.value.draft.copy(content = value)) }
    fun updateDraftCategory(value: NoteCategory) { _uiState.value = _uiState.value.copy(draft = _uiState.value.draft.copy(category = value)) }
    fun updateDraftPriority(value: NotePriority) { _uiState.value = _uiState.value.copy(draft = _uiState.value.draft.copy(priority = value)) }
    fun updateDraftTags(value: List<String>) { _uiState.value = _uiState.value.copy(draft = _uiState.value.draft.copy(tags = value)) }
    fun toggleCaptureExpanded() { _uiState.value = _uiState.value.copy(captureExpanded = !_uiState.value.captureExpanded) }

    fun toggleTagFilter(tag: String) {
        val current = _uiState.value.tagFilter
        _uiState.value = _uiState.value.copy(tagFilter = if (tag in current) current - tag else current + tag)
    }

    fun clearTagFilter() { _uiState.value = _uiState.value.copy(tagFilter = emptySet()) }

    fun updateSearchQuery(query: String) { _uiState.value = _uiState.value.copy(searchQuery = query) }

    fun clearSearch() { _uiState.value = _uiState.value.copy(searchQuery = "") }

    fun startEditing(note: Note) {
        _uiState.value = _uiState.value.copy(editingNote = EditDraft(note.id, note.content, note.category, note.priority, note.tags))
    }

    fun startEditingById(noteId: String) {
        viewModelScope.launch {
            val note = withContext(Dispatchers.IO) { container.noteRepository.getNote(noteId) }
            if (note == null) {
                _uiState.value = _uiState.value.copy(message = "找不到这条记录。")
                return@launch
            }
            _uiState.value = _uiState.value.copy(
                currentSection = when {
                    note.isTrashed -> NoteListSection.TRASH
                    note.isArchived -> NoteListSection.ARCHIVE
                    else -> NoteListSection.INBOX
                },
                editingNote = EditDraft(note.id, note.content, note.category, note.priority, note.tags),
            )
        }
    }

    fun updateEditingContent(value: String) { _uiState.value = _uiState.value.copy(editingNote = _uiState.value.editingNote?.copy(content = value)) }
    fun updateEditingCategory(value: NoteCategory) { _uiState.value = _uiState.value.copy(editingNote = _uiState.value.editingNote?.copy(category = value)) }
    fun updateEditingPriority(value: NotePriority) { _uiState.value = _uiState.value.copy(editingNote = _uiState.value.editingNote?.copy(priority = value)) }
    fun updateEditingTags(value: List<String>) { _uiState.value = _uiState.value.copy(editingNote = _uiState.value.editingNote?.copy(tags = value)) }
    fun cancelEditing() { _uiState.value = _uiState.value.copy(editingNote = null) }
    fun clearMessage() { _uiState.value = _uiState.value.copy(message = null) }
    fun dismissMicrophonePermissionRequest() { _uiState.value = _uiState.value.copy(requiresMicrophonePermission = false) }
    fun dismissOverlayPermissionRequest() {
        _uiState.value = _uiState.value.copy(
            settings = _uiState.value.settings.copy(requiresOverlayPermission = false),
        )
    }
    fun copyNoteContent(noteId: String) {
        viewModelScope.launch {
            val note = withContext(Dispatchers.IO) { container.noteRepository.getNote(noteId) }
            if (note == null) {
                _uiState.value = _uiState.value.copy(message = "找不到这条记录。")
                return@launch
            }
            val text = (note.transcript ?: note.content).ifBlank { note.title }
            val clipboard = getApplication<Application>()
                .getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText(note.title, text))
            _uiState.value = _uiState.value.copy(message = "已复制到剪贴板。")
        }
    }

    fun showSection(section: NoteListSection) { _uiState.value = _uiState.value.copy(currentSection = section) }
    fun prepareQuickRecordLaunch() { _uiState.value = _uiState.value.copy(pendingQuickRecord = true) }
    fun consumeQuickRecordLaunch() { _uiState.value = _uiState.value.copy(pendingQuickRecord = false) }
    fun handleQuickRecordTrigger() {
        when (_uiState.value.recording.state) {
            RecordingState.IDLE -> {
                if (!hasMicrophonePermission()) {
                    _uiState.value = _uiState.value.copy(
                        pendingQuickRecord = true,
                        requiresMicrophonePermission = true,
                    )
                    return
                }
                _uiState.value = _uiState.value.copy(pendingQuickRecord = false)
                startRecording()
            }

            RecordingState.RECORDING -> {
                _uiState.value = _uiState.value.copy(pendingQuickRecord = false)
                pendingRecordingCompletionMessage = "上一段录音已保存。"
                stopRecording()
            }

            RecordingState.SAVING -> {
                _uiState.value = _uiState.value.copy(
                    pendingQuickRecord = false,
                    message = "正在保存录音，请稍候。",
                )
            }
            RecordingState.STARTING -> {
                _uiState.value = _uiState.value.copy(
                    pendingQuickRecord = false,
                    message = "正在准备录音，请稍候。",
                )
            }
        }
    }

    fun resumePendingQuickRecordIfPossible() {
        if (!_uiState.value.pendingQuickRecord) return
        if (!hasMicrophonePermission()) return
        if (_uiState.value.recording.state != RecordingState.IDLE) return
        _uiState.value = _uiState.value.copy(pendingQuickRecord = false)
        startRecording()
    }

    fun runAiForNote(noteId: String) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    container.aiOrchestrator.analyzeNow(noteId)
                }
            }.onSuccess {
                _uiState.value = _uiState.value.copy(message = "已提交 AI 整理。")
            }.onFailure {
                _uiState.value = _uiState.value.copy(message = it.message ?: "AI 整理失败。")
            }
        }
    }

    fun applyAiSuggestion(noteId: String) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val suggestion = container.aiSuggestionRepository.getByNoteId(noteId)
                        ?: error("没有可应用的 AI 建议。")
                    val note = container.noteRepository.getNote(noteId)
                        ?: error("找不到对应便签。")
                    val aiContent = buildAppliedAiContent(note.content, suggestion)
                    val shouldReplaceContent = aiContent.isNotBlank() && aiContent != note.content.trim()
                    val updated = note.copy(
                        title = suggestion.suggestedTitle?.takeIf { it.isNotBlank() } ?: note.title,
                        category = suggestion.suggestedCategory ?: note.category,
                        priority = suggestion.suggestedPriority ?: note.priority,
                        colorToken = defaultColorFor(
                            suggestion.suggestedCategory ?: note.category,
                            suggestion.suggestedPriority ?: note.priority,
                        ),
                        content = if (shouldReplaceContent) aiContent else note.content,
                        originalContent = if (shouldReplaceContent) {
                            note.originalContent ?: note.content
                        } else {
                            note.originalContent
                        },
                    )
                    val saved = container.noteRepository.saveNote(updated)
                    container.aiSuggestionRepository.markStatus(noteId, com.ydoc.app.model.AiSuggestionStatus.APPLIED)
                    syncIfEnabled(saved)
                }
            }.onSuccess {
                _uiState.value = _uiState.value.copy(message = "AI 建议已应用。")
            }.onFailure {
                _uiState.value = _uiState.value.copy(message = it.message ?: "应用 AI 建议失败。")
            }
        }
    }

    fun dismissAiSuggestion(noteId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                container.aiSuggestionRepository.markStatus(
                    noteId,
                    com.ydoc.app.model.AiSuggestionStatus.DISMISSED,
                )
            }
            _uiState.value = _uiState.value.copy(message = "已忽略这条 AI 建议。")
        }
    }

    fun createReminderFromSuggestion(noteId: String, candidate: ReminderCandidate) {
        val scheduledAt = resolveScheduledAt(candidate)
        if (scheduledAt <= System.currentTimeMillis()) {
            _uiState.value = _uiState.value.copy(message = "AI 建议的时间已过，请手动设置提醒。")
            return
        }
        viewModelScope.launch {
            scheduleReminderForNote(
                noteId = noteId,
                title = candidate.title,
                scheduledAt = scheduledAt,
                source = ReminderSource.AI,
            )
        }
    }

    fun addQuickReminder(noteId: String, scheduledAt: Long) {
        viewModelScope.launch {
            scheduleReminderForNote(
                noteId = noteId,
                title = "便签提醒",
                scheduledAt = scheduledAt,
                source = ReminderSource.MANUAL,
            )
        }
    }

    fun createReminderForDate(dayMillis: Long, title: String, hour: Int, minute: Int) {
        viewModelScope.launch {
            val scheduledAt = Calendar.getInstance().apply {
                timeInMillis = dayMillis
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            if (scheduledAt <= System.currentTimeMillis()) {
                _uiState.value = _uiState.value.copy(message = "所选时间已过，请选择未来的时间。")
                return@launch
            }

            runCatching {
                withContext(Dispatchers.IO) {
                    val note = container.noteRepository.createTextNote(
                        content = title,
                        category = NoteCategory.REMINDER,
                        priority = NotePriority.MEDIUM,
                    )
                    val reminder = container.reminderRepository.createReminder(
                        noteId = note.id,
                        title = title,
                        scheduledAt = scheduledAt,
                        source = ReminderSource.MANUAL,
                    )
                    container.reminderScheduler.schedule(reminder)
                    syncStateIfEnabled(force = true)
                }
            }.onSuccess {
                _uiState.value = _uiState.value.copy(message = "日程已创建。")
            }.onFailure {
                _uiState.value = _uiState.value.copy(message = it.message ?: "创建日程失败。")
            }
        }
    }

    fun cancelReminder(reminderId: String) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val reminder = container.reminderRepository.getReminder(reminderId)
                        ?: error("找不到这条提醒。")
                    container.reminderScheduler.cancel(reminder.id, reminder.noteId, reminder.title)
                    container.reminderRepository.cancel(reminder.id)
                }
            }.onSuccess {
                _uiState.value = _uiState.value.copy(message = "提醒已取消。")
            }.onFailure {
                _uiState.value = _uiState.value.copy(message = it.message ?: "取消提醒失败。")
            }
        }
    }

    fun exportReminderToAlarm(reminderId: String) {
        viewModelScope.launch {
            runCatching {
                val reminder = withContext(Dispatchers.IO) {
                    container.reminderRepository.getReminder(reminderId)
                } ?: error("找不到这条提醒。")
                val calendar = Calendar.getInstance().apply {
                    timeInMillis = reminder.scheduledAt
                }
                val app = getApplication<Application>()
                val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(AlarmClock.EXTRA_MESSAGE, reminder.title)
                    putExtra(AlarmClock.EXTRA_HOUR, calendar.get(Calendar.HOUR_OF_DAY))
                    putExtra(AlarmClock.EXTRA_MINUTES, calendar.get(Calendar.MINUTE))
                    putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                }
                if (intent.resolveActivity(app.packageManager) == null) {
                    error("未找到可处理系统闹钟的应用。")
                }
                try {
                    app.startActivity(intent)
                } catch (securityException: SecurityException) {
                    throw IllegalStateException("系统拒绝了闹钟导出权限，请检查闹钟权限或系统限制。", securityException)
                }
            }.onSuccess {
                _uiState.value = _uiState.value.copy(message = "已将提醒导出到系统闹钟。")
            }.onFailure {
                _uiState.value = _uiState.value.copy(message = it.message ?: "打开系统闹钟失败。")
            }
        }
    }

    fun saveDraft() {
        val draft = _uiState.value.draft
        val content = draft.content.trim()
        if (content.isBlank()) {
            _uiState.value = _uiState.value.copy(
                captureExpanded = true,
                message = "先输入一点内容。",
            )
            return
        }
        viewModelScope.launch {
            val note = withContext(Dispatchers.IO) {
                container.noteRepository.createTextNote(content, draft.category, draft.priority, draft.tags)
            }
            // 立即重置 draft，不等待 sync + AI
            _uiState.value = _uiState.value.copy(
                draft = CaptureDraft(category = draft.category, priority = draft.priority),
                captureExpanded = false,
                isSaving = false,
                message = "已保存到本地 inbox。",
            )
            // sync + AI 在后台独立运行，不阻塞 UI
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { syncIfEnabled(note) }
            }
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { container.aiOrchestrator.maybeAnalyze(note.id, AiRunTrigger.TEXT_SAVE) }
            }
        }
    }

    fun saveEditedNote() {
        val editing = _uiState.value.editingNote ?: return
        if (editing.content.trim().isBlank()) {
            _uiState.value = _uiState.value.copy(message = "编辑内容不能为空。")
            return
        }
        viewModelScope.launch {
            val updated = withContext(Dispatchers.IO) {
                val existing = container.noteRepository.getNote(editing.noteId) ?: return@withContext null
                container.noteRepository.saveEditedNote(
                    existing.copy(content = editing.content.trim(), category = editing.category, priority = editing.priority, tags = editing.tags),
                )
            }
            if (updated != null) {
                _uiState.value = _uiState.value.copy(editingNote = null, isSaving = false, message = "记录已更新。")
                viewModelScope.launch(Dispatchers.IO) {
                    runCatching { syncIfEnabled(updated) }
                }
            } else {
                _uiState.value = _uiState.value.copy(isSaving = false, message = "找不到这条记录。")
            }
        }
    }

    fun deleteNote(noteId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    container.reminderRepository.getByNoteId(noteId).forEach { reminder ->
                        container.reminderScheduler.cancel(reminder.id, reminder.noteId, reminder.title)
                    }
                    container.reminderRepository.deleteByNoteId(noteId)
                    container.noteRepository.trashNote(noteId)
                    syncStateIfEnabled(force = true)
                }
            }.onSuccess {
                _uiState.value = _uiState.value.copy(
                    editingNote = _uiState.value.editingNote?.takeIf { it.noteId != noteId },
                    message = "记录已移入回收站。",
                )
            }.onFailure {
                _uiState.value = _uiState.value.copy(message = it.message ?: "移入回收站失败。")
            }
        }
    }

    fun archiveNote(noteId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val note = container.noteRepository.archiveNote(noteId)
                    syncIfEnabled(note, force = true)
                }
            }.onSuccess {
                _uiState.value = _uiState.value.copy(
                    editingNote = _uiState.value.editingNote?.takeIf { it.noteId != noteId },
                    message = "记录已归档。",
                )
            }.onFailure {
                _uiState.value = _uiState.value.copy(message = it.message ?: "归档失败。")
            }
        }
    }

    fun unarchiveNote(noteId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val note = container.noteRepository.unarchiveNote(noteId)
                    syncIfEnabled(note, force = true)
                }
            }.onSuccess {
                _uiState.value = _uiState.value.copy(message = "记录已移回收件箱。")
            }.onFailure {
                _uiState.value = _uiState.value.copy(message = it.message ?: "取消归档失败。")
            }
        }
    }

    fun restoreNote(noteId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val note = container.noteRepository.restoreNote(noteId)
                    syncIfEnabled(note, force = true)
                }
            }.onSuccess {
                _uiState.value = _uiState.value.copy(message = "记录已恢复。")
            }.onFailure {
                _uiState.value = _uiState.value.copy(message = it.message ?: "恢复失败。")
            }
        }
    }

    fun permanentlyDeleteNote(noteId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val note = container.noteRepository.getNote(noteId)
                    container.reminderRepository.getByNoteId(noteId).forEach { reminder ->
                        container.reminderScheduler.cancel(reminder.id, reminder.noteId, reminder.title)
                    }
                    container.reminderRepository.deleteByNoteId(noteId)
                    if (note != null) {
                        container.syncOrchestrator.deleteRemote(note)
                        val relayConfig = _uiState.value.settings.relay
                        if (relayConfig.enabled && !note.relayFileId.isNullOrBlank()) {
                            runCatching { container.relayStorageClient.delete(note.relayFileId, relayConfig) }
                        }
                    }
                    container.noteRepository.deleteNote(noteId)
                }
            }.onSuccess {
                _uiState.value = _uiState.value.copy(message = "记录已彻底删除。")
            }.onFailure {
                _uiState.value = _uiState.value.copy(message = it.message ?: "彻底删除失败。")
            }
        }
    }

    fun emptyTrash() {
        viewModelScope.launch {
            val trashedNotes = _uiState.value.trashedNotes
            withContext(Dispatchers.IO) {
                runCatching {
                    val relayConfig = _uiState.value.settings.relay
                    trashedNotes.forEach { note ->
                        container.reminderRepository.getByNoteId(note.id).forEach { reminder ->
                            container.reminderScheduler.cancel(reminder.id, reminder.noteId, reminder.title)
                        }
                        container.reminderRepository.deleteByNoteId(note.id)
                        if (relayConfig.enabled && !note.relayFileId.isNullOrBlank()) {
                            runCatching { container.relayStorageClient.delete(note.relayFileId, relayConfig) }
                        }
                    }
                    container.noteRepository.emptyTrash()
                    syncStateIfEnabled()
                }
            }.onSuccess {
                _uiState.value = _uiState.value.copy(
                    editingNote = null,
                    message = "回收站已清空。",
                )
            }.onFailure {
                _uiState.value = _uiState.value.copy(message = it.message ?: "清空回收站失败。")
            }
        }
    }

    fun retrySync(noteId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val note = container.noteRepository.getNote(noteId) ?: error("找不到这条记录")
                    syncIfEnabled(note)
                }
            }.onSuccess {
                _uiState.value = _uiState.value.copy(message = "已重新同步。")
            }.onFailure {
                _uiState.value = _uiState.value.copy(message = it.message ?: "重新同步失败。")
            }
        }
    }

    fun startRecording() {
        if (_uiState.value.recording.state != RecordingState.IDLE) {
            _uiState.value = _uiState.value.copy(
                message = when (_uiState.value.recording.state) {
                    RecordingState.STARTING -> "正在准备录音，请稍候。"
                    RecordingState.RECORDING -> "录音已经在进行中。"
                    RecordingState.SAVING -> "正在保存录音，请稍候。"
                    RecordingState.IDLE -> null
                },
            )
            return
        }
        if (!hasMicrophonePermission()) {
            _uiState.value = _uiState.value.copy(
                pendingQuickRecord = true,
                requiresMicrophonePermission = true,
            )
            return
        }
        _uiState.value = _uiState.value.copy(
            captureExpanded = true,
            pendingQuickRecord = false,
            recording = RecordingUiState(state = RecordingState.STARTING),
        )
        viewModelScope.launch {
            val app = getApplication<Application>()
            val startResult = withContext(Dispatchers.IO) {
                runCatching {
                    if (container.audioRecorder.isRecording) {
                        container.audioRecorder.cancel()
                        app.stopService(Intent(app, RecordingService::class.java))
                    }
                    val output = container.audioRecorder.start()
                    runCatching {
                        ContextCompat.startForegroundService(app, Intent(app, RecordingService::class.java))
                    }.getOrElse { serviceError ->
                        container.audioRecorder.cancel()
                        app.stopService(Intent(app, RecordingService::class.java))
                        throw IllegalStateException("录音服务启动失败，请重试。", serviceError)
                    }
                    output.path
                }
            }
            val startedPath = startResult.getOrNull()
            if (startedPath != null) {
                startRecordingTimer(startedPath)
            } else {
                val error = startResult.exceptionOrNull()
                withContext(Dispatchers.IO) {
                    if (container.audioRecorder.isRecording) {
                        container.audioRecorder.cancel()
                    }
                    app.stopService(Intent(app, RecordingService::class.java))
                }
                stopRecordingTimer()
                pendingRecordingCompletionMessage = null
                _uiState.value = _uiState.value.copy(
                    captureExpanded = true,
                    pendingQuickRecord = false,
                    message = error?.message ?: "录音启动失败。",
                )
            }
        }
    }

    fun stopRecording() {
        val currentPriority = _uiState.value.draft.priority
        val currentSettings = _uiState.value.settings
        _uiState.value = _uiState.value.copy(recording = _uiState.value.recording.copy(state = RecordingState.SAVING))
        viewModelScope.launch {
            val app = getApplication<Application>()
            val noteResult = withContext(Dispatchers.IO) {
                runCatching {
                    container.voiceNoteProcessor.stopAndSave(
                        priority = currentPriority,
                        relayConfig = currentSettings.relay,
                        volcengineConfig = currentSettings.volcengine,
                        wifiOnly = currentSettings.webDav.wifiOnly,
                    )
                }
            }
            app.stopService(Intent(app, RecordingService::class.java))
            stopRecordingTimer()
            noteResult.onSuccess { result ->
                val syncError = runCatching { syncIfEnabled(result.note) }.exceptionOrNull()?.message
                val completionMessage = pendingRecordingCompletionMessage
                pendingRecordingCompletionMessage = null
                _uiState.value = _uiState.value.copy(
                    captureExpanded = false,
                    pendingQuickRecord = false,
                    message = completionMessage ?: result.buildUserMessage(syncError),
                )
            }.onFailure {
                pendingRecordingCompletionMessage = null
                _uiState.value = _uiState.value.copy(
                    captureExpanded = true,
                    pendingQuickRecord = false,
                    message = it.message ?: "录音保存失败。",
                )
            }
            return@launch
            withContext(Dispatchers.IO) {
                runCatching {
                    val output = container.audioRecorder.stop()
                    app.stopService(Intent(app, RecordingService::class.java))
                    var note = container.noteRepository.createVoiceNote(
                        noteId = java.util.UUID.randomUUID().toString(),
                        audioPath = output.path,
                        audioFormat = output.format,
                        priority = currentPriority,
                    )
                    val relayConfig = _uiState.value.settings.relay
                    if (relayConfig.enabled) {
                        val upload = container.relayStorageClient.upload(java.io.File(output.path), relayConfig)
                        note = container.noteRepository.attachRelayInfo(note, upload.fileId, upload.url, upload.expiresAt)
                    }
                    val volcengineConfig = _uiState.value.settings.volcengine
                    if (relayConfig.enabled && volcengineConfig.enabled && !note.relayUrl.isNullOrBlank()) {
                        runCatching {
                            container.transcriptionOrchestrator.transcribe(note, volcengineConfig, relayConfig)
                        }.onFailure {
                            container.transcriptionScheduler.enqueueRetry(note.id, _uiState.value.settings.webDav.wifiOnly)
                            throw it
                        }
                        note = container.noteRepository.getNote(note.id) ?: note
                    }
                    syncIfEnabled(note)
                }
            }.onSuccess {
                stopRecordingTimer()
                _uiState.value = _uiState.value.copy(message = when {
                    _uiState.value.settings.relay.enabled && _uiState.value.settings.volcengine.enabled -> "录音已保存，已上传中转服务并提交火山转写。"
                    _uiState.value.settings.relay.enabled -> "录音已保存并上传到中转服务。"
                    else -> "录音已保存。"
                })
            }.onFailure {
                stopRecordingTimer()
                _uiState.value = _uiState.value.copy(message = it.message ?: "录音保存失败。")
            }
        }
    }

    fun cancelRecording() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            withContext(Dispatchers.IO) {
                container.audioRecorder.cancel()
                app.stopService(Intent(app, RecordingService::class.java))
            }
            stopRecordingTimer()
            _uiState.value = _uiState.value.copy(
                pendingQuickRecord = false,
                captureExpanded = _uiState.value.draft.content.isNotBlank(),
                message = "录音已取消。",
            )
        }
    }

    fun toggleAudioPlayback(noteId: String) {
        viewModelScope.launch {
            val note = withContext(Dispatchers.IO) { container.noteRepository.getNote(noteId) }
            if (note == null) {
                _uiState.value = _uiState.value.copy(message = "找不到这条录音。")
                return@launch
            }
            container.localAudioPlayer.playOrToggle(note) { message ->
                _uiState.value = _uiState.value.copy(message = message)
            }
        }
    }

    fun seekAudio(positionMs: Long) {
        container.localAudioPlayer.seekTo(positionMs)
    }

    fun syncNow() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSyncing = true)
            val syncResult = withContext(Dispatchers.IO) {
                val biResult = container.syncOrchestrator.syncBidirectional()
                if (biResult.isFailure) {
                    val fallback = container.syncOrchestrator.syncPending()
                    fallback.map { count -> BidirectionalSyncResult(pushed = count) }
                } else {
                    biResult
                }
            }
            syncResult
                .onSuccess { r ->
                    val parts = mutableListOf<String>()
                    if (r.pushed > 0) parts.add("推送 ${r.pushed} 条")
                    if (r.pulled > 0) parts.add("拉取 ${r.pulled} 条")
                    _uiState.value = _uiState.value.copy(message = if (parts.isEmpty()) "没有可同步的记录。" else parts.joinToString("，") + "。")
                }
                .onFailure { _uiState.value = _uiState.value.copy(message = it.message ?: "同步失败。") }
            _uiState.value = _uiState.value.copy(isSyncing = false)
        }
    }

    fun updateWebDavConfig(update: (WebDavConfig) -> WebDavConfig) {
        val current = _uiState.value.settings
        _uiState.value = _uiState.value.copy(settings = current.copy(webDav = update(current.webDav), hasUnsavedChanges = true))
    }

    fun updateRelayBaseUrl(value: String) {
        val current = _uiState.value.settings
        _uiState.value = _uiState.value.copy(settings = current.copy(relay = current.relay.copy(baseUrl = value), hasUnsavedChanges = true))
    }

    fun updateRelayToken(value: String) {
        val current = _uiState.value.settings
        _uiState.value = _uiState.value.copy(settings = current.copy(relay = current.relay.copy(token = value), hasUnsavedChanges = true))
    }

    fun toggleRelayEnabled(enabled: Boolean) {
        val current = _uiState.value.settings
        _uiState.value = _uiState.value.copy(settings = current.copy(relay = current.relay.copy(enabled = enabled), hasUnsavedChanges = true))
    }

    fun requestOverlayToggle(enabled: Boolean, hasPermission: Boolean) {
        val current = _uiState.value.settings
        if (enabled && !hasPermission) {
            _uiState.value = _uiState.value.copy(
                settings = current.copy(
                    overlay = current.overlay.copy(enabled = false),
                    requiresOverlayPermission = true,
                ),
                message = "请先授予悬浮窗权限。",
            )
            return
        }

        _uiState.value = _uiState.value.copy(
            settings = current.copy(
                overlay = current.overlay.copy(enabled = enabled),
                requiresOverlayPermission = false,
                hasUnsavedChanges = true,
            ),
        )
    }

    fun updateOverlayHandleSize(value: Int) {
        val current = _uiState.value.settings
        _uiState.value = _uiState.value.copy(
            settings = current.copy(
                overlay = current.overlay.copy(handleSizeDp = value.coerceIn(16, 48)),
                hasUnsavedChanges = true,
            ),
        )
    }

    fun updateOverlayHandleAlpha(value: Float) {
        val current = _uiState.value.settings
        _uiState.value = _uiState.value.copy(
            settings = current.copy(
                overlay = current.overlay.copy(handleAlpha = value.coerceIn(0.2f, 1f)),
                hasUnsavedChanges = true,
            ),
        )
    }

    fun updateVolcengineAppId(value: String) {
        val current = _uiState.value.settings
        _uiState.value = _uiState.value.copy(settings = current.copy(volcengine = current.volcengine.copy(appId = value), hasUnsavedChanges = true))
    }

    fun updateVolcengineAccessToken(value: String) {
        val current = _uiState.value.settings
        _uiState.value = _uiState.value.copy(settings = current.copy(volcengine = current.volcengine.copy(accessToken = value), hasUnsavedChanges = true))
    }

    fun updateVolcengineResourceId(value: String) {
        val current = _uiState.value.settings
        _uiState.value = _uiState.value.copy(settings = current.copy(volcengine = current.volcengine.copy(resourceId = value), hasUnsavedChanges = true))
    }

    fun toggleVolcengineEnabled(enabled: Boolean) {
        val current = _uiState.value.settings
        _uiState.value = _uiState.value.copy(settings = current.copy(volcengine = current.volcengine.copy(enabled = enabled), hasUnsavedChanges = true))
    }

    fun updateAiBaseUrl(value: String) {
        val current = _uiState.value.settings
        _uiState.value = _uiState.value.copy(settings = current.copy(ai = current.ai.copy(baseUrl = value), hasUnsavedChanges = true))
    }

    fun updateAiToken(value: String) {
        val current = _uiState.value.settings
        _uiState.value = _uiState.value.copy(settings = current.copy(ai = current.ai.copy(token = value), hasUnsavedChanges = true))
    }

    fun updateAiModel(value: String) {
        val current = _uiState.value.settings
        _uiState.value = _uiState.value.copy(settings = current.copy(ai = current.ai.copy(model = value), hasUnsavedChanges = true))
    }

    fun updateAiPrompt(value: String) {
        val current = _uiState.value.settings
        _uiState.value = _uiState.value.copy(
            settings = current.copy(
                ai = current.ai.copy(promptSupplement = value),
                hasUnsavedChanges = true,
            ),
        )
    }

    fun updateAiEndpointMode(value: AiEndpointMode) {
        val current = _uiState.value.settings
        _uiState.value = _uiState.value.copy(
            settings = current.copy(
                ai = current.ai.copy(endpointMode = value),
                hasUnsavedChanges = true,
            ),
        )
    }

    fun toggleAiEnabled(enabled: Boolean) {
        val current = _uiState.value.settings
        _uiState.value = _uiState.value.copy(settings = current.copy(ai = current.ai.copy(enabled = enabled), hasUnsavedChanges = true))
    }

    fun toggleAiAutoText(enabled: Boolean) {
        val current = _uiState.value.settings
        _uiState.value = _uiState.value.copy(settings = current.copy(ai = current.ai.copy(autoRunOnTextSave = enabled), hasUnsavedChanges = true))
    }

    fun toggleAiAutoVoice(enabled: Boolean) {
        val current = _uiState.value.settings
        _uiState.value = _uiState.value.copy(settings = current.copy(ai = current.ai.copy(autoRunOnVoiceTranscribed = enabled), hasUnsavedChanges = true))
    }

    fun toggleAiAutoRetry(enabled: Boolean) {
        val current = _uiState.value.settings
        _uiState.value = _uiState.value.copy(
            settings = current.copy(
                ai = current.ai.copy(autoRetryOnTransientFailure = enabled),
                hasUnsavedChanges = true,
            ),
        )
    }

    fun toggleWebDavEnabled(enabled: Boolean) {
        val current = _uiState.value.settings
        _uiState.value = _uiState.value.copy(settings = current.copy(webDavEnabled = enabled, hasUnsavedChanges = true))
    }

    fun saveSettings() {
        viewModelScope.launch {
            val snapshot = _uiState.value.settings
            runCatching {
                container.syncTargetRepository.saveTarget(
                    SyncTarget(
                        type = SyncType.WEBDAV,
                        enabled = snapshot.webDavEnabled,
                        config = snapshot.webDav.copy(),
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                container.settingsStore.saveOverlay(snapshot.overlay)
                container.settingsStore.saveRelay(snapshot.relay)
                container.settingsStore.saveVolcengine(snapshot.volcengine)
                container.settingsStore.saveAi(snapshot.ai)
                if (snapshot.webDavEnabled && snapshot.webDav.autoSync) {
                    container.syncScheduler.enqueuePeriodicSync(snapshot.webDav.wifiOnly, snapshot.webDav.syncIntervalMinutes)
                } else {
                    container.syncScheduler.cancelPeriodicSync()
                }
            }.onSuccess {
                _uiState.value = _uiState.value.copy(settings = _uiState.value.settings.copy(hasUnsavedChanges = false), message = "同步、中转与火山设置已保存。")
            }.onFailure {
                _uiState.value = _uiState.value.copy(message = it.message ?: "保存设置失败。")
            }
        }
    }

    fun testWebDavConnection() {
        viewModelScope.launch {
            val snapshot = _uiState.value.settings
            _uiState.value = _uiState.value.copy(settings = snapshot.copy(isTestingWebDav = true))
            val target = SyncTarget(type = SyncType.WEBDAV, enabled = true, config = snapshot.webDav, updatedAt = System.currentTimeMillis())
            val client = container.syncClients.first { it.type == SyncType.WEBDAV.name }
            withContext(Dispatchers.IO) { client.testConnection(target) }
                .onSuccess { _uiState.value = _uiState.value.copy(message = "WebDAV 连接成功。") }
                .onFailure { _uiState.value = _uiState.value.copy(message = "WebDAV 连接失败：${formatError(it)}") }
            _uiState.value = _uiState.value.copy(settings = _uiState.value.settings.copy(isTestingWebDav = false))
        }
    }

    fun testRelayConnection() {
        viewModelScope.launch {
            val snapshot = _uiState.value.settings
            _uiState.value = _uiState.value.copy(settings = snapshot.copy(isTestingRelay = true))
            runCatching { withContext(Dispatchers.IO) { container.relayStorageClient.test(snapshot.relay) } }
                .onSuccess { _uiState.value = _uiState.value.copy(message = "中转服务连接成功。") }
                .onFailure { _uiState.value = _uiState.value.copy(message = "中转服务连接失败：${formatError(it)}") }
            _uiState.value = _uiState.value.copy(settings = _uiState.value.settings.copy(isTestingRelay = false))
        }
    }

    fun testVolcengineConnection() {
        viewModelScope.launch {
            val snapshot = _uiState.value.settings
            _uiState.value = _uiState.value.copy(settings = snapshot.copy(isTestingVolcengine = true))
            runCatching { withContext(Dispatchers.IO) { container.volcengineTranscriptionClient.test(snapshot.volcengine) } }
                .onSuccess { _uiState.value = _uiState.value.copy(message = "火山转写配置有效。") }
                .onFailure { _uiState.value = _uiState.value.copy(message = "火山转写配置失败：${formatError(it)}") }
            _uiState.value = _uiState.value.copy(settings = _uiState.value.settings.copy(isTestingVolcengine = false))
        }
    }

    fun testAiConnection() {
        viewModelScope.launch {
            val snapshot = _uiState.value.settings
            _uiState.value = _uiState.value.copy(settings = snapshot.copy(isTestingAi = true))
            runCatching { withContext(Dispatchers.IO) { container.aiClient.test(snapshot.ai) } }
                .onSuccess { _uiState.value = _uiState.value.copy(message = "AI 服务连接成功。") }
                .onFailure { _uiState.value = _uiState.value.copy(message = "AI 服务连接失败：${formatError(it)}") }
            _uiState.value = _uiState.value.copy(settings = _uiState.value.settings.copy(isTestingAi = false))
        }
    }

    private suspend fun refreshSettings() {
        val webDavTarget = container.syncTargetRepository.getTarget(SyncType.WEBDAV)
        val current = _uiState.value.settings
        _uiState.value = _uiState.value.copy(
            settings = current.copy(
                webDav = webDavTarget?.config as? WebDavConfig ?: current.webDav,
                webDavEnabled = webDavTarget?.enabled ?: current.webDavEnabled,
                hasUnsavedChanges = false,
            ),
        )
    }

    private suspend fun autoSyncOnStart() {
        val settings = _uiState.value.settings
        if (settings.webDavEnabled && settings.webDav.autoSync) {
            withContext(Dispatchers.IO) {
                container.syncOrchestrator.syncBidirectional()
            }
        }
    }

    private fun startRecordingTimer(path: String) {
        recordingTimerJob?.cancel()
        _uiState.value = _uiState.value.copy(recording = RecordingUiState(state = RecordingState.RECORDING, elapsedSeconds = 0, outputPath = path), message = "开始录音。")
        recordingTimerJob = viewModelScope.launch {
            var elapsed = 0
            while (true) {
                delay(1000)
                elapsed += 1
                _uiState.value = _uiState.value.copy(recording = _uiState.value.recording.copy(elapsedSeconds = elapsed))
            }
        }
    }

    private fun stopRecordingTimer() {
        recordingTimerJob?.cancel()
        recordingTimerJob = null
        _uiState.value = _uiState.value.copy(recording = RecordingUiState(), pendingQuickRecord = false)
    }

    private suspend fun syncIfEnabled(note: Note, force: Boolean = false) {
        val settingsSnapshot = _uiState.value.settings
        val autoSyncEnabled = settingsSnapshot.webDavEnabled && (force || settingsSnapshot.webDav.autoSync)
        if (autoSyncEnabled) {
            container.syncOrchestrator.syncNote(note).getOrElse {
                container.syncScheduler.enqueueRetry(settingsSnapshot.webDav.wifiOnly)
                throw it
            }
        }
    }

    private suspend fun syncStateIfEnabled(force: Boolean = false) {
        val settingsSnapshot = _uiState.value.settings
        val autoSyncEnabled = settingsSnapshot.webDavEnabled && (force || settingsSnapshot.webDav.autoSync)
        if (autoSyncEnabled) {
            container.syncOrchestrator.syncBidirectional().getOrElse {
                container.syncScheduler.enqueueRetry(settingsSnapshot.webDav.wifiOnly)
                throw it
            }
        }
    }

    private fun hasMicrophonePermission(): Boolean {
        val app = getApplication<Application>()
        val audioGranted = ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED else true
        return audioGranted && notificationGranted
    }

    private fun buildSyncHint(targets: List<SyncTarget>): String {
        val webDav = targets.firstOrNull { it.type == SyncType.WEBDAV && it.enabled }
        val relayEnabled = _uiState.value.settings.relay.enabled
        val volcEnabled = _uiState.value.settings.volcengine.enabled
        return when {
            webDav == null && !relayEnabled -> "还没启用 WebDAV 和中转服务。保存内容会先进入本地 inbox。"
            relayEnabled && volcEnabled -> "中转服务和火山转写已启用，录音会自动上传并尝试转文字。"
            webDav == null -> "中转服务已启用，后续可以直接把录音文件变成火山可访问的 URL。"
            (webDav.config as WebDavConfig).autoSync && relayEnabled -> "WebDAV 与中转服务已启用，录音可自动上传中转并同步到 NAS。"
            (webDav.config as WebDavConfig).autoSync -> "WebDAV 已启用，保存后会自动实时同步到 NAS。"
            else -> "WebDAV 已启用，但自动同步关闭。你可以继续手动同步。"
        }
    }

    private fun formatError(throwable: Throwable): String {
        val parts = generateSequence(throwable) { it.cause }
            .mapNotNull { error ->
                val name = error.javaClass.simpleName.takeIf { it.isNotBlank() }
                val message = error.message?.takeIf { it.isNotBlank() }
                when {
                    name != null && message != null -> "$name: $message"
                    name != null -> name
                    else -> null
                }
            }
            .take(3)
            .toList()
        return parts.joinToString(" -> ").ifBlank { "未知错误" }
    }

    private fun buildAppliedAiContent(
        originalContent: String,
        suggestion: com.ydoc.app.model.AiSuggestion,
    ): String {
        val summary = suggestion.summary.trim()
        val todos = suggestion.todoItems.map(String::trim).filter(String::isNotBlank)
        return buildString {
            if (summary.isNotBlank()) {
                append(summary)
            }
            if (todos.isNotEmpty()) {
                if (isNotEmpty()) {
                    appendLine()
                    appendLine()
                }
                appendLine("待办建议：")
                todos.forEach { appendLine("- $it") }
            }
        }.trim().ifBlank { originalContent.trim() }
    }

    private suspend fun scheduleReminderForNote(
        noteId: String,
        title: String,
        scheduledAt: Long,
        source: ReminderSource,
    ) {
        runCatching {
            withContext(Dispatchers.IO) {
                val note = container.noteRepository.getNote(noteId) ?: error("找不到这条便签。")
                val reminder = container.reminderRepository.createReminder(
                    noteId = note.id,
                    title = title.takeIf { it.isNotBlank() } ?: note.title,
                    scheduledAt = scheduledAt,
                    source = source,
                    deliveryTargets = setOf(ReminderDeliveryTarget.LOCAL_NOTIFICATION),
                )
                container.reminderScheduler.schedule(reminder)
            }
        }.onSuccess { result ->
            _uiState.value = _uiState.value.copy(
                currentSection = NoteListSection.CALENDAR,
                message = when (result.mode) {
                    ReminderScheduleMode.EXACT -> "提醒已创建。"
                    ReminderScheduleMode.INEXACT -> "提醒已创建，当前系统未授予精确闹钟能力，已降级为非精确提醒。"
                },
            )
        }.onFailure {
            _uiState.value = _uiState.value.copy(message = it.message ?: "创建提醒失败。")
        }
    }

    private fun resolveScheduledAt(candidate: ReminderCandidate): Long {
        // 优先使用 ISO 字符串解析（确定性计算，零误差）
        candidate.scheduledAtIso?.let { iso ->
            val parsed = parseIsoToLocalEpoch(iso)
            if (parsed > 0) return parsed
        }
        // 回退到模型返回的 epoch ms
        return candidate.scheduledAt
    }

    companion object {
        fun factory(application: Application, container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = AppViewModel(application, container) as T
            }
    }
}

private fun parseIsoToLocalEpoch(iso: String): Long {
    val trimmed = iso.trim()
    // 支持格式: "YYYY-MM-DDTHH:mm" 或 "YYYY-MM-DD HH:mm"
    val normalized = trimmed.replace(" ", "T")
    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", java.util.Locale.US)
    sdf.timeZone = java.util.TimeZone.getDefault()
    return runCatching { sdf.parse(normalized)?.time ?: 0L }.getOrDefault(0L)
}
