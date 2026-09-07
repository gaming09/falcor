package com.falcor.viewer.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "falcor_prefs")


@Serializable
data class PersistedCameraCapability(
    val name: String,
    val showTalk: Boolean = false,
    val talkStreamName: String? = null,
    val liveStreamName: String? = null,
    val showPtz: Boolean = false,
    val streamNames: List<String> = emptyList()
)

@Serializable
enum class TileSpan { SMALL, MEDIUM, LARGE }

@Serializable
data class DashboardCameraTile(
    val cameraName: String,
    val span: TileSpan = TileSpan.SMALL,
    val order: Int = 0
)

@Serializable
data class Dashboard(
    val id: String,
    val name: String,
    val tiles: List<DashboardCameraTile> = emptyList()
)

class AppPreferences(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val showDetections: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_SHOW_DETECTIONS] ?: false
    }

    /** When true, swap MOVE_UP/DOWN and MOVE_LEFT/RIGHT for Reolink/ONVIF axis quirks. */
    val ptzInvertPanTilt: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_PTZ_INVERT] ?: false
    }

    val dashboards: Flow<List<Dashboard>> = context.dataStore.data.map { prefs ->
        val raw = prefs[KEY_DASHBOARDS] ?: return@map emptyList()
        runCatching { json.decodeFromString<List<Dashboard>>(raw) }.getOrDefault(emptyList())
    }

    /** User-defined home grid order (camera names). Unknown cams append at end. */
    val cameraOrder: Flow<List<String>> = context.dataStore.data.map { prefs ->
        val raw = prefs[KEY_CAMERA_ORDER] ?: return@map emptyList()
        runCatching { json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
    }

    suspend fun setShowDetections(value: Boolean) {
        context.dataStore.edit { it[KEY_SHOW_DETECTIONS] = value }
    }

    suspend fun setPtzInvertPanTilt(value: Boolean) {
        context.dataStore.edit { it[KEY_PTZ_INVERT] = value }
    }

    suspend fun saveDashboards(list: List<Dashboard>) {
        context.dataStore.edit {
            it[KEY_DASHBOARDS] = json.encodeToString(list)
        }
    }

    suspend fun saveCameraOrder(names: List<String>) {
        context.dataStore.edit {
            it[KEY_CAMERA_ORDER] = json.encodeToString(names)
        }
    }

    suspend fun clearCameraOrder() {
        context.dataStore.edit { it.remove(KEY_CAMERA_ORDER) }
    }

    val cameraCapabilities: Flow<Map<String, PersistedCameraCapability>> = context.dataStore.data.map { prefs ->
        val raw = prefs[KEY_CAMERA_CAPS] ?: return@map emptyMap()
        runCatching {
            json.decodeFromString<List<PersistedCameraCapability>>(raw).associateBy { it.name }
        }.getOrDefault(emptyMap())
    }

    suspend fun saveCameraCapabilities(caps: List<PersistedCameraCapability>) {
        context.dataStore.edit {
            it[KEY_CAMERA_CAPS] = json.encodeToString(caps)
        }
    }

    suspend fun clearCameraCapabilities() {
        context.dataStore.edit { it.remove(KEY_CAMERA_CAPS) }
    }

    companion object {
        private val KEY_SHOW_DETECTIONS = booleanPreferencesKey("show_detections")
        private val KEY_PTZ_INVERT = booleanPreferencesKey("ptz_invert_pan_tilt")
        private val KEY_DASHBOARDS = stringPreferencesKey("dashboards_json")
        private val KEY_CAMERA_CAPS = stringPreferencesKey("camera_capabilities_json")
        private val KEY_CAMERA_ORDER = stringPreferencesKey("camera_order_json")
    }
}
