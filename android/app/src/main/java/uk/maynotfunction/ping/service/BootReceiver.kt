package uk.maynotfunction.ping.service

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import uk.maynotfunction.ping.bridgeApp
import kotlinx.coroutines.launch

/**
 * Starts sharing again after the phone restarts or Ping is updated, if it was on. Both broadcasts
 * are allowed to start a foreground service from the background, but a location one also needs
 * "Allow all the time", so without that it waits for Ping to be opened, as before.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reason = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> "restart"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "update"
            else -> return
        }
        val app = context.bridgeApp
        val pending = goAsync()
        app.appScope.launch {
            try {
                val settings = app.repository.awaitSettings()
                if (!settings.onboardingDone || !settings.sharingEnabled) return@launch
                if (context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    app.repository.log("Not restarting sharing after $reason: needs \"Allow all the time\"")
                    return@launch
                }
                runCatching { LocationService.send(context, LocationService.ACTION_START) }
                    .onSuccess { app.repository.log("Sharing restarted after $reason") }
                    .onFailure { app.repository.log("Couldn't restart sharing after $reason: ${it.message}") }
            } finally {
                pending.finish()
            }
        }
    }
}
