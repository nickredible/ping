package uk.maynotfunction.ping.core

import org.json.JSONObject

/** The newest published GitHub release: the app version (its tag) and the Ping Relay .deb it ships. */
data class LatestRelease(val appVersion: String, val relayVersion: String?, val url: String)

/** What a check found newer than what's installed; null fields are up to date (or unknown). */
data class AvailableUpdate(val appVersion: String?, val relayVersion: String?) {
    val any: Boolean get() = appVersion != null || relayVersion != null
}

object UpdateCheck {
    const val LATEST_RELEASE_URL = "https://api.github.com/repos/nickredible/ping/releases/latest"

    private val DEB_ASSET = Regex("""^uk\.maynotfunction\.pingrelay_([0-9][0-9A-Za-z.+~-]*)_iphoneos-arm64\.deb$""")
    private val TITLE_RELAY = Regex("""Ping Relay\s+v?([0-9][0-9.]*)""")

    /** Parses GitHub's `releases/latest` response. Null if it isn't a usable release. */
    fun parse(json: JSONObject): LatestRelease? {
        val tag = json.optString("tag_name").removePrefix("v").trim()
        if (tag.isEmpty() || !tag[0].isDigit()) return null
        val assets = json.optJSONArray("assets")
        val fromAsset = assets?.let { arr ->
            (0 until arr.length()).firstNotNullOfOrNull { i ->
                DEB_ASSET.matchEntire(arr.optJSONObject(i)?.optString("name").orEmpty())?.groupValues?.get(1)
            }
        }
        val relay = fromAsset ?: TITLE_RELAY.find(json.optString("name"))?.groupValues?.get(1)
        val url = json.optString("html_url").ifBlank { "https://github.com/nickredible/ping/releases/latest" }
        return LatestRelease(tag, relay, url)
    }

    /** Which parts of [latest] are newer than the installed app and relay (null relay = unknown, never flagged). */
    fun available(latest: LatestRelease, installedApp: String, installedRelay: String?): AvailableUpdate =
        AvailableUpdate(
            appVersion = latest.appVersion.takeIf { compare(it, installedApp) > 0 },
            relayVersion = latest.relayVersion?.takeIf { installedRelay != null && compare(it, installedRelay) > 0 },
        )

    /**
     * Compares dotted numeric versions ("2.10.0" > "2.9.1"). Anything after `+`, `-` or `~` (a test
     * build's suffix, e.g. "1.5.0+name1") is ignored, and missing parts count as 0.
     */
    fun compare(a: String, b: String): Int {
        fun parts(v: String) = v.trim().removePrefix("v").split('+', '-', '~')[0]
            .split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val c = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }
}
