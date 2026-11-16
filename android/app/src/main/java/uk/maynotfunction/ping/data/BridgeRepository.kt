package uk.maynotfunction.ping.data

import android.content.Context
import android.location.Location
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import uk.maynotfunction.ping.core.AvailableUpdate
import uk.maynotfunction.ping.core.Cadence
import uk.maynotfunction.ping.core.FriendLocation
import uk.maynotfunction.ping.core.LatestRelease
import uk.maynotfunction.ping.core.LogLine
import uk.maynotfunction.ping.core.MyFix
import uk.maynotfunction.ping.core.PairingCode
import uk.maynotfunction.ping.core.PairingConfig
import uk.maynotfunction.ping.core.RelayWatchdog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "bridge_settings")

enum class MapStyle { Auto, Light, Dark }

/** Auto = Google Maps when Play services and an API key are available, otherwise OpenStreetMap. */
enum class MapProvider(val label: String) { Auto("Auto"), Google("Google"), OpenStreetMap("OSM") }

data class Settings(
    val loaded: Boolean = false,
    val pairingCode: String = "",
    val sharingEnabled: Boolean = true,
    val contactsMatching: Boolean = true,
    val previewContacts: Boolean = false,
    val cadence: Cadence = Cadence.Balanced,
    val customTileUrl: String = "",
    val mapStyle: MapStyle = MapStyle.Auto,
    val mapProvider: MapProvider = MapProvider.Auto,
    val cachedFriendsJson: String = "",
    /** False until the first-run setup is finished (or skipped). */
    val onboardingDone: Boolean = false,
    /** Ping Relay's version as the iPhone last reported it; empty until a relay that reports it answers. */
    val relayVersion: String = "",
    /** The newest GitHub release seen by the daily update check; null before the first check. */
    val latestRelease: LatestRelease? = null,
) {
    val pairing: PairingConfig? get() = PairingCode.parse(pairingCode)
}

data class RelayStatus(
    val lastUploadMs: Long = 0L,
    val uploadCount: Int = 0,
    val rejectedCount: Int = 0,
    val lastTransport: String? = null,
    val lastError: String? = null,
    val lastRejectReason: String? = null,
    val relayActive: Boolean = false,
    val meDeviceActive: Boolean = false,
    val lastDownlinkMs: Long = 0L,
    /** When the oldest friends request the iPhone hasn't answered was sent; 0 if none. */
    val unansweredSinceMs: Long = 0L,
)

/** Single source of truth for settings, this phone's fix, friends, relay status and the debug log. */
class BridgeRepository(context: Context, private val scope: CoroutineScope) {
    private val store = context.applicationContext.dataStore

    private object Keys {
        val pairing = stringPreferencesKey("pairing_code")
        val sharing = booleanPreferencesKey("sharing_enabled")
        val contacts = booleanPreferencesKey("contacts_sync_enabled")
        val preview = booleanPreferencesKey("preview_contacts_when_empty")
        val cadence = intPreferencesKey("cadence_mode")
        val tiles = stringPreferencesKey("custom_tile_url")
        val mapStyle = intPreferencesKey("map_style")
        val mapProvider = intPreferencesKey("map_provider")
        val friends = stringPreferencesKey("cached_friends_json")
        val onboarded = booleanPreferencesKey("onboarding_done")
        val mapStyleReset = booleanPreferencesKey("map_style_reset_2_5_2")
        val relayVersion = stringPreferencesKey("relay_version")
        val latestApp = stringPreferencesKey("latest_release_app")
        val latestRelay = stringPreferencesKey("latest_release_relay")
        val latestUrl = stringPreferencesKey("latest_release_url")
        val notifiedApp = stringPreferencesKey("update_notified_app")
        val notifiedRelay = stringPreferencesKey("update_notified_relay")
    }

    val settings: StateFlow<Settings> = store.data
        .map { p ->
            Settings(
                loaded = true,
                pairingCode = p[Keys.pairing].orEmpty(),
                sharingEnabled = p[Keys.sharing] ?: true,
                contactsMatching = p[Keys.contacts] ?: true,
                previewContacts = p[Keys.preview] ?: false,
                cadence = Cadence.fromOrdinal(p[Keys.cadence] ?: Cadence.Balanced.ordinal),
                customTileUrl = p[Keys.tiles].orEmpty(),
                mapStyle = MapStyle.entries.getOrElse(p[Keys.mapStyle] ?: 0) { MapStyle.Auto },
                mapProvider = MapProvider.entries.getOrElse(p[Keys.mapProvider] ?: 0) { MapProvider.Auto },
                cachedFriendsJson = p[Keys.friends].orEmpty(),
                // Installs from before setup existed count as set up if they've received friends
                // (made permanent in init, before any new friends can arrive).
                onboardingDone = p[Keys.onboarded] ?: !p[Keys.friends].isNullOrEmpty(),
                relayVersion = p[Keys.relayVersion].orEmpty(),
                latestRelease = p[Keys.latestApp]?.let { LatestRelease(it, p[Keys.latestRelay], p[Keys.latestUrl].orEmpty()) },
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, Settings())

    val friends: StateFlow<List<FriendLocation>> = settings
        .map { it.cachedFriendsJson }
        .distinctUntilChanged()
        .map { FriendLocation.listFromJson(it) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _myFix = MutableStateFlow<MyFix?>(null)
    val myFix: StateFlow<MyFix?> = _myFix.asStateFlow()

    private val _status = MutableStateFlow(RelayStatus())
    val status: StateFlow<RelayStatus> = _status.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    /** True while the app's UI is on screen; the service uses precise, fast updates then. */
    private val _uiVisible = MutableStateFlow(false)
    val uiVisible: StateFlow<Boolean> = _uiVisible.asStateFlow()
    fun setUiVisible(visible: Boolean) { _uiVisible.value = visible }

    /** Human-readable description of the service's current location mode (shown in the Me tab). */
    private val _locationMode = MutableStateFlow("Off")
    val locationMode: StateFlow<String> = _locationMode.asStateFlow()
    fun setLocationMode(mode: String) { _locationMode.value = mode }

    private val _logs = MutableStateFlow<List<LogLine>>(emptyList())
    val logs: StateFlow<List<LogLine>> = _logs.asStateFlow()

    init {
        scope.launch {
            store.edit { p ->
                if (PairingCode.parse(p[Keys.pairing]) == null) {
                    p[Keys.pairing] = PairingCode.generate(null, null, null)
                }
                // Decide once whether setup is needed. Without this, friends arriving during
                // setup (the connection check fetches them) would end it early.
                if (p[Keys.onboarded] == null) p[Keys.onboarded] = !p[Keys.friends].isNullOrEmpty()
                // Before 2.5.2 a button on the map pinned Light/Dark for good; go back to Auto once.
                if (p[Keys.mapStyleReset] == null) {
                    p[Keys.mapStyle] = MapStyle.Auto.ordinal
                    p[Keys.mapStyleReset] = true
                }
            }
            log("Bridge ready (AES-256-GCM)")
        }
    }

    suspend fun awaitSettings(): Settings = settings.first { it.loaded }

    // ---- Log ---------------------------------------------------------------------------------

    fun log(message: String) {
        // Never log coordinates or the pairing code; logcat is readable over adb.
        Log.i("Ping", message)
        _logs.update { (listOf(LogLine(System.currentTimeMillis(), message)) + it).take(80) }
    }

    fun clearLog() = _logs.update { emptyList() }

    // ---- Location & relay status ---------------------------------------------------------------

    fun recordFix(location: Location) {
        _myFix.value = MyFix(
            lat = location.latitude,
            lon = location.longitude,
            accuracyM = location.accuracy,
            provider = location.provider ?: "gps",
            timeMs = if (location.time > 0) location.time else System.currentTimeMillis(),
        )
    }

    fun setSyncing(value: Boolean) { _syncing.value = value }

    fun recordUploadSuccess(transport: String) {
        _status.update {
            it.copy(lastUploadMs = System.currentTimeMillis(), uploadCount = it.uploadCount + 1,
                lastTransport = transport, lastError = null)
        }
        log("Uploaded live fix via $transport")
    }

    fun recordUploadFailure(error: String) {
        _status.update { it.copy(lastError = error) }
        log("Sync error: $error")
    }

    fun recordRejected(reason: String) {
        _status.update { it.copy(rejectedCount = it.rejectedCount + 1, lastRejectReason = reason) }
        log("Rejected fix: $reason")
    }

    /** A request for friends sent at [sentAtMs] reached the relay (or, LAN/VPN only, should have). */
    fun recordFriendsRequest(sentAtMs: Long) {
        _status.update {
            it.copy(unansweredSinceMs = RelayWatchdog.afterRequest(it.unansweredSinceMs, it.lastDownlinkMs, sentAtMs))
        }
    }

    /** Starts the quiet-iPhone clock afresh, e.g. when sharing starts after a pause. */
    fun clearUnansweredRequests() = _status.update { it.copy(unansweredSinceMs = 0L) }

    suspend fun applyDownlink(payload: JSONObject) {
        _status.update {
            it.copy(relayActive = payload.optBoolean("relayActive", true),
                meDeviceActive = payload.optBoolean("meDeviceActive", true),
                lastDownlinkMs = System.currentTimeMillis(),
                unansweredSinceMs = 0L)
        }
        payload.optString("relayVersion").takeIf { it.isNotBlank() && it != settings.value.relayVersion }?.let { v ->
            store.edit { it[Keys.relayVersion] = v }
            log("Relay iPhone runs Ping Relay $v")
        }
        val arr = payload.optJSONArray("friends") ?: return
        val list = (0 until arr.length()).mapNotNull { FriendLocation.fromJson(arr.optJSONObject(it)) }
        store.edit { it[Keys.friends] = FriendLocation.listToJson(list) }
        log("Synced ${list.size} friend(s) from relay iPhone")
    }

    // ---- Settings ------------------------------------------------------------------------------

    suspend fun setSharingEnabled(enabled: Boolean) {
        store.edit { it[Keys.sharing] = enabled }
        log(if (enabled) "Location sharing on" else "Location sharing paused")
    }

    suspend fun setContactsMatching(enabled: Boolean) = store.edit { it[Keys.contacts] = enabled }
    suspend fun setPreviewContacts(enabled: Boolean) = store.edit { it[Keys.preview] = enabled }
    suspend fun setCadence(cadence: Cadence) {
        store.edit { it[Keys.cadence] = cadence.ordinal }
        log("Update frequency: ${cadence.label}")
    }
    suspend fun setCustomTileUrl(value: String) = store.edit { it[Keys.tiles] = value.trim() }
    suspend fun setMapStyle(style: MapStyle) = store.edit { it[Keys.mapStyle] = style.ordinal }
    suspend fun setMapProvider(provider: MapProvider) = store.edit { it[Keys.mapProvider] = provider.ordinal }
    suspend fun clearCachedFriends() = store.edit { it.remove(Keys.friends) }

    suspend fun setLatestRelease(release: LatestRelease) = store.edit {
        it[Keys.latestApp] = release.appVersion
        if (release.relayVersion != null) it[Keys.latestRelay] = release.relayVersion else it.remove(Keys.latestRelay)
        it[Keys.latestUrl] = release.url
    }

    /**
     * The parts of [update] not already notified about, recorded as notified. Each version is
     * announced once, so updating one component doesn't re-announce the other.
     */
    suspend fun claimUnnotified(update: AvailableUpdate): AvailableUpdate {
        var fresh = AvailableUpdate(null, null)
        store.edit { p ->
            fresh = AvailableUpdate(
                appVersion = update.appVersion?.takeIf { it != p[Keys.notifiedApp] },
                relayVersion = update.relayVersion?.takeIf { it != p[Keys.notifiedRelay] },
            )
            fresh.appVersion?.let { p[Keys.notifiedApp] = it }
            fresh.relayVersion?.let { p[Keys.notifiedRelay] = it }
        }
        return fresh
    }

    suspend fun setOnboardingDone(done: Boolean) = store.edit { it[Keys.onboarded] = done }

    /** Saves [code] if it parses; returns false otherwise. */
    suspend fun setPairingCode(code: String): Boolean {
        val cfg = PairingCode.parse(code) ?: return false
        store.edit {
            it[Keys.pairing] = cfg.raw
            it.remove(Keys.relayVersion) // may be a different iPhone; it reports again on its next reply
        }
        log("Pairing code updated (channel ${cfg.channelId.take(8)}…)")
        return true
    }

    suspend fun rotateKey() {
        val cur = awaitSettings().pairing
        setPairingCode(PairingCode.generate(cur?.relayUrl, cur?.lanEndpoint, cur?.relayToken))
    }

    /** An http(s) URL, or [PairingCode.NO_RELAY] for LAN/VPN only (which needs a LAN address). */
    suspend fun setRelayUrl(url: String): Boolean {
        val cleaned = url.trim().trimEnd('/')
        val cur = awaitSettings().pairing ?: return false
        if (cleaned.equals(PairingCode.NO_RELAY, ignoreCase = true)) {
            if (cur.lanEndpoint == null) return false
            return setPairingCode(cur.withRelayUrl(PairingCode.NO_RELAY))
        }
        if (!cleaned.startsWith("http")) return false
        return setPairingCode(cur.withRelayUrl(cleaned))
    }

    suspend fun setLanEndpoint(lan: String): Boolean {
        val v = lan.trim()
        if (v.isNotEmpty() && !PairingCode.isSafeLan(v)) return false
        val cur = awaitSettings().pairing ?: return false
        if (v.isEmpty() && !cur.relayEnabled) return false // LAN/VPN-only codes need the address
        return setPairingCode(cur.withLanEndpoint(v))
    }

    suspend fun setRelayToken(token: String): Boolean {
        val v = token.trim()
        if (v.isNotEmpty() && !PairingCode.isSafeToken(v)) return false
        val cur = awaitSettings().pairing ?: return false
        return setPairingCode(cur.withRelayToken(v))
    }
}
