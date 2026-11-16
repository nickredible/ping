package uk.maynotfunction.ping.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.maynotfunction.ping.core.LiveSession.Change

class LiveSessionTest {
    private val timeout = LiveSession.TIMEOUT_MS
    private val max = LiveSession.MAX_MS

    @Test fun startsOnFirstSignalOnly() {
        val s = LiveSession()
        assertEquals(Change.Started, s.onSignal(true, 1_000))
        assertEquals(Change.None, s.onSignal(true, 31_000)) // keep-alive
        assertTrue(s.active)
        assertEquals(1_000, s.startedMs)
    }

    @Test fun endsWhenTheIphoneSaysSo() {
        val s = LiveSession()
        s.onSignal(true, 0)
        assertEquals(Change.Ended, s.onSignal(false, 10_000))
        assertFalse(s.active)
        assertEquals(Change.None, s.onSignal(false, 11_000)) // a repeated stop is harmless
    }

    @Test fun keepAlivesHoldItOpen() {
        val s = LiveSession()
        s.onSignal(true, 0)
        var t = 0L
        repeat(10) {
            t += 30_000
            s.onSignal(true, t)
            assertEquals(Change.None, s.check(t + 5_000))
        }
        assertTrue(s.active)
    }

    @Test fun endsWhenKeepAlivesStop() {
        // The `false` was lost, or the iPhone went offline.
        val s = LiveSession()
        s.onSignal(true, 0)
        assertEquals(Change.None, s.check(timeout))
        assertEquals(Change.Ended, s.check(timeout + 1))
        assertEquals(Change.Started, s.onSignal(true, timeout + 60_000))
    }

    @Test fun cappedSessionsStayOffUntilANewOne() {
        val s = LiveSession()
        var t = 0L
        s.onSignal(true, t)
        while (t < max) { t += 30_000; s.onSignal(true, t) }
        assertEquals(Change.Ended, s.check(t))
        // Keep-alives for the same session don't restart it...
        t += 30_000
        assertEquals(Change.None, s.onSignal(true, t))
        assertFalse(s.active)
        // ...but one after the iPhone ended it, or after a long gap, does.
        s.onSignal(false, t + 1_000)
        assertEquals(Change.Started, s.onSignal(true, t + 2_000))
        s.reset()
        s.onSignal(true, 0)
        while (t < 2 * max) { t += 30_000; s.onSignal(true, t) }
        s.check(t)
        assertEquals(Change.Started, s.onSignal(true, t + timeout + 1))
    }

    @Test fun checkWithoutASessionDoesNothing() {
        assertEquals(Change.None, LiveSession().check(Long.MAX_VALUE))
    }
}
