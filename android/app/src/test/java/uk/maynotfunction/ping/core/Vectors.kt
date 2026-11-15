package uk.maynotfunction.ping.core

import org.json.JSONArray
import org.json.JSONObject

/** The fmb1 test vectors shared with the iOS side, from `tools/vectors/fmb1.json`. */
object Vectors {
    val json: JSONObject by lazy {
        JSONObject(requireNotNull(javaClass.classLoader?.getResource("fmb1.json")) { "fmb1.json not on the test classpath" }.readText())
    }

    /** A relay pairing code for the vectors' dummy channel and key. */
    val config: PairingConfig by lazy {
        requireNotNull(PairingCode.parse("fmb1:https://ntfy.example.com:${json.getString("channelId")}:${json.getString("keyBase64")}"))
    }

    fun payload(direction: String): JSONObject = json.getJSONObject("payloads").getJSONObject(direction)

    /**
     * Compares JSON by value (keys in any order, 8 and 8.0 equal). The compile-time org.json is
     * Android's stub, which has no `similar()`.
     */
    fun sameJson(a: Any?, b: Any?): Boolean = canonical(a) == canonical(b)

    private fun canonical(x: Any?): Any? = when (x) {
        is JSONObject -> x.keys().asSequence().associateWith { canonical(x.get(it)) }.toSortedMap()
        is JSONArray -> (0 until x.length()).map { canonical(x.get(it)) }
        is Number -> x.toDouble()
        JSONObject.NULL -> null
        else -> x
    }
}
