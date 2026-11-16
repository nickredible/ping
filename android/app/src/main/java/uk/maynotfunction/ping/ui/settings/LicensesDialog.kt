package uk.maynotfunction.ping.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import uk.maynotfunction.ping.R

private class Notice(val name: String, val holder: String, val license: String, val url: String)

/** Third-party code, fonts and data bundled with or shown by Ping. Tapping one opens its licence. */
private val notices = listOf(
    Notice("AndroidX: Core, Activity, Lifecycle, DataStore", "The Android Open Source Project",
        "Apache License 2.0", "https://www.apache.org/licenses/LICENSE-2.0"),
    Notice("Jetpack Compose and Material 3", "The Android Open Source Project",
        "Apache License 2.0", "https://www.apache.org/licenses/LICENSE-2.0"),
    Notice("Kotlin and kotlinx.coroutines", "JetBrains s.r.o. and Kotlin contributors",
        "Apache License 2.0", "https://www.apache.org/licenses/LICENSE-2.0"),
    Notice("Maps Compose", "Google LLC",
        "Apache License 2.0", "https://github.com/googlemaps/android-maps-compose/blob/main/LICENSE"),
    Notice("Maps SDK for Android", "Google LLC",
        "Google Maps Platform Terms of Service", "https://cloud.google.com/maps-platform/terms"),
    Notice("Google Sans Flex and Google Sans Code", "Google LLC",
        "SIL Open Font License 1.1", "https://openfontlicense.org"),
    Notice("Map data", "© OpenStreetMap contributors",
        "Open Database License 1.0", "https://www.openstreetmap.org/copyright"),
)

@Composable
fun LicensesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.ic_description), null) },
        title = { Text("Open source licenses") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                notices.forEach { n ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(n.url))) }
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                    ) {
                        Text(n.name, style = MaterialTheme.typography.bodyLarge)
                        Text("${n.holder} • ${n.license}", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
