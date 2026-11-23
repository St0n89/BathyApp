package io.github.st0n89.bathyapp.core.geo

import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin

/**
 * Web-Mercator (spherical) XYZ tile math for online satellite overlays
 * (ESRI World Imagery; scheme identical to OSM/Google XYZ tiles, 256px JPEG).
 *
 * Longitude works in CONTINUOUS space: a window's west/east may extend past
 * ±180°; pass continuous tile coordinates through [tileIndex] to get the
 * real-world tile column. Latitudes are clamped to the Web-Mercator limits
 * (±85.0511°); anything nearer to the poles has no mercator tiles.
 */
object TileMath {

    /** Edge length of a standard XYZ tile in pixels. */
    const val TILE_PX = 256

    /** Web-Mercator latitude limit (tile grid ends here). */
    const val LAT_LIMIT = 85.05112878

    /** World pixel span of the full map at zoom z. */
    fun worldPx(z: Int): Double = TILE_PX.toDouble() * (1L shl z)

    /** Continuous mercator x pixel of a longitude (may be outside ±180°). */
    fun lonToPxX(lon: Double, z: Int): Double = (lon + 180.0) / 360.0 * worldPx(z)

    /** Mercator y pixel of a latitude (rows grow southward, 0 at the top). */
    fun latToPxY(lat: Double, z: Int): Double {
        val c = lat.coerceIn(-LAT_LIMIT, LAT_LIMIT) * Math.PI / 180.0
        val s = sin(c)
        // y = 0.5 - asinh(tan c) / (2pi), written without tan for stability
        val y = 0.5 - ln((1.0 + s) / (1.0 - s)) / (4.0 * Math.PI)
        return y * worldPx(z)
    }

    /** Inverse of [lonToPxX]. */
    fun pxXToLon(x: Double, z: Int): Double = x / worldPx(z) * 360.0 - 180.0

    /** Inverse of [latToPxY] (latitude of a mercator y pixel row). */
    fun pxYToLat(y: Double, z: Int): Double {
        val yNorm = 0.5 - y / worldPx(z)
        val lat = 2.0 * atan(exp(2.0 * Math.PI * yNorm)) - Math.PI / 2.0
        return lat * 180.0 / Math.PI
    }

    /**
     * Real-world tile index of a continuous tile coordinate. Periodic in x
     * (dateline wrap; also handles x >= 2^z after continuous-longitude math).
     * y is periodic too, though latitudes are clamped so y never leaves
     * [0, 2^z-1] in practice.
     */
    fun tileIndex(i: Int, z: Int): Int {
        val n = 1 shl z
        return ((i % n) + n) % n
    }

    /** Approximate ground resolution (metres per tile pixel) of a latitude row. */
    fun metresPerPx(lat: Double, z: Int): Double =
        156543.03392 * cos(lat * Math.PI / 180.0) / (1L shl z)

    /**
     * Largest zoom whose FULL-TILE-ALIGNED window over the bbox still fits the
     * convolution caps (pixels per side, total tile count). Longitude must be
     * continuous (west < east, values outside ±180° allowed). Returns null if
     * even [minZoom] does not fit (bbox too large: caller falls back to the
     * local texture, e.g. whole-globe views).
     */
    fun chooseWindow(
        westLon: Double,
        southLat: Double,
        eastLon: Double,
        northLat: Double,
        minZoom: Int = 3,
        maxZoom: Int = 17,
        maxTiles: Int = 64,
        maxSidePx: Int = 2048,
    ): TileWindow? {
        val s = southLat.coerceIn(-LAT_LIMIT, LAT_LIMIT)
        val n = northLat.coerceIn(-LAT_LIMIT, LAT_LIMIT)
        var best: TileWindow? = null
        for (z in minZoom..maxZoom) {
            val x0 = lonToPxX(westLon, z)
            val x1 = lonToPxX(eastLon, z)
            val y0 = latToPxY(n, z)
            val y1 = latToPxY(s, z)
            // tile-aligned bounds (window grows to whole tiles)
            val tx0 = floor(x0 / TILE_PX).toInt()
            val tx1 = floor((x1 - 1e-9) / TILE_PX).toInt()
            val ty0 = floor((y0 + 1e-9) / TILE_PX).toInt()
            val ty1 = floor((y1 - 1e-9) / TILE_PX).toInt()
            val tilesX = (tx1 - tx0 + 1).coerceAtLeast(1)
            val tilesY = (ty1 - ty0 + 1).coerceAtLeast(1)
            val wPx = tilesX * TILE_PX
            val hPx = tilesY * TILE_PX
            if (tilesX * 1L * tilesY > maxTiles || wPx > maxSidePx || hPx > maxSidePx) break
            best = TileWindow(
                zoom = z,
                westPxX = tx0 * TILE_PX.toDouble(),
                northPxY = ty0 * TILE_PX.toDouble(),
                eastPxX = (tx1 + 1) * TILE_PX.toDouble(),
                southPxY = (ty1 + 1) * TILE_PX.toDouble(),
            )
        }
        return best
    }
}

/**
 * A full-tile-aligned mercator window (pixel bounds), the fetch unit for
 * satellite tiles. Bounds are continuous (west/east may lie outside ±180°).
 */
class TileWindow(
    val zoom: Int,
    val westPxX: Double,
    val northPxY: Double,
    val eastPxX: Double,
    val southPxY: Double,
) {
    val tilesX: Int get() = ((eastPxX - westPxX) / TileMath.TILE_PX).toInt()
    val tilesY: Int get() = ((southPxY - northPxY) / TileMath.TILE_PX).toInt()
    val tileCount: Int get() = tilesX * tilesY
}

/**
 * Equirectangular satellite overlay: an ARGB [px] grid whose rows are linear
 * in latitude and whose columns are linear in longitude, composed from
 * mercator XYZ tiles. The globe renderer samples it exactly like the baked
 * texture (fractional pixel coords, pixel centres at integers).
 *
 * Longitude is continuous around the fetch centre; transparent pixels (0)
 * mark spots where no tile was available (offline/fetch error) so the
 * renderer can fall back to the local texture there.
 */
class TileOverlay(
    val width: Int,
    val height: Int,
    /** Continuous western/eastern longitude of the overlay bbox. */
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
    val px: IntArray,
    val zoom: Int,
    /** Non-transparent pixels, for a coverage readout (0..1). */
    val filledFraction: Double,
) {
    fun latIn(lat: Double): Boolean = lat in south..north

    fun lonIn(lon: Double): Boolean = lon in west..east

    /** Fractional x pixel (centre at integers) of a continuous longitude. */
    fun toX(lon: Double): Double = (lon - west) / (east - west) * width - 0.5

    /** Fractional y pixel (centre at integers) of a latitude (row 0 = north). */
    fun toY(lat: Double): Double = (north - lat) / (north - south) * height - 0.5
}

/**
 * Composes [TileOverlay] for a window: every overlay pixel maps its
 * lon/lat to mercator tile space and takes the nearest tile pixel.
 * [tileSource] returns a decoded 256x256 ARGB tile or null when unavailable;
 * it may be called from any thread and should cache aggressively (tiles are
 * requested row by row, so LRU by recency works well).
 */
fun composeTileOverlay(
    window: TileWindow,
    tileSource: (z: Int, x: Int, y: Int) -> IntArray?,
): TileOverlay {
    val z = window.zoom
    val w = window.tilesX * TileMath.TILE_PX
    val h = window.tilesY * TileMath.TILE_PX
    val px = IntArray(w * h)
    val westLon = TileMath.pxXToLon(window.westPxX, z)
    val eastLon = TileMath.pxXToLon(window.eastPxX, z)
    val northLat = TileMath.pxYToLat(window.northPxY, z)
    val southLat = TileMath.pxYToLat(window.southPxY, z)
    val lonSpan = eastLon - westLon
    val latSpan = northLat - southLat
    var filled = 0
    for (j in 0 until h) {
        val lat = northLat - latSpan * ((j + 0.5) / h)
        val my = TileMath.latToPxY(lat, z)
        val ty = floor(my / TileMath.TILE_PX).toInt()
        val fy = (my - ty * TileMath.TILE_PX).toInt().coerceIn(0, TileMath.TILE_PX - 1)
        val tyReal = TileMath.tileIndex(ty, z)
        var rowTile: IntArray? = null
        var rowTileX = Int.MIN_VALUE
        val rowOff = fy * TileMath.TILE_PX
        var rowStart = j * w
        for (i in 0 until w) {
            val lon = westLon + lonSpan * ((i + 0.5) / w)
            val mx = TileMath.lonToPxX(lon, z)
            val tx = floor(mx / TileMath.TILE_PX).toInt()
            if (tx != rowTileX) {
                rowTile = tileSource(z, TileMath.tileIndex(tx, z), tyReal)
                rowTileX = tx
            }
            val t = rowTile
            if (t != null) {
                val fx = (mx - tx * TileMath.TILE_PX).toInt().coerceIn(0, TileMath.TILE_PX - 1)
                val v = t[rowOff + fx]
                px[rowStart + i] = v
                if (v != 0) filled++
            }
        }
    }
    return TileOverlay(
        width = w, height = h,
        west = westLon, south = southLat, east = eastLon, north = northLat,
        px = px, zoom = z,
        filledFraction = if (w * h == 0) 0.0 else filled.toDouble() / (w * h),
    )
}
