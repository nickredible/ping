package uk.maynotfunction.ping.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import uk.maynotfunction.ping.service.LocationService
import uk.maynotfunction.ping.ui.theme.BridgeTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            BridgeTheme {
                BridgeScreen(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /**
     * Supports `am start ... -a ACTION_PUBLISH_NOW` for scripting/testing. The old app also took a
     * `pairing_code` extra; that's gone because any app could use it to redirect your location.
     */
    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            LocationService.ACTION_PUBLISH_NOW -> viewModel.syncNow()
            LocationService.ACTION_REFRESH_FRIENDS -> viewModel.refreshFriends()
        }
    }
}
