package uk.maynotfunction.ping.ui.friends

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import uk.maynotfunction.ping.R
import uk.maynotfunction.ping.core.MyFix
import uk.maynotfunction.ping.ui.FriendUi
import uk.maynotfunction.ping.ui.components.Avatar
import uk.maynotfunction.ping.ui.components.InfoItem
import uk.maynotfunction.ping.ui.components.itemColors
import uk.maynotfunction.ping.ui.components.distanceLabel
import uk.maynotfunction.ping.ui.components.distanceMeters
import uk.maynotfunction.ping.ui.components.relativeTime
import uk.maynotfunction.ping.ui.components.rememberNow
import uk.maynotfunction.ping.ui.components.segmentedShapes
import kotlin.math.roundToInt

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun FriendsTab(
    friends: List<FriendUi>,
    myFix: MyFix?,
    selectedId: String?,
    onSelect: (FriendUi?) -> Unit,
    onRefresh: () -> Unit,
    onDirections: (FriendUi) -> Unit,
    onContact: (FriendUi) -> Unit,
    bottomPadding: Dp,
) {
    val now = rememberNow()
    val selected = friends.firstOrNull { it.id == selectedId }
    BackHandler(enabled = selected != null) { onSelect(null) }

    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    SharedTransitionLayout {
        AnimatedContent(
            targetState = selected,
            contentKey = { it?.id },
            transitionSpec = {
                (fadeIn(effects) + scaleIn(spatial, initialScale = 0.96f))
                    .togetherWith(fadeOut(effects))
                    .using(SizeTransform(clip = false))
            },
            label = "friendDetail",
        ) { friend ->
            if (friend == null) {
                FriendList(friends, myFix, now, onSelect, onRefresh, bottomPadding, this@SharedTransitionLayout, this)
            } else {
                FriendDetail(friend, myFix, now, { onSelect(null) }, onDirections, onContact, bottomPadding,
                    this@SharedTransitionLayout, this)
            }
        }
    }
}

private fun subtitle(f: FriendUi, now: Long): String =
    listOfNotNull(f.address.takeIf { it.isNotBlank() }, relativeTime(f.timestampMs, now)).joinToString(" • ")

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FriendList(
    friends: List<FriendUi>,
    myFix: MyFix?,
    now: Long,
    onSelect: (FriendUi) -> Unit,
    onRefresh: () -> Unit,
    bottomPadding: Dp,
    shared: SharedTransitionScope,
    animated: AnimatedVisibilityScope,
) = with(shared) {
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item(key = "title") {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 4.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Friends", style = MaterialTheme.typography.headlineMediumEmphasized, modifier = Modifier.weight(1f))
                FilledTonalIconButton(onClick = onRefresh, shapes = IconButtonDefaults.shapes()) {
                    Icon(painterResource(R.drawable.ic_refresh), contentDescription = "Refresh friends")
                }
            }
        }
        if (friends.any { it.isPreview }) {
            item(key = "preview") {
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                    Text("Preview: contacts at made-up positions. Turn this off in Settings › Debug.",
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(14.dp))
                }
            }
        }
        if (friends.isEmpty()) {
            item(key = "empty") { EmptyFriends(onRefresh) }
        }
        itemsIndexed(friends, key = { _, f -> f.id }) { i, f ->
            val distance = myFix?.let { distanceLabel(distanceMeters(it.lat, it.lon, f.lat, f.lon)) }
            SegmentedListItem(
                onClick = { onSelect(f) },
                shapes = segmentedShapes(i, friends.size),
                colors = itemColors(),
                modifier = Modifier.animateItem(),
                leadingContent = {
                    Avatar(f.name, f.photo, 48.dp,
                        Modifier.sharedElement(rememberSharedContentState("avatar-${f.id}"), animated))
                },
                supportingContent = { Text(subtitle(f, now), maxLines = 1) },
                trailingContent = distance?.let { { Text(it, style = MaterialTheme.typography.labelLarge) } },
            ) {
                Text(f.name, Modifier.sharedBounds(rememberSharedContentState("name-${f.id}"), animated))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EmptyFriends(onRefresh: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val shape = MaterialShapes.Cookie9Sided.toShape()
        Surface(shape = shape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(96.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_group), null, Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        Text("No friends yet", style = MaterialTheme.typography.titleMedium)
        Text(
            "Friends who share their location with your Apple ID appear here once the relay iPhone syncs them.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        FilledTonalButton(onClick = onRefresh, shapes = ButtonDefaults.shapes()) { Text("Refresh") }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FriendDetail(
    f: FriendUi,
    myFix: MyFix?,
    now: Long,
    onBack: () -> Unit,
    onDirections: (FriendUi) -> Unit,
    onContact: (FriendUi) -> Unit,
    bottomPadding: Dp,
    shared: SharedTransitionScope,
    animated: AnimatedVisibilityScope,
) = with(shared) {
    val distance = myFix?.let { distanceMeters(it.lat, it.lon, f.lat, f.lon) }
    val avatarShape = MaterialShapes.Cookie9Sided.toShape()
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item {
            // Compact header so the action buttons stay above the floating toolbar at peek height.
            Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalIconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
                    }
                    Avatar(f.name, f.photo, 72.dp,
                        Modifier.sharedElement(rememberSharedContentState("avatar-${f.id}"), animated),
                        shape = avatarShape)
                    Column(Modifier.weight(1f)) {
                        Text(f.name, style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.sharedBounds(rememberSharedContentState("name-${f.id}"), animated))
                        Text(
                            listOfNotNull(relativeTime(f.timestampMs, now), distance?.let { distanceLabel(it) }).joinToString(" • "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    val height = ButtonDefaults.MediumContainerHeight
                    Button(
                        onClick = { onDirections(f) },
                        shapes = ButtonDefaults.shapes(),
                        contentPadding = ButtonDefaults.contentPaddingFor(height),
                        modifier = Modifier.weight(1f).heightIn(height),
                    ) {
                        Icon(painterResource(R.drawable.ic_directions_filled), null, Modifier.size(ButtonDefaults.iconSizeFor(height)))
                        Spacer(Modifier.size(ButtonDefaults.iconSpacingFor(height)))
                        Text("Directions", style = ButtonDefaults.textStyleFor(height))
                    }
                    FilledTonalButton(
                        onClick = { onContact(f) },
                        shapes = ButtonDefaults.shapes(),
                        contentPadding = ButtonDefaults.contentPaddingFor(height),
                        modifier = Modifier.weight(1f).heightIn(height),
                    ) {
                        Icon(painterResource(R.drawable.ic_person), null, Modifier.size(ButtonDefaults.iconSizeFor(height)))
                        Spacer(Modifier.size(ButtonDefaults.iconSpacingFor(height)))
                        Text("Contact", style = ButtonDefaults.textStyleFor(height))
                    }
                }
            }
        }
        val rows = buildList {
            if (f.address.isNotBlank()) add(Triple("Address", f.address, R.drawable.ic_location_on_filled))
            add(Triple("Last updated", relativeTime(f.timestampMs, now), R.drawable.ic_schedule))
            add(Triple("Accuracy", "±${f.accuracyM.roundToInt()} m", R.drawable.ic_my_location))
            if (f.handle.isNotBlank()) add(Triple("Apple ID", f.handle, R.drawable.ic_contacts))
            if (f.isPreview) add(Triple("Preview", "Position is made up for testing", R.drawable.ic_bug_report))
        }
        itemsIndexed(rows) { i, (title, value, icon) -> InfoItem(title, value, segmentedShapes(i, rows.size), icon) }
    }
}
