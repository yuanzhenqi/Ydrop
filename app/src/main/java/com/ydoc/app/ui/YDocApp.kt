package com.ydoc.app.ui

import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ydoc.app.data.AppContainer
import com.ydoc.app.model.AiSuggestion
import com.ydoc.app.model.AiEndpointMode
import com.ydoc.app.model.AudioPlaybackUiState
import com.ydoc.app.model.Note
import com.ydoc.app.model.NoteCategory
import com.ydoc.app.model.NoteColorToken
import com.ydoc.app.model.NotePriority
import com.ydoc.app.model.NoteSource
import com.ydoc.app.model.ReminderCandidate
import com.ydoc.app.model.ReminderEntry
import com.ydoc.app.model.ReminderStatus
import com.ydoc.app.model.RecordingState
import com.ydoc.app.model.RecordingUiState
import com.ydoc.app.model.SyncSettingsState
import com.ydoc.app.model.AiConfig
import com.ydoc.app.model.VolcengineConfig
import com.ydoc.app.model.WebDavConfig
import java.text.SimpleDateFormat
import java.util.Calendar
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
    launchNoteId: String?,
    onLaunchNoteConsumed: () -> Unit,
    onRequestRecordingPermissions: () -> Unit,
    onPinQuickRecordShortcut: () -> Boolean,
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

    LaunchedEffect(launchNoteId) {
        if (!launchNoteId.isNullOrBlank()) {
            viewModel.startEditingById(launchNoteId)
            onLaunchNoteConsumed()
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
        onToggleCaptureExpanded = viewModel::toggleCaptureExpanded,
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
        onAiBaseUrlChange = viewModel::updateAiBaseUrl,
        onAiTokenChange = viewModel::updateAiToken,
        onAiModelChange = viewModel::updateAiModel,
        onAiEndpointModeChange = viewModel::updateAiEndpointMode,
        onToggleAi = viewModel::toggleAiEnabled,
        onToggleAiAutoText = viewModel::toggleAiAutoText,
        onToggleAiAutoVoice = viewModel::toggleAiAutoVoice,
        onOverlayHandleSizeChange = viewModel::updateOverlayHandleSize,
        onOverlayHandleAlphaChange = viewModel::updateOverlayHandleAlpha,
        hasOverlayPermission = hasOverlayPermission,
        onToggleOverlay = { enabled -> viewModel.requestOverlayToggle(enabled, hasOverlayPermission) },
        onSaveSettings = viewModel::saveSettings,
        onTestWebDav = viewModel::testWebDavConnection,
        onTestRelay = viewModel::testRelayConnection,
        onTestVolcengine = viewModel::testVolcengineConnection,
        onTestAi = viewModel::testAiConnection,
        onPinQuickRecordShortcut = onPinQuickRecordShortcut,
        onShowSection = viewModel::showSection,
        onEditNote = viewModel::startEditing,
        onArchiveNote = viewModel::archiveNote,
        onUnarchiveNote = viewModel::unarchiveNote,
        onDeleteNote = viewModel::deleteNote,
        onRestoreNote = viewModel::restoreNote,
        onDeletePermanently = viewModel::permanentlyDeleteNote,
        onEmptyTrash = viewModel::emptyTrash,
        onRetrySync = viewModel::retrySync,
        onRunAi = viewModel::runAiForNote,
        onApplyAi = viewModel::applyAiSuggestion,
        onDismissAi = viewModel::dismissAiSuggestion,
        onCreateReminderFromSuggestion = viewModel::createReminderFromSuggestion,
        onAddQuickReminder = viewModel::addQuickReminder,
        onCancelReminder = viewModel::cancelReminder,
        onExportReminderToAlarm = viewModel::exportReminderToAlarm,
        canPlayAudio = container.localAudioPlayer::canPlay,
        onToggleAudioPlayback = viewModel::toggleAudioPlayback,
        onSeekAudio = viewModel::seekAudio,
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
    onToggleCaptureExpanded: () -> Unit,
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
    onAiBaseUrlChange: (String) -> Unit,
    onAiTokenChange: (String) -> Unit,
    onAiModelChange: (String) -> Unit,
    onAiEndpointModeChange: (AiEndpointMode) -> Unit,
    onToggleAi: (Boolean) -> Unit,
    onToggleAiAutoText: (Boolean) -> Unit,
    onToggleAiAutoVoice: (Boolean) -> Unit,
    onOverlayHandleSizeChange: (Int) -> Unit,
    onOverlayHandleAlphaChange: (Float) -> Unit,
    hasOverlayPermission: Boolean,
    onToggleOverlay: (Boolean) -> Unit,
    onSaveSettings: () -> Unit,
    onTestWebDav: () -> Unit,
    onTestRelay: () -> Unit,
    onTestVolcengine: () -> Unit,
    onTestAi: () -> Unit,
    onPinQuickRecordShortcut: () -> Boolean,
    onShowSection: (NoteListSection) -> Unit,
    onEditNote: (Note) -> Unit,
    onArchiveNote: (String) -> Unit,
    onUnarchiveNote: (String) -> Unit,
    onDeleteNote: (String) -> Unit,
    onRestoreNote: (String) -> Unit,
    onDeletePermanently: (String) -> Unit,
    onEmptyTrash: () -> Unit,
    onRetrySync: (String) -> Unit,
    onRunAi: (String) -> Unit,
    onApplyAi: (String) -> Unit,
    onDismissAi: (String) -> Unit,
    onCreateReminderFromSuggestion: (String, ReminderCandidate) -> Unit,
    onAddQuickReminder: (String, Long) -> Unit,
    onCancelReminder: (String) -> Unit,
    onExportReminderToAlarm: (String) -> Unit,
    canPlayAudio: (Note) -> Boolean,
    onToggleAudioPlayback: (String) -> Unit,
    onSeekAudio: (Long) -> Unit,
    onUpdateEditingContent: (String) -> Unit,
    onUpdateEditingCategory: (NoteCategory) -> Unit,
    onUpdateEditingPriority: (NotePriority) -> Unit,
    onSaveEditedNote: () -> Unit,
    onCancelEditing: () -> Unit,
) {
    val currentNotes = when (state.currentSection) {
        NoteListSection.INBOX -> state.notes
        NoteListSection.CALENDAR -> emptyList()
        NoteListSection.ARCHIVE -> state.archivedNotes
        NoteListSection.TRASH -> state.trashedNotes
    }
    val listState = rememberLazyListState()
    LaunchedEffect(state.editingNote?.noteId) {
        if (state.editingNote != null) {
            listState.animateScrollToItem(1)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(if (showSettings) "设置" else "Ydrop") },
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
                    SettingsCardV2(
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
                        onAiBaseUrlChange = onAiBaseUrlChange,
                        onAiTokenChange = onAiTokenChange,
                        onAiModelChange = onAiModelChange,
                        onAiEndpointModeChange = onAiEndpointModeChange,
                        onToggleAi = onToggleAi,
                        onToggleAiAutoText = onToggleAiAutoText,
                        onToggleAiAutoVoice = onToggleAiAutoVoice,
                        onOverlayHandleSizeChange = onOverlayHandleSizeChange,
                        onOverlayHandleAlphaChange = onOverlayHandleAlphaChange,
                        hasOverlayPermission = hasOverlayPermission,
                        onToggleOverlay = onToggleOverlay,
                        onSaveSettings = onSaveSettings,
                        onTestWebDav = onTestWebDav,
                        onTestRelay = onTestRelay,
                        onTestVolcengine = onTestVolcengine,
                        onTestAi = onTestAi,
                        onPinQuickRecordShortcut = onPinQuickRecordShortcut,
                    )
                }
            } else {
                item {
                    HeroCaptureCard(
                        draft = state.draft,
                        captureExpanded = state.captureExpanded,
                        isSaving = state.isSaving,
                        isSyncing = state.isSyncing,
                        syncHint = state.syncHint,
                        recording = state.recording,
                        onDraftChange = onDraftChange,
                        onDraftCategoryChange = onDraftCategoryChange,
                        onDraftPriorityChange = onDraftPriorityChange,
                        onToggleExpanded = onToggleCaptureExpanded,
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
                        NotesSectionRowV2(
                        state = state,
                        onShowSection = onShowSection,
                        onEmptyTrash = onEmptyTrash,
                    )
                }
                if (state.currentSection == NoteListSection.CALENDAR) {
                    if (state.reminders.isEmpty()) {
                        item { EmptyStateCardV2(section = state.currentSection) }
                    } else {
                        item {
                            ReminderAgendaSection(
                                reminders = state.reminders,
                                notes = state.notes + state.archivedNotes + state.trashedNotes,
                                onOpenNote = { note -> onEditNote(note) },
                                onCancelReminder = onCancelReminder,
                                onExportToAlarm = onExportReminderToAlarm,
                            )
                        }
                    }
                } else if (currentNotes.isEmpty()) {
                        item { EmptyStateCardV2(section = state.currentSection) }
                } else {
                    items(currentNotes, key = { it.id }) { note ->
                        NoteCardV2(
                            note = note,
                            section = state.currentSection,
                            suggestion = state.aiSuggestions[note.id],
                            audioPlayback = state.audioPlayback,
                            canPlayAudio = canPlayAudio(note),
                            onToggleAudioPlayback = { onToggleAudioPlayback(note.id) },
                            onSeekAudio = onSeekAudio,
                            onEdit = { onEditNote(note) },
                            onArchive = { onArchiveNote(note.id) },
                            onUnarchive = { onUnarchiveNote(note.id) },
                            onDelete = { onDeleteNote(note.id) },
                            onRestore = { onRestoreNote(note.id) },
                            onDeletePermanently = { onDeletePermanently(note.id) },
                            onRetrySync = { onRetrySync(note.id) },
                            onRunAi = { onRunAi(note.id) },
                            onApplyAi = { onApplyAi(note.id) },
                            onDismissAi = { onDismissAi(note.id) },
                            onCreateReminderFromSuggestion = { candidate -> onCreateReminderFromSuggestion(note.id, candidate) },
                            onAddQuickReminder = onAddQuickReminder,
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
    captureExpanded: Boolean,
    isSaving: Boolean,
    isSyncing: Boolean,
    syncHint: String,
    recording: RecordingUiState,
    onDraftChange: (String) -> Unit,
    onDraftCategoryChange: (NoteCategory) -> Unit,
    onDraftPriorityChange: (NotePriority) -> Unit,
    onToggleExpanded: () -> Unit,
    onSave: () -> Unit,
    onSync: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onCancelRecording: () -> Unit,
) {
    val effectiveExpanded = captureExpanded || draft.content.isNotBlank() || recording.state != RecordingState.IDLE
    val previewText = draft.content.ifBlank { "记点什么…" }
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 18.dp, vertical = 16.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            if (!effectiveExpanded) {
                                onToggleExpanded()
                            }
                        },
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("快速记录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        previewText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                CompactActionIcon(
                    icon = painterResource(id = android.R.drawable.ic_btn_speak_now),
                    contentDescription = if (recording.state == RecordingState.RECORDING) "停止录音" else "开始录音",
                    enabled = recording.state != RecordingState.SAVING,
                    containerColor = if (recording.state == RecordingState.RECORDING) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                    iconTint = if (recording.state == RecordingState.RECORDING) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                    onClick = {
                        when (recording.state) {
                            RecordingState.IDLE -> onStartRecording()
                            RecordingState.RECORDING -> onStopRecording()
                            RecordingState.SAVING -> Unit
                        }
                    },
                )
                CompactActionIcon(
                    icon = painterResource(id = android.R.drawable.ic_menu_save),
                    contentDescription = "保存记录",
                    enabled = !isSaving,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                    onClick = onSave,
                )
                CompactActionIcon(
                    icon = painterResource(
                        id = if (effectiveExpanded) android.R.drawable.arrow_up_float else android.R.drawable.arrow_down_float,
                    ),
                    contentDescription = if (effectiveExpanded) "收起输入区" else "展开输入区",
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = onToggleExpanded,
                )
            }

            AnimatedVisibility(visible = effectiveExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (recording.state != RecordingState.IDLE) {
                        RecordingStrip(recording, onStartRecording, onStopRecording, onCancelRecording)
                    }
                    OutlinedTextField(
                        value = draft.content,
                        onValueChange = onDraftChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("输入内容") },
                        placeholder = { Text("点击补一句，或直接用语音记下来") },
                        minLines = 2,
                        maxLines = 4,
                    )
                    Text("类型", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SegmentedPillGroup(
                        options = NoteCategory.entries.toList(),
                        selected = draft.category,
                        onSelect = onDraftCategoryChange,
                        label = { it.toChinese() },
                    )
                    Text("优先级", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SegmentedPillGroup(
                        options = NotePriority.entries.toList(),
                        selected = draft.priority,
                        onSelect = onDraftPriorityChange,
                        label = { it.toChinese() },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            syncHint,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (recording.state != RecordingState.IDLE) {
                                AssistChip(onClick = onCancelRecording, label = { Text("取消") })
                            }
                            AssistChip(
                                onClick = onSync,
                                label = { Text(if (isSyncing) "同步中" else "立即同步") },
                            )
                        }
                    }
                }
            }
        }
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
            Text("类型", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SegmentedPillGroup(
                options = NoteCategory.entries.toList(),
                selected = editing.category,
                onSelect = onUpdateCategory,
                label = { it.toChinese() },
            )
            Text("优先级", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SegmentedPillGroup(
                options = NotePriority.entries.toList(),
                selected = editing.priority,
                onSelect = onUpdatePriority,
                label = { it.toChinese() },
            )
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

@Composable
private fun <T> SegmentedPillGroup(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.68f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEach { option ->
                val isSelected = option == selected
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        Color.Transparent
                    },
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(option) }
                            .padding(horizontal = 8.dp, vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label(option),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotesSectionRow(
    state: AppUiState,
    onShowSection: (NoteListSection) -> Unit,
    onEmptyTrash: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.currentSection == NoteListSection.INBOX,
                onClick = { onShowSection(NoteListSection.INBOX) },
                label = { Text("收件箱 ${state.notes.size}") },
            )
            FilterChip(
                selected = state.currentSection == NoteListSection.ARCHIVE,
                onClick = { onShowSection(NoteListSection.ARCHIVE) },
                label = { Text("归档 ${state.archivedNotes.size}") },
            )
            FilterChip(
                selected = state.currentSection == NoteListSection.TRASH,
                onClick = { onShowSection(NoteListSection.TRASH) },
                label = { Text("回收站 ${state.trashedNotes.size}") },
            )
        }
        AnimatedVisibility(
            visible = state.currentSection == NoteListSection.TRASH && state.trashedNotes.isNotEmpty(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            AssistChip(onClick = onEmptyTrash, label = { Text("清空") })
        }
    }
}

@Composable
private fun EmptyStateCard(section: NoteListSection) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
            Text(
                when (section) {
                    NoteListSection.INBOX -> "还没有记录。先记下一条待办、任务、提醒或语音。"
                    NoteListSection.CALENDAR -> "还没有提醒。你可以从便签卡片或 AI 建议里直接创建提醒。"
                    NoteListSection.ARCHIVE -> "这里还没有归档记录。"
                    NoteListSection.TRASH -> "回收站是空的。"
                },
            )
        }
    }
}

@Composable
private fun NoteCard(
    note: Note,
    section: NoteListSection,
    audioPlayback: AudioPlaybackUiState,
    canPlayAudio: Boolean,
    onToggleAudioPlayback: () -> Unit,
    onSeekAudio: (Long) -> Unit,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
    onUnarchive: () -> Unit,
    onDelete: () -> Unit,
    onRestore: () -> Unit,
    onDeletePermanently: () -> Unit,
    onRetrySync: () -> Unit,
) {
    val accent = note.colorToken.toColor()
    var expanded by remember(note.id) { mutableStateOf(false) }
    var isOverflowing by remember(note.id) { mutableStateOf(false) }
    val isVoiceNote = note.source == NoteSource.VOICE
    val isPlayingThisNote = audioPlayback.currentNoteId == note.id && (audioPlayback.isPlaying || audioPlayback.isBuffering || audioPlayback.positionMs > 0)
    val displayTitle = note.displayTitleForMainCard()
    val bodyText = when {
        note.source == NoteSource.VOICE && note.transcript?.isNotBlank() == true && note.content != note.transcript -> note.content
        note.transcript?.isNotBlank() == true -> note.transcript
        else -> note.content
    }
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
                Text(displayTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(
                bodyText,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 6,
                overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                onTextLayout = { result ->
                    if (!expanded) {
                        isOverflowing = result.hasVisualOverflow
                    }
                },
            )
            if (isOverflowing || expanded) {
                AssistChip(
                    onClick = { expanded = !expanded },
                    label = { Text(if (expanded) "收起" else "展开全文") },
                )
            }
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusPill(label = note.category.toChinese(), color = accent.copy(alpha = 0.18f))
                StatusPill(label = note.priority.toChinese(), color = MaterialTheme.colorScheme.secondaryContainer)
                StatusPill(label = note.status.name, color = MaterialTheme.colorScheme.tertiaryContainer)
                if (isVoiceNote) StatusPill(label = "语音", color = MaterialTheme.colorScheme.primaryContainer)
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
                        append("转写状态：$statusLabel")
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
            if (isVoiceNote && isPlayingThisNote) {
                AudioPlaybackBar(
                    playback = audioPlayback,
                    accent = accent,
                    onSeekAudio = onSeekAudio,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (isVoiceNote) {
                    CompactActionIcon(
                        icon = painterResource(
                            id = if (audioPlayback.currentNoteId == note.id && audioPlayback.isPlaying) {
                                android.R.drawable.ic_media_pause
                            } else {
                                android.R.drawable.ic_media_play
                            },
                        ),
                        contentDescription = if (audioPlayback.currentNoteId == note.id && audioPlayback.isPlaying) "暂停录音" else "播放录音",
                        enabled = canPlayAudio,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                        onClick = onToggleAudioPlayback,
                    )
                }
                when (section) {
                    NoteListSection.INBOX -> {
                        CompactActionIcon(painterResource(id = android.R.drawable.ic_menu_edit), "编辑记录", onClick = onEdit)
                        CompactActionIcon(painterResource(id = android.R.drawable.ic_menu_save), "归档记录", onClick = onArchive)
                        CompactActionIcon(
                            icon = painterResource(id = android.R.drawable.ic_menu_delete),
                            contentDescription = "移入回收站",
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                            iconTint = MaterialTheme.colorScheme.onErrorContainer,
                            onClick = onDelete,
                        )
                    }
                    NoteListSection.CALENDAR -> Unit
                    NoteListSection.ARCHIVE -> {
                        CompactActionIcon(painterResource(id = android.R.drawable.ic_menu_edit), "编辑记录", onClick = onEdit)
                        CompactActionIcon(painterResource(id = android.R.drawable.ic_menu_revert), "取消归档", onClick = onUnarchive)
                        CompactActionIcon(
                            icon = painterResource(id = android.R.drawable.ic_menu_delete),
                            contentDescription = "移入回收站",
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                            iconTint = MaterialTheme.colorScheme.onErrorContainer,
                            onClick = onDelete,
                        )
                    }
                    NoteListSection.TRASH -> {
                        CompactActionIcon(painterResource(id = android.R.drawable.ic_menu_revert), "恢复记录", onClick = onRestore)
                        CompactActionIcon(
                            icon = painterResource(id = android.R.drawable.ic_menu_close_clear_cancel),
                            contentDescription = "彻底删除",
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            iconTint = MaterialTheme.colorScheme.onErrorContainer,
                            onClick = onDeletePermanently,
                        )
                    }
                }
                if (section != NoteListSection.TRASH && (note.status.name == "FAILED" || note.status.name == "LOCAL_ONLY")) {
                    CompactActionIcon(painterResource(id = android.R.drawable.stat_notify_sync), "重新同步", onClick = onRetrySync)
                }
            }
            note.syncError?.takeIf { it.isNotBlank() }?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun CompactActionIcon(
    icon: Painter,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = if (enabled) containerColor else containerColor.copy(alpha = 0.45f),
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = icon,
                contentDescription = contentDescription,
                tint = if (enabled) iconTint else iconTint.copy(alpha = 0.45f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun AudioPlaybackBar(
    playback: AudioPlaybackUiState,
    accent: Color,
    onSeekAudio: (Long) -> Unit,
) {
    var isDragging by remember(playback.currentNoteId) { mutableStateOf(false) }
    var sliderValue by remember(playback.currentNoteId) { mutableFloatStateOf(0f) }
    val durationMs = playback.durationMs.coerceAtLeast(1L)
    val progress = (playback.positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)

    LaunchedEffect(playback.currentNoteId, playback.positionMs, playback.durationMs, playback.isPlaying, playback.isBuffering) {
        if (!isDragging) {
            sliderValue = progress
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Slider(
            value = if (isDragging) sliderValue else progress,
            onValueChange = {
                isDragging = true
                sliderValue = it
            },
            onValueChangeFinished = {
                onSeekAudio((sliderValue * durationMs).toLong())
                isDragging = false
            },
            enabled = playback.canSeek,
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = accent.copy(alpha = 0.22f),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                formatDuration(playback.positionMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                formatDuration(playback.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsCardV2(
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
    onAiBaseUrlChange: (String) -> Unit,
    onAiTokenChange: (String) -> Unit,
    onAiModelChange: (String) -> Unit,
    onAiEndpointModeChange: (AiEndpointMode) -> Unit,
    onToggleAi: (Boolean) -> Unit,
    onToggleAiAutoText: (Boolean) -> Unit,
    onToggleAiAutoVoice: (Boolean) -> Unit,
    onOverlayHandleSizeChange: (Int) -> Unit,
    onOverlayHandleAlphaChange: (Float) -> Unit,
    hasOverlayPermission: Boolean,
    onToggleOverlay: (Boolean) -> Unit,
    onSaveSettings: () -> Unit,
    onTestWebDav: () -> Unit,
    onTestRelay: () -> Unit,
    onTestVolcengine: () -> Unit,
    onTestAi: () -> Unit,
    onPinQuickRecordShortcut: () -> Boolean,
) {
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("同步、AI 与快捷入口", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            WebDavSettingsSection(settings, onWebDavChange, onToggleWebDav, onTestWebDav)
            HorizontalDivider()
            RelaySettingsSection(settings, onRelayBaseUrlChange, onRelayTokenChange, onToggleRelay, onTestRelay)
            HorizontalDivider()
            VolcengineSettingsSection(
                settings.volcengine,
                settings.isTestingVolcengine,
                onVolcengineAppIdChange,
                onVolcengineAccessTokenChange,
                onVolcengineResourceIdChange,
                onToggleVolcengine,
                onTestVolcengine,
            )
            HorizontalDivider()
            AiSettingsSection(
                config = settings.ai,
                isTesting = settings.isTestingAi,
                onBaseUrlChange = onAiBaseUrlChange,
                onTokenChange = onAiTokenChange,
                onModelChange = onAiModelChange,
                onEndpointModeChange = onAiEndpointModeChange,
                onToggleAi = onToggleAi,
                onToggleAutoText = onToggleAiAutoText,
                onToggleAutoVoice = onToggleAiAutoVoice,
                onTestAi = onTestAi,
            )
            HorizontalDivider()
            QuickRecordShortcutSection(onPinQuickRecordShortcut = onPinQuickRecordShortcut)
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiSettingsSection(
    config: AiConfig,
    isTesting: Boolean,
    onBaseUrlChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onEndpointModeChange: (AiEndpointMode) -> Unit,
    onToggleAi: (Boolean) -> Unit,
    onToggleAutoText: (Boolean) -> Unit,
    onToggleAutoVoice: (Boolean) -> Unit,
    onTestAi: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("AI 整理", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("自动生成摘要、分类建议、待办和提醒候选。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = config.enabled, onCheckedChange = onToggleAi)
        }
        OutlinedTextField(value = config.baseUrl, onValueChange = onBaseUrlChange, label = { Text("AI Base URL") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = config.token, onValueChange = onTokenChange, label = { Text("AI Token") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = config.model, onValueChange = onModelChange, label = { Text("模型名称") }, modifier = Modifier.fillMaxWidth())
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("协议模式", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AiEndpointMode.entries.forEach { mode ->
                    FilterChip(
                        selected = config.endpointMode == mode,
                        onClick = { onEndpointModeChange(mode) },
                        label = { Text(mode.toChinese()) },
                    )
                }
            }
            Text(
                text = when (config.endpointMode) {
                    AiEndpointMode.AUTO -> "自动识别：优先尝试模型网关，识别失败时回退到 Relay。"
                    AiEndpointMode.RELAY -> "Relay：Base URL 填你自己的 /ai/analyze-note 服务地址。"
                    AiEndpointMode.OPENAI -> "OpenAI 兼容：Base URL 填模型网关根地址，App 会请求 /v1/chat/completions。"
                    AiEndpointMode.ANTHROPIC -> "Anthropic：Base URL 填模型网关根地址，App 会请求 /v1/messages。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("文本保存后自动整理", modifier = Modifier.weight(1f))
            Switch(checked = config.autoRunOnTextSave, onCheckedChange = onToggleAutoText)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("语音转写后自动整理", modifier = Modifier.weight(1f))
            Switch(checked = config.autoRunOnVoiceTranscribed, onCheckedChange = onToggleAutoVoice)
        }
        AssistChip(onClick = onTestAi, label = { Text(if (isTesting) "测试中" else "测试 AI 服务") })
    }
}

@Composable
private fun QuickRecordShortcutSection(
    onPinQuickRecordShortcut: () -> Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("快捷启动", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("发布专用直录入口，方便系统快捷方式、桌面图标和厂商快捷启动绑定。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        AssistChip(
            onClick = { onPinQuickRecordShortcut() },
            label = { Text("创建桌面快捷录音") },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotesSectionRowV2(
    state: AppUiState,
    onShowSection: (NoteListSection) -> Unit,
    onEmptyTrash: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.currentSection == NoteListSection.INBOX,
                onClick = { onShowSection(NoteListSection.INBOX) },
                label = { Text("收件箱 ${state.notes.size}") },
            )
            FilterChip(
                selected = state.currentSection == NoteListSection.CALENDAR,
                onClick = { onShowSection(NoteListSection.CALENDAR) },
                label = { Text("日历 ${state.reminders.count { it.status == ReminderStatus.SCHEDULED }}") },
            )
            FilterChip(
                selected = state.currentSection == NoteListSection.ARCHIVE,
                onClick = { onShowSection(NoteListSection.ARCHIVE) },
                label = { Text("归档 ${state.archivedNotes.size}") },
            )
            FilterChip(
                selected = state.currentSection == NoteListSection.TRASH,
                onClick = { onShowSection(NoteListSection.TRASH) },
                label = { Text("回收站 ${state.trashedNotes.size}") },
            )
        }
        AnimatedVisibility(
            visible = state.currentSection == NoteListSection.TRASH && state.trashedNotes.isNotEmpty(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            AssistChip(onClick = onEmptyTrash, label = { Text("清空") })
        }
    }
}

@Composable
private fun EmptyStateCardV2(section: NoteListSection) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
            Text(
                when (section) {
                    NoteListSection.INBOX -> "还没有记录。先记下一条待办、任务、提醒或语音。"
                    NoteListSection.CALENDAR -> "还没有提醒。你可以从便签卡片或 AI 建议里直接创建提醒。"
                    NoteListSection.ARCHIVE -> "这里还没有归档记录。"
                    NoteListSection.TRASH -> "回收站是空的。"
                },
            )
        }
    }
}

@Composable
private fun ReminderAgendaSection(
    reminders: List<ReminderEntry>,
    notes: List<Note>,
    onOpenNote: (Note) -> Unit,
    onCancelReminder: (String) -> Unit,
    onExportToAlarm: (String) -> Unit,
) {
    val notesById = remember(notes) { notes.associateBy { it.id } }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        reminders.groupBy { SimpleDateFormat("MM-dd EEEE", Locale.getDefault()).format(Date(it.scheduledAt)) }
            .forEach { (dateLabel, dayReminders) ->
                Text(dateLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                dayReminders.forEach { reminder ->
                    Card(
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(reminder.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${formatTime(reminder.scheduledAt)} · ${reminder.status.name}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            notesById[reminder.noteId]?.let { note ->
                                AssistChip(onClick = { onOpenNote(note) }, label = { Text("打开便签") })
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                AssistChip(onClick = { onExportToAlarm(reminder.id) }, label = { Text("导出闹钟") })
                                if (reminder.status == ReminderStatus.SCHEDULED) {
                                    AssistChip(onClick = { onCancelReminder(reminder.id) }, label = { Text("取消提醒") })
                                }
                            }
                        }
                    }
                }
            }
    }
}

@Composable
private fun NoteCardV2(
    note: Note,
    section: NoteListSection,
    suggestion: AiSuggestion?,
    audioPlayback: AudioPlaybackUiState,
    canPlayAudio: Boolean,
    onToggleAudioPlayback: () -> Unit,
    onSeekAudio: (Long) -> Unit,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
    onUnarchive: () -> Unit,
    onDelete: () -> Unit,
    onRestore: () -> Unit,
    onDeletePermanently: () -> Unit,
    onRetrySync: () -> Unit,
    onRunAi: () -> Unit,
    onApplyAi: () -> Unit,
    onDismissAi: () -> Unit,
    onCreateReminderFromSuggestion: (ReminderCandidate) -> Unit,
    onAddQuickReminder: (String, Long) -> Unit,
) {
    val accent = note.colorToken.toColor()
    var expanded by remember(note.id) { mutableStateOf(false) }
    var isOverflowing by remember(note.id) { mutableStateOf(false) }
    var reminderMenuExpanded by remember(note.id) { mutableStateOf(false) }
    val isVoiceNote = note.source == NoteSource.VOICE
    val isPlayingThisNote = audioPlayback.currentNoteId == note.id && (audioPlayback.isPlaying || audioPlayback.isBuffering || audioPlayback.positionMs > 0)
    val displayTitle = note.displayTitleForMainCard()
    val bodyText = when {
        note.source == NoteSource.VOICE && note.transcript?.isNotBlank() == true && note.content != note.transcript -> note.content
        note.transcript?.isNotBlank() == true -> note.transcript
        else -> note.content
    }
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
                Text(displayTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(
                bodyText,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 6,
                overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                onTextLayout = { result ->
                    if (!expanded) {
                        isOverflowing = result.hasVisualOverflow
                    }
                },
            )
            if (isOverflowing || expanded) {
                AssistChip(
                    onClick = { expanded = !expanded },
                    label = { Text(if (expanded) "收起" else "展开全文") },
                )
            }
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusPill(label = note.category.toChinese(), color = accent.copy(alpha = 0.18f))
                StatusPill(label = note.priority.toChinese(), color = MaterialTheme.colorScheme.secondaryContainer)
                StatusPill(label = note.status.name, color = MaterialTheme.colorScheme.tertiaryContainer)
                AssistChip(
                    onClick = onRunAi,
                    label = {
                        Text(
                            when (suggestion?.status?.name) {
                                "RUNNING" -> "整理中"
                                "READY" -> "有建议"
                                "FAILED" -> "整理失败"
                                "APPLIED" -> "已应用"
                                else -> "AI 整理"
                            },
                        )
                    },
                )
                if (isVoiceNote) StatusPill(label = "语音", color = MaterialTheme.colorScheme.primaryContainer)
            }
            if (suggestion != null && suggestion.status.name in listOf("RUNNING", "READY", "FAILED")) {
                AiSuggestionPanel(
                    suggestion = suggestion,
                    onApplyAi = onApplyAi,
                    onDismissAi = onDismissAi,
                    onRunAi = onRunAi,
                    onCreateReminderFromSuggestion = onCreateReminderFromSuggestion,
                )
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
                        append("转写状态：$statusLabel")
                        note.transcriptionUpdatedAt?.let { ts -> append(" | ${formatTime(ts)}") }
                        note.transcriptionError?.takeIf { err -> err.isNotBlank() }?.let { err -> append(" | $err") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (it.name == "FAILED") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "更新时间 ${formatTime(note.updatedAt)}" + (note.lastSyncedAt?.let { "  |  同步时间 ${formatTime(it)}" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (isVoiceNote && isPlayingThisNote) {
                AudioPlaybackBar(
                    playback = audioPlayback,
                    accent = accent,
                    onSeekAudio = onSeekAudio,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (isVoiceNote) {
                    CompactActionIcon(
                        icon = painterResource(
                            id = if (audioPlayback.currentNoteId == note.id && audioPlayback.isPlaying) {
                                android.R.drawable.ic_media_pause
                            } else {
                                android.R.drawable.ic_media_play
                            },
                        ),
                        contentDescription = if (audioPlayback.currentNoteId == note.id && audioPlayback.isPlaying) "暂停录音" else "播放录音",
                        enabled = canPlayAudio,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                        onClick = onToggleAudioPlayback,
                    )
                }
                if (section != NoteListSection.TRASH) {
                    Box {
                        CompactActionIcon(
                            icon = painterResource(id = android.R.drawable.ic_menu_my_calendar),
                            contentDescription = "添加提醒",
                            onClick = { reminderMenuExpanded = true },
                        )
                        DropdownMenu(expanded = reminderMenuExpanded, onDismissRequest = { reminderMenuExpanded = false }) {
                            reminderPresets().forEach { (label, atMillis) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        onAddQuickReminder(note.id, atMillis)
                                        reminderMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
                when (section) {
                    NoteListSection.INBOX -> {
                        CompactActionIcon(painterResource(id = android.R.drawable.ic_menu_edit), "编辑记录", onClick = onEdit)
                        CompactActionIcon(painterResource(id = android.R.drawable.ic_menu_save), "归档记录", onClick = onArchive)
                        CompactActionIcon(
                            icon = painterResource(id = android.R.drawable.ic_menu_delete),
                            contentDescription = "移入回收站",
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                            iconTint = MaterialTheme.colorScheme.onErrorContainer,
                            onClick = onDelete,
                        )
                    }
                    NoteListSection.CALENDAR -> Unit
                    NoteListSection.ARCHIVE -> {
                        CompactActionIcon(painterResource(id = android.R.drawable.ic_menu_edit), "编辑记录", onClick = onEdit)
                        CompactActionIcon(painterResource(id = android.R.drawable.ic_menu_revert), "取消归档", onClick = onUnarchive)
                        CompactActionIcon(
                            icon = painterResource(id = android.R.drawable.ic_menu_delete),
                            contentDescription = "移入回收站",
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                            iconTint = MaterialTheme.colorScheme.onErrorContainer,
                            onClick = onDelete,
                        )
                    }
                    NoteListSection.TRASH -> {
                        CompactActionIcon(painterResource(id = android.R.drawable.ic_menu_revert), "恢复记录", onClick = onRestore)
                        CompactActionIcon(
                            icon = painterResource(id = android.R.drawable.ic_menu_close_clear_cancel),
                            contentDescription = "彻底删除",
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            iconTint = MaterialTheme.colorScheme.onErrorContainer,
                            onClick = onDeletePermanently,
                        )
                    }
                }
                if (section != NoteListSection.TRASH && (note.status.name == "FAILED" || note.status.name == "LOCAL_ONLY")) {
                    CompactActionIcon(painterResource(id = android.R.drawable.stat_notify_sync), "重新同步", onClick = onRetrySync)
                }
            }
            note.syncError?.takeIf { it.isNotBlank() }?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiSuggestionPanel(
    suggestion: AiSuggestion,
    onApplyAi: () -> Unit,
    onDismissAi: () -> Unit,
    onRunAi: () -> Unit,
    onCreateReminderFromSuggestion: (ReminderCandidate) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (suggestion.status.name) {
                "RUNNING" -> Text("AI 正在整理这条便签。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                "FAILED" -> {
                    Text(suggestion.errorMessage ?: "AI 整理失败。", color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = onRunAi, label = { Text("重试") })
                        AssistChip(onClick = onDismissAi, label = { Text("忽略") })
                    }
                }
                else -> {
                    suggestion.summary.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        suggestion.suggestedTitle?.takeIf { it.isNotBlank() }?.let {
                            StatusPill(label = "标题：$it", color = MaterialTheme.colorScheme.primaryContainer)
                        }
                        suggestion.suggestedCategory?.let {
                            StatusPill(label = "分类：${it.toChinese()}", color = MaterialTheme.colorScheme.secondaryContainer)
                        }
                        suggestion.suggestedPriority?.let {
                            StatusPill(label = "优先级：${it.toChinese()}", color = MaterialTheme.colorScheme.tertiaryContainer)
                        }
                    }
                    suggestion.todoItems.takeIf { it.isNotEmpty() }?.let { todos ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("提取到的待办", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                            todos.take(4).forEach { item ->
                                Text("• $item", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    suggestion.extractedEntities.takeIf { it.isNotEmpty() }?.let { entities ->
                        Text(
                            entities.joinToString(" · ") { "${it.label}: ${it.value}" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    suggestion.reminderCandidates.firstOrNull()?.let { candidate ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "提醒候选：${candidate.title} · ${formatTime(candidate.scheduledAt)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            AssistChip(onClick = { onCreateReminderFromSuggestion(candidate) }, label = { Text("创建提醒") })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = onApplyAi, label = { Text("应用建议") })
                        AssistChip(onClick = onDismissAi, label = { Text("忽略") })
                        AssistChip(onClick = onRunAi, label = { Text("重跑") })
                    }
                }
            }
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
}

private fun reminderPresets(nowMillis: Long = System.currentTimeMillis()): List<Pair<String, Long>> {
    val base = Calendar.getInstance().apply { timeInMillis = nowMillis }
    val after30Min = (base.clone() as Calendar).apply { add(Calendar.MINUTE, 30) }.timeInMillis
    val tonight = (base.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 20)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        if (timeInMillis <= nowMillis) add(Calendar.DAY_OF_YEAR, 1)
    }.timeInMillis
    val tomorrowMorning = (base.clone() as Calendar).apply {
        add(Calendar.DAY_OF_YEAR, 1)
        set(Calendar.HOUR_OF_DAY, 9)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    return listOf(
        "30 分钟后" to after30Min,
        "今晚 20:00" to tonight,
        "明早 09:00" to tomorrowMorning,
    )
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

private fun Note.displayTitleForMainCard(): String {
    if (source != NoteSource.VOICE) return title
    val transcriptTitle = transcript
        ?.lineSequence()
        ?.map { it.trim() }
        ?.firstOrNull { it.isNotBlank() }
        ?.take(36)
    return transcriptTitle?.takeIf { it.isNotBlank() } ?: "语音记录"
}

private fun NoteCategory.toChinese(): String = when (this) {
    NoteCategory.NOTE -> "普通"
    NoteCategory.TODO -> "待办"
    NoteCategory.TASK -> "任务"
    NoteCategory.REMINDER -> "提醒"
}

private fun AiEndpointMode.toChinese(): String = when (this) {
    AiEndpointMode.AUTO -> "自动"
    AiEndpointMode.RELAY -> "Relay"
    AiEndpointMode.OPENAI -> "OpenAI"
    AiEndpointMode.ANTHROPIC -> "Anthropic"
}

private fun NotePriority.toChinese(): String = when (this) {
    NotePriority.LOW -> "低"
    NotePriority.MEDIUM -> "中"
    NotePriority.HIGH -> "高"
    NotePriority.URGENT -> "紧急"
}

private fun formatTime(time: Long): String = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(time))
