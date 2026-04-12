package com.ydoc.app.overlay

import com.ydoc.app.model.Note
import com.ydoc.app.model.NoteCategory
import com.ydoc.app.model.NotePriority
import com.ydoc.app.model.OverlayDockSide

enum class OverlaySurfaceState {
    HANDLE_COLLAPSED,
    STRIP_EXPANDED,
    COMPOSER_ACTIVE,
}

enum class OverlayComposerMode {
    TEXT,
    RECORDING,
}

enum class OverlayRecordingOrigin {
    NONE,
    ENTRY_HOLD,
    COMPOSER_BUTTON,
}

enum class OverlayRecordingStopReason {
    ENTRY_RELEASE,
    ENTRY_EXTERNAL_CANCEL,
    COMPOSER_BUTTON,
}

sealed interface OverlayStripItem {
    data class ComposerEntryItem(
        val category: NoteCategory,
        val priority: NotePriority,
        val isRecording: Boolean = false,
        val recordingSeconds: Int = 0,
    ) : OverlayStripItem

    data class NoteStripItem(
        val note: Note,
    ) : OverlayStripItem

    data class ExpandedNoteItem(
        val note: Note,
    ) : OverlayStripItem

    data class EditingNoteItem(
        val note: Note,
        val draft: String,
        val category: NoteCategory,
        val priority: NotePriority,
        val tags: List<String> = emptyList(),
    ) : OverlayStripItem
}

data class OverlayUiState(
    val surfaceState: OverlaySurfaceState = OverlaySurfaceState.HANDLE_COLLAPSED,
    val composerMode: OverlayComposerMode = OverlayComposerMode.TEXT,
    val selectedCategory: NoteCategory = NoteCategory.NOTE,
    val selectedPriority: NotePriority = NotePriority.MEDIUM,
    val composerDraft: String = "",
    val stripItems: List<OverlayStripItem> = emptyList(),
    val isRecording: Boolean = false,
    val recordingSeconds: Int = 0,
    val recordingOrigin: OverlayRecordingOrigin = OverlayRecordingOrigin.NONE,
    val entryHoldActive: Boolean = false,
    val expandedNoteId: String? = null,
    val editingNoteId: String? = null,
    val editingDraft: String = "",
    val editingCategory: NoteCategory = NoteCategory.NOTE,
    val editingPriority: NotePriority = NotePriority.MEDIUM,
    val editingTags: List<String> = emptyList(),
    val imeInsetBottom: Int = 0,
    val imeVisible: Boolean = false,
    val dockSide: OverlayDockSide = OverlayDockSide.RIGHT,
)
