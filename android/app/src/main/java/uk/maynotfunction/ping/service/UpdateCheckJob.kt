package uk.maynotfunction.ping.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import uk.maynotfunction.ping.BuildConfig
import uk.maynotfunction.ping.R
import uk.maynotfunction.ping.bridgeApp
import uk.maynotfunction.ping.core.AvailableUpdate
import uk.maynotfunction.ping.core.LatestRelease
import uk.maynotfunction.ping.core.UpdateCheck
import java.net.HttpURLConnection
import java.net.URL

/**
 * Once a day, asks GitHub for the latest Ping release and notifies (once per version) if it has a
 * newer Ping, or a newer Ping Relay than the relay iPhone last reported.
 */
class UpdateCheckJob : JobService() {
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        work = bridgeApp.appScope.launch {
            val ok = try {
                check()
                true
            } catch (e: CancellationException) {
                throw e // stopped by the system; onStopJob already asked for a retry
            } catch (e: Exception) {
                bridgeApp.repository.log("Update check failed: ${e.message ?: e.javaClass.simpleName}")
                false
            }
            jobFinished(params, !ok)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        return true
    }

    private suspend fun check() {
        val repo = bridgeApp.repository
        val latest = withContext(Dispatchers.IO) { fetchLatest() } ?: return
        repo.setLatestRelease(latest)
        val installedRelay = repo.awaitSettings().relayVersion.ifBlank { null }
        val update = UpdateCheck.available(latest, BuildConfig.VERSION_NAME, installedRelay)
        val fresh = repo.claimUnnotified(update)
        if (fresh.any) {
            repo.log("Update available: ${describe(fresh)}")
            notify(fresh, installedRelay, latest.url)
        }
    }

    private fun fetchLatest(): LatestRelease? {
        val conn = (URL(UpdateCheck.LATEST_RELEASE_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Ping/${BuildConfig.VERSION_NAME}")
        }
        try {
            val code = conn.responseCode
            if (code == 404) return null // no releases published yet
            if (code !in 200..299) error("HTTP $code")
            return UpdateCheck.parse(JSONObject(conn.inputStream.bufferedReader().use { it.readText() }))
        } finally {
            conn.disconnect()
        }
    }

    private fun notify(update: AvailableUpdate, installedRelay: String?, url: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Tells you when a new Ping or Ping Relay is released on GitHub."
            }
        )
        val title = "${describe(update)} ${if (update.appVersion != null && update.relayVersion != null) "are" else "is"} available"
        val text = listOfNotNull(
            update.appVersion?.let { "This phone has Ping ${BuildConfig.VERSION_NAME}." },
            update.relayVersion?.let { "The relay iPhone has Ping Relay $installedRelay." },
            "Tap to open the release on GitHub.",
        ).joinToString(" ")
        val open = PendingIntent.getActivity(this, 4, Intent(Intent.ACTION_VIEW, Uri.parse(url)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        nm.notify(NOTIFICATION_ID, Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setShowWhen(true)
            .build())
    }

    private fun describe(u: AvailableUpdate) =
        listOfNotNull(u.appVersion?.let { "Ping $it" }, u.relayVersion?.let { "Ping Relay $it" }).joinToString(" and ")

    companion object {
        private const val JOB_ID = 4300
        private const val CHANNEL_ID = "app_updates"
        private const val NOTIFICATION_ID = 4203
        private const val INTERVAL_MS = 24 * 60 * 60_000L

        /** Schedules the daily check unless it's already scheduled (survives reboots). */
        fun schedule(context: Context) {
            val js = context.getSystemService(JobScheduler::class.java)
            if (js.getPendingJob(JOB_ID)?.intervalMillis == INTERVAL_MS) return
            js.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, UpdateCheckJob::class.java))
                .setPeriodic(INTERVAL_MS)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build())
        }
    }
}
