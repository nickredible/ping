package uk.maynotfunction.ping.core

import android.location.Location
import android.os.SystemClock
import kotlin.math.abs

/** Only real, live, reasonably accurate fixes are shared. Mocked or simulated providers are rejected. */
object LocationValidator {
    private const val MAX_FIX_AGE_NANOS = 120_000_000_000L // 120 s
    private const val MAX_ACCURACY_M = 1500f

    sealed interface Result {
        data object Valid : Result
        data class Rejected(val reason: String) : Result
    }

    fun validate(location: Location?): Result {
        if (location == null) return Result.Rejected("No location fix")
        if (location.isMock) return Result.Rejected("Mock or simulated location provider")

        val lat = location.latitude
        val lon = location.longitude
        if (lat.isNaN() || lon.isNaN() || lat !in -90.0..90.0 || lon !in -180.0..180.0 ||
            (abs(lat) < 1e-6 && abs(lon) < 1e-6)
        ) return Result.Rejected("Coordinates out of range")

        if (!location.hasAccuracy() || location.accuracy <= 0f || location.accuracy > MAX_ACCURACY_M) {
            return Result.Rejected("Accuracy ${location.accuracy.toInt()} m is outside the live threshold")
        }

        val ageNanos = abs(SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos)
        if (ageNanos > MAX_FIX_AGE_NANOS) {
            return Result.Rejected("Stale fix (${ageNanos / 1_000_000_000L}s old, max 120s)")
        }
        return Result.Valid
    }

    fun isValid(location: Location?) = validate(location) is Result.Valid
}
