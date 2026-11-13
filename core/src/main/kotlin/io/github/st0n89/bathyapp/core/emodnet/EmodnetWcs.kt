package io.github.st0n89.bathyapp.core.emodnet

import io.github.st0n89.bathyapp.core.geo.GeoGrid
import io.github.st0n89.bathyapp.core.tiff.GeoTiffReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

/**
 * Client for the EMODnet Bathymetry WCS 2.0.1 service.
 *
 * GetCoverage returns an uncompressed float32 GeoTIFF of the requested
 * bounding box; the grid values are metres, positive up.
 */
object EmodnetWcs {

    const val BASE_URL = "https://ows.emodnet-bathymetry.eu/wcs"

    data class Coverage(val id: String, val label: String)

    val COVERAGES: List<Coverage> = listOf(
        Coverage("emodnet__mean", "Mean DTM (latest)"),
        Coverage("emodnet__mean_2022", "Mean DTM 2022"),
        Coverage("emodnet__mean_2020", "Mean DTM 2020"),
        Coverage("emodnet__mean_2018", "Mean DTM 2018"),
        Coverage("emodnet__mean_2016", "Mean DTM 2016"),
    )

    const val MAX_COLS = 2048

    /**
     * Builds a GetCoverage URL. EMODnet uses "Long"/"Lat" subset axis
     * labels and i/j scale axes (GeoServer), with float32 GeoTIFF output.
     */
    fun buildUrl(coverageId: String, west: Double, south: Double, east: Double, north: Double, cols: Int): String {
        require(east > west && north > south) { "invalid bounding box" }
        require(cols in 2..MAX_COLS) { "grid width must be 2..$MAX_COLS" }
        val rows = ((north - south) / (east - west) * cols).roundToInt().coerceIn(2, MAX_COLS)
        val sb = StringBuilder(BASE_URL)
        sb.append("?service=WCS&version=2.0.1&request=GetCoverage")
        sb.append("&coverageid=").append(enc(coverageId))
        sb.append("&subset=Long(").append(num(west)).append(',').append(num(east)).append(')')
        sb.append("&subset=Lat(").append(num(south)).append(',').append(num(north)).append(')')
        sb.append("&scaleSize=i(").append(cols).append("),j(").append(rows).append(')')
        sb.append("&format=image/tiff")
        return sb.toString()
    }

    fun fetch(url: String, connectTimeoutMs: Int = 15_000, readTimeoutMs: Int = 120_000): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Accept", "image/tiff")
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use { it.readBytes() } ?: ByteArray(0)
            if (code !in 200..299) {
                throw IOException(serverError(body, code))
            }
            if (body.size < 8 || body[0] != 'I'.code.toByte() && body[0] != 'M'.code.toByte()) {
                throw IOException(serverError(body, code))
            }
            return body
        } finally {
            conn.disconnect()
        }
    }

    /** Loads a grid from the WCS for the given bbox. */
    fun loadGrid(
        coverageId: String,
        west: Double,
        south: Double,
        east: Double,
        north: Double,
        cols: Int,
    ): GeoGrid {
        val bytes = fetch(buildUrl(coverageId, west, south, east, north, cols))
        val tiff = GeoTiffReader.read(SimpleSource(bytes))
        val grid = tiff.toGeoGrid()
        return grid
    }

    private fun serverError(body: ByteArray, code: Int): String {
        val text = String(body, Charsets.UTF_8)
        val match = Regex("exceptionCode=\"([^\"]+)\"").find(text)
        val msg = Regex("<ows:ExceptionText>([^<]+)</ows:ExceptionText>").find(text)?.groupValues?.get(1)
        return buildString {
            append("EMODnet WCS error (HTTP ").append(code).append(')')
            if (match != null) append(": ").append(match.groupValues[1])
            if (msg != null) append(" - ").append(msg.trim())
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun num(v: Double) = String.format(java.util.Locale.ROOT, "%.6f", v)
}

/** In-memory [io.github.st0n89.bathyapp.core.data.SeekSource] for byte arrays. */
class SimpleSource(private val bytes: ByteArray) : io.github.st0n89.bathyapp.core.data.SeekSource {
    override val size: Long = bytes.size.toLong()

    override fun readAt(position: Long, count: Int): ByteArray {
        require(position >= 0 && position + count <= bytes.size) { "read out of bounds" }
        return bytes.copyOfRange(position.toInt(), (position + count).toInt())
    }

    override fun close() {}
}
