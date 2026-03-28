package com.ydoc.app.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ydoc.app.data.AppContainer

class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val container = AppContainer(applicationContext)
        return container.syncOrchestrator.syncPending()
            .fold(
                onSuccess = { Result.success() },
                onFailure = { Result.retry() },
            )
    }
}
