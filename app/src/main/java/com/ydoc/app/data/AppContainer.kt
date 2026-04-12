package com.ydoc.app.data

import android.content.Context
import com.ydoc.app.ai.AiOrchestrator
import com.ydoc.app.ai.RelayAiClient
import com.ydoc.app.data.local.YDocDatabase
import com.ydoc.app.recording.AudioRecorder
import com.ydoc.app.recording.LocalAudioExporter
import com.ydoc.app.recording.LocalAudioPlayer
import com.ydoc.app.recording.VoiceNoteProcessor
import com.ydoc.app.relay.RelayStorageClient
import com.ydoc.app.relay.SelfHostedRelayClient
import com.ydoc.app.sync.SyncClient
import com.ydoc.app.sync.SyncOrchestrator
import com.ydoc.app.sync.SyncScheduler
import com.ydoc.app.sync.WebDavSyncClient
import com.ydoc.app.reminder.ReminderScheduler
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

    // AI/LLM 专用 client，显式超时防止 provider 挂起导致 AiSuggestion 卡在 RUNNING
    private val aiHttpClient = OkHttpClient.Builder()
        .addInterceptor(logger)
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val markdownFormatter = MarkdownFormatter()
    val audioRecorder = AudioRecorder(appContext)
    val localAudioExporter = LocalAudioExporter(appContext)
    val localAudioPlayer = LocalAudioPlayer(appContext)
    val syncScheduler = SyncScheduler(appContext)
    val transcriptionScheduler = TranscriptionScheduler(appContext)
    val reminderScheduler = ReminderScheduler(appContext)
    val settingsStore = SettingsStore(appContext)
    val relayStorageClient: RelayStorageClient = SelfHostedRelayClient(httpClient)
    val volcengineTranscriptionClient = VolcengineTranscriptionClient(httpClient)
    val aiClient = RelayAiClient(aiHttpClient)
    val syncClients: List<SyncClient> = listOf(
        WebDavSyncClient(httpClient, markdownFormatter),
    )

    val noteRepository = NoteRepository(database.noteDao(), database.tombstoneDao())
    val aiSuggestionRepository = AiSuggestionRepository(database.aiSuggestionDao())
    val reminderRepository = ReminderRepository(database.reminderEntryDao())
    val syncTargetRepository = SyncTargetRepository(database.syncTargetDao())
    val syncOrchestrator = SyncOrchestrator(
        noteRepository = noteRepository,
        syncTargetRepository = syncTargetRepository,
        formatter = markdownFormatter,
        clients = syncClients,
    )
    val aiOrchestrator = AiOrchestrator(
        noteRepository = noteRepository,
        aiSuggestionRepository = aiSuggestionRepository,
        aiClient = aiClient,
        settingsStore = settingsStore,
    )
    val transcriptionOrchestrator = TranscriptionOrchestrator(
        noteRepository = noteRepository,
        transcriptionClient = volcengineTranscriptionClient,
        syncOrchestrator = syncOrchestrator,
        relayStorageClient = relayStorageClient,
        aiOrchestrator = aiOrchestrator,
    )
    val voiceNoteProcessor = VoiceNoteProcessor(
        audioRecorder = audioRecorder,
        localAudioExporter = localAudioExporter,
        noteRepository = noteRepository,
        relayStorageClient = relayStorageClient,
        transcriptionOrchestrator = transcriptionOrchestrator,
        transcriptionScheduler = transcriptionScheduler,
    )
}
