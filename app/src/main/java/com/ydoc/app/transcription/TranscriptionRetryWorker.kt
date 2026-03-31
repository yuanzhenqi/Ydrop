package com.ydoc.app.transcription

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ydoc.app.data.AppContainer
import kotlinx.coroutines.flow.first

class TranscriptionRetryWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val noteId = inputData.getString(KEY_NOTE_ID) ?: return Result.failure()
        val container = AppContainer(applicationContext)
        val note = container.noteRepository.getNote(noteId) ?: return Result.failure()
        val current = container.settingsStore.settingsFlow.first()
        if (!current.relay.enabled || !current.volcengine.enabled) return Result.failure()

        return runCatching {
            container.transcriptionOrchestrator.transcribe(note, current.volcengine, current.relay)
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() },
        )
    }

    companion object {
        const val KEY_NOTE_ID = "note_id"
    }
}
