package com.sieve.app.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.sieve.queue.core.RestoreHold
import com.sieve.queue.core.RestoreHoldStore
import kotlinx.coroutines.flow.first

/**
 * The queue's one-time "restore paused" marker, held ids and banner-dismissed flag, kept in the app's DataStore
 * (the same instance as [AppSettings], under its own keys). An empty store is what a device that ran v1.0.3 or
 * older looks like: no marker, which is what makes its first restore a held one. Written in one edit, so the
 * three values never disagree.
 */
class DataStoreRestoreHoldStore(private val dataStore: DataStore<Preferences>) : RestoreHoldStore {

    private object K {
        val MIGRATED = booleanPreferencesKey("restore_hold_migrated")
        val HELD = stringSetPreferencesKey("restore_hold_ids")
        val DISMISSED = booleanPreferencesKey("restore_banner_dismissed")
    }

    override suspend fun load(): RestoreHold = dataStore.data.first().let { p ->
        RestoreHold(
            migrated = p[K.MIGRATED] ?: false,
            heldIds = p[K.HELD] ?: emptySet(),
            bannerDismissed = p[K.DISMISSED] ?: false,
        )
    }

    override suspend fun save(hold: RestoreHold) {
        dataStore.edit {
            it[K.MIGRATED] = hold.migrated
            if (hold.heldIds.isEmpty()) it.remove(K.HELD) else it[K.HELD] = hold.heldIds
            it[K.DISMISSED] = hold.bannerDismissed
        }
    }
}
