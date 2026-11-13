package uk.maynotfunction.ping.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.PhoneLookup
import uk.maynotfunction.ping.core.FriendLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

data class ContactProfile(
    val name: String,
    val contactUri: Uri?,
    val photo: Bitmap?,
)

/** Matches friend handles (phone/email) or names against the system Contacts provider. */
class ContactsRepository(context: Context) {
    private val appContext = context.applicationContext
    private val cache = ConcurrentHashMap<String, ContactProfile>()

    fun hasPermission() =
        appContext.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun clearCache() = cache.clear()

    suspend fun resolve(handle: String, fallbackName: String): ContactProfile = withContext(Dispatchers.IO) {
        val key = "${handle.lowercase()}|$fallbackName|${hasPermission()}"
        cache[key]?.let { return@withContext it }

        val match = if (hasPermission()) {
            when {
                handle.contains('@') -> lookup(Uri.withAppendedPath(Email.CONTENT_LOOKUP_URI, Uri.encode(handle)),
                    Email.CONTACT_ID, Email.DISPLAY_NAME_PRIMARY)
                handle.isNotBlank() -> lookup(Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(handle)),
                    PhoneLookup.CONTACT_ID, PhoneLookup.DISPLAY_NAME)
                else -> null
            } ?: fallbackName.takeIf { it.isNotBlank() }?.let { byDisplayName(it) }
        } else null

        val profile = if (match != null) {
            val uri = ContentUris.withAppendedId(Contacts.CONTENT_URI, match.first)
            ContactProfile(match.second.ifBlank { fallbackName }, uri, loadPhoto(uri))
        } else {
            ContactProfile(fallbackName.ifBlank { handle.ifBlank { "Friend" } }, null, null)
        }
        cache[key] = profile
        profile
    }

    /** Returns (contactId, displayName) for the first row of a lookup URI. */
    private fun lookup(uri: Uri, idCol: String, nameCol: String): Pair<Long, String>? = runCatching {
        appContext.contentResolver.query(uri, arrayOf(idCol, nameCol), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getLong(0) to (c.getString(1) ?: "") else null
        }
    }.getOrNull()

    private fun byDisplayName(name: String): Pair<Long, String>? = runCatching {
        appContext.contentResolver.query(
            Contacts.CONTENT_URI, arrayOf(Contacts._ID, Contacts.DISPLAY_NAME_PRIMARY),
            "${Contacts.DISPLAY_NAME_PRIMARY} = ? COLLATE NOCASE", arrayOf(name), null,
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) to (c.getString(1) ?: name) else null }
    }.getOrNull()

    private fun loadPhoto(contactUri: Uri): Bitmap? = runCatching {
        Contacts.openContactPhotoInputStream(appContext.contentResolver, contactUri, true)?.use { input ->
            val raw = BitmapFactory.decodeStream(input) ?: return null
            val size = minOf(raw.width, raw.height, 256)
            if (raw.width == size && raw.height == size) raw
            else Bitmap.createScaledBitmap(raw, size, size * raw.height / raw.width, true)
        }
    }.getOrNull()

    /**
     * Debug preview: places a few contacts around [lat]/[lon] so the map and list can be checked
     * without a relay iPhone. Positions are made up and clearly marked as previews.
     */
    suspend fun previewFriends(lat: Double, lon: Double, max: Int = 6): List<FriendLocation> =
        withContext(Dispatchers.IO) {
            if (!hasPermission()) return@withContext emptyList()
            val offsets = listOf(0.0028 to -0.0031, -0.0034 to 0.0024, 0.0042 to 0.0038,
                -0.0021 to -0.0045, 0.0015 to 0.0052, -0.0048 to -0.0018)
            val out = mutableListOf<FriendLocation>()
            runCatching {
                appContext.contentResolver.query(
                    Contacts.CONTENT_URI, arrayOf(Contacts._ID, Contacts.DISPLAY_NAME_PRIMARY),
                    "${Contacts.DISPLAY_NAME_PRIMARY} IS NOT NULL", null,
                    "${Contacts.PHOTO_URI} DESC, ${Contacts.STARRED} DESC, ${Contacts.DISPLAY_NAME_PRIMARY} ASC",
                )?.use { c ->
                    while (c.moveToNext() && out.size < max) {
                        val id = c.getLong(0)
                        val name = c.getString(1) ?: continue
                        val (dLat, dLon) = offsets[out.size % offsets.size]
                        out += FriendLocation(
                            id = "contact-$id", handle = "", name = name,
                            lat = lat + dLat, lon = lon + dLon, hAcc = 8.0 + out.size * 2,
                            timestampMs = System.currentTimeMillis() - out.size * 45_000L,
                            address = "", isPreview = true,
                        )
                    }
                }
            }
            out
        }
}
