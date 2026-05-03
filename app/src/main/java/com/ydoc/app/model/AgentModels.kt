package com.ydoc.app.model

/** 会话里的角色。`ERROR` 用来显示 relay/provider 失败时的气泡。 */
enum class AgentRole { USER, ASSISTANT, SYSTEM, ERROR;

    fun wireName(): String = when (this) {
        USER -> "user"
        ASSISTANT -> "assistant"
        SYSTEM -> "system"
        ERROR -> "error"
    }

    companion object {
        fun fromWire(value: String): AgentRole = when (value.lowercase()) {
            "user" -> USER
            "assistant" -> ASSISTANT
            "system" -> SYSTEM
            "error" -> ERROR
            else -> ASSISTANT
        }
    }
}

data class AgentSession(
    val id: String,
    val relaySessionId: String?,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastMessagePreview: String?,
)

data class AgentMessage(
    val id: String,
    val sessionId: String,
    val role: AgentRole,
    val content: String,
    val referencedNoteIds: List<String>,
    val createdAt: Long,
    val providerError: String?,
    val pending: Boolean,
)
