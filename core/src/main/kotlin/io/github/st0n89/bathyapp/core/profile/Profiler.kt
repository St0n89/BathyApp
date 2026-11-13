package io.github.st0n89.bathyapp.core.profile

import io.github.st0n89.bathyapp.core.geo.GeoGrid
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class ProfilePoint(val distKm: Double, val lon: Double, val lat: Double, val elevation: Double)
data class ProfileStats(
    val lengthKm: Double,
    val minElevation: Double,
    val maxElevation: Double,
    val meanElevation: Double,
    val nValid: Int,
)

object Profiler {

    const val EARTH_RADIUS_KM = 6371.0088

    /**
     * Samples a great-circle profile between two points from [grid].
     * Returns [n] points, inclusive of both endpoints. Values follow the
     * grid convention: metres, positive up (water depth negative).
     */
    fun sample(grid: GeoGrid, lon0: Double, lat0: Double, lon1: Double, lat1: Double, n: Int): List<ProfilePoint> {
        require(n >= 2) { "profile needs at least 2 samples" }
        val total = haversineKm(lon0, lat0, lon1, lat1)
        val p0 = toUnitVector(lon0, lat0)
        val p1 = toUnitVector(lon1, lat1)
        val dot = (p0[0] * p1[0] + p0[1] * p1[1] + p0[2] * p1[2]).coerceIn(-1.0, 1.0)
        val omega = kotlin.math.acos(dot)
        val sinOmega = sin(omega)

        return List(n) { i ->
            val t = i.toDouble() / (n - 1)
            val lat: Double
            val lon: Double
            if (sinOmega < 1e-10) {
                lon = lon0
                lat = lat0 + (lat1 - lat0) * t
            } else {
                val a = sin((1 - t) * omega) / sinOmega
                val b = sin(t * omega) / sinOmega
                val x = a * p0[0] + b * p1[0]
                val y = a * p0[1] + b * p1[1]
                val z = a * p0[2] + b * p1[2]
                lat = toDegrees(atan2(z, sqrt(x * x + y * y)))
                lon = toDegrees(atan2(y, x))
            }
            val e = grid.sample(lon, lat)
            ProfilePoint(t * total, lon, lat, if (e.isNaN()) Double.NaN else e.toDouble())
        }
    }

    fun stats(points: List<ProfilePoint>): ProfileStats {
        var mn = Double.POSITIVE_INFINITY
        var mx = Double.NEGATIVE_INFINITY
        var sum = 0.0
        var n = 0
        for (p in points) {
            if (p.elevation.isNaN()) continue
            mn = min(mn, p.elevation)
            mx = max(mx, p.elevation)
            sum += p.elevation
            n++
        }
        val len = points.lastOrNull()?.distKm ?: 0.0
        return ProfileStats(
            lengthKm = len,
            minElevation = if (n == 0) Double.NaN else mn,
            maxElevation = if (n == 0) Double.NaN else mx,
            meanElevation = if (n == 0) Double.NaN else sum / n,
            nValid = n,
        )
    }

    fun haversineKm(lon0: Double, lat0: Double, lon1: Double, lat1: Double): Double {
        val dLat = toRadians(lat1 - lat0)
        val dLon = toRadians(lon1 - lon0)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(toRadians(lat0)) * cos(toRadians(lat1)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_KM * asin(sqrt(a))
    }

    private fun toUnitVector(lon: Double, lat: Double): DoubleArray {
        val la = toRadians(lat)
        val lo = toRadians(lon)
        return doubleArrayOf(cos(la) * cos(lo), cos(la) * sin(lo), sin(la))
    }

    private fun toRadians(d: Double): Double = d * PI / 180.0

    private fun toDegrees(r: Double): Double = r * 180.0 / PI
}
