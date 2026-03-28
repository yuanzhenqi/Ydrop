package com.ydoc.app.transcription

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class TranscriptionScheduler(
    private val context: Context,
) {
    fun enqueueRetry(noteId: String, wifiOnly: Boolean) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<TranscriptionRetryWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .setInputData(Data.Builder().putString(TranscriptionRetryWorker.KEY_NOTE_ID, noteId).build())
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            uniqueName(noteId),
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    private fun uniqueName(noteId: String): String = "ydoc-transcription-retry-$noteId"
}
