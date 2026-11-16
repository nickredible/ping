package uk.maynotfunction.ping.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckTest {
    private fun release(tag: String, title: String = "", vararg assets: String) = JSONObject()
        .put("tag_name", tag)
        .put("name", title)
        .put("html_url", "https://github.com/nickredible/ping/releases/tag/$tag")
        .put("assets", JSONArray(assets.map { JSONObject().put("name", it) }))

    @Test fun comparesNumerically() {
        assertTrue(UpdateCheck.compare("2.10.0", "2.9.1") > 0)
        assertTrue(UpdateCheck.compare("2.7.0", "2.7.1") < 0)
        assertEquals(0, UpdateCheck.compare("v2.7", "2.7.0"))
        assertEquals(0, UpdateCheck.compare("1.5.0+name1", "1.5.0"))
    }

    @Test fun readsRelayVersionFromTheDeb() {
        val r = UpdateCheck.parse(release("v2.8.0", "Ping 2.8.0 / Ping Relay 1.7.0",
            "ping-2.8.0.apk", "uk.maynotfunction.pingrelay_1.7.1_iphoneos-arm64.deb"))!!
        assertEquals("2.8.0", r.appVersion)
        assertEquals("1.7.1", r.relayVersion)
        assertEquals("https://github.com/nickredible/ping/releases/tag/v2.8.0", r.url)
    }

    @Test fun fallsBackToTheTitle() {
        assertEquals("1.7.0", UpdateCheck.parse(release("v2.8.0", "Ping 2.8.0 / Ping Relay 1.7.0", "ping-2.8.0.apk"))!!.relayVersion)
        assertNull(UpdateCheck.parse(release("v2.8.0", "Ping 2.8.0"))!!.relayVersion)
    }

    @Test fun rejectsNonVersionTags() {
        assertNull(UpdateCheck.parse(release("nightly")))
        assertNull(UpdateCheck.parse(JSONObject()))
    }

    @Test fun flagsOnlyNewerComponents() {
        val latest = LatestRelease("2.8.0", "1.6.0", "u")
        assertEquals(AvailableUpdate("2.8.0", null), UpdateCheck.available(latest, "2.7.0", "1.6.0"))
        assertEquals(AvailableUpdate(null, "1.6.0"), UpdateCheck.available(latest, "2.8.0", "1.5.1"))
        assertFalse(UpdateCheck.available(latest, "2.9.0", "1.6.0").any)
    }

    @Test fun unknownRelayVersionIsNeverFlagged() {
        assertNull(UpdateCheck.available(LatestRelease("2.7.0", "9.0.0", "u"), "2.7.0", null).relayVersion)
    }
}
