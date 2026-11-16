package uk.maynotfunction.ping.core

import java.security.SecureRandom
import java.util.Base64

/**
 * A parsed `fmb1` pairing code:
 * `fmb1:<relayUrl>:<channelId>:<base64Key>[?lan=<host:port>][&token=<relay access token>]`
 * A relayUrl of `none` means LAN/VPN only: no relay, and a `lan` address is required.
 *
 * Wire-compatible with the iOS tweak (`ios-tweak/FMBShared.h`).
 */
class PairingConfig(
    val relayUrl: String,
    val channelId: String,
    val key: ByteArray,
    val lanEndpoint: String?,
    val relayToken: String?,
    val raw: String,
) {
    val keyBase64: String get() = Base64.getEncoder().encodeToString(key)

    /** False for a LAN/VPN-only code: everything goes over [lanEndpoint]. */
    val relayEnabled: Boolean get() = relayUrl != PairingCode.NO_RELAY

    /** The public ntfy.sh, which only refills an anonymous client's allowance every ~5 s. */
    val usesPublicRelay: Boolean get() = relayUrl.trimEnd('/') == PairingCode.DEFAULT_RELAY

    fun withRelayUrl(url: String) = PairingCode.build(url, channelId, keyBase64, lanEndpoint, relayToken)
    fun withLanEndpoint(lan: String?) = PairingCode.build(relayUrl, channelId, keyBase64, lan, relayToken)
    fun withRelayToken(token: String?) = PairingCode.build(relayUrl, channelId, keyBase64, lanEndpoint, token)
}

object PairingCode {
    const val DEFAULT_RELAY = "https://ntfy.sh"
    /** relayUrl of a LAN/VPN-only pairing code. */
    const val NO_RELAY = "none"
    private val rng = SecureRandom()

    /** lan/token values end up in URLs and headers, so only conservative characters are allowed. */
    fun isSafeParam(value: String, extraAllowed: String): Boolean =
        value.isNotEmpty() && value.length <= 200 &&
            value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in extraAllowed }

    fun isSafeToken(value: String) = isSafeParam(value, "._-")
    fun isSafeLan(value: String) = isSafeParam(value, ".:-[]")

    fun build(relayUrl: String, channelId: String, keyB64: String, lanEndpoint: String?, relayToken: String?): String =
        buildString {
            append("fmb1:").append(relayUrl).append(':').append(channelId).append(':').append(keyB64)
            var sep = '?'
            lanEndpoint?.trim()?.takeIf { it.isNotEmpty() }?.let { append(sep).append("lan=").append(it); sep = '&' }
            relayToken?.trim()?.takeIf { it.isNotEmpty() }?.let { append(sep).append("token=").append(it) }
        }

    fun parse(code: String?): PairingConfig? {
        val trimmed = code?.trim() ?: return null
        if (!trimmed.startsWith("fmb1:")) return null

        var body = trimmed.substring(5)
        var lan: String? = null
        var token: String? = null
        val q = body.indexOf('?')
        if (q >= 0) {
            val query = body.substring(q + 1)
            body = body.substring(0, q)
            for (pair in query.split('&')) {
                val eq = pair.indexOf('=')
                if (eq < 0) continue
                val k = pair.substring(0, eq)
                val v = pair.substring(eq + 1).trim()
                if (v.isEmpty()) continue
                when (k) {
                    "lan" -> if (isSafeLan(v)) lan = v else return null
                    "token" -> if (isSafeToken(v)) token = v else return null
                }
            }
        }

        val lastColon = body.lastIndexOf(':')
        if (lastColon <= 0) return null
        val keyB64 = body.substring(lastColon + 1)
        val prefix = body.substring(0, lastColon)
        val prevColon = prefix.lastIndexOf(':')
        if (prevColon <= 0) return null
        val channelId = prefix.substring(prevColon + 1)
        val relayUrl = prefix.substring(0, prevColon)

        val normalized = keyB64.replace('-', '+').replace('_', '/').let { it + "=".repeat((4 - it.length % 4) % 4) }
        val key = runCatching { Base64.getDecoder().decode(normalized) }.getOrNull() ?: return null
        if (key.size != 32 || channelId.length < 16) return null
        if (if (relayUrl == NO_RELAY) lan == null else relayUrl.length < 8) return null
        return PairingConfig(relayUrl, channelId, key, lan, token, trimmed)
    }

    fun generate(relayUrl: String?, lanEndpoint: String?, relayToken: String?): String {
        val base = relayUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: DEFAULT_RELAY
        val channel = ByteArray(12).also(rng::nextBytes).joinToString("") { "%02x".format(it) }
        val key = ByteArray(32).also(rng::nextBytes)
        return build(base, channel, Base64.getEncoder().encodeToString(key), lanEndpoint, relayToken)
    }
}
