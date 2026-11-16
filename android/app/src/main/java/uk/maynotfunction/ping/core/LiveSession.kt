package uk.maynotfunction.ping.core

/**
 * A "someone's viewing your location" session, driven by the relay iPhone's `live` messages:
 * `active: true` when Find My starts streaming and every ~30 s while it does, `active: false`
 * when it stops. The session also ends if the keep-alives stop for [timeoutMs] (a lost `false`
 * mustn't leave GPS running), and after [maxMs] at most. A capped session stays off until the
 * iPhone ends it or goes quiet long enough to count as a new one.
 */
class LiveSession(
    private val timeoutMs: Long = TIMEOUT_MS,
    private val maxMs: Long = MAX_MS,
) {
    enum class Change { Started, Ended, None }

    var active = false
        private set
    /** When the current (or last) session started. */
    var startedMs = 0L
        private set
    private var lastSignalMs = Long.MIN_VALUE / 2
    private var capped = false

    /** [viewing] is the message's `active` flag. */
    fun onSignal(viewing: Boolean, nowMs: Long): Change {
        if (!viewing) {
            lastSignalMs = Long.MIN_VALUE / 2
            capped = false
            return end()
        }
        if (nowMs - lastSignalMs > timeoutMs) capped = false
        lastSignalMs = nowMs
        if (active || capped) return Change.None
        active = true
        startedMs = nowMs
        return Change.Started
    }

    /** Ends a session whose keep-alives stopped or that hit [maxMs]. */
    fun check(nowMs: Long): Change = when {
        !active -> Change.None
        nowMs - lastSignalMs > timeoutMs -> end()
        nowMs - startedMs >= maxMs -> { capped = true; end() }
        else -> Change.None
    }

    fun reset() {
        active = false
        lastSignalMs = Long.MIN_VALUE / 2
        capped = false
    }

    private fun end(): Change {
        if (!active) return Change.None
        active = false
        return Change.Ended
    }

    companion object {
        const val TIMEOUT_MS = 150_000L
        const val MAX_MS = 30 * 60_000L
    }
}
