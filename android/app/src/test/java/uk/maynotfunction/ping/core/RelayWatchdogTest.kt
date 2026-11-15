package uk.maynotfunction.ping.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayWatchdogTest {
    private val quiet = RelayWatchdog.QUIET_AFTER_MS

    @Test fun nothingUnansweredIsNeverQuiet() {
        assertFalse(RelayWatchdog.isQuiet(0L, Long.MAX_VALUE))
    }

    @Test fun quietOnlyAfterTheThreshold() {
        val sent = 1_000_000L
        assertFalse(RelayWatchdog.isQuiet(sent, sent + quiet - 1))
        assertTrue(RelayWatchdog.isQuiet(sent, sent + quiet))
    }

    @Test fun firstRequestStartsTheClock() {
        assertEquals(500L, RelayWatchdog.afterRequest(unansweredSinceMs = 0L, lastDownlinkMs = 100L, sentAtMs = 500L))
    }

    @Test fun laterRequestsKeepTheOldestUnanswered() {
        // Asking again every few minutes mustn't keep pushing the alarm back.
        assertEquals(500L, RelayWatchdog.afterRequest(unansweredSinceMs = 500L, lastDownlinkMs = 100L, sentAtMs = 900L))
    }

    @Test fun replyThatBeatTheRelaysResponseCountsAsAnswered() {
        // Sent at 500, the iPhone's reply arrived at 600, the relay's HTTP 200 only after that.
        assertEquals(0L, RelayWatchdog.afterRequest(unansweredSinceMs = 0L, lastDownlinkMs = 600L, sentAtMs = 500L))
    }

    @Test fun silentIphoneScenario() {
        // Healthy: ask every 15 min, each answered within seconds.
        var unanswered = 0L
        var lastDownlink = 0L
        var t = 0L
        repeat(8) {
            unanswered = RelayWatchdog.afterRequest(unanswered, lastDownlink, t)
            lastDownlink = t + 2_000; unanswered = 0L // reply
            t += 15 * 60_000L
            assertFalse(RelayWatchdog.isQuiet(unanswered, t))
        }
        // The iPhone dies: requests keep getting through to the relay, nothing comes back.
        val diedAt = t
        while (t < diedAt + quiet) {
            unanswered = RelayWatchdog.afterRequest(unanswered, lastDownlink, t)
            t += 10 * 60_000L
        }
        assertEquals(diedAt, unanswered)
        assertTrue(RelayWatchdog.isQuiet(unanswered, t))
    }
}
