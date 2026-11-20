package io.github.st0n89.bathyapp.core.geo

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Spherical math for an orthographic globe (unit radius, y north-up).
 * Screen code converts to pixels; everything here is in globe radii.
 */
object GlobeMath {

    /**
     * Orthographic (perspective from infinity) projection. Returns the
     * (x, y) position in globe radii, or null if the point is on the far
     * side (cos(c) < 0).
     */
    fun projectUnit(lat: Double, lon: Double, lat0: Double, lon0: Double): Pair<Double, Double>? {
        val phi = Math.toRadians(lat)
        val lam = Math.toRadians(lon)
        val phi0 = Math.toRadians(lat0)
        val dlam = lam - Math.toRadians(lon0)
        val cosc = sin(phi0) * sin(phi) + cos(phi0) * cos(phi) * cos(dlam)
        if (cosc < 0.0) return null
        val x = cos(phi) * sin(dlam)
        val y = cos(phi0) * sin(phi) - sin(phi0) * cos(phi) * cos(dlam)
        return x to y
    }

    /**
     * Inverse orthographic projection for a point given in globe radii
     * (x east, y north). Returns (lat, lon) or null outside the disk.
     */
    fun unprojectUnit(x: Double, y: Double, lat0: Double, lon0: Double): Pair<Double, Double>? {
        val rho = sqrt(x * x + y * y)
        if (rho > 1.0) return null
        val phi0 = Math.toRadians(lat0)
        val lam0 = Math.toRadians(lon0)
        val phi: Double
        val lam: Double
        if (rho < 1e-9) {
            phi = phi0
            lam = lam0
        } else {
            val c = asin(rho)
            phi = asin(cos(c) * sin(phi0) + y * sin(c) * cos(phi0) / rho)
            lam = lam0 + atan2(x * sin(c), rho * cos(phi0) * cos(c) - y * sin(phi0) * sin(c))
        }
        val lat = Math.toDegrees(phi).coerceIn(-90.0, 90.0)
        var lon = Math.toDegrees(lam)
        lon = ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        return lat to lon
    }

    /** True if the point is on the visible hemisphere (edge included). */
    fun visible(lat: Double, lon: Double, lat0: Double, lon0: Double): Boolean {
        val phi = Math.toRadians(lat)
        val phi0 = Math.toRadians(lat0)
        val cosc = sin(phi0) * sin(phi) + cos(phi0) * cos(phi) * cos(Math.toRadians(lon - lon0))
        return cosc >= 0.0
    }

    /**
     * Builds a lon/lat bbox [w, s, e, n] centred on (lon, lat) with the
     * given spans, shifted (never resized) so it stays inside the world.
     */
    fun bboxAround(lat: Double, lon: Double, spanLon: Double, spanLat: Double): DoubleArray {
        require(spanLon in 0.002..360.0 && spanLat in 0.002..180.0) { "invalid spans" }
        var w = lon - spanLon / 2.0
        var e = lon + spanLon / 2.0
        var s = lat - spanLat / 2.0
        var n = lat + spanLat / 2.0
        if (s < -90.0) { val o = -90.0 - s; s = -90.0; n += o }
        if (n > 90.0) { val o = n - 90.0; n = 90.0; s -= o }
        if (w < -180.0) { val o = -180.0 - w; w = -180.0; e += o }
        if (e > 180.0) { val o = e - 180.0; e = 180.0; w -= o }
        return doubleArrayOf(w, s, e, n)
    }

    /** Clamps arbitrary lon into [-180, 180) and lat into [-90, 90]. */
    fun normLat(lat: Double): Double = lat.coerceIn(-90.0, 90.0)

    fun normLon(lon: Double): Double = ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
}
