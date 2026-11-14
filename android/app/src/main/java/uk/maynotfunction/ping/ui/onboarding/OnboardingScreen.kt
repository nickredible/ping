package uk.maynotfunction.ping.ui.onboarding

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import uk.maynotfunction.ping.R
import uk.maynotfunction.ping.core.PairingCode
import uk.maynotfunction.ping.core.PairingConfig
import uk.maynotfunction.ping.data.RelayClient.CheckResult
import uk.maynotfunction.ping.ui.MainViewModel
import uk.maynotfunction.ping.ui.SetupCheck
import uk.maynotfunction.ping.ui.components.ItemIcon
import uk.maynotfunction.ping.ui.components.TextFieldDialog
import uk.maynotfunction.ping.ui.components.itemColors
import uk.maynotfunction.ping.ui.components.itemContainerColor
import uk.maynotfunction.ping.ui.components.segmentedShapes
import uk.maynotfunction.ping.ui.theme.GoogleSansCode

private enum class Step { Welcome, Permissions, Pair, Check, Battery }

/** Android 17+ blocks connections to local addresses (the iPhone's LAN/VPN link) without this. */
private const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
private val needsLocalNetworkPermission = Build.VERSION.SDK_INT >= 37

/**
 * First-run setup: what Ping does, permissions, pairing with the relay iPhone, a connection
 * check and the battery setting. Shown until [MainViewModel.finishOnboarding]; Settings can
 * bring it back.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OnboardingScreen(vm: MainViewModel) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val motion = MaterialTheme.motionScheme

    var step by rememberSaveable { mutableStateOf(Step.Welcome) }
    // Channel of the code pasted during this setup. The stored code may be the placeholder
    // generated on first launch, which no iPhone knows.
    var pairedChannel by rememberSaveable { mutableStateOf<String?>(null) }
    val paired = pairedChannel != null && state.pairing?.channelId == pairedChannel

    // Bumped on resume and after permission prompts, so granted states are re-read.
    var refreshTick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refreshTick++
        onPauseOrDispose { }
    }

    // The status bar sits over the plain background here, so its icons follow the theme.
    val view = LocalView.current
    val dark = isSystemInDarkTheme()
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
    }

    fun go(target: Step) { step = target }
    BackHandler(enabled = step != Step.Welcome) {
        go(if (step == Step.Battery && !paired) Step.Pair else Step.entries[step.ordinal - 1])
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.surfaceContainer) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp)) {
            val progress by animateFloatAsState(
                (step.ordinal + 1f) / Step.entries.size, motion.defaultSpatialSpec(), label = "progress")
            LinearWavyProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))

            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    val forward = targetState.ordinal > initialState.ordinal
                    val spatial = motion.defaultSpatialSpec<androidx.compose.ui.unit.IntOffset>()
                    val effects = motion.defaultEffectsSpec<Float>()
                    (slideInHorizontally(spatial) { w -> if (forward) w / 4 else -w / 4 } + fadeIn(effects))
                        .togetherWith(slideOutHorizontally(spatial) { w -> if (forward) -w / 4 else w / 4 } + fadeOut(effects))
                        .using(SizeTransform(clip = false))
                },
                modifier = Modifier.weight(1f),
                label = "step",
            ) { current ->
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 32.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    when (current) {
                        Step.Welcome -> WelcomeStep()
                        Step.Permissions -> PermissionsStep(refreshTick, onChanged = {
                            refreshTick++
                            vm.onContactsPermissionChanged()
                        })
                        Step.Pair -> PairStep(
                            pairing = state.pairing.takeIf { paired },
                            onPaired = { channel -> pairedChannel = channel },
                            vm = vm,
                        )
                        Step.Check -> CheckStep(state.pairing, vm)
                        Step.Battery -> BatteryStep(refreshTick)
                    }
                }
            }

            // ---- Buttons ----
            Row(
                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (step) {
                    Step.Welcome -> Unit
                    Step.Pair -> if (!paired) TextButton(onClick = { go(Step.Battery) }) { Text("Skip for now") }
                        else TextButton(onClick = { go(Step.Permissions) }) { Text("Back") }
                    else -> TextButton(onClick = {
                        go(if (step == Step.Battery && !paired) Step.Pair else Step.entries[step.ordinal - 1])
                    }) { Text("Back") }
                }
                Spacer(Modifier.weight(1f))
                val (label, enabled) = when (step) {
                    Step.Welcome -> "Get started" to true
                    Step.Pair -> "Continue" to paired
                    Step.Battery -> "Start sharing" to true
                    else -> "Continue" to true
                }
                Button(
                    onClick = {
                        when (step) {
                            Step.Battery -> {
                                // Location may have been refused; Me → Share can ask again later.
                                if (!context.hasLocation()) vm.setSharing(false)
                                vm.finishOnboarding()
                            }
                            else -> go(Step.entries[step.ordinal + 1])
                        }
                    },
                    enabled = enabled,
                    shapes = ButtonDefaults.shapes(),
                ) { Text(label) }
            }
        }
    }
}

// ---- Steps -----------------------------------------------------------------------------------

@Composable
private fun WelcomeStep() {
    StepHeader(R.drawable.ic_share_location, "Put this phone on Find My",
        "Ping shares this phone's location through a spare iPhone signed into your Apple Account, so " +
            "friends see you in Find My.")
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        InfoRow(R.drawable.ic_near_me, "You → Find My",
            "This phone's location goes to the relay iPhone, which shares it as its own.", 0, 3)
        InfoRow(R.drawable.ic_group, "Friends → you",
            "The relay iPhone sends your friends' locations back to the map here.", 1, 3)
        InfoRow(R.drawable.ic_key, "End-to-end encrypted",
            "The relay server in between only ever sees ciphertext.", 2, 3)
    }
    Note("You'll need the relay iPhone set up with the Ping Relay tweak first. The README covers it.")
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PermissionsStep(refreshTick: Int, onChanged: () -> Unit) {
    val context = LocalContext.current
    // Permissions already refused once: Android won't show the prompt again, so open Settings.
    var refused by rememberSaveable { mutableStateOf(setOf<String>()) }
    var pending by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val key = pending
        if (key != null && result.values.none { it } &&
            context.findActivity()?.shouldShowRequestPermissionRationale(key) == false) {
            refused = refused + key
        }
        pending = null
        onChanged()
    }

    fun request(vararg permissions: String) {
        val key = permissions.first()
        if (key in refused) {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)))
        } else {
            pending = key
            launcher.launch(arrayOf(*permissions))
        }
    }

    val location = remember(refreshTick) { context.hasLocation() }
    val notifications = remember(refreshTick) { context.granted(Manifest.permission.POST_NOTIFICATIONS) }
    val contacts = remember(refreshTick) { context.granted(Manifest.permission.READ_CONTACTS) }
    val localNetwork = remember(refreshTick) { context.granted(LOCAL_NETWORK) }
    val count = if (needsLocalNetworkPermission) 4 else 3

    StepHeader(R.drawable.ic_location_on_filled, "Permissions", "Ping only asks for what it uses.")
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        PermissionRow(R.drawable.ic_my_location, "Location", "Needed to share where this phone is",
            location, Manifest.permission.ACCESS_FINE_LOCATION in refused, 0, count) {
            request(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        PermissionRow(R.drawable.ic_notifications, "Notifications",
            "Shows that sharing is running, which keeps Android from stopping it",
            notifications, Manifest.permission.POST_NOTIFICATIONS in refused, 1, count) {
            request(Manifest.permission.POST_NOTIFICATIONS)
        }
        PermissionRow(R.drawable.ic_contacts, "Contacts", "Optional: names and photos for your friends",
            contacts, Manifest.permission.READ_CONTACTS in refused, 2, count) {
            request(Manifest.permission.READ_CONTACTS)
        }
        if (needsLocalNetworkPermission) {
            PermissionRow(R.drawable.ic_lan, "Local network",
                "Optional: talk to the relay iPhone directly on your Wi-Fi or VPN",
                localNetwork, LOCAL_NETWORK in refused, 3, count) {
                request(LOCAL_NETWORK)
            }
        }
    }
    if (!location) Note("Without location, Ping can still show your friends but can't share where you are.")
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PairStep(pairing: PairingConfig?, onPaired: (String) -> Unit, vm: MainViewModel) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var typing by rememberSaveable { mutableStateOf(false) }
    var showSsh by rememberSaveable { mutableStateOf(false) }
    var clearedClipboard by rememberSaveable { mutableStateOf(false) }

    fun accept(raw: String?, fromClipboard: Boolean) {
        val code = raw?.trim().orEmpty()
        if (code.isEmpty()) { error = "The clipboard is empty."; return }
        val parsed = PairingCode.parse(code)
        if (parsed == null) {
            error = if (code.startsWith("fmb1:")) "That pairing code is incomplete or damaged. Copy it again."
                else "That isn't a Ping pairing code. It starts with fmb1:"
            return
        }
        vm.setPairingCode(parsed.raw) { ok ->
            if (!ok) { error = "Couldn't save the pairing code."; return@setPairingCode }
            error = null
            onPaired(parsed.channelId)
            // It holds the encryption key, so don't leave it on the clipboard.
            clearedClipboard = fromClipboard && runCatching {
                context.getSystemService(ClipboardManager::class.java)?.clearPrimaryClip()
            }.isSuccess
        }
    }

    StepHeader(R.drawable.ic_key, "Pair with the relay iPhone",
        "The pairing code links this phone to the iPhone. It contains your encryption key, so treat it like a password.")

    if (pairing != null) {
        PairedCard(pairing, clearedClipboard)
        TextButton(onClick = { typing = true }) { Text("Use a different code") }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            NumberedRow(1, "On the iPhone, open Settings → Ping Relay and tap Copy Pairing Code.", 0, 3)
            NumberedRow(2, "Get it to this phone over something you trust, like your password manager.", 1, 3)
            NumberedRow(3, "Copy it here, then tap Paste.", 2, 3)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = {
                    scope.launch {
                        val text = clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)?.coerceToText(context)?.toString()
                        accept(text, fromClipboard = true)
                    }
                },
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.weight(1f),
            ) {
                Icon(painterResource(R.drawable.ic_content_paste), null, Modifier.size(ButtonDefaults.IconSize))
                Text("Paste", Modifier.padding(start = ButtonDefaults.IconSpacing))
            }
            OutlinedButton(onClick = { typing = true }, shapes = ButtonDefaults.shapes(), modifier = Modifier.weight(1f)) {
                Text("Type it in")
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

        TextButton(onClick = { showSsh = !showSsh }) { Text(if (showSsh) "Hide SSH option" else "Have SSH access to the iPhone?") }
        if (showSsh) {
            Text("This prints the code without it appearing on the iPhone's screen:",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            CodeBlock("ssh root@<iphone> curl -s http://127.0.0.1:8765/v1/pairing")
        }
    }

    if (typing) TextFieldDialog(
        title = "Pairing code",
        message = "Paste or type the fmb1:… code from Ping Relay's settings on the iPhone.",
        initial = "",
        placeholder = "fmb1:https://…",
        // Password-type keyboard: no autocorrect, suggestions or learning (the code holds the key).
        keyboardType = KeyboardType.Password,
        onDismiss = { typing = false },
        onConfirm = { v -> typing = false; accept(v, fromClipboard = false) },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CheckStep(pairing: PairingConfig?, vm: MainViewModel) {
    val context = LocalContext.current
    var attempt by rememberSaveable { mutableIntStateOf(0) }
    val localNetworkLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { attempt++ }
    var result by remember { mutableStateOf<SetupCheck?>(null) }
    LaunchedEffect(attempt) {
        result = null
        result = vm.runSetupCheck()
    }

    StepHeader(R.drawable.ic_cloud_sync, "Check the connection", "Making sure this phone can reach the relay iPhone.")

    val r = result
    if (r == null || pairing == null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LoadingIndicator(Modifier.size(48.dp))
            Text("Checking… this takes up to 15 seconds.", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    val c = r.connection
    val lanShown = pairing.lanEndpoint != null
    val lanBlocked = lanShown && c.lan == CheckResult.Unreachable &&
        needsLocalNetworkPermission && !context.granted(LOCAL_NETWORK)
    val count = if (lanShown) 3 else 2
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        StatusRow(
            title = "Relay server",
            detail = when (c.relay) {
                CheckResult.Ok -> c.relayDetail ?: "Reachable at ${Uri.parse(pairing.relayUrl).host ?: pairing.relayUrl}"
                CheckResult.Rejected -> "${c.relayDetail}. Check the token on both phones."
                else -> "Can't reach it: ${c.relayDetail}"
            },
            status = c.relay, index = 0, count = count,
        )
        if (lanShown) StatusRow(
            title = "Direct LAN/VPN (${pairing.lanEndpoint})",
            detail = when (c.lan) {
                CheckResult.Ok -> "The iPhone answered directly"
                CheckResult.Rejected -> "${c.lanDetail}. Copy the pairing code from the iPhone again."
                else -> if (lanBlocked) "Android is blocking it until Ping can use the local network."
                    else "Not reachable from here (${c.lanDetail}). That's fine away from home or off the VPN."
            },
            // An unreachable LAN address is expected away from home, so it isn't shown as an error.
            status = if (c.lan == CheckResult.Unreachable) CheckResult.Skipped else c.lan,
            index = 1, count = count,
        )
        StatusRow(
            title = "Relay iPhone",
            detail = when {
                r.iphoneAnswered -> "Answered. Your friends will appear on the map."
                c.relay == CheckResult.Ok -> "No reply yet. If it sent your friends in the last few minutes it " +
                    "won't resend them, so this can be normal. Check that Bridge Status on the iPhone isn't Paused."
                else -> "Fix the relay server first."
            },
            status = if (r.iphoneAnswered) CheckResult.Ok else CheckResult.Skipped,
            index = count - 1, count = count,
        )
    }
    if (lanBlocked) FilledTonalButton(onClick = { localNetworkLauncher.launch(LOCAL_NETWORK) }, shapes = ButtonDefaults.shapes()) {
        Text("Allow local network")
    }
    TextButton(onClick = { attempt++ }) {
        Icon(painterResource(R.drawable.ic_refresh), null, Modifier.size(ButtonDefaults.IconSize))
        Text("Check again", Modifier.padding(start = ButtonDefaults.IconSpacing))
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BatteryStep(refreshTick: Int) {
    val context = LocalContext.current
    val unrestricted = remember(refreshTick) {
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
    }

    StepHeader(R.drawable.ic_battery, "Keep it running",
        "Android can stop apps in the background to save power. Set Ping's battery usage to Unrestricted so " +
            "sharing keeps going.")
    PermissionRow(R.drawable.ic_battery, "Battery usage",
        if (unrestricted) "Unrestricted" else "Optimised: Android may pause sharing",
        unrestricted, blocked = false, index = 0, count = 1) {
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        runCatching { context.startActivity(request) }.onFailure {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)))
        }
    }
    Note("After the phone restarts, open Ping once to start sharing again. You can change how often it " +
        "updates in Settings.")
}

// ---- Building blocks -------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StepHeader(@DrawableRes icon: Int, title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(
            Modifier.size(88.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialShapes.Cookie9Sided.toShape()),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(icon), null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Text(title, style = MaterialTheme.typography.headlineMediumEmphasized)
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun InfoRow(@DrawableRes icon: Int, title: String, body: String, index: Int, count: Int) {
    SegmentedListItem(
        shapes = segmentedShapes(index, count),
        colors = itemColors(),
        leadingContent = { ItemIcon(icon) },
        supportingContent = { Text(body) },
    ) { Text(title) }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NumberedRow(number: Int, text: String, index: Int, count: Int) {
    SegmentedListItem(
        shapes = segmentedShapes(index, count),
        colors = itemColors(),
        leadingContent = {
            Box(Modifier.size(28.dp).background(MaterialTheme.colorScheme.secondaryContainer, MaterialShapes.Circle.toShape()),
                contentAlignment = Alignment.Center) {
                Text("$number", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        },
    ) { Text(text, style = MaterialTheme.typography.bodyLarge) }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PermissionRow(
    @DrawableRes icon: Int,
    title: String,
    body: String,
    granted: Boolean,
    blocked: Boolean,
    index: Int,
    count: Int = 3,
    onRequest: () -> Unit,
) {
    SegmentedListItem(
        shapes = segmentedShapes(index, count),
        colors = itemColors(),
        leadingContent = { ItemIcon(icon) },
        supportingContent = { Text(body) },
        trailingContent = {
            if (granted) {
                Icon(painterResource(R.drawable.ic_check), contentDescription = "Allowed", tint = MaterialTheme.colorScheme.primary)
            } else {
                FilledTonalButton(onClick = onRequest, shapes = ButtonDefaults.shapes()) {
                    Text(if (blocked) "Settings" else "Allow")
                }
            }
        },
    ) { Text(title) }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StatusRow(title: String, detail: String, status: CheckResult, index: Int, count: Int) {
    val scheme = MaterialTheme.colorScheme
    val (icon, tint) = when (status) {
        CheckResult.Ok -> R.drawable.ic_check to scheme.primary
        CheckResult.Rejected, CheckResult.Unreachable -> R.drawable.ic_close to scheme.error
        CheckResult.Skipped -> R.drawable.ic_schedule to scheme.onSurfaceVariant
    }
    SegmentedListItem(
        shapes = segmentedShapes(index, count),
        colors = itemColors(),
        leadingContent = { Icon(painterResource(icon), null, tint = tint) },
        supportingContent = { Text(detail) },
    ) { Text(title) }
}

@Composable
private fun PairedCard(pairing: PairingConfig, clearedClipboard: Boolean) {
    val token = pairing.relayToken
    Surface(shape = MaterialTheme.shapes.large, color = itemContainerColor(), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(painterResource(R.drawable.ic_check), null, tint = MaterialTheme.colorScheme.primary)
                Text("Paired", style = MaterialTheme.typography.titleMedium)
            }
            Detail("Relay server", pairing.relayUrl)
            Detail("Access token", if (token != null && token.length > 4) "Set (…${token.takeLast(4)})" else "None")
            Detail("Direct LAN/VPN", pairing.lanEndpoint ?: "Not set")
            if (clearedClipboard) Text("Removed it from the clipboard.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.6f))
    }
}

@Composable
private fun CodeBlock(text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
        Text(text, fontFamily = GoogleSansCode, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth().padding(12.dp))
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp))
}

// ---- Helpers ---------------------------------------------------------------------------------

private fun Context.granted(permission: String) =
    checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

private fun Context.hasLocation() =
    granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
