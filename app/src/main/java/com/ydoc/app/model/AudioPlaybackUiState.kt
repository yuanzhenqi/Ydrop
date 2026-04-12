package com.ydoc.app.model

data class AudioPlaybackUiState(
    val currentNoteId: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val canSeek: Boolean = false,
)
