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

    val dashboards: Flow<List<Dashboard>> = context.dataStore.data.map { prefs ->
        val raw = prefs[KEY_DASHBOARDS] ?: return@map emptyList()
        runCatching { json.decodeFromString<List<Dashboard>>(raw) }.getOrDefault(emptyList())
    }

    suspend fun setShowDetections(value: Boolean) {
        context.dataStore.edit { it[KEY_SHOW_DETECTIONS] = value }
    }

    suspend fun saveDashboards(list: List<Dashboard>) {
        context.dataStore.edit {
            it[KEY_DASHBOARDS] = json.encodeToString(list)
        }
    }

    companion object {
        private val KEY_SHOW_DETECTIONS = booleanPreferencesKey("show_detections")
        private val KEY_DASHBOARDS = stringPreferencesKey("dashboards_json")
    }
}
