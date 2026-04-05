package com.ydoc.app.recording

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import com.ydoc.app.model.AudioPlaybackUiState
import com.ydoc.app.model.Note
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LocalAudioPlayer(
    private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _playbackState = MutableStateFlow(AudioPlaybackUiState())
    val playbackState: StateFlow<AudioPlaybackUiState> = _playbackState.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var currentNote: Note? = null
    private var progressJob: Job? = null

    fun canPlay(note: Note): Boolean = resolveSource(note) != null

    fun play(note: Note, onMessage: (String) -> Unit) {
        playOrToggle(note, onMessage)
    }

    fun playOrToggle(note: Note, onMessage: (String) -> Unit = {}) {
        val source = resolveSource(note) ?: run {
            onMessage("本机没有可播放的本地音频。")
            return
        }

        val sameNote = currentNote?.id == note.id
        val player = mediaPlayer
        if (sameNote && player != null) {
            if (player.isPlaying) {
                pause()
                return
            }
            resumeExistingPlayer(player, note, onMessage)
            return
        }

        prepareAndPlay(note, source, onMessage)
    }

    fun pause() {
        mediaPlayer?.takeIf { it.isPlaying }?.pause()
        stopProgressUpdates()
        _playbackState.value = _playbackState.value.copy(
            isPlaying = false,
            isBuffering = false,
            positionMs = mediaPlayer?.currentPosition?.toLong() ?: _playbackState.value.positionMs,
            durationMs = mediaPlayer?.duration?.takeIf { it > 0 }?.toLong() ?: _playbackState.value.durationMs,
            canSeek = (mediaPlayer?.duration ?: 0) > 0,
        )
    }

    fun seekTo(positionMs: Long) {
        val player = mediaPlayer ?: return
        val duration = player.duration.takeIf { it > 0 } ?: return
        val target = positionMs.coerceIn(0L, duration.toLong()).toInt()
        player.seekTo(target)
        _playbackState.value = _playbackState.value.copy(
            positionMs = target.toLong(),
            durationMs = duration.toLong(),
            canSeek = true,
        )
    }

    fun release() {
        stopProgressUpdates()
        mediaPlayer?.runCatching {
            if (isPlaying) stop()
        }
        mediaPlayer?.release()
        mediaPlayer = null
        currentNote = null
        _playbackState.value = AudioPlaybackUiState()
    }

    private fun prepareAndPlay(
        note: Note,
        source: AudioSource,
        onMessage: (String) -> Unit,
    ) {
        release()
        currentNote = note
        _playbackState.value = AudioPlaybackUiState(
            currentNoteId = note.id,
            isBuffering = true,
        )

        mediaPlayer = MediaPlayer().apply {
            when (source) {
                is AudioSource.FilePath -> setDataSource(source.path)
                is AudioSource.ContentUri -> setDataSource(context, source.uri)
            }
            setOnPreparedListener { prepared ->
                prepared.start()
                val duration = prepared.duration.takeIf { it > 0 }?.toLong() ?: 0L
                _playbackState.value = AudioPlaybackUiState(
                    currentNoteId = note.id,
                    isPlaying = true,
                    isBuffering = false,
                    positionMs = prepared.currentPosition.toLong(),
                    durationMs = duration,
                    canSeek = duration > 0L,
                )
                startProgressUpdates(prepared, note.id)
            }
            setOnCompletionListener { completed ->
                stopProgressUpdates()
                _playbackState.value = AudioPlaybackUiState(
                    currentNoteId = note.id,
                    isPlaying = false,
                    isBuffering = false,
                    positionMs = completed.duration.toLong(),
                    durationMs = completed.duration.toLong(),
                    canSeek = completed.duration > 0,
                )
            }
            setOnErrorListener { _, _, _ ->
                onMessage("无法播放本地录音。")
                release()
                true
            }
            prepareAsync()
        }
    }

    private fun resumeExistingPlayer(
        player: MediaPlayer,
        note: Note,
        onMessage: (String) -> Unit,
    ) {
        runCatching {
            val duration = player.duration.takeIf { it > 0 } ?: 0
            if (duration > 0 && player.currentPosition >= duration - 250) {
                player.seekTo(0)
            }
            player.start()
            _playbackState.value = _playbackState.value.copy(
                currentNoteId = note.id,
                isPlaying = true,
                isBuffering = false,
                positionMs = player.currentPosition.toLong(),
                durationMs = duration.toLong(),
                canSeek = duration > 0,
            )
            startProgressUpdates(player, note.id)
        }.onFailure {
            onMessage("无法播放本地录音。")
            release()
        }
    }

    private fun startProgressUpdates(player: MediaPlayer, noteId: String) {
        stopProgressUpdates()
        progressJob = scope.launch {
            while (mediaPlayer === player) {
                val duration = player.duration.takeIf { it > 0 }?.toLong() ?: 0L
                _playbackState.value = _playbackState.value.copy(
                    currentNoteId = noteId,
                    isPlaying = player.isPlaying,
                    isBuffering = false,
                    positionMs = player.currentPosition.toLong(),
                    durationMs = duration,
                    canSeek = duration > 0L,
                )
                if (!player.isPlaying) break
                delay(250)
            }
        }
    }

    private fun stopProgressUpdates() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun resolveSource(note: Note): AudioSource? {
        note.audioPath?.takeIf { File(it).exists() }?.let { return AudioSource.FilePath(it) }
        val publicRef = note.audioPublicUri ?: return null
        return if (publicRef.startsWith("content://")) {
            AudioSource.ContentUri(Uri.parse(publicRef))
        } else {
            publicRef.takeIf { File(it).exists() }?.let { AudioSource.FilePath(it) }
        }
    }

    private sealed interface AudioSource {
        data class FilePath(val path: String) : AudioSource
        data class ContentUri(val uri: Uri) : AudioSource
    }
}
