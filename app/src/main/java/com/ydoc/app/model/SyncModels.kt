package com.ydoc.app.model

import kotlinx.serialization.Serializable

enum class SyncType {
    WEBDAV,
}

data class SyncTarget(
    val type: SyncType,
    val enabled: Boolean,
    val config: SyncConfig,
    val updatedAt: Long,
)

sealed interface SyncConfig

@Serializable
data class RelayConfig(
    val baseUrl: String = "",
    val token: String = "",
    val enabled: Boolean = false,
)

@Serializable
data class VolcengineConfig(
    val appId: String = "",
    val accessToken: String = "",
    val resourceId: String = "volc.bigasr.auc",
    val language: String = "zh-CN",
    val enabled: Boolean = false,
)

enum class OverlayDockSide { LEFT, RIGHT }

@Serializable
data class OverlayConfig(
    val enabled: Boolean = false,
    val handleSizeDp: Int = 24,
    val handleAlpha: Float = 0.8f,
    val dockSide: String = OverlayDockSide.RIGHT.name,
)

@Serializable
data class WebDavConfig(
    val baseUrl: String = "",
    val username: String = "",
    val password: String = "",
    val folder: String = "ydoc/inbox",
    val autoSync: Boolean = true,
    val wifiOnly: Boolean = false,
    val syncIntervalMinutes: Int = 15,
) : SyncConfig

data class SyncSettingsState(
    val webDav: WebDavConfig = WebDavConfig(),
    val webDavEnabled: Boolean = false,
    val overlay: OverlayConfig = OverlayConfig(),
    val relay: RelayConfig = RelayConfig(),
    val volcengine: VolcengineConfig = VolcengineConfig(),
    val ai: AiConfig = AiConfig(),
    val requiresOverlayPermission: Boolean = false,
    val isTestingRelay: Boolean = false,
    val isTestingVolcengine: Boolean = false,
    val isTestingWebDav: Boolean = false,
    val isTestingAi: Boolean = false,
    val hasUnsavedChanges: Boolean = false,
)
