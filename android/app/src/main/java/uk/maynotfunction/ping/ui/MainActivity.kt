package uk.maynotfunction.ping.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.maynotfunction.ping.service.LocationService
import uk.maynotfunction.ping.ui.onboarding.OnboardingScreen
import uk.maynotfunction.ping.ui.theme.BridgeTheme

private enum class Screen { Loading, Setup, Main }

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            BridgeTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                val screen = when {
                    !state.settings.loaded -> Screen.Loading
                    !state.settings.onboardingDone -> Screen.Setup
                    else -> Screen.Main
                }
                Crossfade(screen, label = "screen") { current ->
                    when (current) {
                        // Plain background for the moment before settings load, so setup never flashes.
                        Screen.Loading -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer))
                        Screen.Setup -> OnboardingScreen(viewModel)
                        Screen.Main -> BridgeScreen(viewModel)
                    }
                }
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
