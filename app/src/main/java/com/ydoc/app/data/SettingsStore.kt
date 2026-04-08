package com.ydoc.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ydoc.app.config.DefaultConfig
import com.ydoc.app.model.AiConfig
import com.ydoc.app.model.AiEndpointMode
import com.ydoc.app.model.RelayConfig
import com.ydoc.app.model.OverlayConfig
import com.ydoc.app.model.SyncSettingsState
import com.ydoc.app.model.VolcengineConfig
import com.ydoc.app.model.defaultAiPromptTemplate
import com.ydoc.app.model.legacyAiPromptTemplate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "ydoc_settings")

class SettingsStore(
    private val context: Context,
) {
    val settingsFlow: Flow<SyncSettingsState> = context.settingsDataStore.data.map { prefs ->
        SyncSettingsState(
            overlay = OverlayConfig(
                enabled = prefs[Keys.overlayEnabled] ?: false,
                handleSizeDp = prefs[Keys.overlayHandleSize] ?: 24,
                handleAlpha = prefs[Keys.overlayHandleAlpha]?.toFloatOrNull() ?: 0.8f,
                dockSide = prefs[Keys.overlayDockSide] ?: "RIGHT",
            ),
            relay = RelayConfig(
                baseUrl = prefs[Keys.relayBaseUrl] ?: DefaultConfig.RELAY_BASE_URL,
                token = prefs[Keys.relayToken] ?: DefaultConfig.RELAY_TOKEN,
                enabled = prefs[Keys.relayEnabled] ?: false,
            ),
            volcengine = VolcengineConfig(
                appId = prefs[Keys.volcAppId] ?: DefaultConfig.VOLC_APP_ID,
                accessToken = prefs[Keys.volcAccessToken] ?: DefaultConfig.VOLC_ACCESS_TOKEN,
                resourceId = prefs[Keys.volcResourceId] ?: DefaultConfig.VOLC_RESOURCE_ID,
                enabled = prefs[Keys.volcEnabled] ?: false,
            ),
            ai = AiConfig(
                enabled = prefs[Keys.aiEnabled] ?: false,
                baseUrl = prefs[Keys.aiBaseUrl] ?: "",
                token = prefs[Keys.aiToken] ?: "",
                model = prefs[Keys.aiModel] ?: "ydrop-notes-v1",
                promptSupplement = resolveAiPromptSupplement(prefs),
                endpointMode = prefs[Keys.aiEndpointMode]
                    ?.let { runCatching { AiEndpointMode.valueOf(it) }.getOrNull() }
                    ?: AiEndpointMode.AUTO,
                autoRunOnTextSave = prefs[Keys.aiAutoText] ?: true,
                autoRunOnVoiceTranscribed = prefs[Keys.aiAutoVoice] ?: true,
                autoRetryOnTransientFailure = prefs[Keys.aiAutoRetry] ?: true,
            ),
        )
    }

    suspend fun saveRelay(config: RelayConfig) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.relayBaseUrl] = config.baseUrl
            prefs[Keys.relayToken] = config.token
            prefs[Keys.relayEnabled] = config.enabled
        }
    }

    suspend fun saveOverlay(config: OverlayConfig) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.overlayEnabled] = config.enabled
            prefs[Keys.overlayHandleSize] = config.handleSizeDp
            prefs[Keys.overlayHandleAlpha] = config.handleAlpha.toString()
            prefs[Keys.overlayDockSide] = config.dockSide
        }
    }

    suspend fun saveVolcengine(config: VolcengineConfig) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.volcAppId] = config.appId
            prefs[Keys.volcAccessToken] = config.accessToken
            prefs[Keys.volcResourceId] = config.resourceId
            prefs[Keys.volcEnabled] = config.enabled
        }
    }

    suspend fun saveAi(config: AiConfig) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.aiEnabled] = config.enabled
            prefs[Keys.aiBaseUrl] = config.baseUrl
            prefs[Keys.aiToken] = config.token
            prefs[Keys.aiModel] = config.model
            prefs[Keys.aiPromptSupplement] = config.promptSupplement
            prefs[Keys.aiEndpointMode] = config.endpointMode.name
            prefs[Keys.aiAutoText] = config.autoRunOnTextSave
            prefs[Keys.aiAutoVoice] = config.autoRunOnVoiceTranscribed
            prefs[Keys.aiAutoRetry] = config.autoRetryOnTransientFailure
            prefs.remove(Keys.aiPrompt)
        }
    }

    private fun resolveAiPromptSupplement(
        prefs: androidx.datastore.preferences.core.Preferences,
    ): String {
        prefs[Keys.aiPromptSupplement]?.let { return it }
        val legacyPrompt = prefs[Keys.aiPrompt]?.trim().orEmpty()
        if (legacyPrompt.isBlank()) return ""
        if (legacyPrompt == legacyAiPromptTemplate().trim()) return ""
        if (legacyPrompt == defaultAiPromptTemplate().trim()) return ""
        return legacyPrompt
    }

    private object Keys {
        val relayBaseUrl = stringPreferencesKey("relay_base_url")
        val relayToken = stringPreferencesKey("relay_token")
        val relayEnabled = booleanPreferencesKey("relay_enabled")
        val overlayEnabled = booleanPreferencesKey("overlay_enabled")
        val overlayHandleSize = androidx.datastore.preferences.core.intPreferencesKey("overlay_handle_size")
        val overlayHandleAlpha = stringPreferencesKey("overlay_handle_alpha")
        val overlayDockSide = stringPreferencesKey("overlay_dock_side")
        val volcAppId = stringPreferencesKey("volc_app_id")
        val volcAccessToken = stringPreferencesKey("volc_access_token")
        val volcResourceId = stringPreferencesKey("volc_resource_id")
        val volcEnabled = booleanPreferencesKey("volc_enabled")
        val aiEnabled = booleanPreferencesKey("ai_enabled")
        val aiBaseUrl = stringPreferencesKey("ai_base_url")
        val aiToken = stringPreferencesKey("ai_token")
        val aiModel = stringPreferencesKey("ai_model")
        val aiPrompt = stringPreferencesKey("ai_prompt")
        val aiPromptSupplement = stringPreferencesKey("ai_prompt_supplement")
        val aiEndpointMode = stringPreferencesKey("ai_endpoint_mode")
        val aiAutoText = booleanPreferencesKey("ai_auto_text")
        val aiAutoVoice = booleanPreferencesKey("ai_auto_voice")
        val aiAutoRetry = booleanPreferencesKey("ai_auto_retry")
    }
}
