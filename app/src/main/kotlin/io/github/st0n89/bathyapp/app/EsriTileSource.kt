package io.github.st0n89.bathyapp.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * ESRI World Imagery XYZ tile source (256px JPEG tiles) with a small
 * in-memory cache (decoded ARGB tiles) and a size-capped LRU disk cache in
 * the app cache dir, so viewed regions come up instantly and offline.
 *
 * All methods block on I/O; call them from Dispatchers.IO. A tile the
 * server does not deliver (offline, HTTP error) returns null and the globe
 * silently falls back to the local Blue Marble texture there.
 *
 * Attribution (shown in the UI when the layer is active):
 * Esri, Maxar, Earthstar Geographics, and the GIS User Community.
 */
class EsriTileSource(context: Context) {

    private val appContext = context.applicationContext
    private val mem = LruCache<String, IntArray>(MAX_MEM_TILES)
    private val diskLock = ReentrantLock()

    /**
     * Decoded 256x256 ARGB tile or null. Blocking network access on cache miss.
     */
    fun tile(z: Int, x: Int, y: Int): IntArray? {
        val key = "$z/$x/$y"
        mem.get(key)?.let { return it }
        val dir = File(appContext.cacheDir, DIR)
        val f = File(dir, "$z-$x-$y.jpg")
        val bytes = if (f.isFile) {
            try {
                f.readBytes()
            } catch (_: Exception) {
                null
            }
        } else {
            null
        } ?: download(z, x, y)?.also { b -> saveToDisk(f, b) } ?: return null
        val px = decode(bytes) ?: return null
        mem.put(key, px)
        return px
    }

    private fun download(z: Int, x: Int, y: Int): ByteArray? = try {
        val conn = URL("https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$z/$y/$x")
            .openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "image/jpeg,image/*;q=0.8")
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                conn.inputStream.use { it.readBytes() }
            } else {
                null
            }
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        null
    }

    private fun decode(bytes: ByteArray): IntArray? {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        if (bmp.width != TILE || bmp.height != TILE) {
            bmp.recycle()
            return null
        }
        return try {
            val px = IntArray(TILE * TILE)
            bmp.getPixels(px, 0, TILE, 0, 0, TILE, TILE)
            px
        } finally {
            bmp.recycle()
        }
    }

    private fun saveToDisk(f: File, bytes: ByteArray) {
        diskLock.withLock {
            try {
                val dir = f.parentFile ?: return
                if (!dir.isDirectory && !dir.mkdirs()) return
                f.delete()
                f.writeBytes(bytes)
                f.setLastModified(System.currentTimeMillis())
                trimDisk(dir)
            } catch (_: Exception) {
                // cache writes are best-effort
            }
        }
    }

    /** Deletes oldest files while the cache dir exceeds the byte cap. */
    private fun trimDisk(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_DISK_BYTES) return
        val ordered = files.sortedBy { it.lastModified() }
        var i = 0
        while (total > MAX_DISK_BYTES * 9 / 10 && i < ordered.size) {
            val del = ordered[i]
            val len = del.length()
            if (del.delete()) total -= len
            i++
        }
    }

    companion object {
        const val TILE = 256
        const val DIR = "sat_tiles"
        const val MAX_MEM_TILES = 32
        const val MAX_DISK_BYTES = 300L * 1024 * 1024
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 15_000
        const val USER_AGENT = "BathyApp/0.9 (Android; https://github.com/St0n89/BathyApp)"
        const val ATTRIBUTION = "Esri, Maxar, Earthstar Geographics, and the GIS User Community"
    }
}
