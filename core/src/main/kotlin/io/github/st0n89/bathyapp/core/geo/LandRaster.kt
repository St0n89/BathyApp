package io.github.st0n89.bathyapp.core.geo

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Rasterizes land polygons into a flat equirectangular 1-bit mask
 * (1 = land) using a scanline even-odd fill. Holes inside rings are
 * handled automatically because all ring crossings of one scanline are
 * sorted together before parity fill.
 */
object LandRaster {

    fun rasterize(polys: Coastlines, width: Int, height: Int): ByteArray {
        require(width > 0 && height > 0) { "bad mask size ${width}x$height" }
        val mask = ByteArray(width * height)
        val counts = IntArray(height)
        val rows = arrayOfNulls<DoubleArray>(height)
        val fill = IntArray(height)

        // Row range whose scanline centers the edge actually crosses:
        // scanline r has center y = r + 0.5 (y = (90-lat)*height/180, y down).
        fun rowRange(y1: Double, y2: Double): IntArray? {
            val yMin = min(y1, y2)
            val yMax = max(y1, y2)
            val rA = ceil(yMin - 0.5).toInt()
            val rB = floor(yMax - 0.5).toInt()
            val a = rA.coerceIn(0, height - 1)
            val b = rB.coerceIn(0, height - 1)
            val first = if (rA < 0) 0 else rA
            val last = if (rB > height - 1) height - 1 else rB
            return if (first > last || a > b) null else intArrayOf(first, last)
        }

        // pass 1: count crossings per scanline
        for (ring in polys.polylines) {
            val m = ring.size / 2
            for (j in 0 until m) {
                val lat1 = ring[j * 2 + 1].toDouble()
                val lat2 = ring[if (j + 1 == m) 0 * 2 + 1 else (j + 1) * 2 + 1].toDouble()
                if (lat1 == lat2) continue
                val y1 = (90.0 - lat1) * height / 180.0
                val y2 = (90.0 - lat2) * height / 180.0
                val rr = rowRange(y1, y2) ?: continue
                for (r in rr[0]..rr[1]) counts[r]++
            }
        }
        for (r in 0 until height) rows[r] = DoubleArray(counts[r])

        // pass 2: compute crossings
        for (ring in polys.polylines) {
            val m = ring.size / 2
            for (j in 0 until m) {
                val k = if (j + 1 == m) 0 else j + 1
                val lon1 = ring[j * 2].toDouble()
                val lat1 = ring[j * 2 + 1].toDouble()
                val lon2 = ring[k * 2].toDouble()
                val lat2 = ring[k * 2 + 1].toDouble()
                if (lat1 == lat2) continue
                val y1 = (90.0 - lat1) * height / 180.0
                val y2 = (90.0 - lat2) * height / 180.0
                val rr = rowRange(y1, y2) ?: continue
                for (r in rr[0]..rr[1]) {
                    val lat = 90.0 - (r + 0.5) * 180.0 / height
                    val t = (lat1 - lat) / (lat1 - lat2)
                    val lon = lon1 + t * (lon2 - lon1)
                    val x = (lon + 180.0) * width / 360.0
                    val idx = fill[r]
                    rows[r]!![idx] = x
                    fill[r] = idx + 1
                }
            }
        }

        // pass 3: sort each scanline and parity fill between pairs
        for (r in 0 until height) {
            val n = fill[r]
            val xs = rows[r]!!
            if (n < 2) continue
            xs.sort()
            var i = 0
            while (i + 1 < n) {
                val x1 = xs[i]
                val x2 = xs[i + 1]
                val c1 = ceil(x1 - 0.5).toInt().coerceIn(0, width - 1)
                val c2 = floor(x2 - 0.5).toInt().coerceIn(0, width - 1)
                for (c in c1..c2) mask[r * width + c] = 1
                i += 2
            }
        }
        return mask
    }
}
