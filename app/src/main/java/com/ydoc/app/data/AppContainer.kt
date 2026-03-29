package com.ydoc.app.data

import android.content.Context
import com.ydoc.app.data.local.YDocDatabase
import com.ydoc.app.recording.AudioRecorder
import com.ydoc.app.relay.RelayStorageClient
import com.ydoc.app.relay.SelfHostedRelayClient
import com.ydoc.app.sync.SyncClient
import com.ydoc.app.sync.SyncOrchestrator
import com.ydoc.app.sync.SyncScheduler
import com.ydoc.app.sync.WebDavSyncClient
import com.ydoc.app.transcription.TranscriptionOrchestrator
import com.ydoc.app.transcription.TranscriptionScheduler
import com.ydoc.app.transcription.VolcengineTranscriptionClient
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val database = YDocDatabase.build(context)
    private val logger = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BASIC
    }
    private val httpClient = OkHttpClient.Builder()
        .addInterceptor(logger)
        .build()

    private val markdownFormatter = MarkdownFormatter()
    val audioRecorder = AudioRecorder(appContext)
    val syncScheduler = SyncScheduler(appContext)
    val transcriptionScheduler = TranscriptionScheduler(appContext)
    val settingsStore = SettingsStore(appContext)
    val relayStorageClient: RelayStorageClient = SelfHostedRelayClient(httpClient)
    val volcengineTranscriptionClient = VolcengineTranscriptionClient(httpClient)
    val syncClients: List<SyncClient> = listOf(
        WebDavSyncClient(httpClient, markdownFormatter),
    )

    val noteRepository = NoteRepository(database.noteDao(), database.tombstoneDao())
    val syncTargetRepository = SyncTargetRepository(database.syncTargetDao())
    val syncOrchestrator = SyncOrchestrator(
        noteRepository = noteRepository,
        syncTargetRepository = syncTargetRepository,
        formatter = markdownFormatter,
        clients = syncClients,
    )
    val transcriptionOrchestrator = TranscriptionOrchestrator(
        noteRepository = noteRepository,
        transcriptionClient = volcengineTranscriptionClient,
        syncOrchestrator = syncOrchestrator,
    )
}
