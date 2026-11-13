package uk.maynotfunction.ping.ui.map

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

/** Web Mercator helpers. `world` is the width of the whole map in pixels at the current zoom. */
internal object Mercator {
    fun x(lon: Double, world: Double) = (lon + 180.0) / 360.0 * world
    fun y(lat: Double, world: Double): Double {
        val r = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * world
    }
    fun lon(x: Double, world: Double): Double = normalizeLon(x / world * 360.0 - 180.0)
    fun normalizeLon(l: Double): Double = ((l % 360.0) + 540.0) % 360.0 - 180.0
    fun lat(y: Double, world: Double): Double = Math.toDegrees(atan(sinh(PI - 2.0 * PI * y / world)))
    fun metersPerPixel(lat: Double, world: Double) = cos(Math.toRadians(lat)) * 2 * PI * 6_378_137.0 / world
}

@Stable
class MapCamera(lat: Double, lon: Double, zoom: Double) {
    var lat by mutableDoubleStateOf(lat)
    var lon by mutableDoubleStateOf(lon)
    var zoom by mutableDoubleStateOf(zoom)

    /** Pixel size of one tile on screen at an integer zoom; set by [LiveMap]. */
    internal var tileSizePx = 512f
    private var animation: Job? = null

    /**
     * Set by an external map engine (Google Maps) while it's showing: camera moves are handed to
     * it, and it writes its position back into [lat]/[lon]/[zoom].
     */
    internal var externalAnimator: ((lat: Double, lon: Double, zoom: Double) -> Unit)? = null

    internal fun world(z: Double = zoom) = tileSizePx * 2.0.pow(z)

    fun stop() {
        animation?.cancel()
        animation = null
    }

    /** Moves the map by a screen-space delta (content follows the finger). */
    internal fun panBy(delta: Offset) {
        val world = world()
        val y = (Mercator.y(lat, world) - delta.y).coerceIn(0.0, world)
        lon = Mercator.lon(Mercator.x(lon, world) - delta.x, world)
        lat = Mercator.lat(y, world)
    }

    /** Zooms by [factor] keeping the point at [focus] (relative to the viewport centre) fixed. */
    internal fun zoomBy(factor: Float, focus: Offset) {
        val newZoom = (zoom + ln(factor.toDouble()) / ln(2.0)).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val k = 2.0.pow(newZoom - zoom)
        val world = world()
        val cx = Mercator.x(lon, world)
        val cy = Mercator.y(lat, world)
        val newWorld = world * k
        val nx = (cx + focus.x) * k - focus.x
        val ny = ((cy + focus.y) * k - focus.y).coerceIn(0.0, newWorld)
        zoom = newZoom
        lon = Mercator.lon(nx, newWorld)
        lat = Mercator.lat(ny, newWorld)
    }

    internal fun fling(scope: CoroutineScope, velocity: Velocity) {
        stop()
        animation = scope.launch {
            var last = Offset.Zero
            AnimationState(Offset.VectorConverter, Offset.Zero, Offset(velocity.x, velocity.y))
                .animateDecay(exponentialDecay(frictionMultiplier = 1.6f)) {
                    panBy(value - last)
                    last = value
                }
        }
    }

    fun animateTo(scope: CoroutineScope, targetLat: Double, targetLon: Double, targetZoom: Double, spec: AnimationSpec<Float>) {
        stop()
        externalAnimator?.let { it(targetLat, targetLon, targetZoom); return }
        val sLat = lat
        val sLon = lon
        val sZoom = zoom
        // Take the short way round the antimeridian.
        val dLon = ((targetLon - sLon + 540.0) % 360.0) - 180.0
        animation = scope.launch {
            Animatable(0f).animateTo(1f, spec) {
                val t = value.toDouble()
                lat = sLat + (targetLat - sLat) * t
                lon = Mercator.normalizeLon(sLon + dLon * t)
                zoom = sZoom + (targetZoom - sZoom) * t
            }
        }
    }

    companion object {
        const val MIN_ZOOM = 3.0
        const val MAX_ZOOM = 19.0
    }
}

@Composable
fun rememberMapCamera(): MapCamera = rememberSaveable(
    saver = listSaver(save = { listOf(it.lat, it.lon, it.zoom) }, restore = { MapCamera(it[0], it[1], it[2]) }),
) { MapCamera(51.5, -0.12, 3.5) }
