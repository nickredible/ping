package uk.maynotfunction.ping.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class EnvelopeTest {
    private val cfg = Vectors.config

    @Test fun decryptsEnvelopesFromBothSides() {
        val envelopes = Vectors.json.getJSONArray("envelopes")
        val seen = mutableSetOf<String>()
        for (i in 0 until envelopes.length()) {
            val e = envelopes.getJSONObject(i)
            val direction = e.getString("payload")
            val label = "${e.getString("from")} $direction"
            val payload = Envelope.decrypt(e.getJSONObject("envelope"), direction, cfg)
            assertNotNull("$label should decrypt", payload)
            assertTrue("$label payload should match", Vectors.sameJson(payload, Vectors.payload(direction)))
            seen += label
        }
        assertEquals(setOf("ios up", "ios down", "android up", "android down"), seen)
    }

    @Test fun roundTripsWithAFreshIvEachTime() {
        val a = JSONObject(Envelope.encrypt(Vectors.payload("up"), Envelope.UP, cfg))
        val b = JSONObject(Envelope.encrypt(Vectors.payload("up"), Envelope.UP, cfg))
        assertEquals(1, a.getInt("v"))
        assertEquals("up", a.getString("dir"))
        assertEquals(12, Base64.getDecoder().decode(a.getString("iv")).size)
        assertEquals(16, Base64.getDecoder().decode(a.getString("tag")).size)
        assertTrue(a.getString("iv") != b.getString("iv"))
        assertTrue(Vectors.sameJson(Envelope.decrypt(a, Envelope.UP, cfg), Vectors.payload("up")))
    }

    @Test fun directionIsBound() {
        val up = JSONObject(Envelope.encrypt(Vectors.payload("up"), Envelope.UP, cfg))
        assertNull(Envelope.decrypt(up, Envelope.DOWN, cfg))
        // Relabelling dir doesn't help: the AAD still says "up".
        assertNull(Envelope.decrypt(JSONObject(up.toString()).put("dir", "down"), Envelope.DOWN, cfg))
    }

    @Test fun channelIsBound() {
        val up = JSONObject(Envelope.encrypt(Vectors.payload("up"), Envelope.UP, cfg))
        val sameKeyOtherChannel = PairingCode.parse(
            "fmb1:https://ntfy.example.com:ffffffffffffffffffffffff:${Vectors.json.getString("keyBase64")}")!!
        assertNull(Envelope.decrypt(up, Envelope.UP, sameKeyOtherChannel))
    }

    @Test fun wrongKeyFails() {
        val up = JSONObject(Envelope.encrypt(Vectors.payload("up"), Envelope.UP, cfg))
        val other = PairingCode.parse(PairingCode.generate(null, null, null))!!
        val otherKeySameChannel = PairingCode.parse(
            PairingCode.build(cfg.relayUrl, cfg.channelId, other.keyBase64, null, null))!!
        assertNull(Envelope.decrypt(up, Envelope.UP, otherKeySameChannel))
    }

    @Test fun tamperingIsRejected() {
        val env = JSONObject(Envelope.encrypt(Vectors.payload("down"), Envelope.DOWN, cfg))
        for (field in listOf("ct", "tag", "iv")) {
            val bytes = Base64.getDecoder().decode(env.getString(field))
            bytes[0] = (bytes[0].toInt() xor 1).toByte()
            val tampered = JSONObject(env.toString()).put(field, Base64.getEncoder().encodeToString(bytes))
            assertNull("flipped bit in $field", Envelope.decrypt(tampered, Envelope.DOWN, cfg))
        }
    }

    @Test fun malformedEnvelopesReturnNull() {
        assertNull(Envelope.decrypt(JSONObject(), Envelope.UP, cfg))
        assertNull(Envelope.decrypt(JSONObject().put("dir", "up").put("iv", "!!").put("ct", "x").put("tag", "y"), Envelope.UP, cfg))
        val env = JSONObject(Envelope.encrypt(Vectors.payload("up"), Envelope.UP, cfg))
        assertNull(Envelope.decrypt(JSONObject(env.toString()).put("iv", "AAAA"), Envelope.UP, cfg)) // wrong IV length
    }
}
