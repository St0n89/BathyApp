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
 *
 * The server dies with a bare HTTP 500 when a request has to read more
 * than ~12 million native cells (measured: 11.06M OK, 12.9M fails), even
 * with scaleSize set, because GeoServer reads the full native area first.
 * Large bboxes are therefore fetched as tiles below that limit and
 * stitched client-side.
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

    /** Native EMODnet DTM cell size in degrees (3.75 arcsec). */
    const val NATIVE_RES_DEG = 1.0 / 960.0

    /** Measured server crash limit, with safety margin. */
    const val NATIVE_CELL_LIMIT = 9_500_000L

    /** Coverage extent from DescribeCoverage (Lat 11..90, Long -70.5..43). */
    const val EXTENT_WEST = -70.5
    const val EXTENT_EAST = 43.0
    const val EXTENT_SOUTH = 11.0
    const val EXTENT_NORTH = 90.0

    /**
     * Builds a GetCoverage URL. EMODnet uses "Long"/"Lat" subset axis
     * labels and i/j scale axes (GeoServer), with float32 GeoTIFF output.
     */
    fun buildUrl(coverageId: String, west: Double, south: Double, east: Double, north: Double, cols: Int): String {
        require(east > west && north > south) { "invalid bounding box" }
        require(cols in 2..MAX_COLS) { "grid width must be 2..$MAX_COLS" }
        val rows = ((north - south) / (east - west) * cols).roundToInt().coerceIn(2, MAX_COLS)
        return buildUrl(coverageId, west, south, east, north, cols, rows)
    }

    /** Same as [buildUrl] with an explicit output row count. */
    fun buildUrl(coverageId: String, west: Double, south: Double, east: Double, north: Double, cols: Int, rows: Int): String {
        require(east > west && north > south) { "invalid bounding box" }
        require(cols in 2..MAX_COLS && rows in 2..MAX_COLS) { "grid size must be 2..$MAX_COLS" }
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

    /**
     * Loads a grid from the WCS for the given bbox. Large areas are
     * fetched as tiles (each below the server's native-cell crash limit)
     * and stitched into one grid.
     */
    fun loadGrid(
        coverageId: String,
        west: Double,
        south: Double,
        east: Double,
        north: Double,
        cols: Int,
        onStatus: (String) -> Unit = {},
    ): GeoGrid {
        require(east > west && north > south) { "invalid bounding box" }
        require(cols in 2..MAX_COLS) { "grid width must be 2..$MAX_COLS" }
        val overlapsExtent = east > EXTENT_WEST && west < EXTENT_EAST &&
            north > EXTENT_SOUTH && south < EXTENT_NORTH
        if (!overlapsExtent) {
            throw IllegalStateException(
                "EMODnet deckt nur europäische Gewässer ab (Long $EXTENT_WEST…$EXTENT_EAST, Lat $EXTENT_SOUTH…$EXTENT_NORTH). Nutze GEBCO für weltweite Gebiete."
            )
        }
        val rows = ((north - south) / (east - west) * cols).roundToInt().coerceIn(2, MAX_COLS)

        val tx = tileCountX(west, south, east, north)
        val ty = tileCountY(west, south, east, north)
        if (tx <= 1 && ty <= 1) {
            onStatus("Frage EMODnet an …")
            return fetchTile(coverageId, west, south, east, north, cols, rows)
        }

        // split the target grid into tiles, each tile at least 2 px
        val colCounts = splitEvenly(cols, tx)
        val rowCounts = splitEvenly(rows, ty)
        val txc = colCounts.size
        val tyc = rowCounts.size
        val dx = (east - west) / cols
        val dy = (north - south) / rows
        val nTiles = txc * tyc
        var done = 0
        val tiles = Array(tyc) { arrayOfNulls<GeoGrid>(txc) }
        for (r in 0 until tyc) {
            val tTop = north - rowCounts.take(r).sum() * dy
            val tBottom = tTop - rowCounts[r] * dy
            for (c in 0 until txc) {
                val tLeft = west + colCounts.take(c).sum() * dx
                val tRight = tLeft + colCounts[c] * dx
                done++
                onStatus("Lade Kachel $done/$nTiles …")
                tiles[r][c] = fetchTile(coverageId, tLeft, tBottom, tRight, tTop, colCounts[c], rowCounts[r])
            }
        }
        onStatus("Füge Kacheln zusammen …")
        return stitch(tiles, west, south, east, north, cols, rows)
    }

    /** Number of tile columns needed to stay under [NATIVE_CELL_LIMIT]. */
    fun tileCountX(west: Double, south: Double, east: Double, north: Double): Int =
        tileCounts(west, south, east, north).first

    /** Number of tile rows needed to stay under [NATIVE_CELL_LIMIT]. */
    fun tileCountY(west: Double, south: Double, east: Double, north: Double): Int =
        tileCounts(west, south, east, north).second

    private fun tileCounts(west: Double, south: Double, east: Double, north: Double): Pair<Int, Int> {
        val lonSpan = east - west
        val latSpan = north - south
        val native = (lonSpan / NATIVE_RES_DEG * latSpan / NATIVE_RES_DEG).toLong()
        if (native <= NATIVE_CELL_LIMIT) return 1 to 1
        val f = native.toDouble() / NATIVE_CELL_LIMIT
        val aspect = lonSpan / latSpan
        val tx = kotlin.math.ceil(kotlin.math.sqrt(f * aspect)).toInt().coerceAtLeast(1)
        val ty = kotlin.math.ceil(kotlin.math.sqrt(f / aspect)).toInt().coerceAtLeast(1)
        return tx to ty
    }

    /** Splits [total] into [parts] near-equal integers, each at least 2. */
    internal fun splitEvenly(total: Int, parts: Int): IntArray {
        var p = parts.coerceIn(1, total / 2).coerceAtLeast(1)
        if (total < 2 * p) p = (total / 2).coerceAtLeast(1)
        val base = total / p
        val rem = total % p
        return IntArray(p) { base + if (it < rem) 1 else 0 }
    }

    private fun fetchTile(coverageId: String, west: Double, south: Double, east: Double, north: Double, cols: Int, rows: Int): GeoGrid {
        val bytes = fetch(buildUrl(coverageId, west, south, east, north, cols, rows))
        val tiff = GeoTiffReader.read(SimpleSource(bytes))
        return tiff.toGeoGrid()
    }

    /**
     * Stitches tile grids (indexed [latBand][lonBand], lat band 0 = the
     * northernmost) into one regular grid covering the requested bbox.
     * Tiles are sampled bilinearly, so their exact dimensions do not matter.
     */
    internal fun stitch(tiles: Array<Array<GeoGrid?>>, west: Double, south: Double, east: Double, north: Double, cols: Int, rows: Int): GeoGrid {
        require(tiles.isNotEmpty() && tiles[0].isNotEmpty()) { "empty tile grid" }
        tiles.forEach { row -> require(row.all { it != null }) { "missing tile" } }
        val dx = (east - west) / cols
        val dy = (north - south) / rows

        // lat band edges (outer cell edges) from the tile extents, north-first
        val latEdges = DoubleArray(tiles.size + 1)
        latEdges[0] = north
        for (r in tiles.indices) {
            latEdges[r + 1] = latEdges[r] - (tiles[r].first()!!.north - tiles[r].first()!!.south)
        }

        val values = FloatArray(cols * rows)
        val latCenters = DoubleArray(rows) { north - (it + 0.5) * dy }
        val latBandIdx = IntArray(rows)
        for (j in 0 until rows) {
            var r = 0
            while (r < tiles.size - 1 && latCenters[j] < latEdges[r + 1]) r++
            latBandIdx[j] = r
        }
        for (i in 0 until cols) {
            val lon = west + (i + 0.5) * dx
            for (j in 0 until rows) {
                val row = tiles[latBandIdx[j]]
                var c = 0
                var edge = row[0]!!.west
                while (c < row.size - 1 && lon >= edge + (row[c]!!.east - row[c]!!.west)) {
                    edge += row[c]!!.east - row[c]!!.west
                    c++
                }
                values[j * cols + i] = row[c]!!.sample(lon, latCenters[j])
            }
        }
        return GeoGrid(cols, rows, values, west, south, east, north)
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
