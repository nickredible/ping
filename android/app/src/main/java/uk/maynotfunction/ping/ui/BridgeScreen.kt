package uk.maynotfunction.ping.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.maynotfunction.ping.R
import uk.maynotfunction.ping.data.MapStyle
import uk.maynotfunction.ping.ui.friends.FriendsTab
import uk.maynotfunction.ping.ui.map.GoogleLiveMap
import uk.maynotfunction.ping.ui.map.LiveMap
import uk.maynotfunction.ping.ui.map.googleMapsStatus
import uk.maynotfunction.ping.ui.map.useGoogleMaps
import uk.maynotfunction.ping.ui.map.rememberMapCamera
import uk.maynotfunction.ping.ui.me.MeTab
import uk.maynotfunction.ping.ui.settings.SettingsTab
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.max

enum class Tab(val label: String, @DrawableRes val icon: Int, @DrawableRes val selectedIcon: Int) {
    Friends("Friends", R.drawable.ic_group, R.drawable.ic_group_filled),
    Settings("Settings", R.drawable.ic_settings, R.drawable.ic_settings_filled),
    Me("Me", R.drawable.ic_near_me, R.drawable.ic_near_me_filled),
}

private fun Context.granted(permission: String) =
    checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

private fun Context.hasLocation() =
    granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BridgeScreen(vm: MainViewModel) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val motion = MaterialTheme.motionScheme

    var tab by rememberSaveable { mutableStateOf(Tab.Friends) }
    var selectedFriendId by rememberSaveable { mutableStateOf<String?>(null) }
    var contactsGranted by remember { mutableStateOf(context.granted(Manifest.permission.READ_CONTACTS)) }

    val sheetState = rememberStandardBottomSheetState(initialValue = SheetValue.PartiallyExpanded)
    val scaffoldState = rememberBottomSheetScaffoldState(sheetState)
    val camera = rememberMapCamera()
    val showMessage: (String) -> Unit = { msg -> scope.launch { scaffoldState.snackbarHostState.showSnackbar(msg) } }

    // ---- Permissions & lifecycle ---------------------------------------------------------------
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.READ_CONTACTS] == true) {
            contactsGranted = true
            vm.onContactsPermissionChanged()
        }
        if (context.hasLocation()) vm.onResumed(hasLocationPermission = true)
    }
    // Setup asks for everything with an explanation. Here, only re-ask for what's in use: location
    // while sharing, and (Android 17+) local network access when a LAN/VPN address is set.
    LaunchedEffect(Unit) {
        val missing = buildList {
            if (state.settings.sharingEnabled && !context.hasLocation()) {
                add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (android.os.Build.VERSION.SDK_INT >= 37 && state.pairing?.lanEndpoint != null &&
                !context.granted("android.permission.ACCESS_LOCAL_NETWORK")) {
                add("android.permission.ACCESS_LOCAL_NETWORK")
            }
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }
    LifecycleResumeEffect(Unit) {
        contactsGranted = context.granted(Manifest.permission.READ_CONTACTS)
        vm.onResumed(context.hasLocation())
        onPauseOrDispose { vm.onPaused() }
    }

    // Centre on this phone the first time we get a fix.
    var centeredOnce by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.myFix != null) {
        val fix = state.myFix ?: return@LaunchedEffect
        if (!centeredOnce) {
            centeredOnce = true
            camera.animateTo(scope, fix.lat, fix.lon, 15.0, motion.slowSpatialSpec())
        }
    }

    val mapsStatus = remember { googleMapsStatus(context) }
    val googleMaps = useGoogleMaps(state.settings.mapProvider, mapsStatus)

    val darkMap = when (state.settings.mapStyle) {
        MapStyle.Auto -> isSystemInDarkTheme()
        MapStyle.Light -> false
        MapStyle.Dark -> true
    }

    // The status bar always sits over the map, so its icons follow the map style, not the theme.
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.SideEffect {
        val window = (view.context as android.app.Activity).window
        androidx.core.view.WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkMap
    }

    fun focusFriend(friend: FriendUi) {
        selectedFriendId = friend.id
        tab = Tab.Friends
        camera.animateTo(scope, friend.lat, friend.lon, max(camera.zoom, 16.0), motion.defaultSpatialSpec())
        scope.launch { sheetState.partialExpand() }
    }

    val density = LocalDensity.current
    val navBottom = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    val statusTop = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    // Leaves room under the floating toolbar at the end of each list.
    val listBottomPadding = navBottom + 104.dp

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screenHeight = maxHeight
        BottomSheetScaffold(
            scaffoldState = scaffoldState,
            // Drawn below instead, above the floating tab bar (this one would sit underneath it).
            snackbarHost = {},
            sheetPeekHeight = (screenHeight * 0.42f),
            sheetContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            sheetShadowElevation = 8.dp,
            sheetContent = {
                // Fixed height that stops below the status bar when fully expanded; lists scroll inside.
                Box(Modifier.fillMaxWidth().height(screenHeight - statusTop - 40.dp)) {
                    AnimatedContent(
                        targetState = tab,
                        transitionSpec = {
                            val forward = targetState.ordinal > initialState.ordinal
                            val spatial = motion.defaultSpatialSpec<androidx.compose.ui.unit.IntOffset>()
                            val effects = motion.defaultEffectsSpec<Float>()
                            (slideInHorizontally(spatial) { w -> if (forward) w / 4 else -w / 4 } + fadeIn(effects))
                                .togetherWith(slideOutHorizontally(spatial) { w -> if (forward) -w / 4 else w / 4 } + fadeOut(effects))
                                .using(SizeTransform(clip = false))
                        },
                        label = "tabs",
                    ) { current ->
                        when (current) {
                            Tab.Friends -> FriendsTab(
                                friends = state.friends,
                                myFix = state.myFix,
                                selectedId = selectedFriendId,
                                onSelect = { f -> if (f == null) selectedFriendId = null else focusFriend(f) },
                                onRefresh = { vm.refreshFriends(); showMessage("Asked the relay iPhone for an update") },
                                onDirections = { openDirections(context, it) },
                                onContact = { openContact(context, it) },
                                bottomPadding = listBottomPadding,
                            )
                            Tab.Settings -> SettingsTab(
                                state = state,
                                vm = vm,
                                hasContactsPermission = contactsGranted,
                                requestContactsPermission = { permissionLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS)) },
                                showMessage = showMessage,
                                bottomPadding = listBottomPadding,
                            )
                            Tab.Me -> MeTab(
                                state = state,
                                onSharingChange = { on ->
                                    if (on && !context.hasLocation()) {
                                        permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.ACCESS_COARSE_LOCATION))
                                    }
                                    vm.setSharing(on)
                                },
                                onSyncNow = vm::syncNow,
                                onRefreshFriends = { vm.refreshFriends(); showMessage("Asked the relay iPhone for an update") },
                                bottomPadding = listBottomPadding,
                            )
                        }
                    }
                }
            },
        ) {
            val screenHeightPx = with(density) { screenHeight.toPx() }
            if (googleMaps) {
                GoogleLiveMap(
                    camera = camera,
                    friends = state.friends,
                    myFix = state.myFix,
                    selectedFriendId = selectedFriendId,
                    dark = darkMap,
                    topPadding = statusTop + 64.dp,
                    bottomPadding = screenHeight * 0.42f,
                    onFriendClick = ::focusFriend,
                    onMapTap = { scope.launch { sheetState.partialExpand() } },
                )
            } else {
                LiveMap(
                    camera = camera,
                    friends = state.friends,
                    myFix = state.myFix,
                    selectedFriendId = selectedFriendId,
                    dark = darkMap,
                    customTileUrl = state.settings.customTileUrl,
                    visibleBottomPx = { runCatching { sheetState.requireOffset() }.getOrDefault(screenHeightPx) },
                    onFriendClick = ::focusFriend,
                    onMapTap = { scope.launch { sheetState.partialExpand() } },
                )
            }
            MapOverlay(
                state = state,
                showOsmAttribution = !googleMaps,
                onStatusClick = { tab = Tab.Me },
                onMyLocation = {
                    val fix = state.myFix
                    if (fix != null) camera.animateTo(scope, fix.lat, fix.lon, max(camera.zoom, 15.0), motion.defaultSpatialSpec())
                    else vm.syncNow()
                },
            )
        }

        TabToolbar(
            selected = tab,
            onSelect = { target ->
                if (target == tab && target == Tab.Friends) selectedFriendId = null
                tab = target
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        )

        // Messages sit just above the floating tab bar, in the theme's surface colours so they
        // follow light/dark like the system's own toasts (Material's default is inverted).
        SnackbarHost(
            hostState = scaffoldState.snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp + 72.dp + 12.dp),
        ) { data ->
            Snackbar(
                snackbarData = data,
                shape = MaterialTheme.shapes.large,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface,
                actionColor = MaterialTheme.colorScheme.primary,
                dismissActionContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Tomato-style floating toolbar: toggle buttons whose label expands on the selected tab. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TabToolbar(selected: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    val motion = MaterialTheme.motionScheme
    val haptics = LocalHapticFeedback.current
    val scheme = MaterialTheme.colorScheme
    HorizontalFloatingToolbar(
        expanded = true,
        colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
        modifier = modifier,
    ) {
        Tab.entries.forEach { item ->
            val isSelected = item == selected
            ToggleButton(
                checked = isSelected,
                onCheckedChange = {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onSelect(item)
                },
                shapes = ToggleButtonShapes(CircleShape, CircleShape, CircleShape),
                colors = ToggleButtonDefaults.colors(
                    containerColor = scheme.primaryContainer,
                    contentColor = scheme.onPrimaryContainer,
                    checkedContainerColor = scheme.primary,
                    checkedContentColor = scheme.onPrimary,
                ),
                modifier = Modifier.height(56.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AnimatedContent(isSelected, label = "icon") { sel ->
                        Icon(painterResource(if (sel) item.selectedIcon else item.icon), contentDescription = item.label)
                    }
                    AnimatedVisibility(
                        visible = isSelected,
                        enter = expandHorizontally(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
                        exit = shrinkHorizontally(motion.defaultSpatialSpec()) + fadeOut(motion.defaultEffectsSpec()),
                    ) {
                        Text(item.label, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
                            modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MapOverlay(
    state: UiState,
    showOsmAttribution: Boolean,
    onStatusClick: () -> Unit,
    onMyLocation: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(state, onStatusClick)
            // Google Maps draws its own logo and attribution.
            val tiles = state.settings.customTileUrl
            if (showOsmAttribution) Text(
                when {
                    tiles.contains("{z}") -> "Map data © its provider"
                    tiles.isNotBlank() && !tiles.startsWith("http") -> "© OpenStreetMap contributors © CARTO"
                    else -> "© OpenStreetMap contributors"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f), CircleShape)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        // Map style (light/dark/follow the system) is in Settings.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalIconButton(onClick = onMyLocation, shapes = IconButtonDefaults.shapes(),
                modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.ic_my_location), contentDescription = "My location")
            }
        }
    }
}

@Composable
private fun StatusPill(state: UiState, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val sharing = state.settings.sharingEnabled
    val error = state.status.lastError != null
    val label = when {
        !sharing -> "Paused"
        error -> "Relay problem"
        state.syncing -> "Syncing…"
        state.myFix != null -> String.format(Locale.getDefault(), "Sharing • ±%d m", state.myFix.accuracyM.toInt())
        else -> "Starting…"
    }
    val dot by animateColorAsState(
        when { !sharing -> scheme.outline; error -> scheme.error; else -> scheme.primary },
        MaterialTheme.motionScheme.defaultEffectsSpec(), label = "dot",
    )
    val pulse = rememberInfiniteTransition(label = "statusPulse")
    val pulseScale by pulse.animateFloat(1f, 1.5f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "s")

    Surface(onClick = onClick, shape = CircleShape, color = scheme.surfaceContainerHigh, shadowElevation = 4.dp) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).scale(if (sharing && !error) pulseScale else 1f).background(dot, CircleShape))
            AnimatedContent(label, label = "statusText") {
                Text(it, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 10.dp))
            }
        }
    }
}

private fun openDirections(context: Context, f: FriendUi) {
    val uri = Uri.parse(String.format(Locale.US, "geo:%f,%f?q=%f,%f(%s)", f.lat, f.lon, f.lat, f.lon, Uri.encode(f.name)))
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
}

private fun openContact(context: Context, f: FriendUi) {
    val intent = when {
        f.contactUri != null -> Intent(Intent.ACTION_VIEW, f.contactUri)
        f.handle.isNotBlank() -> Intent(ContactsContract.Intents.SHOW_OR_CREATE_CONTACT,
            if (f.handle.contains('@')) Uri.fromParts("mailto", f.handle, null) else Uri.fromParts("tel", f.handle, null))
        else -> Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CONTACTS)
    }
    runCatching { context.startActivity(intent) }
}
