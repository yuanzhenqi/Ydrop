package com.ydoc.app.ui.agent

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ydoc.app.data.AgentRepository
import com.ydoc.app.data.AppContainer
import com.ydoc.app.data.SettingsStore
import com.ydoc.app.model.AgentMessage
import com.ydoc.app.model.AgentSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AgentUiState(
    val sessions: List<AgentSession> = emptyList(),
    val currentSessionId: String? = null,
    val messages: List<AgentMessage> = emptyList(),
    val sending: Boolean = false,
    val refreshing: Boolean = false,
    val aiConfigured: Boolean = false,
    val error: String? = null,
)

class AgentViewModel(
    application: Application,
    private val repository: AgentRepository,
    private val settingsStore: SettingsStore,
) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(AgentUiState())
    val state: StateFlow<AgentUiState> = _state.asStateFlow()

    private var messagesJob: Job? = null

    init {
        viewModelScope.launch {
            launch {
                repository.observeSessions().collectLatest { sessions ->
                    val currentId = _state.value.currentSessionId
                    val resolvedCurrent = currentId?.takeIf { id -> sessions.any { it.id == id } }
                        ?: sessions.firstOrNull()?.id
                    _state.value = _state.value.copy(sessions = sessions, currentSessionId = resolvedCurrent)
                    if (resolvedCurrent != null && resolvedCurrent != currentId) {
                        subscribeMessages(resolvedCurrent)
                    }
                }
            }
            launch {
                settingsStore.settingsFlow.collectLatest { settings ->
                    val configured = settings.relay.baseUrl.isNotBlank() &&
                        settings.relay.token.isNotBlank() &&
                        settings.ai.enabled
                    _state.value = _state.value.copy(aiConfigured = configured)
                }
            }
            launch {
                _state.value = _state.value.copy(refreshing = true)
                // 清理 5 分钟前还卡在 pending 的占位气泡：app 被杀、网络中断、旧 APK 遗留都会留下这类"永远思考中"的 bubble。
                runCatching { repository.expireStalePendingOlderThan(5 * 60 * 1000L) }
                runCatching { repository.refreshSessions() }
                _state.value = _state.value.copy(refreshing = false)
            }
        }
    }

    fun switchSession(sessionId: String) {
        if (_state.value.currentSessionId == sessionId) return
        _state.value = _state.value.copy(currentSessionId = sessionId, messages = emptyList())
        subscribeMessages(sessionId)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.hydrateSession(sessionId) }
        }
    }

    fun createSession() {
        viewModelScope.launch {
            val id = withContext(Dispatchers.IO) { repository.createLocalSession() }
            _state.value = _state.value.copy(currentSessionId = id, messages = emptyList())
            subscribeMessages(id)
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteSession(sessionId)
        }
    }

    fun renameSession(sessionId: String, title: String) {
        val trimmed = title.trim().ifBlank { return }
        viewModelScope.launch(Dispatchers.IO) {
            repository.renameSession(sessionId, trimmed)
        }
    }

    fun send(content: String) {
        val text = content.trim()
        if (text.isBlank()) return
        val sessionId = _state.value.currentSessionId ?: run {
            viewModelScope.launch {
                val id = withContext(Dispatchers.IO) { repository.createLocalSession() }
                _state.value = _state.value.copy(currentSessionId = id, messages = emptyList())
                subscribeMessages(id)
                performSend(id, text)
            }
            return
        }
        performSend(sessionId, text)
    }

    private fun performSend(sessionId: String, text: String) {
        if (_state.value.sending) return
        _state.value = _state.value.copy(sending = true, error = null)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { repository.sendMessage(sessionId, text) }
            _state.value = _state.value.copy(
                sending = false,
                error = result.exceptionOrNull()?.message,
            )
        }
    }

    private fun subscribeMessages(sessionId: String) {
        messagesJob?.cancel()
        messagesJob = viewModelScope.launch {
            repository.observeMessages(sessionId).collectLatest { messages ->
                _state.value = _state.value.copy(messages = messages)
            }
        }
    }

    companion object {
        fun factory(container: AppContainer, application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    return AgentViewModel(application, container.agentRepository, container.settingsStore) as T
                }
            }
    }
}
