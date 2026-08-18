package com.hashmi.familylink.data

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map


private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_preferences")

class UserPreferencesRepository(private val context: Context) {

    private object Keys {
        val APP_MODE = stringPreferencesKey("app_mode")
        val DEVICE_ID = stringPreferencesKey("device_id")
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val PAIRING_KEY = stringPreferencesKey("pairing_key")
        val AUTHORIZED_DEVICE_ID = stringPreferencesKey("authorized_device_id")
        val AUTHORIZED_PAIRING_KEY = stringPreferencesKey("authorized_pairing_key")
        val AUTHORIZED_DEVICE_NAME = stringPreferencesKey("authorized_device_name")
        val LAST_SERVER_HOST = stringPreferencesKey("last_server_host")
        val LAST_SERVER_PORT = intPreferencesKey("last_server_port")
        val LAST_SERVER_NAME = stringPreferencesKey("last_server_name")
    }

    val appModeFlow: Flow<AppMode> = context.dataStore.data.map { preferences ->
        runCatching {
            AppMode.valueOf(preferences[Keys.APP_MODE] ?: AppMode.UNDEFINED.name)
        }.getOrDefault(AppMode.UNDEFINED)
    }

    val deviceIdFlow: Flow<String?> = context.dataStore.data.map { it[Keys.DEVICE_ID] }

    val deviceNameFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[Keys.DEVICE_NAME] ?: defaultDeviceName()
    }

    val pairingKeyFlow: Flow<String?> = context.dataStore.data.map { it[Keys.PAIRING_KEY] }

    val authorizedClientFlow: Flow<AuthorizedClient?> = context.dataStore.data.map { preferences ->
        val key = preferences[Keys.AUTHORIZED_PAIRING_KEY] ?: return@map null
        AuthorizedClient(
            deviceId = preferences[Keys.AUTHORIZED_DEVICE_ID].orEmpty(),
            pairingKey = key,
            deviceName = preferences[Keys.AUTHORIZED_DEVICE_NAME].orEmpty()
        )
    }

    val lastServerHostFlow: Flow<String?> = context.dataStore.data.map { it[Keys.LAST_SERVER_HOST] }

    suspend fun currentAppMode(): AppMode = appModeFlow.first()

    suspend fun updateAppMode(appMode: AppMode) {
        context.dataStore.edit { it[Keys.APP_MODE] = appMode.name }
    }

    suspend fun updateDeviceName(name: String) {
        context.dataStore.edit { it[Keys.DEVICE_NAME] = name.trim().ifBlank { defaultDeviceName() } }
    }

    suspend fun updateDeviceId(deviceId: String) {
        context.dataStore.edit { it[Keys.DEVICE_ID] = deviceId }
    }

    suspend fun ensureIdentity(): DeviceIdentity {
        var identity: DeviceIdentity? = null
        context.dataStore.edit { preferences ->
            val id = preferences[Keys.DEVICE_ID] ?: newDeviceId().also { preferences[Keys.DEVICE_ID] = it }
            val key = preferences[Keys.PAIRING_KEY] ?: PairingKeys.generate().also { preferences[Keys.PAIRING_KEY] = it }
            val name = preferences[Keys.DEVICE_NAME] ?: defaultDeviceName().also { preferences[Keys.DEVICE_NAME] = it }
            identity = DeviceIdentity(id, key, name)
        }
        return identity!!
    }

    suspend fun setAuthorizedClient(deviceId: String, pairingKey: String, deviceName: String) {
        context.dataStore.edit { preferences ->
            preferences[Keys.AUTHORIZED_DEVICE_ID] = deviceId
            preferences[Keys.AUTHORIZED_PAIRING_KEY] = pairingKey
            preferences[Keys.AUTHORIZED_DEVICE_NAME] = deviceName
        }
    }

    suspend fun clearAuthorizedClient() {
        context.dataStore.edit { preferences ->
            preferences.remove(Keys.AUTHORIZED_DEVICE_ID)
            preferences.remove(Keys.AUTHORIZED_PAIRING_KEY)
            preferences.remove(Keys.AUTHORIZED_DEVICE_NAME)
        }
    }

    suspend fun setLastServer(host: String, port: Int, name: String) {
        context.dataStore.edit { preferences ->
            preferences[Keys.LAST_SERVER_HOST] = host
            preferences[Keys.LAST_SERVER_PORT] = port
            preferences[Keys.LAST_SERVER_NAME] = name
        }
    }

    suspend fun lastServer(): Pair<String, Int>? {
        val prefs = context.dataStore.data.first()
        val host = prefs[Keys.LAST_SERVER_HOST] ?: return null
        val port = prefs[Keys.LAST_SERVER_PORT] ?: QrPayload.DEFAULT_PORT
        return host to port
    }

    suspend fun resetMode() {
        context.dataStore.edit { preferences ->
            preferences[Keys.APP_MODE] = AppMode.UNDEFINED.name
            preferences.remove(Keys.AUTHORIZED_DEVICE_ID)
            preferences.remove(Keys.AUTHORIZED_PAIRING_KEY)
            preferences.remove(Keys.AUTHORIZED_DEVICE_NAME)
            preferences.remove(Keys.LAST_SERVER_HOST)
            preferences.remove(Keys.LAST_SERVER_PORT)
            preferences.remove(Keys.LAST_SERVER_NAME)
        }
    }

    companion object {
        fun newDeviceId(): String = java.util.UUID.randomUUID().toString()

        fun defaultDeviceName(): String {
            val model = Build.MODEL?.trim().orEmpty()
            return if (model.isBlank()) "Family Device" else model
        }
    }
}
