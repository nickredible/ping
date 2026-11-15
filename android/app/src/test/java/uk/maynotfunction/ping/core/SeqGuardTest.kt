package uk.maynotfunction.ping.core

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeqGuardTest {
    private fun msg(seq: Long?) = JSONObject().put("type", "friends_state").also { if (seq != null) it.put("seq", seq) }

    @Test fun nextStrictlyIncreases() {
        val guard = SeqGuard(0)
        val seqs = List(1000) { guard.next() }
        assertTrue(seqs.zipWithNext().all { (a, b) -> b > a })
    }

    @Test fun acceptsOnlyNewerRecentMessages() {
        val guard = SeqGuard(5 * 60_000L)
        val now = System.currentTimeMillis()
        assertTrue(guard.accept(msg(now)))
        assertFalse("replay", guard.accept(msg(now)))
        assertFalse("older", guard.accept(msg(now - 1)))
        assertTrue(guard.accept(msg(now + 1)))
    }

    @Test fun rejectsMissingSeqAndOutOfWindow() {
        val guard = SeqGuard(5 * 60_000L)
        val now = System.currentTimeMillis()
        assertFalse("missing seq", guard.accept(msg(null)))
        assertFalse("too old, e.g. replayed after a restart", guard.accept(msg(now - 10 * 60_000L)))
        assertFalse("too far in the future", guard.accept(msg(now + 10 * 60_000L)))
    }
}
