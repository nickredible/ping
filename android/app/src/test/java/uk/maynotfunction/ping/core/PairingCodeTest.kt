package uk.maynotfunction.ping.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class PairingCodeTest {
    private val pairing = Vectors.json.getJSONObject("pairing")
    private val key = Base64.getDecoder().decode(Vectors.json.getString("keyBase64"))
    private val channel = Vectors.json.getString("channelId")

    @Test fun parsesSharedValidCodes() {
        val valid = pairing.getJSONArray("valid")
        for (i in 0 until valid.length()) {
            val v = valid.getJSONObject(i)
            val code = v.getString("code")
            val cfg = PairingCode.parse(code)
            assertNotNull("should parse: $code", cfg)
            cfg!!
            assertEquals(code, v.getString("relayUrl"), cfg.relayUrl)
            assertEquals(code, channel, cfg.channelId)
            assertArrayEquals(code, key, cfg.key)
            assertEquals(code, v.optString("lan").ifEmpty { null }, cfg.lanEndpoint)
            assertEquals(code, v.optString("token").ifEmpty { null }, cfg.relayToken)
            assertEquals(code, code.trim(), cfg.raw)
        }
    }

    @Test fun rejectsSharedInvalidCodes() {
        val invalid = pairing.getJSONArray("invalid")
        for (i in 0 until invalid.length()) {
            val code = invalid.getString(i)
            assertNull("should be rejected: $code", PairingCode.parse(code))
        }
        assertNull(PairingCode.parse(null))
    }

    @Test fun lanOnlyCodesDontUseTheRelay() {
        val cfg = PairingCode.parse("fmb1:none:$channel:${Vectors.json.getString("keyBase64")}?lan=10.0.0.5:8765")!!
        assertFalse(cfg.relayEnabled)
        assertTrue(Vectors.config.relayEnabled)
    }

    @Test fun recognisesThePublicRelay() {
        assertTrue(PairingCode.parse(PairingCode.generate("https://ntfy.sh/", null, null))!!.usesPublicRelay)
        assertFalse(Vectors.config.usesPublicRelay)
    }

    @Test fun buildRoundTrips() {
        val code = PairingCode.build("https://ntfy.example.com", channel, Vectors.json.getString("keyBase64"),
            "192.168.1.13:8765", "tk_abc")
        val cfg = PairingCode.parse(code)!!
        assertEquals("192.168.1.13:8765", cfg.lanEndpoint)
        assertEquals("tk_abc", cfg.relayToken)
        // Blank optional parts are left out entirely.
        assertEquals(Vectors.config.raw, PairingCode.build("https://ntfy.example.com", channel, cfg.keyBase64, " ", ""))
    }

    @Test fun withersKeepTheRestOfTheCode() {
        val cfg = PairingCode.parse(Vectors.config.withLanEndpoint("10.0.0.5:8765"))!!
            .let { PairingCode.parse(it.withRelayToken("tk_new"))!! }
            .let { PairingCode.parse(it.withRelayUrl("https://relay.example.org"))!! }
        assertEquals("https://relay.example.org", cfg.relayUrl)
        assertEquals("10.0.0.5:8765", cfg.lanEndpoint)
        assertEquals("tk_new", cfg.relayToken)
        assertArrayEquals(key, cfg.key)
        assertNull(PairingCode.parse(cfg.withLanEndpoint(null))!!.lanEndpoint)
    }

    @Test fun generateMakesFreshParseableCodes() {
        val a = PairingCode.parse(PairingCode.generate("https://ntfy.example.com/", null, "tk_x"))!!
        val b = PairingCode.parse(PairingCode.generate(null, "10.0.0.5:8765", null))!!
        assertEquals("https://ntfy.example.com", a.relayUrl) // trailing slash trimmed
        assertEquals(PairingCode.DEFAULT_RELAY, b.relayUrl)
        assertEquals(24, a.channelId.length)
        assertFalse(a.key.contentEquals(b.key))
        assertFalse(a.channelId == b.channelId)
    }

    @Test fun safeParamRules() {
        assertTrue(PairingCode.isSafeToken("tk_AbC-1.2"))
        assertFalse(PairingCode.isSafeToken("tk abc"))
        assertFalse(PairingCode.isSafeToken(""))
        assertFalse(PairingCode.isSafeToken("a".repeat(201)))
        assertTrue(PairingCode.isSafeLan("[fd7a::1]:8765"))
        assertFalse(PairingCode.isSafeLan("host/path"))
    }
}
