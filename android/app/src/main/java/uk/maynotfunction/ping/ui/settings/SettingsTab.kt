package uk.maynotfunction.ping.ui.settings

import android.content.ClipData
import android.content.ClipDescription
import android.os.PersistableBundle
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import uk.maynotfunction.ping.R
import uk.maynotfunction.ping.core.Cadence
import uk.maynotfunction.ping.core.LogLine
import uk.maynotfunction.ping.core.PairingConfig
import uk.maynotfunction.ping.data.MapProvider
import uk.maynotfunction.ping.data.MapStyle
import uk.maynotfunction.ping.ui.map.GoogleMapsStatus
import uk.maynotfunction.ping.ui.map.googleMapsStatus
import uk.maynotfunction.ping.ui.map.useGoogleMaps
import uk.maynotfunction.ping.ui.MainViewModel
import uk.maynotfunction.ping.ui.UiState
import uk.maynotfunction.ping.ui.components.ClickItem
import uk.maynotfunction.ping.ui.components.ConnectedChoice
import uk.maynotfunction.ping.ui.components.ItemIcon
import uk.maynotfunction.ping.ui.components.SwitchItem
import uk.maynotfunction.ping.ui.components.TextFieldDialog
import uk.maynotfunction.ping.ui.components.itemContainerColor
import uk.maynotfunction.ping.ui.components.sectionHeader
import uk.maynotfunction.ping.ui.components.segmentedShapes
import uk.maynotfunction.ping.ui.theme.GoogleSansCode
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private enum class EditDialog { PairingCode, RelayUrl, Token, Lan, Tiles, NewKey }

@Composable
fun SettingsTab(
    state: UiState,
    vm: MainViewModel,
    hasContactsPermission: Boolean,
    requestContactsPermission: () -> Unit,
    showMessage: (String) -> Unit,
    bottomPadding: Dp,
) {
    val settings = state.settings
    val pairing = state.pairing
    var dialog by rememberSaveable { mutableStateOf<EditDialog?>(null) }
    var revealKey by rememberSaveable { mutableStateOf(false) }
    var showDebug by rememberSaveable { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val mapsStatus = remember { googleMapsStatus(context) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item(key = "title") {
            Text("Settings", style = MaterialTheme.typography.headlineMediumEmphasized,
                modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp))
        }

        // Turning sharing on/off lives in the Me tab.
        sectionHeader("Sharing")
        item(key = "cadence") {
            ChoiceCard(
                title = "Update frequency",
                supporting = when (settings.cadence) {
                    Cadence.Fast -> "Every 15 m of movement, at least every 2 min"
                    Cadence.Balanced -> "Every 40 m of movement, at least every 5 min"
                    Cadence.Saver -> "Every 100 m of movement, at least every 10 min"
                },
                icon = R.drawable.ic_speed,
                index = 0, count = 2,
            ) {
                ConnectedChoice(Cadence.entries, settings.cadence, { it.label }, vm::setCadence)
            }
        }
        item(key = "contacts") {
            SwitchItem("Use contact names & photos", settings.contactsMatching && hasContactsPermission,
                { on ->
                    if (on && !hasContactsPermission) requestContactsPermission()
                    vm.setContactsMatching(on)
                },
                segmentedShapes(1, 2), R.drawable.ic_contacts,
                if (!hasContactsPermission) "Needs contacts permission" else null)
        }

        sectionHeader("Relay")
        item(key = "pairing") {
            PairingCard(
                pairing = pairing,
                reveal = revealKey,
                onToggleReveal = { revealKey = !revealKey },
                onCopy = {
                    val code = pairing?.raw ?: return@PairingCard
                    scope.launch {
                        val clip = ClipData.newPlainText("Pairing code", code).apply {
                            // Keeps the key out of clipboard previews and keyboard suggestions.
                            description.extras = PersistableBundle().apply {
                                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                            }
                        }
                        clipboard.setClipEntry(ClipEntry(clip))
                    }
                },
                onEdit = { dialog = EditDialog.PairingCode },
                onNewKey = { dialog = EditDialog.NewKey },
            )
        }
        item(key = "relayUrl") {
            ClickItem("Relay server", pairing?.let { if (it.relayEnabled) it.relayUrl else "None (LAN/VPN only)" } ?: "—",
                segmentedShapes(1, 4), R.drawable.ic_dns) {
                dialog = EditDialog.RelayUrl
            }
        }
        item(key = "token") {
            val t = pairing?.relayToken
            ClickItem("Access token", when {
                    pairing?.relayEnabled == false -> "Not used without a relay"
                    t != null && t.length > 4 -> "Set (…${t.takeLast(4)})"
                    else -> "None"
                },
                segmentedShapes(2, 4), R.drawable.ic_password) { dialog = EditDialog.Token }
        }
        item(key = "lan") {
            ClickItem("Direct LAN/VPN address", when {
                    pairing?.lanEndpoint == null -> "Not set (internet relay only)"
                    !pairing.relayEnabled -> "${pairing.lanEndpoint} (the only link, no relay)"
                    else -> pairing.lanEndpoint
                },
                segmentedShapes(3, 4), R.drawable.ic_lan) { dialog = EditDialog.Lan }
        }

        sectionHeader("Map")
        item(key = "mapProvider") {
            val showingGoogle = useGoogleMaps(settings.mapProvider, mapsStatus)
            ChoiceCard(
                title = "Map provider",
                supporting = when {
                    showingGoogle -> "Showing Google Maps"
                    mapsStatus != GoogleMapsStatus.Available && settings.mapProvider != MapProvider.OpenStreetMap ->
                        "Showing OpenStreetMap • Google Maps: ${mapsStatus.description.lowercase()}"
                    else -> "Showing OpenStreetMap"
                },
                icon = R.drawable.ic_map, index = 0, count = 3,
            ) {
                ConnectedChoice(MapProvider.entries, settings.mapProvider, { it.label }, vm::setMapProvider)
            }
        }
        item(key = "mapStyle") {
            ChoiceCard("Map style", null, R.drawable.ic_layers, 1, 3) {
                ConnectedChoice(MapStyle.entries, settings.mapStyle, { it.name }, vm::setMapStyle)
            }
        }
        item(key = "tiles") {
            ClickItem("OpenStreetMap tiles", settings.customTileUrl.ifBlank { "OpenStreetMap" }
                .let { if (!it.startsWith("http") && it != "OpenStreetMap") "CARTO (API key set)" else it },
                segmentedShapes(2, 3), R.drawable.ic_map) { dialog = EditDialog.Tiles }
        }

        sectionHeader("Help")
        item(key = "setup") {
            ClickItem("Run setup again", "Permissions, pairing, connection check and battery", segmentedShapes(0, 1),
                R.drawable.ic_cloud_sync) { vm.restartOnboarding() }
        }

        item(key = "debugHeader") { DebugHeader(showDebug) { showDebug = !showDebug } }
        if (showDebug) {
            item(key = "preview") {
                SwitchItem("Preview contacts when empty", settings.previewContacts, vm::setPreviewContacts,
                    segmentedShapes(0, 3), R.drawable.ic_bug_report, "Shows contacts at made-up positions")
            }
            item(key = "clearFriends") {
                ClickItem("Clear cached friends", "Removes the last list received from the iPhone", segmentedShapes(1, 3),
                    R.drawable.ic_delete_sweep) { vm.clearCachedFriends(); showMessage("Cleared cached friends") }
            }
            item(key = "log") { LogCard(state.logs, onClear = vm::clearLog) }
        }
    }

    when (dialog) {
        EditDialog.PairingCode -> TextFieldDialog(
            title = "Pairing code",
            message = "Paste the fmb1:… code from Ping Relay's settings on the iPhone.",
            initial = "",
            placeholder = "fmb1:https://…",
            // Password-type keyboard: no autocorrect, suggestions or learning (the code holds the key).
            keyboardType = KeyboardType.Password,
            onDismiss = { dialog = null },
            onConfirm = { v -> vm.setPairingCode(v) { ok -> showMessage(if (ok) "Paired" else "That isn't a valid pairing code") }; dialog = null },
        )
        EditDialog.RelayUrl -> TextFieldDialog(
            title = "Relay server",
            message = "ntfy-compatible HTTPS server. Set the same server on the iPhone. Enter \"none\" to skip the relay and use only the LAN/VPN address, if both phones share a network or VPN.",
            initial = pairing?.takeIf { it.relayEnabled }?.relayUrl.orEmpty(),
            keyboardType = KeyboardType.Uri,
            onDismiss = { dialog = null },
            onConfirm = { v ->
                vm.setRelayUrl(v) { ok ->
                    if (!ok) showMessage(if (v.trim().equals("none", true)) "Set a LAN/VPN address first" else "Enter an http(s) URL or \"none\"")
                }
                dialog = null
            },
            neutralLabel = "Use ntfy.sh",
            onNeutral = { vm.setRelayUrl("https://ntfy.sh") { }; dialog = null },
        )
        EditDialog.Token -> TextFieldDialog(
            title = "Access token",
            message = "Only for a self-hosted ntfy server with authentication. It becomes part of the pairing code, so set the same token on the iPhone.",
            initial = "",
            placeholder = "tk_…",
            secret = true,
            onDismiss = { dialog = null },
            onConfirm = { v -> vm.setRelayToken(v) { ok -> showMessage(if (ok) "Token saved" else "Tokens may only contain letters, digits, . _ -") }; dialog = null },
            neutralLabel = "Clear",
            onNeutral = { vm.setRelayToken("") { showMessage("Token cleared") }; dialog = null },
        )
        EditDialog.Lan -> TextFieldDialog(
            title = "Direct LAN/VPN address",
            message = "Optional host:port of the relay iPhone for direct sync on your network or VPN (port 8765). The relay is still used for pushes from the iPhone.",
            initial = pairing?.lanEndpoint.orEmpty(),
            placeholder = "192.168.1.50:8765",
            keyboardType = KeyboardType.Uri,
            onDismiss = { dialog = null },
            onConfirm = { v ->
                vm.setLanEndpoint(v) { ok ->
                    if (!ok) showMessage(if (v.isBlank()) "Without a relay the LAN/VPN address is required" else "Use host:port")
                }
                dialog = null
            },
        )
        EditDialog.Tiles -> TextFieldDialog(
            title = "Map tiles",
            message = "Paste a CARTO basemaps API key, or a tile URL template containing {z}, {x} and {y}. Leave empty for OpenStreetMap.",
            initial = settings.customTileUrl,
            onDismiss = { dialog = null },
            onConfirm = { v -> vm.setTileUrl(v); dialog = null },
            neutralLabel = "Use OSM",
            onNeutral = { vm.setTileUrl(""); dialog = null },
        )
        EditDialog.NewKey -> AlertDialog(
            onDismissRequest = { dialog = null },
            icon = { Icon(painterResource(R.drawable.ic_key), null) },
            title = { Text("Generate a new key?") },
            text = { Text("This creates a new channel and encryption key. The iPhone stops receiving updates until you paste the new code into it.") },
            confirmButton = { TextButton(onClick = { vm.rotateKey(); dialog = null }) { Text("Generate") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        null -> Unit
    }
}

/** Header of the Debug section, styled like the other section headers; tap to show or hide it. */
@Composable
private fun DebugHeader(expanded: Boolean, onToggle: () -> Unit) {
    val rotation by androidx.compose.animation.core.animateFloatAsState(
        if (expanded) 180f else 0f, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "chevron")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onToggle)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Debug", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f))
        Icon(painterResource(R.drawable.ic_expand_more), contentDescription = if (expanded) "Hide debug" else "Show debug",
            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.rotate(rotation))
    }
}

/** A segmented "card" holding a title row plus custom content (e.g. a connected button group). */
@Composable
private fun ChoiceCard(
    title: String,
    supporting: String?,
    icon: Int,
    index: Int,
    count: Int,
    content: @Composable () -> Unit,
) {
    Surface(shape = segmentedShapes(index, count).shape, color = itemContainerColor(), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ItemIcon(icon)
                Column {
                    Text(title, style = MaterialTheme.typography.bodyLarge)
                    if (supporting != null) {
                        AnimatedContent(supporting, label = "supporting") {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PairingCard(
    pairing: PairingConfig?,
    reveal: Boolean,
    onToggleReveal: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onNewKey: () -> Unit,
) {
    Surface(shape = segmentedShapes(0, 4).shape, color = itemContainerColor(), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ItemIcon(R.drawable.ic_key)
                Column(Modifier.weight(1f)) {
                    Text("Pairing code", style = MaterialTheme.typography.bodyLarge)
                    Text("Contains the encryption key. Treat it like a password.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onToggleReveal) { Text(if (reveal) "Hide" else "Show") }
            }
            val shown = when {
                pairing == null -> "Not configured"
                reveal -> pairing.raw
                else -> "fmb1:${pairing.relayUrl}:${pairing.channelId.take(6)}…:••••••••" +
                    (if (pairing.lanEndpoint != null || pairing.relayToken != null) "?…" else "")
            }
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
                Text(shown, fontFamily = GoogleSansCode, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(12.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onCopy, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f)) {
                    Icon(painterResource(R.drawable.ic_content_copy), null, Modifier.size(ButtonDefaults.IconSize))
                    Text("Copy", Modifier.padding(start = ButtonDefaults.IconSpacing))
                }
                FilledTonalButton(onClick = onEdit, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f)) {
                    Icon(painterResource(R.drawable.ic_content_paste), null, Modifier.size(ButtonDefaults.IconSize))
                    Text("Paste", Modifier.padding(start = ButtonDefaults.IconSpacing))
                }
                OutlinedButton(onClick = onNewKey, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f)) {
                    Icon(painterResource(R.drawable.ic_autorenew), null, Modifier.size(ButtonDefaults.IconSize))
                    Text("New", Modifier.padding(start = ButtonDefaults.IconSpacing))
                }
            }
        }
    }
}

@Composable
private fun LogCard(logs: List<LogLine>, onClear: () -> Unit) {
    val fmt = remember { DateFormat.getTimeInstance(DateFormat.MEDIUM) }
    Surface(shape = segmentedShapes(2, 3).shape, color = itemContainerColor(), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Log", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onClear) { Text("Clear") }
            }
            if (logs.isEmpty()) {
                Text("Nothing yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            logs.take(25).forEach {
                Text("${fmt.format(Date(it.timeMs))}  ${it.message}", fontFamily = GoogleSansCode,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
