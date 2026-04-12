package com.ydoc.app.model

enum class RecordingState {
    IDLE,
    STARTING,
    RECORDING,
    SAVING,
}

data class RecordingUiState(
    val state: RecordingState = RecordingState.IDLE,
    val elapsedSeconds: Int = 0,
    val outputPath: String? = null,
)
