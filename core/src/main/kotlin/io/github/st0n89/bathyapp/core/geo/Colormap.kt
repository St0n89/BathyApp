package io.github.st0n89.bathyapp.core.geo

import kotlin.math.min

/**
 * Terrain colour mapping for elevation grids (positive up):
 * water from pale aqua (0 m) to abyssal navy, land from beach sand to white peaks.
 */
object Colormap {

    private val waterStops = listOf(
        0.00f to intArrayOf(168, 219, 224),
        0.04f to intArrayOf(120, 189, 205),
        0.12f to intArrayOf(72, 145, 190),
        0.30f to intArrayOf(36, 104, 160),
        0.60f to intArrayOf(16, 60, 120),
        1.00f to intArrayOf(5, 25, 65),
    )

    private val landStops = listOf(
        0.00f to intArrayOf(233, 226, 187),
        0.15f to intArrayOf(184, 199, 123),
        0.45f to intArrayOf(143, 164, 91),
        0.70f to intArrayOf(163, 133, 90),
        0.88f to intArrayOf(177, 158, 133),
        1.00f to intArrayOf(245, 245, 245),
    )

    fun color(elevation: Float, minDepth: Float, maxDepth: Float): Int {
        if (elevation.isNaN()) return NODATA_ARGB
        if (elevation <= 0f) {
            val span = -minDepth
            val t = if (span <= 0f) 0f else min(1f, (-elevation) / span)
            return argb(ramp(waterStops, t))
        }
        val span = maxDepth
        val t = if (span <= 0f) 0f else min(1f, elevation / span)
        return argb(ramp(landStops, t))
    }

    fun toArgbPixels(grid: GeoGrid): IntArray {
        val st = grid.stats()
        val minDepth = min(0f, st.min)
        val maxDepth = max(0f, st.max)
        return IntArray(grid.values.size) { i -> color(grid.values[i], minDepth, maxDepth) }
    }

    const val NODATA_ARGB = 0xFF444444.toInt()

    /** Colour for a fraction t in [0,1] of the water ramp (for colour bars). */
    fun waterRamp(t: Float): Int = argb(ramp(waterStops, t))

    /** Colour for a fraction t in [0,1] of the land ramp (for colour bars). */
    fun landRamp(t: Float): Int = argb(ramp(landStops, t))

    private fun ramp(stops: List<Pair<Float, IntArray>>, t: Float): IntArray {
        if (t <= stops.first().first) return stops.first().second
        for (i in 0 until stops.size - 1) {
            val (t0, c0) = stops[i]
            val (t1, c1) = stops[i + 1]
            if (t <= t1) {
                val f = if (t1 == t0) 0f else (t - t0) / (t1 - t0)
                return intArrayOf(
                    lerp(c0[0], c1[0], f),
                    lerp(c0[1], c1[1], f),
                    lerp(c0[2], c1[2], f),
                )
            }
        }
        return stops.last().second
    }

    private fun lerp(a: Int, b: Int, f: Float): Int = (a + (b - a) * f).toInt()

    private fun argb(rgb: IntArray): Int =
        (0xFF shl 24) or (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2]
}
