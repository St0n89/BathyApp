package io.github.st0n89.bathyapp.app

import io.github.st0n89.bathyapp.core.sediment.SedGrid
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * GlobSed v3 global sediment thickness (NOAA NCEI, 5 arc minutes).
 *
 * The NCEI archive endpoint is unreachable from mobile networks much of
 * the time (ongoing cloud migration), so the download goes through the
 * CaltechDATA mirror of the identical GlobSed.zip package and streams
 * ONLY up to the end of the ArcGIS ASCII entry (~20 MB on the wire;
 * the 300 MB .xyz and the HDF5 .nc entries are never read).
 */
object GlobSed {
    const val ZIP_URL = "https://data.caltech.edu/api/records/k4070-ngc79/files/GlobSed.zip/content"
    private const val ENTRY_SUFFIX = "globsed-v3.asc"

    /** Cache file in filesDir; binary mirror of the parsed grid. */
    private const val CACHE_MAGIC = "BAGSED3"

    fun cacheFile(dir: File): File = File(dir, "globsed_v3.bin")

    fun downloadAndParse(onNote: (String) -> Unit): SedGrid {
        val conn = URL(ZIP_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 300_000
        try {
            conn.inputStream.use { raw ->
                val zip = ZipInputStream(BufferedInputStream(raw, 1 shl 16))
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name.endsWith(ENTRY_SUFFIX, ignoreCase = true)) {
                        onNote("Lese GlobSed v3 (5′-Raster, einmalig ~20 MB) …")
                        return SedGrid.parseAscii(zip, onRow = { rows ->
                            if (rows % 180 == 0) onNote("Parsen: Zeile ${rows + 1}/2161")
                        })
                    }
                    entry = zip.nextEntry
                }
                throw IllegalStateException("globsed-v3.asc wurde im ZIP nicht gefunden")
            }
        } finally {
            conn.disconnect()
        }
    }

    fun writeCache(file: File, g: SedGrid) {
        DataOutputStream(BufferedOutputStream(FileOutputStream(file), 1 shl 16)).use { out ->
            out.writeUTF(CACHE_MAGIC)
            out.writeInt(g.nx)
            out.writeInt(g.ny)
            out.writeDouble(g.west)
            out.writeDouble(g.north)
            out.writeDouble(g.lonStep)
            out.writeDouble(g.latStep)
            for (i in 0 until g.nx * g.ny) out.writeShort(g.values[i].toInt())
        }
    }

    /** Returns the cached grid or null if no usable cache exists. */
    fun readCache(file: File): SedGrid? {
        if (!file.isFile) return null
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(file), 1 shl 16)).use { inn ->
                if (inn.readUTF() != CACHE_MAGIC) return null
                val nx = inn.readInt()
                val ny = inn.readInt()
                val west = inn.readDouble()
                val north = inn.readDouble()
                val lonStep = inn.readDouble()
                val latStep = inn.readDouble()
                if (nx < 2 || ny < 2 || nx.toLong() * ny > 100_000_000L) return null
                val v = ShortArray(nx * ny)
                for (i in v.indices) v[i] = inn.readShort()
                SedGrid(nx, ny, v, west, north, lonStep, latStep)
            }
        } catch (_: Exception) {
            null
        }
    }
}
