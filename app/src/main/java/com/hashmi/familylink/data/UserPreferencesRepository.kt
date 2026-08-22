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
        val PAIRED_HUB_ID = stringPreferencesKey("paired_hub_id")
        val PAIRED_HUB_HOST = stringPreferencesKey("paired_hub_host")
        val PAIRED_HUB_PORT = intPreferencesKey("paired_hub_port")
        val PAIRED_HUB_NAME = stringPreferencesKey("paired_hub_name")
        val PAIRED_HUB_KEY = stringPreferencesKey("paired_hub_key")
        val RELAY_URL = stringPreferencesKey("relay_url")
        val PROJECTION_GRANT_CODE = intPreferencesKey("projection_grant_code")
        val PROJECTION_GRANT_DATA = stringPreferencesKey("projection_grant_data")
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

    /**
     * The hub this client has paired with (set only after the hub accepted
     * a handshake), or null while unpaired.
     */
    val pairedHubFlow: Flow<PairedHub?> = context.dataStore.data.map { preferences ->
        val id = preferences[Keys.PAIRED_HUB_ID] ?: return@map null
        PairedHub(
            hubDeviceId = id,
            host = preferences[Keys.PAIRED_HUB_HOST],
            port = preferences[Keys.PAIRED_HUB_PORT] ?: QrPayload.DEFAULT_PORT,
            serverName = preferences[Keys.PAIRED_HUB_NAME].orEmpty().ifBlank { "Family Hub" },
            serverKey = preferences[Keys.PAIRED_HUB_KEY].orEmpty()
        )
    }

    /** URL (ws:// or wss://) of the internet relay server, blank when unused. */
    val relayUrlFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[Keys.RELAY_URL].orEmpty().trim()
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

    suspend fun setPairedHub(
        hubDeviceId: String,
        host: String?,
        port: Int,
        serverName: String,
        serverKey: String
    ) {
        context.dataStore.edit { preferences ->
            preferences[Keys.PAIRED_HUB_ID] = hubDeviceId
            if (host.isNullOrBlank()) preferences.remove(Keys.PAIRED_HUB_HOST) else preferences[Keys.PAIRED_HUB_HOST] = host
            preferences[Keys.PAIRED_HUB_PORT] = port
            preferences[Keys.PAIRED_HUB_NAME] = serverName
            preferences[Keys.PAIRED_HUB_KEY] = serverKey
        }
    }

    suspend fun clearPairedHub() {
        context.dataStore.edit { preferences ->
            preferences.remove(Keys.PAIRED_HUB_ID)
            preferences.remove(Keys.PAIRED_HUB_HOST)
            preferences.remove(Keys.PAIRED_HUB_PORT)
            preferences.remove(Keys.PAIRED_HUB_NAME)
            preferences.remove(Keys.PAIRED_HUB_KEY)
            preferences.remove(Keys.LAST_SERVER_HOST)
            preferences.remove(Keys.LAST_SERVER_PORT)
            preferences.remove(Keys.LAST_SERVER_NAME)
        }
    }

    suspend fun updateRelayUrl(url: String) {
        val cleaned = url.trim()
        context.dataStore.edit { preferences ->
            if (cleaned.isBlank()) {
                preferences.remove(Keys.RELAY_URL)
            } else {
                preferences[Keys.RELAY_URL] = cleaned
            }
        }
    }

    suspend fun saveProjectionGrant(grant: ProjectionGrant) {
        context.dataStore.edit { preferences ->
            preferences[Keys.PROJECTION_GRANT_CODE] = grant.resultCode
            preferences[Keys.PROJECTION_GRANT_DATA] = grant.resultDataUri
        }
    }

    suspend fun projectionGrant(): ProjectionGrant? {
        val prefs = context.dataStore.data.first()
        val uri = prefs[Keys.PROJECTION_GRANT_DATA] ?: return null
        val code = prefs[Keys.PROJECTION_GRANT_CODE] ?: -1
        if (uri.isBlank() || code <= 0) return null
        return ProjectionGrant(code, uri)
    }

    suspend fun clearProjectionGrant() {
        context.dataStore.edit { preferences ->
            preferences.remove(Keys.PROJECTION_GRANT_CODE)
            preferences.remove(Keys.PROJECTION_GRANT_DATA)
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
            preferences.remove(Keys.PAIRED_HUB_ID)
            preferences.remove(Keys.PAIRED_HUB_HOST)
            preferences.remove(Keys.PAIRED_HUB_PORT)
            preferences.remove(Keys.PAIRED_HUB_NAME)
            preferences.remove(Keys.PAIRED_HUB_KEY)
            preferences.remove(Keys.RELAY_URL)
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
