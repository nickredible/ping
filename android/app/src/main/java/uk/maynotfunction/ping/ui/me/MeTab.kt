package uk.maynotfunction.ping.ui.me

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import uk.maynotfunction.ping.R
import uk.maynotfunction.ping.ui.UiState
import uk.maynotfunction.ping.ui.components.InfoItem
import uk.maynotfunction.ping.ui.components.relativeTime
import uk.maynotfunction.ping.ui.components.rememberNow
import uk.maynotfunction.ping.ui.components.segmentedShapes
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MeTab(
    state: UiState,
    onSharingChange: (Boolean) -> Unit,
    onSyncNow: () -> Unit,
    onRefreshFriends: () -> Unit,
    bottomPadding: Dp,
) {
    val now = rememberNow(5_000)
    val sharing = state.settings.sharingEnabled
    val status = state.status
    val haptics = LocalHapticFeedback.current

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item(key = "title") {
            Text("Me", style = MaterialTheme.typography.headlineMediumEmphasized,
                modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 12.dp))
        }
        item(key = "hero") {
            val container by animateColorAsState(
                if (sharing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                MaterialTheme.motionScheme.defaultEffectsSpec(), label = "heroColor",
            )
            val content = if (sharing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
            Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        val badge = MaterialShapes.Sunny.toShape()
                        Surface(shape = badge, color = content.copy(alpha = 0.12f), modifier = Modifier.size(56.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                AnimatedContent(state.syncing, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "badge") { syncing ->
                                    if (syncing) LoadingIndicator(Modifier.size(40.dp), color = content)
                                    else Icon(painterResource(if (sharing) R.drawable.ic_share_location else R.drawable.ic_location_off),
                                        null, Modifier.size(28.dp))
                                }
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Text("Share My Location", style = MaterialTheme.typography.titleLarge)
                            Text(
                                when {
                                    !sharing -> "Paused. Friends see your last shared location."
                                    status.lastError != null -> "Relay problem: ${status.lastError}"
                                    state.myFix == null -> "Waiting for a GPS fix…"
                                    else -> "Live • ±${state.myFix.accuracyM.roundToInt()} m"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Switch(checked = sharing, onCheckedChange = {
                            haptics.performHapticFeedback(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                            onSharingChange(it)
                        })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val h = ButtonDefaults.MediumContainerHeight
                        Button(onClick = onSyncNow, enabled = !state.syncing, shapes = ButtonDefaults.shapes(),
                            contentPadding = ButtonDefaults.contentPaddingFor(h), modifier = Modifier.weight(1f).heightIn(h)) {
                            Icon(painterResource(R.drawable.ic_sync), null, Modifier.size(ButtonDefaults.iconSizeFor(h)))
                            Spacer(Modifier.size(ButtonDefaults.iconSpacingFor(h)))
                            Text("Sync now", style = ButtonDefaults.textStyleFor(h))
                        }
                        FilledTonalButton(onClick = onRefreshFriends, shapes = ButtonDefaults.shapes(),
                            contentPadding = ButtonDefaults.contentPaddingFor(h), modifier = Modifier.weight(1f).heightIn(h)) {
                            Icon(painterResource(R.drawable.ic_refresh), null, Modifier.size(ButtonDefaults.iconSizeFor(h)))
                            Spacer(Modifier.size(ButtonDefaults.iconSpacingFor(h)))
                            Text("Friends", style = ButtonDefaults.textStyleFor(h))
                        }
                    }
                }
            }
            Spacer(Modifier.size(16.dp))
        }

        val fix = state.myFix
        val rows = listOf(
            Triple("This phone",
                fix?.let { String.format(Locale.getDefault(), "%.5f, %.5f  ±%d m  (%s, %s)", it.lat, it.lon,
                    it.accuracyM.roundToInt(), it.provider, relativeTime(it.timeMs, now)) } ?: "No fix yet",
                R.drawable.ic_satellite_alt),
            Triple("Location mode", state.locationMode, R.drawable.ic_speed),
            Triple("Last upload",
                if (status.lastUploadMs > 0) "${relativeTime(status.lastUploadMs, now)} via ${status.lastTransport} • ${status.uploadCount} this session"
                else "Not yet this session",
                R.drawable.ic_cloud_sync),
            Triple("Relay iPhone",
                when {
                    status.lastDownlinkMs == 0L -> "No reply yet this session"
                    !status.relayActive -> "Bridge paused on the iPhone"
                    !status.meDeviceActive -> "Not set as the My Location device"
                    else -> "Active • heard from ${relativeTime(status.lastDownlinkMs, now)}"
                },
                R.drawable.ic_wifi_tethering),
            Triple("Rejected fixes",
                if (status.rejectedCount == 0) "None" else "${status.rejectedCount} • last: ${status.lastRejectReason}",
                R.drawable.ic_location_off),
            Triple("Relay", state.pairing?.relayUrl ?: "Not paired", R.drawable.ic_dns),
        )
        itemsIndexed(rows, key = { _, r -> r.first }) { i, (title, value, icon) ->
            InfoItem(title, value, segmentedShapes(i, rows.size), icon)
        }
    }
}
