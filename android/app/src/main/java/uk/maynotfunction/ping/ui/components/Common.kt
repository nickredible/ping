package uk.maynotfunction.ping.ui.components

import android.graphics.Bitmap
import android.location.Location
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Contact photo, or a tonal monogram when there isn't one. */
@Composable
fun Avatar(
    name: String,
    photo: Bitmap?,
    size: Dp,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
) {
    val image = remember(photo) { photo?.asImageBitmap() }
    if (image != null) {
        Image(image, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(shape))
        return
    }
    val scheme = MaterialTheme.colorScheme
    val (bg, fg) = when (abs(name.hashCode()) % 3) {
        0 -> scheme.primaryContainer to scheme.onPrimaryContainer
        1 -> scheme.secondaryContainer to scheme.onSecondaryContainer
        else -> scheme.tertiaryContainer to scheme.onTertiaryContainer
    }
    val initials = name.split(' ', '-').filter { it.isNotBlank() }.take(2)
        .joinToString("") { it.first().uppercase() }.ifEmpty { "?" }
    Box(modifier.size(size).clip(shape).background(bg), contentAlignment = Alignment.Center) {
        Text(initials, color = fg, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.38f).sp)
    }
}

/** Current time, refreshed every [periodMs] so relative labels stay current. */
@Composable
fun rememberNow(periodMs: Long = 15_000L): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(periodMs)
            value = System.currentTimeMillis()
        }
    }
    return now
}

fun relativeTime(timeMs: Long, now: Long): String {
    if (timeMs <= 0) return "Never"
    val d = now - timeMs
    return when {
        d < 60_000 -> "Now"
        d < 3_600_000 -> "${d / 60_000} min ago"
        d < 86_400_000 -> "${d / 3_600_000} hr ago"
        else -> DateUtils.getRelativeTimeSpanString(timeMs, now, DateUtils.DAY_IN_MILLIS).toString()
    }
}

fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
    val out = FloatArray(1)
    Location.distanceBetween(lat1, lon1, lat2, lon2, out)
    return out[0]
}

fun distanceLabel(meters: Float): String =
    if (meters < 1000f) "${meters.roundToInt()} m" else String.format(Locale.getDefault(), "%.1f km", meters / 1000f)
