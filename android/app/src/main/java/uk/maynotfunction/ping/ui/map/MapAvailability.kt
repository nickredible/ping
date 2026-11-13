package uk.maynotfunction.ping.ui.map

import android.content.Context
import uk.maynotfunction.ping.BuildConfig
import uk.maynotfunction.ping.data.MapProvider
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

/** Why Google Maps can or can't be used on this device/build. */
enum class GoogleMapsStatus(val description: String) {
    Available("Available"),
    NoApiKey("No API key in this build"),
    NoPlayServices("Google Play services unavailable"),
}

fun googleMapsStatus(context: Context): GoogleMapsStatus = when {
    !BuildConfig.HAS_MAPS_KEY -> GoogleMapsStatus.NoApiKey
    runCatching {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }.getOrDefault(false) -> GoogleMapsStatus.Available
    else -> GoogleMapsStatus.NoPlayServices
}

/** Resolves the setting to what's actually shown: Google only if it can work. */
fun useGoogleMaps(setting: MapProvider, status: GoogleMapsStatus): Boolean =
    status == GoogleMapsStatus.Available && setting != MapProvider.OpenStreetMap
