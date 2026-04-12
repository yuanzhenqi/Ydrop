package com.ydoc.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.TextButton
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
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Close
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ydoc.app.R
import com.ydoc.app.data.AppContainer
import com.ydoc.app.model.AiSuggestion
import com.ydoc.app.model.AiSuggestionStatus
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
import com.ydoc.app.model.defaultAiPromptTemplate
import com.ydoc.app.model.shouldShowPanel
import com.ydoc.app.ui.components.AudioPlaybackBar
import com.ydoc.app.ui.components.CompactActionIcon
import com.ydoc.app.ui.components.SegmentedPillGroup
import com.ydoc.app.ui.components.SettingsSectionHeader
import com.ydoc.app.ui.components.SettingsToggleRow
import com.ydoc.app.ui.components.StatusPill
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
    quickRecordRequestToken: Long?,
    onQuickRecordRequestConsumed: () -> Unit,
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

    LaunchedEffect(quickRecordRequestToken) {
        if (quickRecordRequestToken != null) {
            viewModel.handleQuickRecordTrigger()
            onQuickRecordRequestConsumed()
        }
    }

    LaunchedEffect(state.pendingQuickRecord, state.recording.state) {
        if (state.pendingQuickRecord && state.recording.state == RecordingState.IDLE) {
            viewModel.resumePendingQuickRecordIfPossible()
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
        onDraftTagsChange = viewModel::updateDraftTags,
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
        onAiPromptChange = viewModel::updateAiPrompt,
        onAiEndpointModeChange = viewModel::updateAiEndpointMode,
        onToggleAi = viewModel::toggleAiEnabled,
        onToggleAiAutoText = viewModel::toggleAiAutoText,
        onToggleAiAutoVoice = viewModel::toggleAiAutoVoice,
        onToggleAiAutoRetry = viewModel::toggleAiAutoRetry,
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
        onCopyNote = viewModel::copyNoteContent,
        onCancelReminder = viewModel::cancelReminder,
        onExportReminderToAlarm = viewModel::exportReminderToAlarm,
        onCreateReminderForDate = viewModel::createReminderForDate,
        canPlayAudio = container.localAudioPlayer::canPlay,
        onToggleAudioPlayback = viewModel::toggleAudioPlayback,
        onSeekAudio = viewModel::seekAudio,
        onUpdateEditingContent = viewModel::updateEditingContent,
        onUpdateEditingCategory = viewModel::updateEditingCategory,
        onUpdateEditingPriority = viewModel::updateEditingPriority,
        onUpdateEditingTags = viewModel::updateEditingTags,
        onSaveEditedNote = viewModel::saveEditedNote,
        onCancelEditing = viewModel::cancelEditing,
        onSearchQueryChange = viewModel::updateSearchQuery,
        onClearSearch = viewModel::clearSearch,
        onToggleTagFilter = viewModel::toggleTagFilter,
        onClearTagFilter = viewModel::clearTagFilter,
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
    onDraftTagsChange: (List<String>) -> Unit,
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
    onAiPromptChange: (String) -> Unit,
    onAiEndpointModeChange: (AiEndpointMode) -> Unit,
    onToggleAi: (Boolean) -> Unit,
    onToggleAiAutoText: (Boolean) -> Unit,
    onToggleAiAutoVoice: (Boolean) -> Unit,
    onToggleAiAutoRetry: (Boolean) -> Unit,
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
    onCopyNote: (String) -> Unit,
    onCancelReminder: (String) -> Unit,
    onExportReminderToAlarm: (String) -> Unit,
    onCreateReminderForDate: (Long, String, Int, Int) -> Unit,
    canPlayAudio: (Note) -> Boolean,
    onToggleAudioPlayback: (String) -> Unit,
    onSeekAudio: (Long) -> Unit,
    onUpdateEditingContent: (String) -> Unit,
    onUpdateEditingCategory: (NoteCategory) -> Unit,
    onUpdateEditingPriority: (NotePriority) -> Unit,
    onUpdateEditingTags: (List<String>) -> Unit,
    onSaveEditedNote: () -> Unit,
    onCancelEditing: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onToggleTagFilter: (String) -> Unit,
    onClearTagFilter: () -> Unit,
) {
    val baseNotes = when (state.currentSection) {
        NoteListSection.INBOX -> state.notes
        NoteListSection.CALENDAR -> emptyList()
        NoteListSection.ARCHIVE -> state.archivedNotes
        NoteListSection.TRASH -> state.trashedNotes
    }
    val currentNotes = remember(baseNotes, state.tagFilter, state.searchQuery) {
        var filtered = baseNotes
        if (state.tagFilter.isNotEmpty()) {
            filtered = filtered.filter { note -> note.tags.any { it in state.tagFilter } }
        }
        if (state.searchQuery.isNotBlank()) {
            val q = state.searchQuery.trim()
            filtered = filtered.filter { note ->
                note.title.contains(q, ignoreCase = true) ||
                    note.content.contains(q, ignoreCase = true) ||
                    note.tags.any { it.contains(q, ignoreCase = true) }
            }
        }
        filtered
    }
    val upcomingReminders = state.reminders
        .filter { it.status == ReminderStatus.SCHEDULED }
        .sortedBy { it.scheduledAt }
    var searchMode by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(state.editingNote?.noteId) {
        if (state.editingNote != null) {
            listState.animateScrollToItem(1)
        }
    }

    Scaffold(
        topBar = {
            if (searchMode && !showSettings) {
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = onSearchQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text("搜索标题、内容或标签") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    trailingIcon = {
                        if (state.searchQuery.isNotEmpty()) {
                            IconButton(onClick = onClearSearch) {
                                Icon(Icons.Rounded.Close, contentDescription = "清除")
                            }
                        }
                        IconButton(onClick = { searchMode = false; onClearSearch() }) {
                            Icon(Icons.Rounded.Close, contentDescription = "关闭搜索")
                        }
                    },
                )
            } else {
                CenterAlignedTopAppBar(
                    title = { Text(if (showSettings) "设置" else "Ydrop") },
                    actions = {
                        if (!showSettings) {
                            IconButton(onClick = { searchMode = true }) {
                                Icon(Icons.Rounded.Search, contentDescription = "搜索")
                            }
                        }
                        IconButton(onClick = { if (showSettings) onCloseSettings() else onOpenSettings() }) {
                            Icon(Icons.Rounded.Settings, contentDescription = "设置")
                        }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 100.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (showSettings) {
                    item {
                        SettingsCardTabbed(
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
                        onAiPromptChange = onAiPromptChange,
                        onAiEndpointModeChange = onAiEndpointModeChange,
                        onToggleAi = onToggleAi,
                        onToggleAiAutoText = onToggleAiAutoText,
                        onToggleAiAutoVoice = onToggleAiAutoVoice,
                        onToggleAiAutoRetry = onToggleAiAutoRetry,
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
                state.editingNote?.let { editing ->
                    item {
                        EditNoteCard(
                            editing = editing,
                            suggestedTags = state.suggestedTags,
                            onUpdateContent = onUpdateEditingContent,
                            onUpdateCategory = onUpdateEditingCategory,
                            onUpdatePriority = onUpdateEditingPriority,
                            onUpdateTags = onUpdateEditingTags,
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
                // Tag filter bar
                if (state.suggestedTags.isNotEmpty() && state.tagFilter.isNotEmpty() || state.suggestedTags.isNotEmpty()) {
                    item {
                        TagFilterBar(
                            suggestedTags = state.suggestedTags,
                            tagFilter = state.tagFilter,
                            onToggleTag = onToggleTagFilter,
                            onClearFilter = onClearTagFilter,
                        )
                    }
                }
                if (state.currentSection == NoteListSection.INBOX) {
                    item {
                        ReminderCalendarPreviewCardV2(
                            reminders = upcomingReminders,
                            notes = state.notes + state.archivedNotes + state.trashedNotes,
                            onOpenNote = { note -> onEditNote(note) },
                            onArchiveNote = onArchiveNote,
                            onUnarchiveNote = onUnarchiveNote,
                            onDeleteNote = onDeleteNote,
                            onRestoreNote = onRestoreNote,
                            onCancelReminder = onCancelReminder,
                            onExportToAlarm = onExportReminderToAlarm,
                            onOpenCalendar = { onShowSection(NoteListSection.CALENDAR) },
                            onCreateReminderForDate = onCreateReminderForDate,
                        )
                    }
                }
                if (state.currentSection == NoteListSection.CALENDAR) {
                    item {
                        ReminderCalendarSection(
                            reminders = state.reminders,
                            notes = state.notes + state.archivedNotes + state.trashedNotes,
                            onOpenNote = { note -> onEditNote(note) },
                            onArchiveNote = onArchiveNote,
                            onUnarchiveNote = onUnarchiveNote,
                            onDeleteNote = onDeleteNote,
                            onRestoreNote = onRestoreNote,
                            onCancelReminder = onCancelReminder,
                            onExportToAlarm = onExportReminderToAlarm,
                            onCreateReminderForDate = onCreateReminderForDate,
                        )
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
                            onCopy = { onCopyNote(note.id) },
                        )
                    }
                }
            }
            }
            if (!showSettings) {
                HeroCaptureCard(
                    draft = state.draft,
                    captureExpanded = state.captureExpanded,
                    isSaving = state.isSaving,
                    isSyncing = state.isSyncing,
                    syncHint = state.syncHint,
                    recording = state.recording,
                    suggestedTags = state.suggestedTags,
                    onDraftChange = onDraftChange,
                    onDraftCategoryChange = onDraftCategoryChange,
                    onDraftPriorityChange = onDraftPriorityChange,
                    onDraftTagsChange = onDraftTagsChange,
                    onToggleExpanded = onToggleCaptureExpanded,
                    onSave = onSave,
                    onSync = onSync,
                    onStartRecording = onStartRecording,
                    onStopRecording = onStopRecording,
                    onCancelRecording = onCancelRecording,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .fillMaxWidth(),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeroCaptureCard(
    modifier: Modifier = Modifier,
    draft: CaptureDraft,
    captureExpanded: Boolean,
    isSaving: Boolean,
    isSyncing: Boolean,
    syncHint: String,
    recording: RecordingUiState,
    suggestedTags: List<String>,
    onDraftChange: (String) -> Unit,
    onDraftCategoryChange: (NoteCategory) -> Unit,
    onDraftPriorityChange: (NotePriority) -> Unit,
    onDraftTagsChange: (List<String>) -> Unit,
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
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 18.dp, vertical = 16.dp)
                .animateContentSize(animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing)),
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
                    contentDescription = when (recording.state) {
                        RecordingState.IDLE -> "开始录音"
                        RecordingState.STARTING -> "正在准备录音"
                        RecordingState.RECORDING -> "停止录音"
                        RecordingState.SAVING -> "正在保存录音"
                    },
                    enabled = recording.state == RecordingState.IDLE || recording.state == RecordingState.RECORDING,
                    containerColor = when (recording.state) {
                        RecordingState.RECORDING -> MaterialTheme.colorScheme.errorContainer
                        RecordingState.STARTING -> MaterialTheme.colorScheme.tertiaryContainer
                        else -> MaterialTheme.colorScheme.secondaryContainer
                    },
                    iconTint = when (recording.state) {
                        RecordingState.RECORDING -> MaterialTheme.colorScheme.onErrorContainer
                        RecordingState.STARTING -> MaterialTheme.colorScheme.onTertiaryContainer
                        else -> MaterialTheme.colorScheme.onSecondaryContainer
                    },
                    onClick = {
                        when (recording.state) {
                            RecordingState.IDLE -> onStartRecording()
                            RecordingState.STARTING -> Unit
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

            AnimatedVisibility(
                visible = effectiveExpanded,
                enter = expandVertically(animationSpec = tween(150, easing = FastOutSlowInEasing)) + fadeIn(tween(100)),
                exit = shrinkVertically(animationSpec = tween(150, easing = FastOutSlowInEasing)) + fadeOut(tween(80)),
            ) {
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
                    var tagInput by remember(draft.tags) { mutableStateOf(draft.tags.joinToString(", ")) }
                    Text("标签", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = tagInput,
                        onValueChange = { value ->
                            tagInput = value
                            onDraftTagsChange(value.split(",").map { it.trim() }.filter { it.isNotBlank() })
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("逗号分隔，如：工作, 日程, 重要") },
                    )
                    // Tag suggestion chips
                    val availableSuggestions = suggestedTags.filter { it !in draft.tags }
                    if (availableSuggestions.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            availableSuggestions.take(6).forEach { tag ->
                                FilterChip(
                                    selected = false,
                                    onClick = {
                                        val newTags = draft.tags + tag
                                        tagInput = newTags.joinToString(", ")
                                        onDraftTagsChange(newTags)
                                    },
                                    label = { Text("#$tag", style = MaterialTheme.typography.labelSmall) },
                                )
                            }
                        }
                    }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditNoteCard(
    editing: EditDraft,
    suggestedTags: List<String>,
    onUpdateContent: (String) -> Unit,
    onUpdateCategory: (NoteCategory) -> Unit,
    onUpdatePriority: (NotePriority) -> Unit,
    onUpdateTags: (List<String>) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    var tagInput by remember(editing.noteId) { mutableStateOf(editing.tags.joinToString(", ")) }
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
            Text("标签", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = tagInput,
                onValueChange = { value ->
                    tagInput = value
                    onUpdateTags(value.split(",").map { it.trim() }.filter { it.isNotBlank() })
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("逗号分隔，如：工作, 日程, 重要") },
            )
            val editSuggestions = suggestedTags.filter { it !in editing.tags }
            if (editSuggestions.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    editSuggestions.take(6).forEach { tag ->
                        FilterChip(
                            selected = false,
                            onClick = {
                                val newTags = editing.tags + tag
                                tagInput = newTags.joinToString(", ")
                                onUpdateTags(newTags)
                            },
                            label = { Text("#$tag", style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
            }
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
                            RecordingState.STARTING -> MaterialTheme.colorScheme.tertiary
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
                        RecordingState.STARTING -> "正在准备录音，会在启动完成后自动开始计时。"
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
private fun WebDavSettingsSection(
    settings: SyncSettingsState,
    onWebDavChange: ((WebDavConfig) -> WebDavConfig) -> Unit,
    onToggleWebDav: (Boolean) -> Unit,
    onTestWebDav: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsSectionHeader(
            title = "NAS 同步",
            description = "只保留 WebDAV，同步路径更清晰。",
            checked = settings.webDavEnabled,
            onCheckedChange = onToggleWebDav,
        )
        OutlinedTextField(value = settings.webDav.baseUrl, onValueChange = { onWebDavChange { current -> current.copy(baseUrl = it) } }, label = { Text("WebDAV 地址") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = settings.webDav.username, onValueChange = { onWebDavChange { current -> current.copy(username = it) } }, label = { Text("用户名") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = settings.webDav.password, onValueChange = { onWebDavChange { current -> current.copy(password = it) } }, label = { Text("密码") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = settings.webDav.folder, onValueChange = { onWebDavChange { current -> current.copy(folder = it) } }, label = { Text("云端目录") }, modifier = Modifier.fillMaxWidth())
        SettingsToggleRow(
            label = "自动同步",
            checked = settings.webDav.autoSync,
            onCheckedChange = { onWebDavChange { current -> current.copy(autoSync = it) } },
        )
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
        SettingsToggleRow(
            label = "仅 Wi-Fi 同步",
            checked = settings.webDav.wifiOnly,
            onCheckedChange = { onWebDavChange { current -> current.copy(wifiOnly = it) } },
        )
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
        SettingsSectionHeader(
            title = "中转服务",
            description = "录音可先上传到自建 FastAPI 中转服务，生成豆包可访问的外链。",
            checked = settings.relay.enabled,
            onCheckedChange = onToggleRelay,
        )
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
        SettingsSectionHeader(
            title = "豆包语音转写",
            description = "这里配置 App ID、Access Token 和 Resource ID。",
            checked = config.enabled,
            onCheckedChange = onToggleEnabled,
        )
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
        SettingsSectionHeader(
            title = "悬浮侧边把手",
            description = if (hasPermission) {
                "启用后可从任意界面拉出最近记录、文字输入和长按录音。"
            } else {
                "启用前需要先授予悬浮窗权限。"
            },
            checked = enabled,
            onCheckedChange = onToggleOverlay,
        )
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

private enum class SettingsTab(
    val label: String,
) {
    SYNC("同步"),
    TRANSCRIPTION("转写"),
    AI("AI"),
    OVERLAY("悬浮窗"),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsCardTabbed(
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
    onAiPromptChange: (String) -> Unit,
    onAiEndpointModeChange: (AiEndpointMode) -> Unit,
    onToggleAi: (Boolean) -> Unit,
    onToggleAiAutoText: (Boolean) -> Unit,
    onToggleAiAutoVoice: (Boolean) -> Unit,
    onToggleAiAutoRetry: (Boolean) -> Unit,
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
    var selectedTab by remember { mutableStateOf(SettingsTab.SYNC) }
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("设置中心", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "按同步、转写、AI 和悬浮窗分组管理，底部统一保存当前改动。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SegmentedPillGroup(
                options = SettingsTab.entries.toList(),
                selected = selectedTab,
                onSelect = { selectedTab = it },
                label = { it.label },
            )
            HorizontalDivider()
            when (selectedTab) {
                SettingsTab.SYNC -> {
                    WebDavSettingsSection(settings, onWebDavChange, onToggleWebDav, onTestWebDav)
                }

                SettingsTab.TRANSCRIPTION -> {
                    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
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
                        QuickRecordShortcutSection(onPinQuickRecordShortcut = onPinQuickRecordShortcut)
                    }
                }

                SettingsTab.AI -> {
                    AiSettingsSection(
                        config = settings.ai,
                        isTesting = settings.isTestingAi,
                        onBaseUrlChange = onAiBaseUrlChange,
                        onTokenChange = onAiTokenChange,
                        onModelChange = onAiModelChange,
                        onPromptChange = onAiPromptChange,
                        onEndpointModeChange = onAiEndpointModeChange,
                        onToggleAi = onToggleAi,
                        onToggleAutoText = onToggleAiAutoText,
                        onToggleAutoVoice = onToggleAiAutoVoice,
                        onToggleAutoRetry = onToggleAiAutoRetry,
                        onTestAi = onTestAi,
                    )
                }

                SettingsTab.OVERLAY -> {
                    OverlaySettingsSection(
                        enabled = settings.overlay.enabled,
                        handleSizeDp = settings.overlay.handleSizeDp,
                        handleAlpha = settings.overlay.handleAlpha,
                        hasPermission = hasOverlayPermission,
                        onToggleOverlay = onToggleOverlay,
                        onHandleSizeChange = onOverlayHandleSizeChange,
                        onHandleAlphaChange = onOverlayHandleAlphaChange,
                    )
                }
            }
            HorizontalDivider()
            Text(
                text = if (settings.hasUnsavedChanges) {
                    "当前分组有未保存的改动，保存后会统一生效。"
                } else {
                    "当前设置已保存。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onSaveSettings, enabled = settings.hasUnsavedChanges) {
                Text("保存全部设置")
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
    onPromptChange: (String) -> Unit,
    onEndpointModeChange: (AiEndpointMode) -> Unit,
    onToggleAi: (Boolean) -> Unit,
    onToggleAutoText: (Boolean) -> Unit,
    onToggleAutoVoice: (Boolean) -> Unit,
    onToggleAutoRetry: (Boolean) -> Unit,
    onTestAi: () -> Unit,
) {
    var promptExpanded by remember { mutableStateOf(false) }
    var builtInRulesExpanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsSectionHeader(
            title = "AI 整理",
            description = "自动生成摘要、分类建议、待办和提醒候选。",
            checked = config.enabled,
            onCheckedChange = onToggleAi,
        )
        OutlinedTextField(value = config.baseUrl, onValueChange = onBaseUrlChange, label = { Text("AI Base URL") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = config.token, onValueChange = onTokenChange, label = { Text("AI Token") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = config.model, onValueChange = onModelChange, label = { Text("模型名称") }, modifier = Modifier.fillMaxWidth())
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("提示词策略", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (config.promptSupplement.isBlank()) {
                                "当前只使用内置提醒/任务提取规则。"
                            } else {
                                "当前使用内置规则，并附加了自定义补充指令。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    AssistChip(
                        onClick = { promptExpanded = !promptExpanded },
                        label = { Text(if (promptExpanded) "收起" else "展开") },
                    )
                }
                Text(
                    "支持变量：{{current_time}}、{{current_timezone}}、{{current_time_ms}}。系统会固定追加结构化 JSON 约束和示例。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AnimatedVisibility(visible = promptExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "内置基础提示词",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            AssistChip(
                                onClick = { builtInRulesExpanded = !builtInRulesExpanded },
                                label = { Text(if (builtInRulesExpanded) "隐藏规则" else "查看规则") },
                            )
                        }
                        AnimatedVisibility(visible = builtInRulesExpanded) {
                            OutlinedTextField(
                                value = defaultAiPromptTemplate(),
                                onValueChange = {},
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("系统内置规则（只读）") },
                                readOnly = true,
                                minLines = 8,
                            )
                        }
                        OutlinedTextField(
                            value = config.promptSupplement,
                            onValueChange = onPromptChange,
                            label = { Text("补充指令（可选）") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 4,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistChip(
                                onClick = { onPromptChange("") },
                                enabled = config.promptSupplement.isNotBlank(),
                                label = { Text("清空补充指令") },
                            )
                        }
                    }
                }
            }
        }
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
        SettingsToggleRow(
            label = "文本保存后自动整理",
            checked = config.autoRunOnTextSave,
            onCheckedChange = onToggleAutoText,
        )
        SettingsToggleRow(
            label = "语音转写后自动整理",
            checked = config.autoRunOnVoiceTranscribed,
            onCheckedChange = onToggleAutoVoice,
        )
        SettingsToggleRow(
            label = "超时/网络错误自动重试（最多 5 次）",
            checked = config.autoRetryOnTransientFailure,
            onCheckedChange = onToggleAutoRetry,
        )
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
private fun ReminderCalendarPreviewCardV2(
    reminders: List<ReminderEntry>,
    notes: List<Note>,
    onOpenNote: (Note) -> Unit,
    onArchiveNote: (String) -> Unit,
    onUnarchiveNote: (String) -> Unit,
    onDeleteNote: (String) -> Unit,
    onRestoreNote: (String) -> Unit,
    onCancelReminder: (String) -> Unit,
    onExportToAlarm: (String) -> Unit,
    onOpenCalendar: () -> Unit,
    onCreateReminderForDate: (Long, String, Int, Int) -> Unit,
) {
    val monthStartMillis = remember { startOfMonth(System.currentTimeMillis()) }
    val todayStartMillis = remember { startOfDay(System.currentTimeMillis()) }
    val scheduledReminders = remember(reminders) { reminders.filter { it.status == ReminderStatus.SCHEDULED } }
    val allReminders = remember(reminders) {
        reminders
            .filter { it.status == ReminderStatus.SCHEDULED }
            .sortedBy { it.scheduledAt }
    }
    val reminderCountsByDay = remember(scheduledReminders) {
        scheduledReminders.groupingBy { startOfDay(it.scheduledAt) }.eachCount()
    }
    var expandedDayStartMillis by remember(reminders) { mutableStateOf<Long?>(null) }
    val selectedDayStartMillis = expandedDayStartMillis ?: todayStartMillis
    val selectedDayReminders = remember(allReminders, expandedDayStartMillis) {
        expandedDayStartMillis?.let { selectedDay ->
            allReminders.filter { startOfDay(it.scheduledAt) == selectedDay }
        }.orEmpty()
    }

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("提醒日历", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (scheduledReminders.isEmpty()) {
                            "主页保留一个可点的月历缩略图，方便你直接查看本月和当天提醒。"
                        } else {
                            "本月有 ${reminderCountsByDay.size} 天带提醒，点日期可在这里展开当天安排。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AssistChip(
                    onClick = onOpenCalendar,
                    label = { Text(if (scheduledReminders.isEmpty()) "打开日历" else "查看月历") },
                )
            }
            Text(
                monthLabel(monthStartMillis),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ReminderMonthGrid(
                monthStartMillis = monthStartMillis,
                selectedDayStartMillis = selectedDayStartMillis,
                reminderCountsByDay = reminderCountsByDay,
                onSelectDay = { selectedDay ->
                    expandedDayStartMillis = if (expandedDayStartMillis == selectedDay) null else selectedDay
                },
                compact = true,
                enabled = true,
            )
            if (expandedDayStartMillis != null) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "当天提醒 · ${dayLabel(selectedDayStartMillis)}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (selectedDayReminders.isEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        ) {
                            Text(
                                text = "这一天还没有提醒，你可以从便签快捷添加，或者继续打开完整月历查看整月安排。",
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        ReminderDayAgendaList(
                            reminders = selectedDayReminders,
                            notes = notes,
                            selectedDayMillis = selectedDayStartMillis,
                            onOpenNote = onOpenNote,
                            onArchiveNote = onArchiveNote,
                            onUnarchiveNote = onUnarchiveNote,
                            onDeleteNote = onDeleteNote,
                            onRestoreNote = onRestoreNote,
                            onCancelReminder = onCancelReminder,
                            onExportToAlarm = onExportToAlarm,
                            onCreateReminderForDate = onCreateReminderForDate,
                        )
                    }
                }
            } else {
                Text(
                    "点某一天会在这里展开当天提醒；右上角可以进入完整月历页。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReminderCalendarSection(
    reminders: List<ReminderEntry>,
    notes: List<Note>,
    onOpenNote: (Note) -> Unit,
    onArchiveNote: (String) -> Unit,
    onUnarchiveNote: (String) -> Unit,
    onDeleteNote: (String) -> Unit,
    onRestoreNote: (String) -> Unit,
    onCancelReminder: (String) -> Unit,
    onExportToAlarm: (String) -> Unit,
    onCreateReminderForDate: (Long, String, Int, Int) -> Unit,
) {
    val scheduledReminders = remember(reminders) {
        reminders.filter { it.status == ReminderStatus.SCHEDULED }.sortedBy { it.scheduledAt }
    }
    val allReminders = remember(reminders) {
        reminders
            .filter { it.status == ReminderStatus.SCHEDULED }
            .sortedBy { it.scheduledAt }
    }
    val reminderCountsByDay = remember(scheduledReminders) {
        scheduledReminders.groupingBy { startOfDay(it.scheduledAt) }.eachCount()
    }
    val baseMonthStartMillis = remember { startOfMonth(System.currentTimeMillis()) }
    val initialSelectedDay = remember(reminders) {
        initialSelectedDayForMonth(baseMonthStartMillis, scheduledReminders)
    }
    val pagerState = rememberPagerState(
        initialPage = CALENDAR_PAGER_CENTER_PAGE,
        pageCount = { CALENDAR_PAGER_PAGE_COUNT },
    )
    var selectedDayStartMillis by remember(reminders) { mutableStateOf(initialSelectedDay) }
    var preferredDayOfMonth by remember(reminders) {
        mutableStateOf(Calendar.getInstance().apply { timeInMillis = initialSelectedDay }.get(Calendar.DAY_OF_MONTH))
    }
    val monthStartMillis = remember(pagerState.currentPage) {
        shiftMonth(baseMonthStartMillis, pagerState.currentPage - CALENDAR_PAGER_CENTER_PAGE)
    }
    val selectedDayReminders = remember(allReminders, selectedDayStartMillis) {
        allReminders.filter { startOfDay(it.scheduledAt) == selectedDayStartMillis }
    }
    LaunchedEffect(pagerState.currentPage, reminders) {
        selectedDayStartMillis = clampDayToMonth(monthStartMillis, preferredDayOfMonth)
    }

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("提醒月历", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (scheduledReminders.isEmpty()) {
                            "现在还没有已安排的提醒，月历会在这里持续可见。"
                        } else {
                            "先看整月，再在下方查看选中日期的提醒详情。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "左右滑动切换月份",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth(),
            ) { page ->
                val pageMonthStartMillis = shiftMonth(baseMonthStartMillis, page - CALENDAR_PAGER_CENTER_PAGE)
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        monthLabel(pageMonthStartMillis),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    ReminderMonthGrid(
                        monthStartMillis = pageMonthStartMillis,
                        selectedDayStartMillis = if (isSameMonth(selectedDayStartMillis, pageMonthStartMillis)) {
                            selectedDayStartMillis
                        } else {
                            clampDayToMonth(pageMonthStartMillis, preferredDayOfMonth)
                        },
                        reminderCountsByDay = reminderCountsByDay,
                        onSelectDay = {
                            selectedDayStartMillis = it
                            preferredDayOfMonth = Calendar.getInstance().apply { timeInMillis = it }.get(Calendar.DAY_OF_MONTH)
                        },
                        compact = false,
                        enabled = true,
                    )
                }
            }
            HorizontalDivider()
            Text(
                "当天提醒 · ${dayLabel(selectedDayStartMillis)}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (selectedDayReminders.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                ) {
                    Text(
                        "这一天还没有提醒，换个日期看看，或从便签里新建提醒。",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                ReminderDayAgendaList(
                    reminders = selectedDayReminders,
                    notes = notes,
                    selectedDayMillis = selectedDayStartMillis,
                    onOpenNote = onOpenNote,
                    onArchiveNote = onArchiveNote,
                    onUnarchiveNote = onUnarchiveNote,
                    onDeleteNote = onDeleteNote,
                    onRestoreNote = onRestoreNote,
                    onCancelReminder = onCancelReminder,
                    onExportToAlarm = onExportToAlarm,
                    onCreateReminderForDate = onCreateReminderForDate,
                )
            }
        }
    }
}

@Composable
private fun ReminderMonthGrid(
    monthStartMillis: Long,
    selectedDayStartMillis: Long,
    reminderCountsByDay: Map<Long, Int>,
    onSelectDay: (Long) -> Unit,
    compact: Boolean,
    enabled: Boolean,
) {
    val firstDayOfWeek = remember(monthStartMillis) {
        Calendar.getInstance().apply { timeInMillis = monthStartMillis }.firstDayOfWeek
    }
    val weekdayLabels = remember(firstDayOfWeek) { weekdayLabels(firstDayOfWeek) }
    val cells = remember(monthStartMillis) { buildMonthCells(monthStartMillis, firstDayOfWeek) }

    Column(verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            weekdayLabels.forEach { label ->
                Text(
                    text = label,
                    modifier = Modifier.weight(1f),
                    style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        cells.chunked(7).forEach { week ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                week.forEach { dayStartMillis ->
                    if (dayStartMillis == null) {
                        Spacer(modifier = Modifier.weight(1f).aspectRatio(1f))
                    } else {
                        val reminderCount = reminderCountsByDay[dayStartMillis] ?: 0
                        val isSelected = dayStartMillis == selectedDayStartMillis
                        val containerColor = when {
                            isSelected -> MaterialTheme.colorScheme.primaryContainer
                            reminderCount > 0 -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = if (compact) 0.5f else 0.7f)
                            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        }
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .clickable(enabled = enabled) { onSelectDay(dayStartMillis) },
                            shape = RoundedCornerShape(if (compact) 14.dp else 18.dp),
                            color = containerColor,
                            border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(if (compact) 6.dp else 8.dp),
                                verticalArrangement = Arrangement.SpaceBetween,
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    text = Calendar.getInstance().apply { timeInMillis = dayStartMillis }.get(Calendar.DAY_OF_MONTH).toString(),
                                    style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    textAlign = TextAlign.Center,
                                )
                                if (reminderCount > 0) {
                                    Text(
                                        text = if (reminderCount > 3) "3+" else reminderCount.toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                } else {
                                    Spacer(modifier = Modifier.size(12.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReminderDayAgendaList(
    reminders: List<ReminderEntry>,
    notes: List<Note>,
    selectedDayMillis: Long,
    onOpenNote: (Note) -> Unit,
    onArchiveNote: (String) -> Unit,
    onUnarchiveNote: (String) -> Unit,
    onDeleteNote: (String) -> Unit,
    onRestoreNote: (String) -> Unit,
    onCancelReminder: (String) -> Unit,
    onExportToAlarm: (String) -> Unit,
    onCreateReminderForDate: (Long, String, Int, Int) -> Unit,
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    if (showCreateDialog) {
        CreateReminderDialog(
            dayMillis = selectedDayMillis,
            onConfirm = { title, hour, minute ->
                onCreateReminderForDate(selectedDayMillis, title, hour, minute)
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false },
        )
    }
    val notesById = remember(notes) { notes.associateBy { it.id } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        reminders.forEach { reminder ->
            val note = notesById[reminder.noteId]
            val reminderTitle = reminder.title.ifBlank { "未命名提醒" }
            val reminderMeta = buildString {
                append(safeReminderTime(reminder.scheduledAt))
                append(" · ")
                append(reminder.status.name)
            }
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(reminderTitle, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                reminderMeta,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            note?.let {
                                ReminderActionIconButton(
                                    iconRes = R.drawable.ic_overlay_edit,
                                    contentDescription = "打开便签",
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    onClick = { onOpenNote(it) },
                                )
                            }
                            ReminderActionIconButton(
                                iconRes = R.drawable.ic_overlay_remind,
                                contentDescription = "导出闹钟",
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                onClick = { onExportToAlarm(reminder.id) },
                            )
                            if (reminder.status == ReminderStatus.SCHEDULED) {
                                ReminderActionIconButton(
                                    iconRes = R.drawable.ic_reminder_cancel,
                                    contentDescription = "取消提醒",
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    onClick = { onCancelReminder(reminder.id) },
                                )
                            }
                        }
                    }
                    if (note != null) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = note.colorToken.toColor().copy(alpha = 0.08f),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        note.displayTitleForMainCard().ifBlank { "关联便签" },
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    StatusPill(
                                        label = noteCalendarStateLabel(note),
                                        color = noteCalendarStateColor(note),
                                    )
                                }
                                noteCalendarSnippet(note)?.let { snippet ->
                                    Text(
                                        snippet,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    when {
                                        note.isTrashed -> {
                                            ReminderActionIconButton(
                                                iconRes = android.R.drawable.ic_menu_revert,
                                                contentDescription = "恢复便签",
                                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                                onClick = { onRestoreNote(note.id) },
                                            )
                                        }

                                        note.isArchived -> {
                                            ReminderActionIconButton(
                                                iconRes = android.R.drawable.ic_menu_revert,
                                                contentDescription = "取消归档",
                                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                                onClick = { onUnarchiveNote(note.id) },
                                            )
                                            ReminderActionIconButton(
                                                iconRes = android.R.drawable.ic_menu_delete,
                                                contentDescription = "删除便签",
                                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f),
                                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                                onClick = { onDeleteNote(note.id) },
                                            )
                                        }

                                        else -> {
                                            ReminderActionIconButton(
                                                iconRes = android.R.drawable.ic_menu_save,
                                                contentDescription = "归档便签",
                                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                                onClick = { onArchiveNote(note.id) },
                                            )
                                            ReminderActionIconButton(
                                                iconRes = android.R.drawable.ic_menu_delete,
                                                contentDescription = "删除便签",
                                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f),
                                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                                onClick = { onDeleteNote(note.id) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Text(
                            "关联便签不可用",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        FilledTonalButton(
            onClick = { showCreateDialog = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("新增日程")
        }
    }
}

@Composable
private fun CreateReminderDialog(
    dayMillis: Long,
    onConfirm: (String, Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var hour by remember { mutableStateOf("09") }
    var minute by remember { mutableStateOf("00") }
    val dateLabel = remember(dayMillis) {
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            .format(java.util.Date(dayMillis))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增日程 ($dateLabel)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("日程标题") },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = hour,
                        onValueChange = { v -> if (v.length <= 2 && v.all { it.isDigit() }) hour = v },
                        modifier = Modifier.weight(1f),
                        label = { Text("时") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = minute,
                        onValueChange = { v -> if (v.length <= 2 && v.all { it.isDigit() }) minute = v },
                        modifier = Modifier.weight(1f),
                        label = { Text("分") },
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val h = hour.toIntOrNull()?.coerceIn(0, 23) ?: 9
                    val m = minute.toIntOrNull()?.coerceIn(0, 59) ?: 0
                    onConfirm(title.ifBlank { "日程提醒" }, h, m)
                },
            ) { Text("创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun ReminderActionIconButton(
    iconRes: Int,
    contentDescription: String,
    containerColor: Color,
    tint: Color,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .size(36.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(999.dp),
        color = containerColor,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private fun noteCalendarStateLabel(note: Note): String = when {
    note.isTrashed -> "回收站"
    note.isArchived -> "已归档"
    else -> "收件箱"
}

@Composable
private fun noteCalendarStateColor(note: Note): Color = when {
    note.isTrashed -> MaterialTheme.colorScheme.errorContainer
    note.isArchived -> MaterialTheme.colorScheme.secondaryContainer
    else -> note.colorToken.toColor().copy(alpha = 0.18f)
}

private fun noteCalendarSnippet(note: Note): String? {
    val raw = when {
        note.source == NoteSource.VOICE && note.transcript?.isNotBlank() == true -> note.transcript
        else -> note.content
    }.orEmpty().trim()
    return raw
        .replace('\n', ' ')
        .takeIf { it.isNotBlank() }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagFilterBar(
    suggestedTags: List<String>,
    tagFilter: Set<String>,
    onToggleTag: (String) -> Unit,
    onClearFilter: () -> Unit,
) {
    if (suggestedTags.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        suggestedTags.forEach { tag ->
            FilterChip(
                selected = tag in tagFilter,
                onClick = { onToggleTag(tag) },
                label = { Text("#$tag", style = MaterialTheme.typography.labelSmall) },
            )
        }
        if (tagFilter.isNotEmpty()) {
            TextButton(onClick = onClearFilter) {
                Text("清除筛选", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
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
    onCopy: () -> Unit,
) {
    val accent = note.colorToken.toColor()
    var expanded by remember(note.id) { mutableStateOf(false) }
    var showOriginalContent by remember(note.id, note.originalContent) { mutableStateOf(false) }
    var reminderMenuExpanded by remember(note.id) { mutableStateOf(false) }
    var actionOverflowExpanded by remember(note.id) { mutableStateOf(false) }
    val isVoiceNote = note.source == NoteSource.VOICE
    val isPlayingThisNote = audioPlayback.currentNoteId == note.id &&
        (audioPlayback.isPlaying || audioPlayback.isBuffering || audioPlayback.positionMs > 0)
    val displayTitle = note.displayTitleForMainCard()
    val bodyText = when {
        note.source == NoteSource.VOICE && note.transcript?.isNotBlank() == true && note.content != note.transcript -> note.content
        note.transcript?.isNotBlank() == true -> note.transcript
        else -> note.content
    }
    val originalContent = note.originalContent
        ?.trim()
        ?.takeIf { it.isNotBlank() && it != bodyText.trim() }
    val aiStatusDotColor = when (suggestion?.status) {
        AiSuggestionStatus.RUNNING -> MaterialTheme.colorScheme.tertiary
        AiSuggestionStatus.READY -> MaterialTheme.colorScheme.primary
        AiSuggestionStatus.FAILED -> MaterialTheme.colorScheme.error
        AiSuggestionStatus.APPLIED -> MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
        else -> null
    }

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.clickable { expanded = !expanded },
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .animateContentSize(animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing)),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // ── Row 1: Title + AI dot + timestamp ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(modifier = Modifier.size(10.dp).background(accent, RoundedCornerShape(999.dp)))
                Text(
                    displayTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                aiStatusDotColor?.let { dotColor ->
                    Box(modifier = Modifier.size(6.dp).background(dotColor, RoundedCornerShape(999.dp)))
                }
                Text(
                    formatTime(note.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ── Row 2: Content preview (collapsed=2 lines) ──
            Text(
                bodyText,
                style = MaterialTheme.typography.bodySmall,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ── Row 3: Meta pills ──
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusPill(label = note.category.toChinese(), color = accent.copy(alpha = 0.18f))
                StatusPill(label = note.priority.toChinese(), color = MaterialTheme.colorScheme.secondaryContainer)
                if (isVoiceNote) StatusPill(label = "语音", color = MaterialTheme.colorScheme.primaryContainer)
                if (note.tags.isNotEmpty()) {
                    note.tags.take(2).forEach { tag ->
                        StatusPill(label = "#$tag", color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                    if (note.tags.size > 2) {
                        Text("+${note.tags.size - 2}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // ── Expanded detail ──
            if (expanded) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                // Original content toggle
                originalContent?.let { hiddenContent ->
                    Text(
                        if (showOriginalContent) "隐藏原内容" else "查看原内容",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { showOriginalContent = !showOriginalContent },
                    )
                    AnimatedVisibility(
                        visible = showOriginalContent,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = accent.copy(alpha = 0.08f),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text("原内容", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = accent)
                                Text(hiddenContent, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                // Full tags
                if (note.tags.size > 2) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        note.tags.forEach { tag ->
                            StatusPill(label = "#$tag", color = MaterialTheme.colorScheme.surfaceVariant)
                        }
                    }
                }

                // AI panel
                if (suggestion != null && suggestion.shouldShowPanel()) {
                    AiSuggestionPanel(
                        suggestion = suggestion,
                        onApplyAi = onApplyAi,
                        onDismissAi = onDismissAi,
                        onRunAi = onRunAi,
                        onCreateReminderFromSuggestion = onCreateReminderFromSuggestion,
                    )
                }

                // Transcription status
                note.transcriptionStatus.takeIf { it.name != "NOT_STARTED" }?.let {
                    val statusLabel = when (it.name) {
                        "UPLOADING" -> "上传中"
                        "TRANSCRIBING" -> "转写中"
                        "DONE" -> "转写成功"
                        "FAILED" -> "转写失败"
                        else -> "未开始"
                    }
                    Text(
                        buildString {
                            append("转写：$statusLabel")
                            note.transcriptionUpdatedAt?.let { ts -> append(" | ${formatTime(ts)}") }
                            note.transcriptionError?.takeIf { err -> err.isNotBlank() }?.let { err -> append(" | $err") }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (it.name == "FAILED") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Sync time
                note.lastSyncedAt?.let {
                    Text("同步于 ${formatTime(it)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                // Audio playback
                if (isVoiceNote && isPlayingThisNote) {
                    AudioPlaybackBar(playback = audioPlayback, accent = accent, onSeekAudio = onSeekAudio)
                }

                // Action row: play + reminder + overflow menu
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isVoiceNote) {
                        CompactActionIcon(
                            icon = painterResource(
                                id = if (audioPlayback.currentNoteId == note.id && audioPlayback.isPlaying) {
                                    android.R.drawable.ic_media_pause
                                } else {
                                    android.R.drawable.ic_media_play
                                },
                            ),
                            contentDescription = if (audioPlayback.currentNoteId == note.id && audioPlayback.isPlaying) "暂停" else "播放",
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
                    // Overflow menu for all other actions
                    Box {
                        CompactActionIcon(
                            icon = painterResource(id = android.R.drawable.ic_menu_sort_by_size),
                            contentDescription = "更多操作",
                            onClick = { actionOverflowExpanded = true },
                        )
                        DropdownMenu(expanded = actionOverflowExpanded, onDismissRequest = { actionOverflowExpanded = false }) {
                            if (section != NoteListSection.TRASH) {
                                DropdownMenuItem(text = { Text("复制") }, onClick = { onCopy(); actionOverflowExpanded = false })
                                DropdownMenuItem(text = { Text("编辑") }, onClick = { onEdit(); actionOverflowExpanded = false })
                                when (section) {
                                    NoteListSection.INBOX -> {
                                        DropdownMenuItem(text = { Text("归档") }, onClick = { onArchive(); actionOverflowExpanded = false })
                                    }
                                    NoteListSection.ARCHIVE -> {
                                        DropdownMenuItem(text = { Text("取消归档") }, onClick = { onUnarchive(); actionOverflowExpanded = false })
                                    }
                                    else -> Unit
                                }
                                DropdownMenuItem(text = { Text("删除") }, onClick = { onDelete(); actionOverflowExpanded = false })
                            }
                            when (section) {
                                NoteListSection.TRASH -> {
                                    DropdownMenuItem(text = { Text("恢复") }, onClick = { onRestore(); actionOverflowExpanded = false })
                                    DropdownMenuItem(text = { Text("彻底删除") }, onClick = { onDeletePermanently(); actionOverflowExpanded = false })
                                }
                                else -> Unit
                            }
                            if (section != NoteListSection.TRASH && (note.status.name == "FAILED" || note.status.name == "LOCAL_ONLY")) {
                                DropdownMenuItem(text = { Text("重新同步") }, onClick = { onRetrySync(); actionOverflowExpanded = false })
                            }
                        }
                    }
                }

                // Sync error
                note.syncError?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
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

private fun startOfDay(timeMillis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = timeMillis
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun startOfMonth(timeMillis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = startOfDay(timeMillis)
    set(Calendar.DAY_OF_MONTH, 1)
}.timeInMillis

private fun shiftMonth(monthStartMillis: Long, delta: Int): Long = Calendar.getInstance().apply {
    timeInMillis = monthStartMillis
    add(Calendar.MONTH, delta)
    set(Calendar.DAY_OF_MONTH, 1)
}.timeInMillis

private const val CALENDAR_PAGER_CENTER_PAGE = 120
private const val CALENDAR_PAGER_PAGE_COUNT = 241

private fun initialSelectedDayForMonth(
    monthStartMillis: Long,
    scheduledReminders: List<ReminderEntry>,
): Long {
    val firstReminderDay = scheduledReminders
        .asSequence()
        .map { startOfDay(it.scheduledAt) }
        .firstOrNull { isSameMonth(it, monthStartMillis) }
    if (firstReminderDay != null) return firstReminderDay

    val today = startOfDay(System.currentTimeMillis())
    return if (isSameMonth(today, monthStartMillis)) today else monthStartMillis
}

private fun clampDayToMonth(monthStartMillis: Long, dayOfMonth: Int): Long {
    val calendar = Calendar.getInstance().apply { timeInMillis = startOfMonth(monthStartMillis) }
    val targetDay = dayOfMonth.coerceIn(1, calendar.getActualMaximum(Calendar.DAY_OF_MONTH))
    calendar.set(Calendar.DAY_OF_MONTH, targetDay)
    return startOfDay(calendar.timeInMillis)
}

private fun isSameMonth(lhs: Long, rhs: Long): Boolean {
    val left = Calendar.getInstance().apply { timeInMillis = lhs }
    val right = Calendar.getInstance().apply { timeInMillis = rhs }
    return left.get(Calendar.YEAR) == right.get(Calendar.YEAR) &&
        left.get(Calendar.MONTH) == right.get(Calendar.MONTH)
}

private fun buildMonthCells(
    monthStartMillis: Long,
    firstDayOfWeek: Int,
): List<Long?> {
    val monthStart = Calendar.getInstance().apply { timeInMillis = startOfMonth(monthStartMillis) }
    val offset = (monthStart.get(Calendar.DAY_OF_WEEK) - firstDayOfWeek + 7) % 7
    val totalDays = monthStart.getActualMaximum(Calendar.DAY_OF_MONTH)
    val cells = MutableList<Long?>(offset) { null }
    repeat(totalDays) { index ->
        val day = Calendar.getInstance().apply { timeInMillis = monthStart.timeInMillis }
        day.add(Calendar.DAY_OF_MONTH, index)
        cells += startOfDay(day.timeInMillis)
    }
    while (cells.size % 7 != 0) {
        cells += null
    }
    return cells
}

private fun weekdayLabels(firstDayOfWeek: Int): List<String> {
    val labels = listOf("日", "一", "二", "三", "四", "五", "六")
    val startIndex = when (firstDayOfWeek) {
        Calendar.MONDAY -> 1
        Calendar.TUESDAY -> 2
        Calendar.WEDNESDAY -> 3
        Calendar.THURSDAY -> 4
        Calendar.FRIDAY -> 5
        Calendar.SATURDAY -> 6
        else -> 0
    }
    return (0 until 7).map { labels[(startIndex + it) % 7] }
}

private fun monthLabel(timeMillis: Long): String =
    SimpleDateFormat("yyyy 年 M 月", Locale.getDefault()).format(Date(timeMillis))

private fun dayLabel(timeMillis: Long): String =
    SimpleDateFormat("M 月 d 日 EEEE", Locale.getDefault()).format(Date(timeMillis))


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

private fun safeReminderTime(time: Long): String =
    runCatching { formatTime(time) }.getOrDefault("时间待定")
