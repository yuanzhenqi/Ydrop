package com.ydoc.app.model

enum class ReminderSource {
    MANUAL,
    AI,
}

enum class ReminderStatus {
    SCHEDULED,
    DISMISSED,
    FIRED,
    CANCELLED,
}

enum class ReminderDeliveryTarget {
    LOCAL_NOTIFICATION,
    SYSTEM_ALARM_EXPORT,
    FEISHU,
}

data class ReminderEntry(
    val id: String,
    val noteId: String,
    val title: String,
    val scheduledAt: Long,
    val source: ReminderSource,
    val status: ReminderStatus,
    val deliveryTargets: Set<ReminderDeliveryTarget>,
    val createdAt: Long,
    val updatedAt: Long,
)
