package uk.maynotfunction.ping.core

/**
 * Decides when the relay iPhone counts as quiet: Ping asked it for friends, the request got
 * through, and nothing has come back for [QUIET_AFTER_MS].
 *
 * Every request for friends gets a reply on the downlink, and the service asks at least every
 * 15 minutes while sharing, so a healthy iPhone never stays silent this long. Requests that never
 * left this phone (no signal, relay down) don't count, so being offline doesn't raise the alarm.
 */
object RelayWatchdog {
    const val QUIET_AFTER_MS = 30 * 60_000L

    /**
     * The new "unanswered since" time after a request sent at [sentAtMs] got through. Keeps an
     * older unanswered request, and ignores a request whose reply already arrived (the reply can
     * beat the relay's own HTTP response back).
     */
    fun afterRequest(unansweredSinceMs: Long, lastDownlinkMs: Long, sentAtMs: Long): Long = when {
        unansweredSinceMs > 0 -> unansweredSinceMs
        lastDownlinkMs >= sentAtMs -> 0L
        else -> sentAtMs
    }

    fun isQuiet(unansweredSinceMs: Long, nowMs: Long): Boolean =
        unansweredSinceMs > 0 && nowMs - unansweredSinceMs >= QUIET_AFTER_MS
}
