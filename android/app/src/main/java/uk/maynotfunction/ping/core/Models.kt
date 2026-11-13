package uk.maynotfunction.ping.core

import org.json.JSONArray
import org.json.JSONObject

/** A friend's location as reported by the relay iPhone (or a contacts preview). */
data class FriendLocation(
    val id: String,
    val handle: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val hAcc: Double,
    val timestampMs: Long,
    val address: String,
    val isPreview: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("handle", handle).put("name", name)
        .put("lat", lat).put("lon", lon).put("hAcc", hAcc)
        .put("timestampMs", timestampMs).put("address", address)

    companion object {
        fun fromJson(obj: JSONObject?): FriendLocation? {
            obj ?: return null
            val lat = obj.optDouble("lat", Double.NaN)
            val lon = obj.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) return null
            val id = obj.optString("id", "unknown")
            val handle = obj.optString("handle", id)
            return FriendLocation(
                id = id,
                handle = handle,
                name = obj.optString("name", handle),
                lat = lat,
                lon = lon,
                hAcc = obj.optDouble("hAcc", 10.0),
                timestampMs = obj.optLong("timestampMs", System.currentTimeMillis()),
                address = cleanAppleLabel(obj.optString("address", "")),
            )
        }

        private val appleLabel = Regex("^_\\\$!<(.+)>!\\\$_\$")

        /**
         * Apple stores built-in place labels in Contacts' internal form, e.g. `_$!<Home>!$_`
         * (Find My translates it for display). Returns `Home`; anything else is unchanged.
         */
        fun cleanAppleLabel(value: String): String {
            val m = appleLabel.matchEntire(value.trim()) ?: return value
            return m.groupValues[1].replaceFirstChar { it.titlecase() }
        }

        fun listFromJson(raw: String?): List<FriendLocation> {
            if (raw.isNullOrEmpty()) return emptyList()
            return runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { fromJson(arr.optJSONObject(it)) }
            }.getOrDefault(emptyList())
        }

        fun listToJson(list: List<FriendLocation>): String =
            JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()
    }
}

/** This phone's latest verified fix. */
data class MyFix(
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val provider: String,
    val timeMs: Long,
)

enum class Cadence(val label: String, val distanceM: Float, val heartbeatMs: Long) {
    Fast("Fast", 15f, 2 * 60_000L),
    Balanced("Balanced", 40f, 5 * 60_000L),
    Saver("Saver", 100f, 10 * 60_000L);

    companion object {
        fun fromOrdinal(i: Int) = entries.getOrElse(i) { Balanced }
    }
}

data class LogLine(val timeMs: Long, val message: String)
