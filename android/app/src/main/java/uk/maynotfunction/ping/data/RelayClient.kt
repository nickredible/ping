package uk.maynotfunction.ping.data

import android.content.Context
import android.location.Location
import android.net.ConnectivityManager
import android.os.BatteryManager
import uk.maynotfunction.ping.core.Envelope
import uk.maynotfunction.ping.core.PairingConfig
import uk.maynotfunction.ping.core.SeqGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to the relay iPhone: encrypted uplink over an ntfy-style HTTPS relay
 * (`<relay>/fmb-<channel>-up`) or a direct LAN/VPN link (`http://<lan>/v1/sync`), and a
 * streaming subscription to the downlink topic (`<relay>/fmb-<channel>-down/json`).
 *
 * The downlink is one long-lived connection rather than polling: ntfy.sh only refills an
 * anonymous client's request allowance at about one request per 5 s, so polling every few
 * seconds runs it dry and gets HTTP 429. Both directions back off when the relay says so.
 */
class RelayClient(context: Context, private val repo: BridgeRepository) {
    private val appContext = context.applicationContext
    @Volatile private var lastDownlinkTimeSec = 0L
    /** Uploads to the relay are skipped until this time after a 429. */
    @Volatile private var relayBlockedUntilMs = 0L

    companion object {
        private const val MAX_DOWNLINK_AGE_MS = 5 * 60_000L
        private const val MIN_BACKOFF_MS = 5_000L
        private const val MAX_BACKOFF_MS = 5 * 60_000L
        private const val DEFAULT_RETRY_AFTER_MS = 60_000L
        // Shared across instances: seq must keep increasing for the app's lifetime.
        private val upSeq = SeqGuard(0)
        private val downSeq = SeqGuard(MAX_DOWNLINK_AGE_MS)
    }

    private class HttpResult(val code: Int, val retryAfterMs: Long?, val error: String?)

    enum class CheckResult { Ok, Rejected, Unreachable, Skipped }

    /** Outcome of [checkConnection]; the details are short, user-facing reasons. */
    class ConnectionCheck(
        val relay: CheckResult,
        val relayDetail: String?,
        val lan: CheckResult,
        val lanDetail: String?,
    )

    private suspend fun pairing(): PairingConfig? = repo.awaitSettings().pairing

    suspend fun publishLocation(location: Location, requestFriendsRefresh: Boolean): Boolean {
        val cfg = pairing() ?: run { repo.recordUploadFailure("Invalid pairing code"); return false }
        val now = System.currentTimeMillis()
        val payload = JSONObject()
            .put("type", "location_update")
            .put("seq", upSeq.next())
            .put("lat", location.latitude)
            .put("lon", location.longitude)
            .put("hAcc", location.accuracy.toDouble())
            .put("vAcc", if (location.hasVerticalAccuracy()) location.verticalAccuracyMeters.toDouble() else 8.0)
            .put("alt", if (location.hasAltitude()) location.altitude else 0.0)
            .put("speed", if (location.hasSpeed()) location.speed.toDouble() else 0.0)
            .put("course", if (location.hasBearing()) location.bearing.toDouble() else -1.0)
            // Capture time of the fix itself, so the iPhone's 120 s freshness check is meaningful.
            .put("fixTimeMs", if (location.time > 0) location.time else now)
            .put("sentTimeMs", now)
            .put("provider", location.provider ?: "gps")
            .put("isMock", false)
            .put("batteryPct", batteryPercent())
            .put("requestFriendsRefresh", requestFriendsRefresh)
        return sendUp(cfg, payload, recordResult = true)
    }

    suspend fun sendRefreshRequest(): Boolean {
        val cfg = pairing() ?: return false
        val payload = JSONObject()
            .put("type", "refresh_request")
            .put("seq", upSeq.next())
            .put("requestFriendsRefresh", true)
            .put("sentTimeMs", System.currentTimeMillis())
        return sendUp(cfg, payload, recordResult = false)
    }

    /**
     * One-off check for the setup screen. Sends a refresh request to the relay and, if the
     * pairing code has one, to the LAN address. A LAN reply proves the iPhone holds the same key.
     * The relay only proves the server and token work: the iPhone's answer arrives on the downlink.
     */
    suspend fun checkConnection(): ConnectionCheck = withContext(Dispatchers.IO) {
        val cfg = pairing() ?: return@withContext ConnectionCheck(
            CheckResult.Skipped, "No pairing code", CheckResult.Skipped, null)
        fun refreshEnvelope() = Envelope.encrypt(
            JSONObject()
                .put("type", "refresh_request")
                .put("seq", upSeq.next())
                .put("requestFriendsRefresh", true)
                .put("sentTimeMs", System.currentTimeMillis()),
            Envelope.UP, cfg,
        )

        val (lan, lanDetail) = cfg.lanEndpoint?.let { endpoint ->
            runCatching { lanSync(cfg, endpoint, refreshEnvelope()) }.fold(
                onSuccess = { (code, payload) ->
                    when {
                        code == 401 -> CheckResult.Rejected to "The iPhone doesn't recognise this code"
                        code !in 200..299 -> CheckResult.Unreachable to "HTTP $code"
                        payload == null -> CheckResult.Rejected to "Couldn't decrypt the iPhone's reply"
                        else -> CheckResult.Ok to null
                    }
                },
                onFailure = { CheckResult.Unreachable to (it.message ?: it.javaClass.simpleName) },
            )
        } ?: (CheckResult.Skipped to null)

        val (relay, relayDetail) = if (!cfg.relayEnabled) CheckResult.Skipped to "Not used" else runCatching {
            post("${cfg.relayUrl}/fmb-${cfg.channelId}-up", refreshEnvelope(), cfg.relayToken, 8_000)
        }.fold(
            onSuccess = { r ->
                when {
                    r.code in 200..299 -> CheckResult.Ok to null
                    r.code == 401 || r.code == 403 -> CheckResult.Rejected to "Access token rejected (HTTP ${r.code})"
                    r.code == 429 -> CheckResult.Ok to "Reachable, but rate-limited right now"
                    else -> CheckResult.Unreachable to "HTTP ${r.code}${r.error?.let { ": $it" } ?: ""}"
                }
            },
            onFailure = { CheckResult.Unreachable to (it.message ?: it.javaClass.simpleName) },
        )
        ConnectionCheck(relay, relayDetail, lan, lanDetail)
    }

    private suspend fun sendUp(cfg: PairingConfig, payload: JSONObject, recordResult: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            val envelope = Envelope.encrypt(payload, Envelope.UP, cfg)
            val sentAt = System.currentTimeMillis()
            val asksForFriends = payload.optBoolean("requestFriendsRefresh")
            cfg.lanEndpoint?.let { lan ->
                // A LAN/VPN reply carries the friends, so there's nothing left unanswered.
                if (tryLanSync(cfg, lan, envelope)) {
                    if (recordResult) repo.recordUploadSuccess("LAN/VPN")
                    return@withContext true
                }
            }

            if (!cfg.relayEnabled) {
                // LAN/VPN only: with a network up, an iPhone that doesn't answer is the quiet one.
                if (asksForFriends && hasNetwork()) repo.recordFriendsRequest(sentAt)
                if (recordResult) repo.recordUploadFailure(
                    cfg.lanEndpoint?.let { "Relay iPhone not reachable at $it" } ?: "No LAN/VPN address set")
                return@withContext false
            }

            val now = System.currentTimeMillis()
            if (now < relayBlockedUntilMs) {
                if (recordResult) repo.recordUploadFailure("Relay rate limit, retrying in ${(relayBlockedUntilMs - now) / 1000}s")
                return@withContext false
            }

            val result = runCatching {
                post("${cfg.relayUrl}/fmb-${cfg.channelId}-up", envelope, cfg.relayToken, 8_000)
            }.getOrElse {
                if (recordResult) repo.recordUploadFailure("${it.javaClass.simpleName}: ${it.message}")
                return@withContext false
            }
            when {
                result.code in 200..299 -> {
                    if (asksForFriends) repo.recordFriendsRequest(sentAt)
                    if (recordResult) repo.recordUploadSuccess("internet relay")
                    true
                }
                result.code == 429 -> {
                    relayBlockedUntilMs = now + (result.retryAfterMs ?: DEFAULT_RETRY_AFTER_MS)
                    repo.recordUploadFailure("Relay rate limit (429): ${result.error ?: "too many requests"}")
                    false
                }
                else -> {
                    if (recordResult) repo.recordUploadFailure("HTTP ${result.code} from relay${result.error?.let { ": $it" } ?: ""}")
                    false
                }
            }
        }

    /**
     * Subscribes to the downlink topic until cancelled, reconnecting with exponential backoff
     * (or the relay's Retry-After). [onLocateRequest] runs when the iPhone asks for a fresh fix.
     */
    suspend fun streamDownlink(onLocateRequest: suspend () -> Unit) {
        var backoff = MIN_BACKOFF_MS
        var lastError: String? = null
        while (currentCoroutineContext().isActive) {
            val cfg = pairing()
            if (cfg == null) {
                delay(10_000)
                continue
            }
            val end = if (cfg.relayEnabled) streamOnce(cfg, onLocateRequest) else streamLanOnce(cfg, onLocateRequest)
            if (end.connected) {
                backoff = MIN_BACKOFF_MS
                lastError = null
            } else {
                if (end.error != null && end.error != lastError) repo.log("Downlink: ${end.error}")
                lastError = end.error
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
            delay(end.retryAfterMs ?: backoff)
        }
    }

    private class StreamEnd(val connected: Boolean, val retryAfterMs: Long?, val error: String?)

    private suspend fun streamOnce(cfg: PairingConfig, onLocateRequest: suspend () -> Unit): StreamEnd = coroutineScope {
        val since = if (lastDownlinkTimeSec > 0) lastDownlinkTimeSec.toString() else "60s"
        val conn = (URL("${cfg.relayUrl}/fmb-${cfg.channelId}-down/json?since=$since")
            .openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 100_000 // ntfy sends a keepalive event every ~45 s
            cfg.relayToken?.let { setRequestProperty("Authorization", "Bearer $it") }
        }
        // Blocking socket reads ignore coroutine cancellation; closing the connection unblocks them.
        val closer = launch { try { awaitCancellation() } finally { conn.disconnect() } }
        try {
            withContext(Dispatchers.IO) {
                val code = conn.responseCode
                if (code !in 200..299) {
                    val r = readError(conn, code)
                    return@withContext StreamEnd(false, r.retryAfterMs,
                        "HTTP $code${r.error?.let { ": $it" } ?: ""}")
                }
                conn.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        handleDownlinkLine(line, cfg, onLocateRequest)
                    }
                }
                StreamEnd(true, null, null)
            }
        } catch (e: Exception) {
            StreamEnd(false, null, e.message ?: e.javaClass.simpleName)
        } finally {
            closer.cancel()
            conn.disconnect()
        }
    }

    /**
     * LAN/VPN-only downlink: holds `POST /v1/stream` open on the iPhone. The body is an encrypted
     * `subscribe` message, which proves the key; the reply is one encrypted envelope per line
     * (starting with the current friends) plus `{"event":"keepalive"}` lines every ~45 s.
     */
    private suspend fun streamLanOnce(cfg: PairingConfig, onLocateRequest: suspend () -> Unit): StreamEnd = coroutineScope {
        val lan = cfg.lanEndpoint ?: return@coroutineScope StreamEnd(false, null, "No LAN/VPN address set")
        val conn = (URL("http://$lan/v1/stream").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5_000
            readTimeout = 100_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        val closer = launch { try { awaitCancellation() } finally { conn.disconnect() } }
        try {
            withContext(Dispatchers.IO) {
                val subscribe = JSONObject()
                    .put("type", "subscribe")
                    .put("seq", upSeq.next())
                    .put("sentTimeMs", System.currentTimeMillis())
                conn.outputStream.use { it.write(Envelope.encrypt(subscribe, Envelope.UP, cfg).toByteArray()) }
                val code = conn.responseCode
                if (code !in 200..299) {
                    return@withContext StreamEnd(false, null,
                        if (code == 401) "Relay iPhone rejected the pairing code" else "HTTP $code from the relay iPhone")
                }
                conn.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        handleDownlinkLine(line, cfg, onLocateRequest)
                    }
                }
                StreamEnd(true, null, null)
            }
        } catch (e: Exception) {
            StreamEnd(false, null, e.message ?: e.javaClass.simpleName)
        } finally {
            closer.cancel()
            conn.disconnect()
        }
    }

    private suspend fun handleDownlinkLine(line: String, cfg: PairingConfig, onLocateRequest: suspend () -> Unit) {
        val outer = runCatching { JSONObject(line.trim()) }.getOrNull() ?: return
        if (outer.optString("event", "message") != "message") return // open / keepalive
        val t = outer.optLong("time")
        if (t > 0 && t >= lastDownlinkTimeSec) lastDownlinkTimeSec = t + 1
        val env = when {
            outer.has("ct") -> outer
            outer.optString("message").startsWith("{") -> runCatching { JSONObject(outer.getString("message")) }.getOrNull()
            else -> null
        } ?: return
        val payload = Envelope.decrypt(env, Envelope.DOWN, cfg) ?: return
        if (!downSeq.accept(payload)) return
        if (payload.optString("type") == "locate_request") {
            repo.log("Relay iPhone asked for a fresh fix")
            onLocateRequest()
        }
        repo.applyDownlink(payload)
    }

    private suspend fun tryLanSync(cfg: PairingConfig, lan: String, envelope: String): Boolean =
        runCatching { lanSync(cfg, lan, envelope).first in 200..299 }.getOrDefault(false)

    /**
     * POSTs [envelope] to the iPhone's `/v1/sync` and applies the reply. Returns the HTTP code and
     * the decrypted reply (null if it didn't decrypt); throws if the iPhone can't be reached.
     */
    private suspend fun lanSync(cfg: PairingConfig, lan: String, envelope: String): Pair<Int, JSONObject?> {
        val conn = (URL("http://$lan/v1/sync").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 2_500
            readTimeout = 3_500
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(envelope.toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) return code to null
            val body = conn.inputStream.bufferedReader().readText()
            val payload = runCatching { Envelope.decrypt(JSONObject(body), Envelope.DOWN, cfg) }.getOrNull()
            payload?.takeIf { downSeq.accept(it) }?.let { repo.applyDownlink(it) }
            return code to payload
        } finally {
            conn.disconnect()
        }
    }

    private fun post(url: String, body: String, token: String?, timeoutMs: Int): HttpResult {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
        }
        return try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            if (code in 200..299) HttpResult(code, null, null) else readError(conn, code)
        } finally {
            conn.disconnect()
        }
    }

    /** Reads Retry-After and ntfy's JSON error text (`{"error":"limit reached: …"}`). */
    private fun readError(conn: HttpURLConnection, code: Int): HttpResult {
        val retryAfter = conn.getHeaderField("Retry-After")?.trim()?.toLongOrNull()?.times(1000)
        val text = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
        val error = text?.let { runCatching { JSONObject(it).optString("error") }.getOrNull()?.ifBlank { null } ?: it.take(120) }
        return HttpResult(code, retryAfter, error)
    }

    private fun hasNetwork(): Boolean =
        appContext.getSystemService(ConnectivityManager::class.java)?.activeNetwork != null

    private fun batteryPercent(): Int =
        appContext.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 } ?: 100
}
