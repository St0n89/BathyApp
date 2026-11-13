package io.github.st0n89.bathyapp.core

import io.github.st0n89.bathyapp.core.emodnet.EmodnetWcs
import io.github.st0n89.bathyapp.core.export.Exporters
import io.github.st0n89.bathyapp.core.geo.GeoGrid
import io.github.st0n89.bathyapp.core.profile.Profiler
import io.github.st0n89.bathyapp.core.profile.ProfilePoint
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProfilerTest {

    private fun linearGrid(): GeoGrid {
        // elevation = -100 - 10 * (lat - 50) over 50..54 N, 8..12 E
        val nx = 41; val ny = 41
        val v = FloatArray(nx * ny) { i ->
            val r = i / nx
            val c = i % nx
            val lat = 50.0 + r * 0.1
            val lon = 8.0 + c * 0.1
            (-100 - 10 * (lat - 50) - 2 * (lon - 8)).toFloat()
        }
        return GeoGrid(nx, ny, v, 8.0, 50.0, 12.0, 54.0)
    }

    @Test
    fun meridianProfileLengthMatchesHaversine() {
        val grid = linearGrid()
        val pts = Profiler.sample(grid, 10.0, 50.0, 10.0, 51.0, 101)
        val d = Profiler.haversineKm(10.0, 50.0, 10.0, 51.0)
        assertEquals(d, pts.last().distKm, 0.01)
        assertEquals(111.19, d, 0.1) // sanity: ~111.19 km per degree latitude
    }

    @Test
    fun profileDepthFollowsLinearSlope() {
        val grid = linearGrid()
        // along the meridian lon=10: e = -100 - 10*(lat-50) - 2*2
        val pts = Profiler.sample(grid, 10.0, 50.0, 10.0, 52.0, 201)
        val mid = pts[100]
        assertEquals(-114.0, mid.elevation, 0.2) // lat 51 -> -100 - 10 - 4
        val st = Profiler.stats(pts)
        assertEquals(-124.0, st.minElevation, 0.5)
        assertEquals(-104.0, st.maxElevation, 0.5)
    }

    @Test
    fun profileOutsideGridIsNaN() {
        val grid = linearGrid()
        val pts = Profiler.sample(grid, 10.0, 50.0, 10.0, 60.0, 51)
        assertTrue(pts.last().elevation.isNaN())
        val st = Profiler.stats(pts)
        // valid samples still counted
        assertTrue(st.nValid in 1 until 51)
    }

    @Test
    fun csvExportsContainHeadersAndValues() {
        val grid = linearGrid()
        val out = ByteArrayOutputStream()
        Exporters.writeGridCsv(grid, out, "unit test")
        val text = out.toString("UTF-8")
        assertTrue(text.startsWith("# BathyApp grid export"))
        assertTrue(text.contains("lon,lat,elevation_m"))
        assertTrue(text.contains("8.05,53.95,-100"))

        val pts = listOf(
            ProfilePoint(0.0, 10.0, 50.0, -104.0),
            ProfilePoint(1.5, 10.0, 50.0135, -104.5),
        )
        val pout = ByteArrayOutputStream()
        Exporters.writeProfileCsv(pts, pout, "unit test")
        val ptext = pout.toString("UTF-8")
        assertTrue(ptext.contains("distance_km,lon,lat,elevation_m"))
        assertTrue(ptext.contains("0,10,50,-104"))
    }

    @Test
    fun wcsUrlMatchesValidatedPattern() {
        val url = EmodnetWcs.buildUrl("emodnet__mean", 5.0, 53.0, 5.2, 53.2, 400)
        assertTrue(url.startsWith("https://ows.emodnet-bathymetry.eu/wcs?service=WCS&version=2.0.1&request=GetCoverage"))
        assertTrue(url.contains("coverageid=emodnet__mean"))
        assertTrue(url.contains("subset=Long(5.000000,5.200000)"))
        assertTrue(url.contains("subset=Lat(53.000000,53.200000)"))
        // 0.2 x 0.2 degrees with 400 cols -> 400 rows
        assertTrue(url.contains("scaleSize=i(400),j(400)"))
        assertTrue(url.contains("format=image/tiff"))
    }

    @Test
    fun wcsUrlComputesRowsFromAspectRatio() {
        val url = EmodnetWcs.buildUrl("emodnet__mean", 0.0, 50.0, 4.0, 52.0, 400)
        assertTrue(url.contains("scaleSize=i(400),j(200)"))
    }
}
