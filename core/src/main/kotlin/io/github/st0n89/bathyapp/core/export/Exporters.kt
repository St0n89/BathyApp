package io.github.st0n89.bathyapp.core.export

import io.github.st0n89.bathyapp.core.geo.GeoGrid
import io.github.st0n89.bathyapp.core.profile.ProfilePoint
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.Locale

/** CSV / GeoTIFF exporters. All outputs use raw grid values: metres, positive up. */
object Exporters {

    private fun writer(out: OutputStream) = OutputStreamWriter(out, StandardCharsets.UTF_8)

    /** Grid CSV: lon,lat,elevation_m for every cell centre. */
    fun writeGridCsv(grid: GeoGrid, out: OutputStream, sourceNote: String) {
        val w = writer(out)
        w.write("# BathyApp grid export\n")
        w.write("# source: $sourceNote\n")
        w.write("# units: metres, positive up (negative = water depth below MSL)\n")
        w.write("lon,lat,elevation_m\n")
        val sb = StringBuilder(64)
        for (r in 0 until grid.ny) {
            val lat = grid.latOf(r)
            for (c in 0 until grid.nx) {
                sb.setLength(0)
                fmt(sb, grid.lonOf(c)).append(',')
                fmt(sb, lat).append(',')
                val v = grid.values[r * grid.nx + c]
                if (v.isNaN()) sb.append("NaN") else fmt(sb, v.toDouble())
                sb.append('\n')
                w.write(sb.toString())
            }
        }
        w.flush()
    }

    /** Profile CSV: distance_km,lon,lat,elevation_m. */
    fun writeProfileCsv(points: List<ProfilePoint>, out: OutputStream, sourceNote: String) {
        val w = writer(out)
        w.write("# BathyApp profile export\n")
        w.write("# source: $sourceNote\n")
        w.write("# units: metres, positive up (negative = water depth below MSL)\n")
        w.write("distance_km,lon,lat,elevation_m\n")
        val sb = StringBuilder(64)
        for (p in points) {
            sb.setLength(0)
            fmt(sb, p.distKm).append(',')
            fmt(sb, p.lon).append(',')
            fmt(sb, p.lat).append(',')
            if (p.elevation.isNaN()) sb.append("NaN") else fmt(sb, p.elevation)
            sb.append('\n')
            w.write(sb.toString())
        }
        w.flush()
    }

    /** One-line summary of the extent for export headers. */
    fun extentNote(grid: GeoGrid): String =
        String.format(Locale.ROOT, "bbox W%.4f S%.4f E%.4f N%.4f, %dx%d cells", grid.west, grid.south, grid.east, grid.north, grid.nx, grid.ny)

    private fun fmt(sb: StringBuilder, v: Double): StringBuilder {
        if (v == kotlin.math.floor(v) && kotlin.math.abs(v) < 1e15) {
            sb.append(v.toLong().toString())
        } else {
            sb.append(String.format(Locale.ROOT, "%.6f", v))
        }
        return sb
    }
}
