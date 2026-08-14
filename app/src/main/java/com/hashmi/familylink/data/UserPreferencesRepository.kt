package com.hashmi.familylink.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_preferences")

class UserPreferencesRepository(private val context: Context) {

    private object PreferencesKeys {
        val APP_MODE = stringPreferencesKey("app_mode")
        val DEVICE_ID = stringPreferencesKey("device_id")
    }

    val appModeFlow: Flow<AppMode> = context.dataStore.data.map { preferences ->
        val modeString = preferences[PreferencesKeys.APP_MODE] ?: AppMode.UNDEFINED.name
        AppMode.valueOf(modeString)
    }

    val deviceIdFlow: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[PreferencesKeys.DEVICE_ID]
    }

    suspend fun updateAppMode(appMode: AppMode) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.APP_MODE] = appMode.name
        }
    }

    suspend fun updateDeviceId(deviceId: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.DEVICE_ID] = deviceId
        }
    }
}
