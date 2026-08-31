package com.sieve.storage.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class StoragePrefs(
    val outputTreeUri: String?,        // content://.../tree/...
    val outputDirLabelDefault: String?,
    val useMediaStore: Boolean,        // when no tree granted
)

class StorageSettings(private val dataStore: DataStore<Preferences>) {

    private object Keys {
        val TREE = stringPreferencesKey("output_tree_uri")
        val LABEL = stringPreferencesKey("output_dir_label_default")
        val USE_MS = booleanPreferencesKey("use_media_store")
    }

    val prefs: Flow<StoragePrefs> = dataStore.data.map { p ->
        StoragePrefs(
            outputTreeUri = p[Keys.TREE],
            outputDirLabelDefault = p[Keys.LABEL],
            useMediaStore = p[Keys.USE_MS] ?: true, // default: fall back to MediaStore when no tree
        )
    }

    suspend fun setOutputTree(uri: String?) {
        dataStore.edit { e -> if (uri == null) e.remove(Keys.TREE) else e[Keys.TREE] = uri }
    }
    suspend fun setDefaultDirLabel(label: String?) {
        dataStore.edit { e -> if (label == null) e.remove(Keys.LABEL) else e[Keys.LABEL] = label }
    }
    suspend fun setUseMediaStore(enabled: Boolean) {
        dataStore.edit { e -> e[Keys.USE_MS] = enabled }
    }
}
