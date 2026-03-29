package com.ydoc.app.ui

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ydoc.app.data.AppContainer
import com.ydoc.app.model.Note
import com.ydoc.app.model.NoteCategory
import com.ydoc.app.model.NotePriority
import com.ydoc.app.model.OverlayConfig
import com.ydoc.app.model.RecordingState
import com.ydoc.app.model.RecordingUiState
import com.ydoc.app.model.SyncSettingsState
import com.ydoc.app.model.SyncTarget
import com.ydoc.app.model.SyncType
import com.ydoc.app.model.VolcengineConfig
import com.ydoc.app.model.WebDavConfig
import com.ydoc.app.recording.RecordingService
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

data class CaptureDraft(
    val content: String = "",
    val category: NoteCategory = NoteCategory.NOTE,
    val priority: NotePriority = NotePriority.MEDIUM,
)

data class EditDraft(
    val noteId: String,
    val content: String,
    val category: NoteCategory,
    val priority: NotePriority,
)

data class AppUiState(
    val draft: CaptureDraft = CaptureDraft(),
    val notes: List<Note> = emptyList(),
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

    init {
        viewModelScope.launch {
            container.syncTargetRepository.seedDefaults()
            launch {
                container.noteRepository.observeNotes().collect { notes ->
                    _uiState.value = _uiState.value.copy(notes = notes)
                }
            }
            launch {
                container.syncTargetRepository.observeTargets().collect { targets ->
                    _uiState.value = _uiState.value.copy(syncTargets = targets, syncHint = buildSyncHint(targets))
                }
            }
            launch {
                container.settingsStore.settingsFlow.collect { stored ->
                    _uiState.value = _uiState.value.copy(
                        settings = _uiState.value.settings.copy(
                            overlay = stored.overlay,
                            relay = stored.relay,
                            volcengine = stored.volcengine,
                            hasUnsavedChanges = false,
                        ),
                    )
                }
            }
            refreshSettings()
        }
    }

    fun updateDraftContent(value: String) { _uiState.value = _uiState.value.copy(draft = _uiState.value.draft.copy(content = value)) }
    fun updateDraftCategory(value: NoteCategory) { _uiState.value = _uiState.value.copy(draft = _uiState.value.draft.copy(category = value)) }
    fun updateDraftPriority(value: NotePriority) { _uiState.value = _uiState.value.copy(draft = _uiState.value.draft.copy(priority = value)) }

    fun startEditing(note: Note) {
        _uiState.value = _uiState.value.copy(editingNote = EditDraft(note.id, note.content, note.category, note.priority))
    }

    fun updateEditingContent(value: String) { _uiState.value = _uiState.value.copy(editingNote = _uiState.value.editingNote?.copy(content = value)) }
    fun updateEditingCategory(value: NoteCategory) { _uiState.value = _uiState.value.copy(editingNote = _uiState.value.editingNote?.copy(category = value)) }
    fun updateEditingPriority(value: NotePriority) { _uiState.value = _uiState.value.copy(editingNote = _uiState.value.editingNote?.copy(priority = value)) }
    fun cancelEditing() { _uiState.value = _uiState.value.copy(editingNote = null) }
    fun clearMessage() { _uiState.value = _uiState.value.copy(message = null) }
    fun dismissMicrophonePermissionRequest() { _uiState.value = _uiState.value.copy(requiresMicrophonePermission = false) }
    fun dismissOverlayPermissionRequest() {
        _uiState.value = _uiState.value.copy(
            settings = _uiState.value.settings.copy(requiresOverlayPermission = false),
        )
    }
    fun prepareQuickRecordLaunch() { _uiState.value = _uiState.value.copy(pendingQuickRecord = true) }
    fun consumeQuickRecordLaunch() { _uiState.value = _uiState.value.copy(pendingQuickRecord = false) }

    fun saveDraft() {
        val draft = _uiState.value.draft
        val content = draft.content.trim()
        if (content.isBlank()) {
            _uiState.value = _uiState.value.copy(message = "先输入一点内容。")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            withContext(Dispatchers.IO) {
                runCatching {
                    val note = container.noteRepository.createTextNote(content, draft.category, draft.priority)
                    syncIfEnabled(note)
                }
            }.onSuccess {
                _uiState.value = _uiState.value.copy(
                    draft = CaptureDraft(category = draft.category, priority = draft.priority),
                    message = if (_uiState.value.settings.webDavEnabled && _uiState.value.settings.webDav.autoSync) "已保存并触发实时同步。" else "已保存到本地 inbox。",
                )
            }.onFailure {
                _uiState.value = _uiState.value.copy(message = it.message ?: "保存失败。")
            }
            _uiState.value = _uiState.value.copy(isSaving = false)
        }
    }

    fun saveEditedNote() {
        val editing = _uiState.value.editingNote ?: return
        if (editing.content.trim().isBlank()) {
            _uiState.value = _uiState.value.copy(message = "编辑内容不能为空。")
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            withContext(Dispatchers.IO) {
                runCatching {
                    val existing = container.noteRepository.getNote(editing.noteId) ?: error("找不到这条记录")
                    val updated = container.noteRepository.saveEditedNote(existing.copy(content = editing.content.trim(), category = editing.category, priority = editing.priority))
                    syncIfEnabled(updated)
                }
            }.onSuccess {
                _uiState.value = _uiState.value.copy(editingNote = null, message = "记录已更新。")
            }.onFailure {
                _uiState.value = _uiState.value.copy(message = it.message ?: "更新失败。")
            }
            _uiState.value = _uiState.value.copy(isSaving = false)
        }
    }

    fun deleteNote(noteId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val note = container.noteRepository.getNote(noteId)
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
                _uiState.value = _uiState.value.copy(editingNote = _uiState.value.editingNote?.takeIf { it.noteId != noteId }, message = "记录已删除。")
            }.onFailure {
                _uiState.value = _uiState.value.copy(message = it.message ?: "删除失败。")
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
        if (!hasMicrophonePermission()) {
            _uiState.value = _uiState.value.copy(requiresMicrophonePermission = true)
            return
        }
        viewModelScope.launch {
            val app = getApplication<Application>()
            withContext(Dispatchers.IO) {
                runCatching {
                    val output = container.audioRecorder.start()
                    ContextCompat.startForegroundService(app, Intent(app, RecordingService::class.java))
                    output.path
                }
            }.onSuccess { path -> startRecordingTimer(path) }
                .onFailure { _uiState.value = _uiState.value.copy(message = it.message ?: "录音启动失败。") }
        }
    }

    fun stopRecording() {
        val currentPriority = _uiState.value.draft.priority
        _uiState.value = _uiState.value.copy(recording = _uiState.value.recording.copy(state = RecordingState.SAVING))
        viewModelScope.launch {
            val app = getApplication<Application>()
            withContext(Dispatchers.IO) {
                runCatching {
                    val output = container.audioRecorder.stop()
                    app.stopService(Intent(app, RecordingService::class.java))
                    var note = container.noteRepository.createVoiceNote(output.path, output.format, currentPriority)
                    val relayConfig = _uiState.value.settings.relay
                    if (relayConfig.enabled) {
                        val upload = container.relayStorageClient.upload(java.io.File(output.path), relayConfig)
                        note = container.noteRepository.attachRelayInfo(note, upload.fileId, upload.url, upload.expiresAt)
                    }
                    val volcengineConfig = _uiState.value.settings.volcengine
                    if (relayConfig.enabled && volcengineConfig.enabled && !note.relayUrl.isNullOrBlank()) {
                        runCatching {
                            container.transcriptionOrchestrator.transcribe(note, volcengineConfig)
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
            _uiState.value = _uiState.value.copy(message = "录音已取消。")
        }
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
        _uiState.value = _uiState.value.copy(recording = RecordingUiState())
    }

    private suspend fun syncIfEnabled(note: Note) {
        val settingsSnapshot = _uiState.value.settings
        val autoSyncEnabled = settingsSnapshot.webDavEnabled && settingsSnapshot.webDav.autoSync
        if (autoSyncEnabled) {
            container.syncOrchestrator.syncNote(note).getOrElse {
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

    companion object {
        fun factory(application: Application, container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = AppViewModel(application, container) as T
            }
    }
}
