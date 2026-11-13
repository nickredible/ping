package uk.maynotfunction.ping.ui.map

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import uk.maynotfunction.ping.core.MyFix
import uk.maynotfunction.ping.ui.FriendUi
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.LocationSource
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.ComposeMapColorScheme
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.MarkerComposable
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import kotlinx.coroutines.launch
import kotlin.math.ln

/**
 * Google's zoom levels assume 256 dp tiles; [LiveMap] draws tiles at 160 dp, so the same
 * on-screen scale is `googleZoom = mapCameraZoom + log2(160 / 256)`.
 */
private val ZOOM_OFFSET = ln(160.0 / 256.0) / ln(2.0)

/** Where the coordinate sits on a [FriendPin] bitmap: the avatar's centre, above the name label. */
private const val PIN_ANCHOR_Y = 0.34f

/**
 * Google Maps version of [LiveMap], used when Play services and an API key are available.
 *
 * It shares [camera] with the OpenStreetMap map: while showing, camera moves are handed to
 * Google's own animation and Google's position is written back, so switching keeps your place.
 * The blue dot is fed from the app's own fixes, so the map never starts a location request.
 */
@Composable
fun GoogleLiveMap(
    camera: MapCamera,
    friends: List<FriendUi>,
    myFix: MyFix?,
    selectedFriendId: String?,
    dark: Boolean,
    topPadding: Dp,
    bottomPadding: Dp,
    onFriendClick: (FriendUi) -> Unit,
    onMapTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cameraState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(camera.lat, camera.lon), (camera.zoom + ZOOM_OFFSET).toFloat())
    }

    DisposableEffect(camera, cameraState) {
        camera.externalAnimator = { lat, lon, zoom ->
            scope.launch {
                runCatching {
                    cameraState.animate(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), (zoom + ZOOM_OFFSET).toFloat()), 700)
                }
            }
        }
        onDispose { camera.externalAnimator = null }
    }
    LaunchedEffect(cameraState) {
        snapshotFlow { cameraState.position }.collect { p ->
            camera.lat = p.target.latitude
            camera.lon = p.target.longitude
            camera.zoom = p.zoom - ZOOM_OFFSET
        }
    }

    val locationSource = remember { FixLocationSource() }
    LaunchedEffect(myFix) { myFix?.let(locationSource::push) }
    val hasLocationPermission =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    GoogleMap(
        modifier = modifier.fillMaxSize(),
        cameraPositionState = cameraState,
        properties = MapProperties(isMyLocationEnabled = hasLocationPermission, minZoomPreference = 3f),
        locationSource = locationSource,
        uiSettings = MapUiSettings(
            compassEnabled = false,
            indoorLevelPickerEnabled = false,
            mapToolbarEnabled = false,
            myLocationButtonEnabled = false,
            rotationGesturesEnabled = false,
            tiltGesturesEnabled = false,
            zoomControlsEnabled = false,
        ),
        onMapClick = { onMapTap() },
        // Keeps the camera centred on (and the Google logo inside) the part above the sheet.
        contentPadding = PaddingValues(top = topPadding, bottom = bottomPadding),
        mapColorScheme = if (dark) ComposeMapColorScheme.DARK else ComposeMapColorScheme.LIGHT,
    ) {
        friends.forEach { f ->
            key(f.id) {
                val selected = f.id == selectedFriendId
                MarkerComposable(
                    f.id, selected, f.name, f.photo ?: Unit,
                    state = rememberUpdatedMarkerState(LatLng(f.lat, f.lon)),
                    anchor = Offset(0.5f, PIN_ANCHOR_Y),
                    zIndex = if (selected) 2f else 1f,
                    onClick = { onFriendClick(f); true },
                ) {
                    FriendPin(f, selected, onClick = {}, animateSelection = false)
                }
            }
        }
    }
}

/** Feeds the app's own verified fixes to Google's blue dot instead of a second location request. */
private class FixLocationSource : LocationSource {
    private var listener: LocationSource.OnLocationChangedListener? = null
    private var last: Location? = null

    override fun activate(l: LocationSource.OnLocationChangedListener) {
        listener = l
        last?.let(l::onLocationChanged)
    }

    override fun deactivate() {
        listener = null
    }

    fun push(fix: MyFix) {
        val loc = Location("bridge").apply {
            latitude = fix.lat
            longitude = fix.lon
            accuracy = fix.accuracyM
            time = fix.timeMs
        }
        last = loc
        listener?.onLocationChanged(loc)
    }
}
