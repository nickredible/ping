package uk.maynotfunction.ping.ui.map

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import uk.maynotfunction.ping.core.MyFix
import uk.maynotfunction.ping.ui.FriendUi
import uk.maynotfunction.ping.ui.components.Avatar
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt

/** Inverts OSM's light tiles into a dark map while roughly keeping hues (same matrix as the old app). */
private val DarkTileFilter = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            -0.58f, -0.62f, 0.28f, 0f, 195f,
            -0.42f, -0.38f, 0.18f, 0f, 195f,
            0.12f, -0.58f, -0.52f, 0f, 205f,
            0f, 0f, 0f, 1f, 0f,
        )
    )
)

private val PinSize = 48.dp
private val PinRing = 3.dp

/**
 * Read-only slippy map: tiles drawn on a Compose canvas, friends and "me" as composables
 * placed over it. There is no way to pick or enter a location here.
 *
 * [visibleBottomPx] is the y coordinate where the bottom sheet starts, so the camera centres
 * on the part of the map that's actually visible.
 */
@Composable
fun LiveMap(
    camera: MapCamera,
    friends: List<FriendUi>,
    myFix: MyFix?,
    selectedFriendId: String?,
    dark: Boolean,
    customTileUrl: String,
    visibleBottomPx: () -> Float,
    onFriendClick: (FriendUi) -> Unit,
    onMapTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loader = remember { TileLoader(context.applicationContext, scope) }
    val density = LocalDensity.current
    // OSM tiles are 256 px; drawing them at 160 dp keeps labels readable on dense screens.
    camera.tileSizePx = with(density) { 160.dp.toPx() }
    val background = MaterialTheme.colorScheme.surfaceContainerHighest
    val meColor = MaterialTheme.colorScheme.primary
    val currentVisibleBottom by rememberUpdatedState(visibleBottomPx)
    val currentOnMapTap by rememberUpdatedState(onMapTap)

    Box(modifier.fillMaxSize()) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(camera) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        camera.stop()
                        val tracker = VelocityTracker()
                        var panTotal = Offset.Zero
                        do {
                            val event = awaitPointerEvent()
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            if (zoom != 1f || pan != Offset.Zero) {
                                val centroid = event.calculateCentroid(useCurrent = false)
                                val center = Offset(size.width / 2f, viewportCenterY(size.height.toFloat(), currentVisibleBottom()))
                                if (zoom != 1f) camera.zoomBy(zoom, centroid - center)
                                camera.panBy(pan)
                                event.changes.fastForEach { if (it.positionChanged()) it.consume() }
                            }
                            panTotal += pan
                            event.changes.firstOrNull()?.let { tracker.addPosition(it.uptimeMillis, panTotal) }
                        } while (event.changes.any { it.pressed })
                        val v = tracker.calculateVelocity()
                        if (hypot(v.x, v.y) > 400f) camera.fling(scope, v)
                    }
                }
                .pointerInput(camera) {
                    detectTapGestures(
                        onDoubleTap = { pos ->
                            val center = Offset(size.width / 2f, viewportCenterY(size.height.toFloat(), currentVisibleBottom()))
                            camera.zoomBy(2f, pos - center)
                        },
                        onTap = { currentOnMapTap() },
                    )
                }
        ) {
            drawRect(background)
            loader.version // redraw when tiles arrive
            val centerY = viewportCenterY(size.height, visibleBottomPx())
            drawTiles(camera, loader, customTileUrl, dark, centerY)
            myFix?.let { drawAccuracy(camera, it, centerY, meColor) }
        }

        // Pins: laid out from the camera state during the layout phase, so pans don't recompose.
        Layout(
            content = {
                friends.fastForEach { friend ->
                    FriendPin(friend, selected = friend.id == selectedFriendId, onClick = { onFriendClick(friend) })
                }
                if (myFix != null) MyLocationDot()
            },
            modifier = Modifier.fillMaxSize(),
        ) { measurables, constraints ->
            val placeables = measurables.map { it.measure(Constraints()) }
            layout(constraints.maxWidth, constraints.maxHeight) {
                val world = camera.world()
                val cx = constraints.maxWidth / 2f
                val cy = viewportCenterY(constraints.maxHeight.toFloat(), visibleBottomPx())
                val camX = Mercator.x(camera.lon, world)
                val camY = Mercator.y(camera.lat, world)
                fun screen(lat: Double, lon: Double): Offset {
                    var dx = Mercator.x(lon, world) - camX
                    if (dx > world / 2) dx -= world else if (dx < -world / 2) dx += world
                    return Offset((cx + dx).toFloat(), (cy + Mercator.y(lat, world) - camY).toFloat())
                }
                val pinRadius = (PinSize + PinRing * 2).roundToPx() / 2
                friends.forEachIndexed { i, f ->
                    val p = placeables[i]
                    val s = screen(f.lat, f.lon)
                    p.place(s.x.roundToInt() - p.width / 2, s.y.roundToInt() - pinRadius,
                        zIndex = if (f.id == selectedFriendId) 2f else 1f)
                }
                if (myFix != null) {
                    val p = placeables.last()
                    val s = screen(myFix.lat, myFix.lon)
                    p.place(s.x.roundToInt() - p.width / 2, s.y.roundToInt() - p.height / 2, zIndex = 0.5f)
                }
            }
        }

    }
}

/** Centre of the visible map area (between the top of the screen and the sheet). */
private fun viewportCenterY(height: Float, visibleBottom: Float): Float =
    visibleBottom.coerceIn(height * 0.35f, height) / 2f

private fun DrawScope.drawTiles(camera: MapCamera, loader: TileLoader, custom: String, dark: Boolean, centerY: Float) {
    val z = floor(camera.zoom).toInt().coerceIn(2, 19)
    val scale = 2.0.pow(camera.zoom - z)
    val tile = camera.tileSizePx.toDouble()
    val world = tile * (1 shl z)
    val camX = Mercator.x(camera.lon, world)
    val camY = Mercator.y(camera.lat, world)
    val cx = size.width / 2.0
    val n = 1 shl z
    val drawSize = tile * scale

    val minTx = floor((camX - cx / scale) / tile).toInt()
    val maxTx = floor((camX + (size.width - cx) / scale) / tile).toInt()
    val minTy = floor((camY - centerY / scale) / tile).toInt().coerceAtLeast(0)
    val maxTy = floor((camY + (size.height - centerY) / scale) / tile).toInt().coerceAtMost(n - 1)
    val dstSize = IntSize(ceil(drawSize).toInt() + 1, ceil(drawSize).toInt() + 1)

    for (ty in minTy..maxTy) for (tx in minTx..maxTx) {
        val x = ((tx % n) + n) % n
        val dst = IntOffset(
            (cx + (tx * tile - camX) * scale).roundToInt(),
            (centerY + (ty * tile - camY) * scale).roundToInt(),
        )
        val (url, needsFilter) = TileLoader.tileUrl(custom, dark, z, x, ty)
        val filter = if (needsFilter) DarkTileFilter else null
        val bmp = loader.get(url)
        if (bmp != null) {
            drawImage(bmp, IntOffset.Zero, IntSize(bmp.width, bmp.height), dst, dstSize,
                filterQuality = FilterQuality.Medium, colorFilter = filter)
            continue
        }
        // While loading, stretch the matching part of an already-loaded lower-zoom tile.
        for (up in 1..4) {
            val pz = z - up
            if (pz < 0) break
            val parent: ImageBitmap = loader.peek(TileLoader.tileUrl(custom, dark, pz, x shr up, ty shr up).first) ?: continue
            val sub = parent.width / (1 shl up)
            val src = IntOffset((x - ((x shr up) shl up)) * sub, (ty - ((ty shr up) shl up)) * sub)
            drawImage(parent, src, IntSize(sub, sub), dst, dstSize, filterQuality = FilterQuality.Low, colorFilter = filter)
            break
        }
    }
}

private fun DrawScope.drawAccuracy(camera: MapCamera, fix: MyFix, centerY: Float, color: Color) {
    val world = camera.world()
    var dx = Mercator.x(fix.lon, world) - Mercator.x(camera.lon, world)
    if (dx > world / 2) dx -= world else if (dx < -world / 2) dx += world
    val center = Offset((size.width / 2 + dx).toFloat(), (centerY + Mercator.y(fix.lat, world) - Mercator.y(camera.lat, world)).toFloat())
    val radius = (fix.accuracyM / Mercator.metersPerPixel(fix.lat, world)).toFloat()
    if (radius < 6f) return
    drawCircle(color.copy(alpha = 0.12f), radius, center)
    drawCircle(color.copy(alpha = 0.35f), radius, center, style = Stroke(width = 1.dp.toPx()))
}

@Composable
internal fun FriendPin(friend: FriendUi, selected: Boolean, onClick: () -> Unit, animateSelection: Boolean = true) {
    // Google Maps renders pins to a static bitmap, where a scale transform would be clipped.
    val scale by animateFloatAsState(
        if (selected && animateSelection) 1.25f else 1f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "pinScale",
    )
    val ring = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        ),
    ) {
        Box(
            Modifier
                .scale(scale)
                .shadow(6.dp, CircleShape)
                .background(ring, CircleShape)
                .padding(PinRing)
        ) {
            Avatar(friend.name, friend.photo, PinSize)
        }
        Surface(
            shape = CircleShape,
            color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 2.dp,
            modifier = Modifier.padding(top = 6.dp),
        ) {
            Text(
                friend.name.substringBefore(' '),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun MyLocationDot() {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val halo by pulse.animateFloat(
        initialValue = 0.6f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "halo",
    )
    val primary = MaterialTheme.colorScheme.primary
    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(44.dp).scale(halo).background(primary.copy(alpha = 0.22f), CircleShape))
        Box(
            Modifier
                .size(18.dp)
                .shadow(3.dp, CircleShape)
                .background(primary, CircleShape)
                .border(3.dp, MaterialTheme.colorScheme.surface, CircleShape)
        )
    }
}
