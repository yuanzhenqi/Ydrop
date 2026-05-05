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

data class EditDraft(
    val noteId: String,
    val content: String,
    val category: NoteCategory,
    val priority: NotePriority,
    val tags: List<String> = emptyList(),
    /** 编辑开始时笔记的附件快照——**进入编辑态后就不跟随 observeActiveNotes 刷新**，保证取消能回滚。 */
    val attachments: List<com.ydoc.app.model.NoteAttachment> = emptyList(),
    /** 本次编辑里用户点 "+ 加图" 新加的，但**还没落库**。取消时直接清盘；保存时才真正落库 + 排 Worker。 */
    val pendingAddedAttachments: List<com.ydoc.app.model.NoteAttachment> = emptyList(),
    /** 本次编辑里用户 × 掉的**已有**附件 id 集合；保存时才真删，取消时作废。 */
    val pendingRemovedAttachmentIds: Set<String> = emptySet(),
) {
    /** UI 展示的"当前附件列表" = 快照去掉待删 + 加上待增。 */
    val effectiveAttachments: List<com.ydoc.app.model.NoteAttachment>
        get() = attachments.filterNot { it.id in pendingRemovedAttachmentIds } + pendingAddedAttachments
}

enum class NoteListSection {
    INBOX,
    CALENDAR,
    ARCHIVE,
    TRASH,
}

data class AppUiState(
    val showNewNoteEditor: Boolean = false,
    val showAgentScreen: Boolean = false,
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
    val batchOrganize: BatchOrganizeUiState = BatchOrganizeUiState(),
    val selectionMode: Boolean = false,
    val selectedNoteIds: Set<String> = emptySet(),
)

data class BatchOrganizeUiState(
    val visible: Boolean = false,
    val loading: Boolean = false,
    val totalAnalyzed: Int = 0,
    val clusters: List<com.ydoc.app.model.ClusterSuggestion> = emptyList(),
    val appliedClusterIds: Set<String> = emptySet(),
    val applyingClusterIds: Set<String> = emptySet(),
    val error: String? = null,
    /** 上次成功分析的时间戳。null 表示从未分析过。 */
    val lastAnalyzedAt: Long? = null,
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
                    // 编辑态下**不**覆盖 draft.attachments——进入编辑后用户的加图/删图操作都是 staged，
                    // 如果这里强行同步 db 最新，会让用户刚 stage 的 pending 被 observe 覆盖掉，造成 UI 闪烁甚至丢状态。
                    // draft.attachments 作为"编辑开始时的快照"保留到保存/取消为止。
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
            launch { fixupVoiceTitlesOnce() }
        }
    }

    /** 一次性修复：把 WebDAV 拉回污染成占位符的语音笔记 title，从 content/transcript 抽首行。 */
    private suspend fun fixupVoiceTitlesOnce() {
        runCatching {
            if (container.settingsStore.isVoiceTitleFixupDone()) return@runCatching
            val fixed = withContext(Dispatchers.IO) { container.noteRepository.fixupVoiceTitles() }
            container.settingsStore.markVoiceTitleFixupDone()
            if (fixed > 0) {
                _uiState.value = _uiState.value.copy(message = "已修复 $fixed 条语音笔记的标题。")
            }
        }
    }

    fun openNewNoteEditor() { _uiState.value = _uiState.value.copy(showNewNoteEditor = true) }
    fun closeNewNoteEditor() { _uiState.value = _uiState.value.copy(showNewNoteEditor = false) }
    fun openAgentScreen() { _uiState.value = _uiState.value.copy(showAgentScreen = true) }
    fun closeAgentScreen() { _uiState.value = _uiState.value.copy(showAgentScreen = false) }

    fun toggleTagFilter(tag: String) {
        val current = _uiState.value.tagFilter
        _uiState.value = _uiState.value.copy(tagFilter = if (tag in current) current - tag else current + tag)
    }

    fun clearTagFilter() { _uiState.value = _uiState.value.copy(tagFilter = emptySet()) }

    fun updateSearchQuery(query: String) { _uiState.value = _uiState.value.copy(searchQuery = query) }

    fun clearSearch() { _uiState.value = _uiState.value.copy(searchQuery = "") }

    fun startEditing(note: Note) {
        _uiState.value = _uiState.value.copy(
            editingNote = EditDraft(
                noteId = note.id,
                content = note.content,
                category = note.category,
                priority = note.priority,
                tags = note.tags,
                attachments = note.attachments,
            ),
        )
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
                editingNote = EditDraft(
                    noteId = note.id,
                    content = note.content,
                    category = note.category,
                    priority = note.priority,
                    tags = note.tags,
                    attachments = note.attachments,
                ),
            )
        }
    }

    fun updateEditingContent(value: String) { _uiState.value = _uiState.value.copy(editingNote = _uiState.value.editingNote?.copy(content = value)) }
    fun updateEditingCategory(value: NoteCategory) { _uiState.value = _uiState.value.copy(editingNote = _uiState.value.editingNote?.copy(category = value)) }
    fun updateEditingPriority(value: NotePriority) { _uiState.value = _uiState.value.copy(editingNote = _uiState.value.editingNote?.copy(priority = value)) }
    fun updateEditingTags(value: List<String>) { _uiState.value = _uiState.value.copy(editingNote = _uiState.value.editingNote?.copy(tags = value)) }
    fun cancelEditing() {
        val draft = _uiState.value.editingNote
        // 放弃编辑时，pendingAdded 的附件已经被 AttachmentStore.import 落盘，要手动清理避免孤儿文件。
        // pendingRemoved 里的 id 作废就行——对应文件还在本地，note.attachments 没动。
        if (draft != null && draft.pendingAddedAttachments.isNotEmpty()) {
            val toDelete = draft.pendingAddedAttachments
            viewModelScope.launch(Dispatchers.IO) {
                toDelete.forEach { container.attachmentStore.delete(it) }
            }
        }
        _uiState.value = _uiState.value.copy(editingNote = null)
    }
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
                    // 合并 AI 建议的标签（不覆盖已有，只追加）
                    val mergedTags = if (suggestion.suggestedTags.isNotEmpty()) {
                        (note.tags + suggestion.suggestedTags).distinct()
                    } else {
                        note.tags
                    }
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
                        tags = mergedTags,
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

    /**
     * 把 AI 整理后的内容还原回 originalContent。语义：
     * - 仅 note.originalContent 非空才有意义；
     * - content 回滚到 originalContent，originalContent 清空（一次性还原）；
     * - title/category/priority/colorToken/tags 不动——用户可能在 apply 之后又手改过这些，
     *   一刀切回滚反而会丢用户意图，content 是 AI 改写最重的一项，先解决主要矛盾；
     * - aiSuggestion 状态打回 DISMISSED，便于用户后续重整。
     */
    fun restoreOriginalContent(noteId: String) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val note = container.noteRepository.getNote(noteId)
                        ?: error("找不到对应便签。")
                    val original = note.originalContent
                        ?: error("这条便签没有可还原的原内容。")
                    val restored = note.copy(
                        content = original,
                        originalContent = null,
                    )
                    val saved = container.noteRepository.saveNote(restored)
                    runCatching {
                        container.aiSuggestionRepository.markStatus(
                            noteId,
                            com.ydoc.app.model.AiSuggestionStatus.DISMISSED,
                        )
                    }
                    syncIfEnabled(saved)
                }
            }.onSuccess {
                _uiState.value = _uiState.value.copy(message = "已还原为原内容。")
            }.onFailure {
                _uiState.value = _uiState.value.copy(message = it.message ?: "还原失败。")
            }
        }
    }

    fun openBatchOrganize() {
        val current = _uiState.value.batchOrganize
        _uiState.value = _uiState.value.copy(
            batchOrganize = current.copy(visible = true, error = null),
        )
        // 缓存优先：首次打开（无 clusters 且非 loading）才自动分析；
        // 有上次结果的情况下用户需要点「重新分析」才重跑。
        if (current.clusters.isEmpty() && !current.loading && current.lastAnalyzedAt == null) {
            analyzeBatch()
        }
    }

    fun closeBatchOrganize() {
        // 只收起不清空：保留 clusters / lastAnalyzedAt，下次打开仍能看到上次结果。
        _uiState.value = _uiState.value.copy(
            batchOrganize = _uiState.value.batchOrganize.copy(
                visible = false,
                error = null,
                loading = false,
                applyingClusterIds = emptySet(),
            ),
        )
    }

    fun analyzeBatch() {
        val cfg = _uiState.value.settings.ai
        if (!cfg.enabled || cfg.baseUrl.isBlank()) {
            _uiState.value = _uiState.value.copy(
                batchOrganize = _uiState.value.batchOrganize.copy(
                    error = "请先在设置中启用并配置 AI",
                    loading = false,
                ),
            )
            return
        }
        val localNotes = _uiState.value.notes
            .take(50)
            .map { note ->
                com.ydoc.app.model.BatchOrganizeNoteInput(
                    id = note.id,
                    title = note.title.orEmpty(),
                    content = note.content.orEmpty(),
                    category = note.category.name,
                    tags = note.tags,
                    created_at = note.createdAt,
                )
            }
        if (localNotes.size < 2) {
            _uiState.value = _uiState.value.copy(
                batchOrganize = _uiState.value.batchOrganize.copy(
                    error = "收件箱至少需要 2 条笔记才能做批量整理",
                    loading = false,
                ),
            )
            return
        }
        _uiState.value = _uiState.value.copy(
            batchOrganize = _uiState.value.batchOrganize.copy(loading = true, error = null),
        )
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    container.aiClient.batchOrganize(
                        com.ydoc.app.model.BatchOrganizeRequest(notes = localNotes, max_notes = 50),
                        cfg,
                    )
                }
            }.onSuccess { result ->
                _uiState.value = _uiState.value.copy(
                    batchOrganize = _uiState.value.batchOrganize.copy(
                        loading = false,
                        totalAnalyzed = result.total_analyzed,
                        clusters = result.clusters,
                        appliedClusterIds = emptySet(),
                        error = null,
                        lastAnalyzedAt = System.currentTimeMillis(),
                    ),
                )
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    batchOrganize = _uiState.value.batchOrganize.copy(
                        loading = false,
                        error = e.message ?: "批量整理失败",
                    ),
                )
            }
        }
    }

    // ─── 多选模式 ───

    fun enterSelectionMode(firstNoteId: String) {
        _uiState.value = _uiState.value.copy(
            selectionMode = true,
            selectedNoteIds = setOf(firstNoteId),
        )
    }

    fun toggleSelection(noteId: String) {
        val current = _uiState.value.selectedNoteIds
        val next = if (noteId in current) current - noteId else current + noteId
        _uiState.value = _uiState.value.copy(
            selectedNoteIds = next,
            // 全取消也保持多选模式；显式退出由 exitSelectionMode 控制
        )
    }

    fun selectAllVisible() {
        val notes = when (_uiState.value.currentSection) {
            NoteListSection.INBOX -> _uiState.value.notes
            NoteListSection.ARCHIVE -> _uiState.value.archivedNotes
            NoteListSection.TRASH -> _uiState.value.trashedNotes
            else -> emptyList()
        }
        _uiState.value = _uiState.value.copy(
            selectedNoteIds = notes.map { it.id }.toSet(),
        )
    }

    fun exitSelectionMode() {
        _uiState.value = _uiState.value.copy(
            selectionMode = false,
            selectedNoteIds = emptySet(),
        )
    }

    /** 合并选中的笔记为一条新 note，原笔记移入回收站。 */
    fun mergeSelectedNotes() {
        val ids = _uiState.value.selectedNoteIds.toList()
        if (ids.size < 2) {
            _uiState.value = _uiState.value.copy(message = "至少选择 2 条笔记才能合并。")
            return
        }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    container.noteRepository.mergeNotes(ids)
                }
            }.onSuccess { newNote ->
                _uiState.value = _uiState.value.copy(
                    selectionMode = false,
                    selectedNoteIds = emptySet(),
                    message = "已合并 ${ids.size} 条笔记，原笔记已移入回收站。",
                )
                // 顺带触发一次 AI 整理（若 AI 已配置，走常规 TEXT_SAVE 链路）
                viewModelScope.launch(Dispatchers.IO) {
                    runCatching {
                        container.aiOrchestrator.maybeAnalyze(newNote.id, AiRunTrigger.TEXT_SAVE)
                    }
                }
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(message = e.message ?: "合并失败。")
            }
        }
    }

    /** 用选中的笔记做一次 AI 聚类分析，结果展示在 BatchOrganizeSheet 里。 */
    fun analyzeSelectedNotes() {
        val ids = _uiState.value.selectedNoteIds
        if (ids.size < 2) {
            _uiState.value = _uiState.value.copy(message = "至少选择 2 条笔记才能整理。")
            return
        }
        val cfg = _uiState.value.settings.ai
        if (!cfg.enabled || cfg.baseUrl.isBlank()) {
            _uiState.value = _uiState.value.copy(
                batchOrganize = _uiState.value.batchOrganize.copy(
                    visible = true,
                    error = "请先在设置中启用并配置 AI",
                    loading = false,
                ),
            )
            return
        }
        val pool = (_uiState.value.notes + _uiState.value.archivedNotes)
            .filter { it.id in ids }
        if (pool.size < 2) {
            _uiState.value = _uiState.value.copy(message = "所选笔记不足。")
            return
        }
        val inputs = pool.map { note ->
            com.ydoc.app.model.BatchOrganizeNoteInput(
                id = note.id,
                title = note.title,
                content = note.content,
                category = note.category.name,
                tags = note.tags,
                created_at = note.createdAt,
            )
        }
        _uiState.value = _uiState.value.copy(
            selectionMode = false,
            selectedNoteIds = emptySet(),
            batchOrganize = _uiState.value.batchOrganize.copy(
                visible = true,
                loading = true,
                error = null,
                clusters = emptyList(),
                appliedClusterIds = emptySet(),
            ),
        )
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    container.aiClient.batchOrganize(
                        com.ydoc.app.model.BatchOrganizeRequest(notes = inputs, max_notes = inputs.size),
                        cfg,
                    )
                }
            }.onSuccess { result ->
                _uiState.value = _uiState.value.copy(
                    batchOrganize = _uiState.value.batchOrganize.copy(
                        loading = false,
                        totalAnalyzed = result.total_analyzed,
                        clusters = result.clusters,
                        appliedClusterIds = emptySet(),
                        error = null,
                        lastAnalyzedAt = System.currentTimeMillis(),
                    ),
                )
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    batchOrganize = _uiState.value.batchOrganize.copy(
                        loading = false,
                        error = e.message ?: "整理失败",
                    ),
                )
            }
        }
    }

    fun applyCluster(cluster: com.ydoc.app.model.ClusterSuggestion) {
        val current = _uiState.value.batchOrganize
        if (cluster.cluster_id in current.appliedClusterIds) return
        _uiState.value = _uiState.value.copy(
            batchOrganize = current.copy(
                applyingClusterIds = current.applyingClusterIds + cluster.cluster_id,
            ),
        )
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    when (cluster.suggested_action) {
                        "convert_to_task" -> {
                            cluster.note_ids.forEach { nid ->
                                val note = container.noteRepository.getNote(nid) ?: return@forEach
                                container.noteRepository.saveNote(
                                    note.copy(
                                        category = com.ydoc.app.model.NoteCategory.TASK,
                                        colorToken = defaultColorFor(com.ydoc.app.model.NoteCategory.TASK, note.priority),
                                    ),
                                )
                            }
                            "${cluster.note_ids.size} 条已转为任务"
                        }
                        "merge" -> {
                            if (cluster.note_ids.isEmpty()) return@withContext "无"
                            val notes = cluster.note_ids.mapNotNull { container.noteRepository.getNote(it) }
                            if (notes.size < 2) return@withContext "无需合并"
                            val first = notes.first()
                            val merged = notes.joinToString("\n\n---\n\n") { "## ${it.title}\n${it.content}" }
                            container.noteRepository.saveNote(
                                first.copy(
                                    title = cluster.suggested_title ?: first.title,
                                    content = merged,
                                ),
                            )
                            // 其他笔记进入回收站
                            notes.drop(1).forEach { container.noteRepository.trashNote(it.id) }
                            "已合并 ${notes.size} 条笔记"
                        }
                        else -> "建议为保持独立，无需应用"
                    }
                }
            }.onSuccess { msg ->
                val nowState = _uiState.value.batchOrganize
                _uiState.value = _uiState.value.copy(
                    message = msg,
                    batchOrganize = nowState.copy(
                        appliedClusterIds = nowState.appliedClusterIds + cluster.cluster_id,
                        applyingClusterIds = nowState.applyingClusterIds - cluster.cluster_id,
                    ),
                )
            }.onFailure { e ->
                val nowState = _uiState.value.batchOrganize
                _uiState.value = _uiState.value.copy(
                    message = "应用失败：${e.message}",
                    batchOrganize = nowState.copy(
                        applyingClusterIds = nowState.applyingClusterIds - cluster.cluster_id,
                    ),
                )
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

    fun saveQuickText(rawContent: String) {
        saveNewNote(rawContent, emptyList())
    }

    /**
     * 从 NewNoteEditor 保存：文字 / 附件可任一为空，但不能都空。
     * - 只有文字 → createTextNote，和旧 saveQuickText 等价。
     * - 带附件（无论有没有文字）→ createAttachmentNote，每张图排 ImageAnalyzeWorker 做 OCR + Vision。
     */
    fun saveNewNote(rawContent: String, attachments: List<com.ydoc.app.model.NoteAttachment>) {
        val content = rawContent.trim()
        if (content.isBlank() && attachments.isEmpty()) {
            _uiState.value = _uiState.value.copy(message = "先写点什么或加一张图吧。")
            return
        }
        viewModelScope.launch {
            val note = withContext(Dispatchers.IO) {
                if (attachments.isEmpty()) {
                    container.noteRepository.createTextNote(
                        content = content,
                        category = NoteCategory.NOTE,
                        priority = NotePriority.MEDIUM,
                        tags = emptyList(),
                    )
                } else {
                    container.noteRepository.createAttachmentNote(
                        attachments = attachments,
                        hint = content,
                    )
                }
            }
            // 每张图排一轮 OCR + Vision 分析
            attachments.forEach { att ->
                com.ydoc.app.sync.ImageAnalyzeWorker.scheduleBoth(
                    getApplication(), note.id, att.id,
                )
            }
            _uiState.value = _uiState.value.copy(
                showNewNoteEditor = false,
                isSaving = false,
                message = if (attachments.isEmpty()) "已保存到本地 inbox。"
                    else "已保存带 ${attachments.size} 张图的笔记。",
            )
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { syncIfEnabled(note) }
            }
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { container.aiOrchestrator.maybeAnalyze(note.id, AiRunTrigger.TEXT_SAVE) }
            }
        }
    }

    /**
     * EditNoteCard 的「+ 加图」按钮调用：新 imported 的附件先 stage 在 draft，**不**立即落库。
     * 用户点保存才真正 commit（见 saveEditedNote），点取消会清盘回滚（见 cancelEditing）。
     */
    fun stageAddAttachments(attachments: List<com.ydoc.app.model.NoteAttachment>) {
        if (attachments.isEmpty()) return
        val draft = _uiState.value.editingNote ?: return
        _uiState.value = _uiState.value.copy(
            editingNote = draft.copy(
                pendingAddedAttachments = draft.pendingAddedAttachments + attachments,
            ),
        )
    }

    /**
     * EditNoteCard 的附件 × 按钮调用：
     * - 刚 stage 的新图（在 pendingAdded 里）→ 直接从 pending 剔除 + 清盘（没必要延迟）
     * - 已存在的老图 → 只标记为待删，保存才真删，取消能回滚
     */
    fun stageRemoveAttachment(attachmentId: String) {
        val draft = _uiState.value.editingNote ?: return
        val pendingAdded = draft.pendingAddedAttachments.firstOrNull { it.id == attachmentId }
        if (pendingAdded != null) {
            viewModelScope.launch(Dispatchers.IO) { container.attachmentStore.delete(pendingAdded) }
            _uiState.value = _uiState.value.copy(
                editingNote = draft.copy(
                    pendingAddedAttachments = draft.pendingAddedAttachments.filterNot { it.id == attachmentId },
                ),
            )
        } else {
            _uiState.value = _uiState.value.copy(
                editingNote = draft.copy(
                    pendingRemovedAttachmentIds = draft.pendingRemovedAttachmentIds + attachmentId,
                ),
            )
        }
    }

    fun removeTagFromNote(noteId: String, tag: String) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val note = container.noteRepository.getNote(noteId) ?: return@withContext
                    if (tag !in note.tags) return@withContext
                    container.noteRepository.saveNote(note.copy(tags = note.tags - tag))
                }
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(message = e.message ?: "删除标签失败。")
            }
        }
    }

    fun saveEditedNote() {
        val editing = _uiState.value.editingNote ?: return
        // 附件可以单独存在于笔记里（加了图但删了文字也算有效内容），所以只在"文字为空 && effectiveAttachments 为空"时拒保存。
        if (editing.content.trim().isBlank() && editing.effectiveAttachments.isEmpty()) {
            _uiState.value = _uiState.value.copy(message = "编辑内容不能为空。")
            return
        }
        viewModelScope.launch {
            // 1. 先把 pending 的附件操作 commit 到 DB，让后续 saveEditedNote 拿到的 existing.attachments 已是最新。
            withContext(Dispatchers.IO) {
                // 删除：先清盘（对应文件属于 attachments 快照里的 File）再从笔记 attachments 里摘
                editing.pendingRemovedAttachmentIds.forEach { attId ->
                    val target = editing.attachments.firstOrNull { it.id == attId }
                    container.noteRepository.removeAttachmentFromNote(editing.noteId, attId)
                    if (target != null) {
                        container.attachmentStore.delete(target)
                        // relay 侧孤儿：best-effort 清远端文件。失败不阻塞本地删除。
                        runCatching { deleteRemoteAttachmentIfAny(target) }
                    }
                }
                // 新增：落库 + 排 OCR+Vision Worker
                if (editing.pendingAddedAttachments.isNotEmpty()) {
                    container.noteRepository.addAttachmentsToNote(editing.noteId, editing.pendingAddedAttachments)
                    editing.pendingAddedAttachments.forEach { att ->
                        com.ydoc.app.sync.ImageAnalyzeWorker.scheduleBoth(
                            getApplication(), editing.noteId, att.id,
                        )
                    }
                }
            }
            // 2. 再保存内容字段（content / category / priority / tags）
            val updated = withContext(Dispatchers.IO) {
                val existing = container.noteRepository.getNote(editing.noteId) ?: return@withContext null
                container.noteRepository.saveEditedNote(
                    existing.copy(
                        content = editing.content.trim(),
                        category = editing.category,
                        priority = editing.priority,
                        colorToken = defaultColorFor(editing.category, editing.priority),
                        tags = editing.tags,
                    ),
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

    /** 尝试调 relay DELETE 清掉服务端的图片副本。relay 没配 / 没 remoteUrl / 网络失败都吃掉。 */
    private suspend fun deleteRemoteAttachmentIfAny(attachment: com.ydoc.app.model.NoteAttachment) {
        val remote = attachment.remoteUrl?.takeIf { it.isNotBlank() } ?: return
        val relay = _uiState.value.settings.relay
        if (!relay.enabled || relay.baseUrl.isBlank() || relay.token.isBlank()) return
        runCatching { container.imageAnalyzeClient.deleteByRemoteUrl(relay, remote) }
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

    /** 主界面 category pill 点击切换类型：没变化就 noop；变了就立即写库 + 触发同步。 */
    fun changeNoteCategory(noteId: String, category: NoteCategory) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val note = container.noteRepository.getNote(noteId) ?: return@launch
                if (note.category == category) return@launch
                val updated = container.noteRepository.saveNote(
                    note.copy(
                        category = category,
                        colorToken = defaultColorFor(category, note.priority),
                    ),
                )
                runCatching { syncIfEnabled(updated) }
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(message = e.message ?: "切换类型失败。")
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
                        // 清图片附件的本地文件 + relay 远端副本（best-effort，一个失败不影响其它）
                        note.attachments.forEach { att ->
                            runCatching { container.attachmentStore.delete(att) }
                            runCatching { deleteRemoteAttachmentIfAny(att) }
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
            pendingQuickRecord = false,
            recording = RecordingUiState(state = RecordingState.STARTING),
        )
        viewModelScope.launch {
            val app = getApplication<Application>()
            val startResult = withContext(Dispatchers.IO) {
                try {
                    if (container.audioRecorder.isRecording) {
                        container.audioRecorder.cancel()
                        app.stopService(Intent(app, RecordingService::class.java))
                    }
                    // Android 14 FOREGROUND_SERVICE_MICROPHONE：先把 FGS 拉起再录音，
                    // 否则进程被后台化时 MediaRecorder.start() 直接抛异常。
                    try {
                        ContextCompat.startForegroundService(app, Intent(app, RecordingService::class.java))
                    } catch (serviceError: Throwable) {
                        throw IllegalStateException("录音服务启动失败，请重试。", serviceError)
                    }
                    delay(120)
                    val output = try {
                        container.audioRecorder.start()
                    } catch (error: Throwable) {
                        app.stopService(Intent(app, RecordingService::class.java))
                        throw error
                    }
                    Result.success(output.path)
                } catch (e: Throwable) {
                    Result.failure<String>(e)
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
                    pendingQuickRecord = false,
                    message = error?.message ?: "录音启动失败。",
                )
            }
        }
    }

    fun stopRecording() {
        val currentPriority = NotePriority.MEDIUM
        val currentSettings = _uiState.value.settings
        _uiState.value = _uiState.value.copy(
            pendingQuickRecord = false,
            recording = _uiState.value.recording.copy(state = RecordingState.SAVING),
        )
        viewModelScope.launch {
            val app = getApplication<Application>()
            // 第一阶段：本地落 note（几百毫秒），立刻切 IDLE 让 dock 恢复两个按钮。
            val createResult = withContext(Dispatchers.IO) {
                runCatching {
                    container.voiceNoteProcessor.stopAndCreateNote(currentPriority)
                }
            }
            app.stopService(Intent(app, RecordingService::class.java))
            stopRecordingTimer()

            createResult.onSuccess { saveResult ->
                val note = saveResult.note
                val completionMessage = pendingRecordingCompletionMessage
                pendingRecordingCompletionMessage = null
                val uploadingRelay = currentSettings.relay.enabled
                val submitVolc = uploadingRelay && currentSettings.volcengine.enabled
                val quickMessage = when {
                    completionMessage != null -> completionMessage
                    submitVolc -> "录音已保存，后台上传并转写中…"
                    uploadingRelay -> "录音已保存，后台上传中转服务中…"
                    else -> saveResult.buildUserMessage(null)
                }
                _uiState.value = _uiState.value.copy(message = quickMessage)

                // 第二阶段：relay upload + 豆包转写 + AI 分析；独立 coroutine，不阻塞 UI。
                viewModelScope.launch(Dispatchers.IO) {
                    val processResult = runCatching {
                        if (uploadingRelay) {
                            container.voiceNoteProcessor.processNoteInBackground(
                                noteId = note.id,
                                relayConfig = currentSettings.relay,
                                volcengineConfig = currentSettings.volcengine,
                                wifiOnly = currentSettings.webDav.wifiOnly,
                            )
                        } else {
                            saveResult
                        }
                    }
                    processResult.onSuccess { finalResult ->
                        // 后台处理有错误消息（如转写失败）时，再通过 message 通知一次
                        finalResult.remoteError?.takeIf { it.isNotBlank() }?.let { err ->
                            _uiState.value = _uiState.value.copy(message = err)
                        }
                        runCatching { syncIfEnabled(finalResult.note) }
                    }.onFailure { e ->
                        _uiState.value = _uiState.value.copy(
                            message = e.message?.take(200) ?: "后台处理失败。",
                        )
                        runCatching { syncIfEnabled(note) }
                    }
                }
            }.onFailure { e ->
                pendingRecordingCompletionMessage = null
                _uiState.value = _uiState.value.copy(
                    message = e.message ?: "录音保存失败。",
                )
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

    fun toggleAiLocalOcr(enabled: Boolean) {
        val current = _uiState.value.settings
        _uiState.value = _uiState.value.copy(
            settings = current.copy(
                ai = current.ai.copy(localOcrEnabled = enabled),
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
