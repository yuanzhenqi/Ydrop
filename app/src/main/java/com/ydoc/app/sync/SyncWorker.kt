package com.ydoc.app.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ydoc.app.appContainer

class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val container = applicationContext.appContainer
        val biResult = container.syncOrchestrator.syncBidirectional().getOrElse {
            return container.syncOrchestrator.syncPending()
                .fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
        }
        return if (biResult.failed > 0) Result.retry() else Result.success()
    }
}
