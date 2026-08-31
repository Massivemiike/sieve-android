package com.sieve.app.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.sieve.app.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The consolidated app preferences (theme/accent/caps/network/schedule). Storage-tree prefs
 *  live separately in :storage StorageSettings, backed by the SAME DataStore instance. */
data class AppPrefs(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val accentHex: String = DEFAULT_ACCENT,
    val defaultPresetId: String = "best-video",
    val maxDownloads: Int = 3,
    val maxTranscodes: Int = 1,
    val proxy: String? = null,
    val userAgent: String? = null,
    val speedLimit: String? = null,
    val cookiesFileUri: String? = null,
    val scheduleEnabled: Boolean = false,
    val scheduleStart: Int = 0, // minutes from midnight
    val scheduleEnd: Int = 0,
) {
    companion object { const val DEFAULT_ACCENT = "#E0A458" }
}

class AppSettings(private val dataStore: DataStore<Preferences>) {

    private object K {
        val THEME = stringPreferencesKey("theme_mode")
        val ACCENT = stringPreferencesKey("accent_hex")
        val PRESET = stringPreferencesKey("default_preset_id")
        val MAXD = intPreferencesKey("max_downloads")
        val MAXT = intPreferencesKey("max_transcodes")
        val PROXY = stringPreferencesKey("proxy")
        val UA = stringPreferencesKey("user_agent")
        val SPEED = stringPreferencesKey("speed_limit")
        val COOKIES = stringPreferencesKey("cookies_file_uri")
        val SCHED_ON = booleanPreferencesKey("schedule_enabled")
        val SCHED_START = intPreferencesKey("schedule_start")
        val SCHED_END = intPreferencesKey("schedule_end")
    }

    val flow: Flow<AppPrefs> = dataStore.data.map { p ->
        AppPrefs(
            themeMode = p[K.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.DARK,
            accentHex = p[K.ACCENT] ?: AppPrefs.DEFAULT_ACCENT,
            defaultPresetId = p[K.PRESET] ?: "best-video",
            maxDownloads = p[K.MAXD] ?: 3,
            maxTranscodes = p[K.MAXT] ?: 1,
            proxy = p[K.PROXY],
            userAgent = p[K.UA],
            speedLimit = p[K.SPEED],
            cookiesFileUri = p[K.COOKIES],
            scheduleEnabled = p[K.SCHED_ON] ?: false,
            scheduleStart = p[K.SCHED_START] ?: 0,
            scheduleEnd = p[K.SCHED_END] ?: 0,
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) = dataStore.edit { it[K.THEME] = mode.name }
    suspend fun setAccentHex(hex: String) = dataStore.edit { it[K.ACCENT] = hex }
    suspend fun setDefaultPreset(id: String) = dataStore.edit { it[K.PRESET] = id }
    suspend fun setMaxDownloads(n: Int) = dataStore.edit { it[K.MAXD] = n.coerceIn(1, 10) }
    suspend fun setMaxTranscodes(n: Int) = dataStore.edit { it[K.MAXT] = n.coerceIn(1, 4) }
    suspend fun setProxy(v: String?) = editNullable(K.PROXY, v)
    suspend fun setUserAgent(v: String?) = editNullable(K.UA, v)
    suspend fun setSpeedLimit(v: String?) = editNullable(K.SPEED, v)
    suspend fun setCookiesFileUri(v: String?) = editNullable(K.COOKIES, v)
    suspend fun setSchedule(enabled: Boolean, start: Int, end: Int) = dataStore.edit {
        it[K.SCHED_ON] = enabled; it[K.SCHED_START] = start; it[K.SCHED_END] = end
    }

    private suspend fun editNullable(key: Preferences.Key<String>, v: String?) = dataStore.edit { e ->
        if (v.isNullOrBlank()) e.remove(key) else e[key] = v
    }
}
