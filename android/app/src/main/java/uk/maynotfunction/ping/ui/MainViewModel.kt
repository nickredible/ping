package uk.maynotfunction.ping.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import uk.maynotfunction.ping.bridgeApp
import uk.maynotfunction.ping.core.Cadence
import uk.maynotfunction.ping.core.FriendLocation
import uk.maynotfunction.ping.core.LogLine
import uk.maynotfunction.ping.core.MyFix
import uk.maynotfunction.ping.data.MapProvider
import uk.maynotfunction.ping.data.MapStyle
import uk.maynotfunction.ping.data.RelayClient
import uk.maynotfunction.ping.data.RelayStatus
import uk.maynotfunction.ping.data.Settings
import uk.maynotfunction.ping.service.LocationService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@Immutable
data class FriendUi(
    val id: String,
    val handle: String,
    val name: String,
    val photo: Bitmap?,
    val contactUri: Uri?,
    val lat: Double,
    val lon: Double,
    val accuracyM: Double,
    val timestampMs: Long,
    val address: String,
    val isPreview: Boolean,
)

/** Result of the setup screen's connection check. */
class SetupCheck(val connection: RelayClient.ConnectionCheck, val iphoneAnswered: Boolean)

@Immutable
data class UiState(
    val settings: Settings = Settings(),
    val myFix: MyFix? = null,
    val status: RelayStatus = RelayStatus(),
    val syncing: Boolean = false,
    val friends: List<FriendUi> = emptyList(),
    val logs: List<LogLine> = emptyList(),
    val locationMode: String = "Off",
) {
    val pairing get() = settings.pairing
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = app.bridgeApp.repository
    private val relay = app.bridgeApp.relay
    private val contacts = app.bridgeApp.contacts

    /** Bumped when contacts permission changes so names/photos are re-resolved. */
    private val contactsEpoch = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val friendsUi: StateFlow<List<FriendUi>> = combine(
        repo.friends,
        repo.settings.map { it.contactsMatching to it.previewContacts }.distinctUntilChanged(),
        repo.myFix.map { it?.let { f -> f.lat to f.lon } }.distinctUntilChanged { a, b -> (a == null) == (b == null) },
        contactsEpoch,
    ) { friends, (matching, preview), myPos, _ -> Triple(friends, matching, preview to myPos) }
        .mapLatest { (friends, matching, previewAndPos) ->
            val (preview, myPos) = previewAndPos
            val source = when {
                friends.isNotEmpty() -> friends
                preview && myPos != null -> contacts.previewFriends(myPos.first, myPos.second)
                else -> emptyList()
            }
            source.map { it.toUi(matching) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val uiState: StateFlow<UiState> = combine(
        repo.settings, repo.myFix, repo.status, repo.syncing, friendsUi,
    ) { settings, fix, status, syncing, friends ->
        UiState(settings, fix, status, syncing, friends)
    }.combine(repo.logs) { state, logs -> state.copy(logs = logs) }
        .combine(repo.locationMode) { state, mode -> state.copy(locationMode = mode) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    private suspend fun FriendLocation.toUi(matchContacts: Boolean): FriendUi {
        val profile = if (matchContacts) contacts.resolve(handle, name) else null
        return FriendUi(
            id = id, handle = handle, name = profile?.name ?: name,
            photo = profile?.photo, contactUri = profile?.contactUri,
            lat = lat, lon = lon, accuracyM = hAcc, timestampMs = timestampMs,
            address = address, isPreview = isPreview,
        )
    }

    // ---- Lifecycle -------------------------------------------------------------------------------

    private var foregroundPoll: Job? = null

    /**
     * Called when the UI resumes. With sharing on, the service does the work; with sharing
     * paused, stream friend updates here while the app is visible.
     */
    fun onResumed(hasLocationPermission: Boolean) {
        repo.setUiVisible(true)
        viewModelScope.launch {
            val settings = repo.awaitSettings()
            if (settings.sharingEnabled && hasLocationPermission) {
                LocationService.send(getApplication(), LocationService.ACTION_START)
            } else {
                foregroundPoll?.cancel()
                foregroundPoll = viewModelScope.launch {
                    relay.sendRefreshRequest()
                    relay.streamDownlink { } // until onPaused() cancels it
                }
            }
        }
    }

    /** Called when the UI leaves the screen: the service drops back to low-power location. */
    fun onPaused() {
        repo.setUiVisible(false)
        stopForegroundStream()
    }

    private fun stopForegroundStream() {
        foregroundPoll?.cancel()
        foregroundPoll = null
    }

    fun onContactsPermissionChanged() {
        contacts.clearCache()
        contactsEpoch.value++
    }

    // ---- Actions ---------------------------------------------------------------------------------

    fun setSharing(enabled: Boolean) = viewModelScope.launch {
        repo.setSharingEnabled(enabled)
        if (enabled) {
            stopForegroundStream()
            LocationService.send(getApplication(), LocationService.ACTION_START)
        } else {
            LocationService.send(getApplication(), LocationService.ACTION_STOP)
            onResumed(hasLocationPermission = false)
        }
    }

    fun syncNow() = viewModelScope.launch {
        if (!repo.awaitSettings().sharingEnabled) repo.setSharingEnabled(true)
        LocationService.send(getApplication(), LocationService.ACTION_PUBLISH_NOW)
    }

    fun refreshFriends() {
        if (repo.settings.value.sharingEnabled) {
            LocationService.send(getApplication(), LocationService.ACTION_REFRESH_FRIENDS)
        } else viewModelScope.launch {
            relay.sendRefreshRequest() // the reply arrives on the foreground stream
        }
    }

    fun setCadence(c: Cadence) = viewModelScope.launch { repo.setCadence(c) }
    fun setContactsMatching(on: Boolean) = viewModelScope.launch {
        repo.setContactsMatching(on)
        onContactsPermissionChanged()
    }
    fun setPreviewContacts(on: Boolean) = viewModelScope.launch { repo.setPreviewContacts(on) }
    fun setMapStyle(style: MapStyle) = viewModelScope.launch { repo.setMapStyle(style) }
    fun setMapProvider(provider: MapProvider) = viewModelScope.launch { repo.setMapProvider(provider) }
    fun setTileUrl(v: String) = viewModelScope.launch { repo.setCustomTileUrl(v) }
    fun rotateKey() = viewModelScope.launch { repo.rotateKey() }
    fun clearLog() = repo.clearLog()
    fun clearCachedFriends() = viewModelScope.launch { repo.clearCachedFriends() }

    // ---- First-run setup --------------------------------------------------------------------------

    fun finishOnboarding() = viewModelScope.launch {
        repo.setOnboardingDone(true)
        repo.log("Setup finished")
    }

    fun restartOnboarding() = viewModelScope.launch { repo.setOnboardingDone(false) }

    /**
     * Checks the relay and LAN link, then listens on the downlink for up to [listenMs] for the
     * iPhone's answer. Anything that decrypts there proves the iPhone has the same code.
     */
    suspend fun runSetupCheck(listenMs: Long = 15_000): SetupCheck = coroutineScope {
        val before = repo.status.value.lastDownlinkMs
        val listener = launch { relay.streamDownlink { } }
        try {
            val connection = relay.checkConnection()
            val answered = connection.lan == RelayClient.CheckResult.Ok ||
                (connection.relay == RelayClient.CheckResult.Ok &&
                    withTimeoutOrNull(listenMs) { repo.status.first { it.lastDownlinkMs > before } } != null)
            SetupCheck(connection, answered)
        } finally {
            listener.cancel()
        }
    }

    fun setPairingCode(code: String, onResult: (Boolean) -> Unit) =
        viewModelScope.launch { onResult(repo.setPairingCode(code)) }
    fun setRelayUrl(url: String, onResult: (Boolean) -> Unit) =
        viewModelScope.launch { onResult(repo.setRelayUrl(url)) }
    fun setRelayToken(token: String, onResult: (Boolean) -> Unit) =
        viewModelScope.launch { onResult(repo.setRelayToken(token)) }
    fun setLanEndpoint(lan: String, onResult: (Boolean) -> Unit) =
        viewModelScope.launch { onResult(repo.setLanEndpoint(lan)) }
}
