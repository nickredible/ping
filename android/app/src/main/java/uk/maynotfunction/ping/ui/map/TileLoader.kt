package uk.maynotfunction.ping.ui.map

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Fetches raster map tiles with a memory LRU and a disk cache in `cacheDir/tiles`.
 * [version] increases whenever a tile arrives, so drawing code that reads it redraws.
 */
class TileLoader(context: Context, private val scope: CoroutineScope) {
    private val memory = LruCache<String, ImageBitmap>(192)
    private val inflight = ConcurrentHashMap.newKeySet<String>()
    private val failedAt = ConcurrentHashMap<String, Long>()
    private val io = Dispatchers.IO.limitedParallelism(6)
    private val diskDir = File(context.cacheDir, "tiles")

    var version by mutableIntStateOf(0)
        private set

    init {
        scope.launch(io) {
            diskDir.mkdirs()
            val cutoff = System.currentTimeMillis() - MAX_DISK_AGE_MS
            diskDir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        }
    }

    /** Returns the tile if cached in memory, otherwise starts loading it and returns null. */
    fun get(url: String): ImageBitmap? {
        memory[url]?.let { return it }
        request(url)
        return null
    }

    /** Memory-only lookup (used for lower-zoom fallbacks without triggering downloads). */
    fun peek(url: String): ImageBitmap? = memory[url]

    private fun request(url: String) {
        val failed = failedAt[url]
        if (failed != null && System.currentTimeMillis() - failed < RETRY_AFTER_MS) return
        if (!inflight.add(url)) return
        scope.launch(io) {
            try {
                val file = File(diskDir, sha1(url))
                val bytes = file.takeIf { it.exists() }?.readBytes() ?: download(url)?.also { file.writeBytes(it) }
                val bitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.asImageBitmap()
                if (bitmap != null) {
                    memory.put(url, bitmap)
                    failedAt.remove(url)
                    withContext(Dispatchers.Main) { version++ }
                } else {
                    failedAt[url] = System.currentTimeMillis()
                }
            } catch (_: Exception) {
                failedAt[url] = System.currentTimeMillis()
            } finally {
                inflight.remove(url)
            }
        }
    }

    private fun download(url: String): ByteArray? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 10_000
            // OSM's tile policy requires an identifying User-Agent.
            conn.setRequestProperty("User-Agent", "Ping/2.4 (Android)")
            if (conn.responseCode == 200) conn.inputStream.use { it.readBytes() } else null
        } finally {
            conn.disconnect()
        }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val MAX_DISK_AGE_MS = 14L * 24 * 3600 * 1000
        private const val RETRY_AFTER_MS = 15_000L

        /**
         * Tile URL for the configured source: a `{z}/{x}/{y}` template, a CARTO API key, or
         * standard OpenStreetMap. Returns the URL and whether it needs the dark-mode filter.
         */
        fun tileUrl(custom: String, dark: Boolean, z: Int, x: Int, y: Int): Pair<String, Boolean> = when {
            custom.contains("{z}") && custom.contains("{x}") && custom.contains("{y}") ->
                custom.replace("{z}", "$z").replace("{x}", "$x").replace("{y}", "$y") to false
            custom.isNotBlank() && !custom.startsWith("http") -> {
                val style = if (dark) "dark_all" else "rastertiles/voyager"
                "https://a.basemaps.cartocdn.com/$style/$z/$x/$y@2x.png?key=${android.net.Uri.encode(custom.trim())}" to false
            }
            else -> "https://tile.openstreetmap.org/$z/$x/$y.png" to dark
        }
    }
}
