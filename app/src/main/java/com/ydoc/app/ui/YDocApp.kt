package com.ydoc.app.ui

import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ydoc.app.data.AppContainer
import com.ydoc.app.model.Note
import com.ydoc.app.model.NoteCategory
import com.ydoc.app.model.NoteColorToken
import com.ydoc.app.model.NotePriority
import com.ydoc.app.model.RecordingState
import com.ydoc.app.model.RecordingUiState
import com.ydoc.app.model.SyncSettingsState
import com.ydoc.app.model.VolcengineConfig
import com.ydoc.app.model.WebDavConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun YDocApp(
    container: AppContainer,
    factory: ViewModelProvider.Factory,
    hasOverlayPermission: Boolean,
    onRequestOverlayPermission: () -> Unit,
    onToggleOverlay: (Boolean) -> Unit,
    quickRecordRequested: Boolean,
    onRequestRecordingPermissions: () -> Unit,
) {
    val viewModel: AppViewModel = viewModel(factory = factory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(state.requiresMicrophonePermission) {
        if (state.requiresMicrophonePermission) {
            onRequestRecordingPermissions()
            viewModel.dismissMicrophonePermissionRequest()
        }
    }

    LaunchedEffect(state.settings.requiresOverlayPermission) {
        if (state.settings.requiresOverlayPermission) {
            onRequestOverlayPermission()
            viewModel.dismissOverlayPermissionRequest()
        }
    }

    LaunchedEffect(state.settings.overlay.enabled) {
        onToggleOverlay(state.settings.overlay.enabled)
    }

    LaunchedEffect(quickRecordRequested) {
        if (quickRecordRequested) {
            viewModel.prepareQuickRecordLaunch()
        }
    }

    LaunchedEffect(state.pendingQuickRecord, state.requiresMicrophonePermission, state.recording.state) {
        if (state.pendingQuickRecord && !state.requiresMicrophonePermission && state.recording.state == RecordingState.IDLE) {
            viewModel.startRecording()
            viewModel.consumeQuickRecordLaunch()
        }
    }

    YDocScreen(
        state = state,
        showSettings = showSettings,
        onOpenSettings = { showSettings = true },
        onCloseSettings = { showSettings = false },
        snackbarHostState = snackbarHostState,
        onDraftChange = viewModel::updateDraftContent,
        onDraftCategoryChange = viewModel::updateDraftCategory,
        onDraftPriorityChange = viewModel::updateDraftPriority,
        onSave = viewModel::saveDraft,
        onSync = viewModel::syncNow,
        onStartRecording = viewModel::startRecording,
        onStopRecording = viewModel::stopRecording,
        onCancelRecording = viewModel::cancelRecording,
        onWebDavChange = viewModel::updateWebDavConfig,
        onToggleWebDav = viewModel::toggleWebDavEnabled,
        onRelayBaseUrlChange = viewModel::updateRelayBaseUrl,
        onRelayTokenChange = viewModel::updateRelayToken,
        onToggleRelay = viewModel::toggleRelayEnabled,
        onVolcengineAppIdChange = viewModel::updateVolcengineAppId,
        onVolcengineAccessTokenChange = viewModel::updateVolcengineAccessToken,
        onVolcengineResourceIdChange = viewModel::updateVolcengineResourceId,
        onToggleVolcengine = viewModel::toggleVolcengineEnabled,
        onOverlayHandleSizeChange = viewModel::updateOverlayHandleSize,
        onOverlayHandleAlphaChange = viewModel::updateOverlayHandleAlpha,
        hasOverlayPermission = hasOverlayPermission,
        onToggleOverlay = { enabled -> viewModel.requestOverlayToggle(enabled, hasOverlayPermission) },
        onSaveSettings = viewModel::saveSettings,
        onTestWebDav = viewModel::testWebDavConnection,
        onTestRelay = viewModel::testRelayConnection,
        onTestVolcengine = viewModel::testVolcengineConnection,
        onEditNote = viewModel::startEditing,
        onDeleteNote = viewModel::deleteNote,
        onRetrySync = viewModel::retrySync,
        onUpdateEditingContent = viewModel::updateEditingContent,
        onUpdateEditingCategory = viewModel::updateEditingCategory,
        onUpdateEditingPriority = viewModel::updateEditingPriority,
        onSaveEditedNote = viewModel::saveEditedNote,
        onCancelEditing = viewModel::cancelEditing,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun YDocScreen(
    state: AppUiState,
    showSettings: Boolean,
    onOpenSettings: () -> Unit,
    onCloseSettings: () -> Unit,
    snackbarHostState: SnackbarHostState,
    onDraftChange: (String) -> Unit,
    onDraftCategoryChange: (NoteCategory) -> Unit,
    onDraftPriorityChange: (NotePriority) -> Unit,
    onSave: () -> Unit,
    onSync: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onCancelRecording: () -> Unit,
    onWebDavChange: ((WebDavConfig) -> WebDavConfig) -> Unit,
    onToggleWebDav: (Boolean) -> Unit,
    onRelayBaseUrlChange: (String) -> Unit,
    onRelayTokenChange: (String) -> Unit,
    onToggleRelay: (Boolean) -> Unit,
    onVolcengineAppIdChange: (String) -> Unit,
    onVolcengineAccessTokenChange: (String) -> Unit,
    onVolcengineResourceIdChange: (String) -> Unit,
    onToggleVolcengine: (Boolean) -> Unit,
    onOverlayHandleSizeChange: (Int) -> Unit,
    onOverlayHandleAlphaChange: (Float) -> Unit,
    hasOverlayPermission: Boolean,
    onToggleOverlay: (Boolean) -> Unit,
    onSaveSettings: () -> Unit,
    onTestWebDav: () -> Unit,
    onTestRelay: () -> Unit,
    onTestVolcengine: () -> Unit,
    onEditNote: (Note) -> Unit,
    onDeleteNote: (String) -> Unit,
    onRetrySync: (String) -> Unit,
    onUpdateEditingContent: (String) -> Unit,
    onUpdateEditingCategory: (NoteCategory) -> Unit,
    onUpdateEditingPriority: (NotePriority) -> Unit,
    onSaveEditedNote: () -> Unit,
    onCancelEditing: () -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.editingNote?.noteId) {
        if (state.editingNote != null) {
            listState.animateScrollToItem(1)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(if (showSettings) "设置" else "YDoc") },
                actions = {
                    IconButton(onClick = { if (showSettings) onCloseSettings() else onOpenSettings() }) {
                        Icon(Icons.Rounded.Settings, contentDescription = "设置")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (showSettings) {
                item {
                    SettingsCard(
                        settings = state.settings,
                        onWebDavChange = onWebDavChange,
                        onToggleWebDav = onToggleWebDav,
                        onRelayBaseUrlChange = onRelayBaseUrlChange,
                        onRelayTokenChange = onRelayTokenChange,
                        onToggleRelay = onToggleRelay,
                        onVolcengineAppIdChange = onVolcengineAppIdChange,
                        onVolcengineAccessTokenChange = onVolcengineAccessTokenChange,
                        onVolcengineResourceIdChange = onVolcengineResourceIdChange,
                        onToggleVolcengine = onToggleVolcengine,
                        onOverlayHandleSizeChange = onOverlayHandleSizeChange,
                        onOverlayHandleAlphaChange = onOverlayHandleAlphaChange,
                        hasOverlayPermission = hasOverlayPermission,
                        onToggleOverlay = onToggleOverlay,
                        onSaveSettings = onSaveSettings,
                        onTestWebDav = onTestWebDav,
                        onTestRelay = onTestRelay,
                        onTestVolcengine = onTestVolcengine,
                    )
                }
            } else {
                item {
                    HeroCaptureCard(
                        draft = state.draft,
                        isSaving = state.isSaving,
                        isSyncing = state.isSyncing,
                        syncHint = state.syncHint,
                        recording = state.recording,
                        onDraftChange = onDraftChange,
                        onDraftCategoryChange = onDraftCategoryChange,
                        onDraftPriorityChange = onDraftPriorityChange,
                        onSave = onSave,
                        onSync = onSync,
                        onStartRecording = onStartRecording,
                        onStopRecording = onStopRecording,
                        onCancelRecording = onCancelRecording,
                    )
                }
                state.editingNote?.let { editing ->
                    item {
                        EditNoteCard(
                            editing = editing,
                            onUpdateContent = onUpdateEditingContent,
                            onUpdateCategory = onUpdateEditingCategory,
                            onUpdatePriority = onUpdateEditingPriority,
                            onSave = onSaveEditedNote,
                            onCancel = onCancelEditing,
                        )
                    }
                }
                item {
                    Text("最近记录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                if (state.notes.isEmpty()) {
                    item { EmptyStateCard() }
                } else {
                    items(state.notes, key = { it.id }) { note ->
                        NoteCard(
                            note = note,
                            onEdit = { onEditNote(note) },
                            onDelete = { onDeleteNote(note.id) },
                            onRetrySync = { onRetrySync(note.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroCaptureCard(
    draft: CaptureDraft,
    isSaving: Boolean,
    isSyncing: Boolean,
    syncHint: String,
    recording: RecordingUiState,
    onDraftChange: (String) -> Unit,
    onDraftCategoryChange: (NoteCategory) -> Unit,
    onDraftPriorityChange: (NotePriority) -> Unit,
    onSave: () -> Unit,
    onSync: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onCancelRecording: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .padding(22.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("快速记录", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "把想法、待办、提醒先抓进 inbox。文字和录音都会在同一条同步链路里实时推送。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RecordingStrip(recording, onStartRecording, onStopRecording, onCancelRecording)
            OutlinedTextField(
                value = draft.content,
                onValueChange = onDraftChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("输入内容") },
                placeholder = { Text("比如：给悬浮把手加长按录音") },
                minLines = 4,
            )
            Text("类型", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            FlowRowChips(NoteCategory.entries.map { it.name to (draft.category == it) }) { onDraftCategoryChange(NoteCategory.valueOf(it)) }
            Text("重要程度", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            FlowRowChips(NotePriority.entries.map { it.name to (draft.priority == it) }) { onDraftPriorityChange(NotePriority.valueOf(it)) }
            Text(syncHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RecordingPrimaryButton(recording = recording, onStartRecording = onStartRecording, onStopRecording = onStopRecording, onCancelRecording = onCancelRecording)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = onSave,
                        enabled = !isSaving,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        if (isSaving) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) else Text("保存并同步")
                    }
                    AssistChip(onClick = onSync, label = { if (isSyncing) Text("同步中") else Text("立即同步") })
                }
            }
        }
    }
}

@Composable
private fun RecordingPrimaryButton(
    recording: RecordingUiState,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onCancelRecording: () -> Unit,
) {
    Button(
        onClick = if (recording.state == RecordingState.IDLE) onStartRecording else onStopRecording,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
    ) {
        Text(
            when (recording.state) {
                RecordingState.IDLE -> "开始语音记录"
                RecordingState.RECORDING -> "停止并保存录音"
                RecordingState.SAVING -> "正在保存录音..."
            },
        )
    }
}

@Composable
private fun EditNoteCard(
    editing: EditDraft,
    onUpdateContent: (String) -> Unit,
    onUpdateCategory: (NoteCategory) -> Unit,
    onUpdatePriority: (NotePriority) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("编辑记录", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            OutlinedTextField(value = editing.content, onValueChange = onUpdateContent, modifier = Modifier.fillMaxWidth(), minLines = 4, label = { Text("内容") })
            FlowRowChips(NoteCategory.entries.map { it.name to (editing.category == it) }) { onUpdateCategory(NoteCategory.valueOf(it)) }
            FlowRowChips(NotePriority.entries.map { it.name to (editing.priority == it) }) { onUpdatePriority(NotePriority.valueOf(it)) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onSave) { Text("保存修改") }
                AssistChip(onClick = onCancel, label = { Text("取消") })
            }
        }
    }
}

@Composable
private fun RecordingStrip(
    recording: RecordingUiState,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onCancelRecording: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)),
        shape = RoundedCornerShape(22.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(
                        color = when (recording.state) {
                            RecordingState.IDLE -> MaterialTheme.colorScheme.secondary
                            RecordingState.RECORDING -> MaterialTheme.colorScheme.error
                            RecordingState.SAVING -> MaterialTheme.colorScheme.tertiary
                        },
                        shape = RoundedCornerShape(999.dp),
                    ),
            )
            Spacer(modifier = Modifier.size(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("语音入口", fontWeight = FontWeight.SemiBold)
                Text(
                    when (recording.state) {
                        RecordingState.IDLE -> "点击开始录音，系统会用前台服务保持录音稳定。"
                        RecordingState.RECORDING -> "录音中 ${recording.elapsedSeconds}s"
                        RecordingState.SAVING -> "正在保存录音，并尝试上传中转与提交豆包转写..."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(
    settings: SyncSettingsState,
    onWebDavChange: ((WebDavConfig) -> WebDavConfig) -> Unit,
    onToggleWebDav: (Boolean) -> Unit,
    onRelayBaseUrlChange: (String) -> Unit,
    onRelayTokenChange: (String) -> Unit,
    onToggleRelay: (Boolean) -> Unit,
    onVolcengineAppIdChange: (String) -> Unit,
    onVolcengineAccessTokenChange: (String) -> Unit,
    onVolcengineResourceIdChange: (String) -> Unit,
    onToggleVolcengine: (Boolean) -> Unit,
    onOverlayHandleSizeChange: (Int) -> Unit,
    onOverlayHandleAlphaChange: (Float) -> Unit,
    hasOverlayPermission: Boolean,
    onToggleOverlay: (Boolean) -> Unit,
    onSaveSettings: () -> Unit,
    onTestWebDav: () -> Unit,
    onTestRelay: () -> Unit,
    onTestVolcengine: () -> Unit,
) {
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("同步与转写配置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            WebDavSettingsSection(settings, onWebDavChange, onToggleWebDav, onTestWebDav)
            HorizontalDivider()
            RelaySettingsSection(settings, onRelayBaseUrlChange, onRelayTokenChange, onToggleRelay, onTestRelay)
            HorizontalDivider()
            VolcengineSettingsSection(settings.volcengine, settings.isTestingVolcengine, onVolcengineAppIdChange, onVolcengineAccessTokenChange, onVolcengineResourceIdChange, onToggleVolcengine, onTestVolcengine)
            HorizontalDivider()
            OverlaySettingsSection(
                enabled = settings.overlay.enabled,
                handleSizeDp = settings.overlay.handleSizeDp,
                handleAlpha = settings.overlay.handleAlpha,
                hasPermission = hasOverlayPermission,
                onToggleOverlay = onToggleOverlay,
                onHandleSizeChange = onOverlayHandleSizeChange,
                onHandleAlphaChange = onOverlayHandleAlphaChange,
            )
            Button(onClick = onSaveSettings, enabled = settings.hasUnsavedChanges) {
                Text("保存设置")
            }
        }
    }
}

@Composable
private fun WebDavSettingsSection(
    settings: SyncSettingsState,
    onWebDavChange: ((WebDavConfig) -> WebDavConfig) -> Unit,
    onToggleWebDav: (Boolean) -> Unit,
    onTestWebDav: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("NAS 同步", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("只保留 WebDAV，同步路径更清晰。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = settings.webDavEnabled, onCheckedChange = onToggleWebDav)
        }
        OutlinedTextField(value = settings.webDav.baseUrl, onValueChange = { onWebDavChange { current -> current.copy(baseUrl = it) } }, label = { Text("WebDAV 地址") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = settings.webDav.username, onValueChange = { onWebDavChange { current -> current.copy(username = it) } }, label = { Text("用户名") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = settings.webDav.password, onValueChange = { onWebDavChange { current -> current.copy(password = it) } }, label = { Text("密码") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = settings.webDav.folder, onValueChange = { onWebDavChange { current -> current.copy(folder = it) } }, label = { Text("云端目录") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("自动同步", modifier = Modifier.weight(1f))
            Switch(checked = settings.webDav.autoSync, onCheckedChange = { onWebDavChange { current -> current.copy(autoSync = it) } })
        }
        if (settings.webDav.autoSync) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("同步间隔", modifier = Modifier.weight(1f))
                val intervals = listOf(5 to "5 分钟", 15 to "15 分钟", 30 to "30 分钟", 60 to "1 小时")
                var expanded by remember { mutableStateOf(false) }
                val currentLabel = intervals.firstOrNull { it.first == settings.webDav.syncIntervalMinutes }?.second ?: "${settings.webDav.syncIntervalMinutes} 分钟"
                Box {
                    AssistChip(onClick = { expanded = true }, label = { Text(currentLabel) })
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        intervals.forEach { (minutes, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    onWebDavChange { current -> current.copy(syncIntervalMinutes = minutes) }
                                    expanded = false
                                },
                            )
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("仅 Wi-Fi 同步", modifier = Modifier.weight(1f))
            Switch(checked = settings.webDav.wifiOnly, onCheckedChange = { onWebDavChange { current -> current.copy(wifiOnly = it) } })
        }
        AssistChip(onClick = onTestWebDav, label = { if (settings.isTestingWebDav) Text("测试中") else Text("测试 WebDAV") })
    }
}

@Composable
private fun RelaySettingsSection(
    settings: SyncSettingsState,
    onRelayBaseUrlChange: (String) -> Unit,
    onRelayTokenChange: (String) -> Unit,
    onToggleRelay: (Boolean) -> Unit,
    onTestRelay: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("中转服务", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("录音可先上传到自建 FastAPI 中转服务，生成豆包可访问的外链。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = settings.relay.enabled, onCheckedChange = onToggleRelay)
        }
        OutlinedTextField(value = settings.relay.baseUrl, onValueChange = onRelayBaseUrlChange, label = { Text("Relay Base URL") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = settings.relay.token, onValueChange = onRelayTokenChange, label = { Text("Relay Token") }, modifier = Modifier.fillMaxWidth())
        AssistChip(onClick = onTestRelay, label = { if (settings.isTestingRelay) Text("测试中") else Text("测试中转服务") })
    }
}

@Composable
private fun VolcengineSettingsSection(
    config: VolcengineConfig,
    isTesting: Boolean,
    onAppIdChange: (String) -> Unit,
    onAccessTokenChange: (String) -> Unit,
    onResourceIdChange: (String) -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onTest: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("豆包语音转写", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("这里配置 App ID、Access Token 和 Resource ID。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = config.enabled, onCheckedChange = onToggleEnabled)
        }
        OutlinedTextField(value = config.appId, onValueChange = onAppIdChange, label = { Text("App ID") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = config.accessToken, onValueChange = onAccessTokenChange, label = { Text("Access Token") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = config.resourceId, onValueChange = onResourceIdChange, label = { Text("Resource ID") }, modifier = Modifier.fillMaxWidth())
        AssistChip(onClick = onTest, label = { if (isTesting) Text("测试中") else Text("测试豆包配置") })
    }
}

@Composable
private fun OverlaySettingsSection(
    enabled: Boolean,
    handleSizeDp: Int,
    handleAlpha: Float,
    hasPermission: Boolean,
    onToggleOverlay: (Boolean) -> Unit,
    onHandleSizeChange: (Int) -> Unit,
    onHandleAlphaChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("悬浮侧边把手", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    if (hasPermission) "启用后可从任意界面拉出最近记录、文字输入和长按录音。" else "启用前需要先授予悬浮窗权限。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggleOverlay)
        }
        Text("把手大小 ${handleSizeDp}dp", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = handleSizeDp.toFloat(),
            onValueChange = { onHandleSizeChange(it.toInt()) },
            valueRange = 16f..48f,
        )
        Text("把手透明度 ${(handleAlpha * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = handleAlpha,
            onValueChange = onHandleAlphaChange,
            valueRange = 0.2f..1f,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowChips(labels: List<Pair<String, Boolean>>, onClick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEach { (label, selected) ->
            FilterChip(selected = selected, onClick = { onClick(label) }, label = { Text(label) })
        }
    }
}

@Composable
private fun EmptyStateCard() {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
            Text("还没有记录。先记下一条待办、任务、提醒或语音。")
        }
    }
}

@Composable
private fun NoteCard(
    note: Note,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRetrySync: () -> Unit,
) {
    val accent = note.colorToken.toColor()
    var expanded by remember(note.id) { mutableStateOf(false) }
    val bodyText = note.transcript?.takeIf { it.isNotBlank() } ?: note.content
    Card(
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .padding(18.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(modifier = Modifier.size(12.dp).background(accent, RoundedCornerShape(999.dp)))
                Text(note.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(
                bodyText,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 4,
                overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AnimatedVisibility(
                visible = !expanded && bodyText.length > 80,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                AssistChip(
                    onClick = { expanded = true },
                    label = { Text("展开全文") },
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                AssistChip(
                    onClick = { expanded = false },
                    label = { Text("收起") },
                )
            }
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusPill(label = note.category.toChinese(), color = accent.copy(alpha = 0.18f))
                StatusPill(label = note.priority.toChinese(), color = MaterialTheme.colorScheme.secondaryContainer)
                StatusPill(label = note.status.name, color = MaterialTheme.colorScheme.tertiaryContainer)
                if (note.source.name == "VOICE") StatusPill(label = "VOICE", color = MaterialTheme.colorScheme.primaryContainer)
            }
            note.transcriptionStatus.takeIf { it.name != "NOT_STARTED" }?.let {
                val statusLabel = when (it.name) {
                    "UPLOADING" -> "上传中"
                    "TRANSCRIBING" -> "转写中"
                    "DONE" -> "转写成功"
                    "FAILED" -> "转写失败"
                    else -> "未开始"
                }
                Text(
                    text = buildString {
                        append("转写状态 $statusLabel")
                        note.transcriptionUpdatedAt?.let { ts -> append(" | ${formatTime(ts)}") }
                        note.transcriptionError?.takeIf { err -> err.isNotBlank() }?.let { err -> append(" | $err") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (it.name == "FAILED") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!note.relayUrl.isNullOrBlank()) {
                Text("中转外链已生成", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Text(
                text = "更新时间 ${formatTime(note.updatedAt)}" + (note.lastSyncedAt?.let { "  |  同步时间 ${formatTime(it)}" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AssistChip(onClick = onEdit, label = { Text("编辑") })
                AssistChip(onClick = onDelete, label = { Text("删除") })
                if (note.status.name == "FAILED" || note.status.name == "LOCAL_ONLY") AssistChip(onClick = onRetrySync, label = { Text("重新同步") })
            }
            note.syncError?.takeIf { it.isNotBlank() }?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun StatusPill(label: String, color: Color) {
    Surface(shape = RoundedCornerShape(999.dp), color = color) {
        Text(text = label, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
    }
}

private fun NoteColorToken.toColor(): Color = when (this) {
    NoteColorToken.SAGE -> Color(0xFF6C8E7B)
    NoteColorToken.AMBER -> Color(0xFFD89A2B)
    NoteColorToken.SKY -> Color(0xFF4F86C6)
    NoteColorToken.ROSE -> Color(0xFFC9656C)
}

private fun NoteCategory.toChinese(): String = when (this) {
    NoteCategory.NOTE -> "普通"
    NoteCategory.TODO -> "待办"
    NoteCategory.TASK -> "任务"
    NoteCategory.REMINDER -> "提醒"
}

private fun NotePriority.toChinese(): String = when (this) {
    NotePriority.LOW -> "低"
    NotePriority.MEDIUM -> "中"
    NotePriority.HIGH -> "高"
    NotePriority.URGENT -> "紧急"
}

private fun formatTime(time: Long): String = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(time))
