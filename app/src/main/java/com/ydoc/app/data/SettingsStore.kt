package com.ydoc.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ydoc.app.config.DefaultConfig
import com.ydoc.app.model.RelayConfig
import com.ydoc.app.model.OverlayConfig
import com.ydoc.app.model.SyncSettingsState
import com.ydoc.app.model.VolcengineConfig
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
    }
}
