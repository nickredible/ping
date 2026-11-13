package uk.maynotfunction.ping.core

import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * fmb1 envelope: AES-256-GCM (NIST SP 800-38D) with a random 96-bit IV, 128-bit tag and
 * direction-bound AAD `fmb1:<up|down>:<channelId>`. Wire-compatible with the iOS tweak.
 */
object Envelope {
    const val UP = "up"
    const val DOWN = "down"

    private const val IV_BYTES = 12
    private const val TAG_BYTES = 16
    private val rng = SecureRandom()

    private fun aad(direction: String, cfg: PairingConfig) = "fmb1:$direction:${cfg.channelId}".toByteArray()

    fun encrypt(payload: JSONObject, direction: String, cfg: PairingConfig): String {
        val iv = ByteArray(IV_BYTES).also(rng::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(cfg.key, "AES"), GCMParameterSpec(TAG_BYTES * 8, iv))
            updateAAD(aad(direction, cfg))
        }
        val out = cipher.doFinal(payload.toString().toByteArray())
        val enc = Base64.getEncoder()
        return JSONObject()
            .put("v", 1)
            .put("dir", direction)
            .put("ts", System.currentTimeMillis())
            .put("iv", enc.encodeToString(iv))
            .put("ct", enc.encodeToString(out.copyOfRange(0, out.size - TAG_BYTES)))
            .put("tag", enc.encodeToString(out.copyOfRange(out.size - TAG_BYTES, out.size)))
            .toString()
    }

    /** Returns the decrypted payload, or null if the envelope is malformed, tampered with or for another direction. */
    fun decrypt(env: JSONObject, expectedDirection: String, cfg: PairingConfig): JSONObject? = runCatching {
        if (env.optString("dir") != expectedDirection) return null
        val dec = Base64.getMimeDecoder()
        val iv = dec.decode(env.getString("iv"))
        val ct = dec.decode(env.getString("ct"))
        val tag = dec.decode(env.getString("tag"))
        if (iv.size != IV_BYTES || tag.size != TAG_BYTES) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(cfg.key, "AES"), GCMParameterSpec(TAG_BYTES * 8, iv))
            updateAAD(aad(expectedDirection, cfg))
        }
        JSONObject(String(cipher.doFinal(ct + tag)))
    }.getOrNull()
}

/**
 * Replay protection: every payload carries `seq`, the sender's epoch ms forced to strictly
 * increase. A receiver only accepts messages newer than the last one it accepted and within
 * a maximum age, so captured messages can't be replayed.
 */
class SeqGuard(private val maxAgeMs: Long) {
    private var lastSent = 0L
    private var lastAccepted = 0L

    @Synchronized
    fun next(): Long {
        val now = System.currentTimeMillis()
        lastSent = if (now > lastSent) now else lastSent + 1
        return lastSent
    }

    @Synchronized
    fun accept(payload: JSONObject): Boolean {
        if (!payload.has("seq")) return false
        val seq = payload.optLong("seq")
        if (seq <= lastAccepted) return false
        if (kotlin.math.abs(System.currentTimeMillis() - seq) > maxAgeMs) return false
        lastAccepted = seq
        return true
    }
}
