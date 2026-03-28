package com.ydoc.app.overlay

import com.ydoc.app.model.Note
import com.ydoc.app.model.NoteCategory
import com.ydoc.app.model.NotePriority

enum class OverlayMode {
    COLLAPSED,
    EXPANDED,
    RECORDING,
}

data class OverlayUiState(
    val mode: OverlayMode = OverlayMode.COLLAPSED,
    val selectedCategory: NoteCategory = NoteCategory.NOTE,
    val selectedPriority: NotePriority = NotePriority.MEDIUM,
    val draftText: String = "",
    val recentNotes: List<Note> = emptyList(),
    val isRecording: Boolean = false,
    val recordingSeconds: Int = 0,
)
