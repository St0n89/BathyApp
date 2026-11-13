package io.github.st0n89.bathyapp.core.geo

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Regular geographic grid of elevation values in metres, positive up
 * (GEBCO / EMODnet convention: water depth is negative).
 *
 * Rows are north-first (row 0 is the northernmost), columns west-first.
 * [west]/[east]/[south]/[north] are the OUTER cell-edge bounds.
 */
class GeoGrid(
    val nx: Int,
    val ny: Int,
    val values: FloatArray,
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
) {
    init {
        require(values.size == nx * ny) { "values size ${values.size} != nx*ny $nx*$ny" }
        require(west < east) { "west >= east" }
        require(south < north) { "south >= north" }
    }

    val dx: Double = (east - west) / nx
    val dy: Double = (north - south) / ny

    /** Longitude of the centre of column [i]. */
    fun lonOf(i: Int): Double = west + (i + 0.5) * dx

    /** Latitude of the centre of row [j] (row 0 = north). */
    fun latOf(j: Int): Double = north - (j + 0.5) * dy

    /** Row-major cell access: [row] is the north-first row, [col] the west-first column. */
    operator fun get(row: Int, col: Int): Float = values[row * nx + col]

    /** Bilinear sample at geographic coordinates; NaN outside or on nodata. */
    fun sample(lon: Double, lat: Double): Float {
        val fx = (lon - west) / dx - 0.5
        val fy = (north - lat) / dy - 0.5
        if (fx < -0.5 || fy < -0.5 || fx > nx - 0.5 || fy > ny - 0.5) return Float.NaN
        val x = max(0.0, min(fx, (nx - 1).toDouble()))
        val y = max(0.0, min(fy, (ny - 1).toDouble()))
        val i0 = floor(x).toInt().coerceAtMost(nx - 2)
        val j0 = floor(y).toInt().coerceAtMost(ny - 2)
        val tx = x - i0
        val ty = y - j0
        val v00 = get(j0, i0)
        val v10 = get(j0, i0 + 1)
        val v01 = get(j0 + 1, i0)
        val v11 = get(j0 + 1, i0 + 1)
        if (v00.isNaN() || v10.isNaN() || v01.isNaN() || v11.isNaN()) return Float.NaN
        val top = v00 + (v10 - v00) * tx
        val bot = v01 + (v11 - v01) * tx
        return (top + (bot - top) * ty).toFloat()
    }

    data class Stats(val min: Float, val max: Float, val mean: Double, val count: Int)

    fun stats(): Stats {
        var mn = Float.POSITIVE_INFINITY
        var mx = Float.NEGATIVE_INFINITY
        var sum = 0.0
        var n = 0
        for (v in values) {
            if (v.isNaN()) continue
            if (v < mn) mn = v
            if (v > mx) mx = v
            sum += v
            n++
        }
        return Stats(mn, mx, if (n > 0) sum / n else Double.NaN, n)
    }

    companion object {
        /** Root-mean-square difference between two grids of identical geometry. */
        fun rmsDifference(a: GeoGrid, b: GeoGrid): Double {
            require(a.nx == b.nx && a.ny == b.ny)
            var s = 0.0
            var n = 0
            for (i in a.values.indices) {
                val va = a.values[i]
                val vb = b.values[i]
                if (va.isNaN() || vb.isNaN()) continue
                s += ((va - vb) * (va - vb)).toDouble()
                n++
            }
            return if (n == 0) Double.NaN else sqrt(s / n)
        }
    }
}
