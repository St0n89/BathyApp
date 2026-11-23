package io.github.st0n89.bathyapp.core

import io.github.st0n89.bathyapp.core.sediment.SedGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class SedGridTest {

    private fun ascii(ncols: Int, nrows: Int, rows: List<String>, nodata: String = "-9999", yll: Double = -5.25): String {
        val sb = StringBuilder()
        sb.append("ncols         $ncols\n")
        sb.append("nrows         $nrows\n")
        sb.append("xllcorner     -10.25\n")
        sb.append("yllcorner     $yll\n")
        sb.append("cellsize      0.5\n")
        sb.append("NODATA_value  $nodata\n")
        for (r in rows) sb.append(r).append('\n')
        return sb.toString()
    }

    @Test
    fun parseHeaderAndRegistration() {
        // 4x3 grid: ncols 4 at 0.5 deg from xllcorner -10.25 ->
        // centers -10, -9.5, -9, -8.5 (corner + cell/2)
        val txt = ascii(4, 3, listOf("10 20 30 40", "50 60 70 80", "90 100 110 120"))
        val g = SedGrid.parseAscii(ByteArrayInputStream(txt.toByteArray()))
        assertEquals(4, g.nx)
        assertEquals(3, g.ny)
        assertEquals(-10.0, g.west, 1e-12)
        // yllcorner -5.25 + cell/2 = -5.0 south edge, north = -5 + 2*0.5 = -4.0
        assertEquals(-4.0, g.north, 1e-12)
        assertEquals(-8.5, g.east, 1e-12)
        assertEquals(-5.0, g.south, 1e-12)
        // row 0 is north-first
        assertEquals(10.0, g[0, 0].toDouble(), 0.0)
        assertEquals(40.0, g[0, 3].toDouble(), 0.0)
        assertEquals(120.0, g[2, 3].toDouble(), 0.0)
    }

    @Test
    fun qnanAndNodataTokensBecomeZero() {
        val txt = ascii(3, 2, listOf("1.#QNAN 12.5 -9999", "1.#QNAN 0.0 3"))
        val g = SedGrid.parseAscii(ByteArrayInputStream(txt.toByteArray()))
        assertEquals(0, g[0, 0].toInt())
        assertEquals(13, g[0, 1].toInt()) // 12.5 rounds to 13
        assertEquals(0, g[0, 2].toInt())
        assertEquals(0, g[1, 0].toInt())
        assertEquals(0, g[1, 1].toInt())
        assertEquals(3, g[1, 2].toInt())
    }

    @Test
    fun fractionalMetresRoundToNearestInt() {
        val txt = ascii(2, 2, listOf("100.4 100.5", "0 0"))
        val g = SedGrid.parseAscii(ByteArrayInputStream(txt.toByteArray()))
        assertEquals(100, g[0, 0].toInt())
        assertEquals(101, g[0, 1].toInt()) // round(100.5) rounds up
    }

    @Test
    fun sampleBilinearBlendsNodeValues() {
        // 2x2, rows centred -4.0 (north) and -4.5 (south): yll = south - cell/2
        val txt = ascii(2, 2, listOf("100 200", "300 400"), yll = -4.75)
        val g = SedGrid.parseAscii(ByteArrayInputStream(txt.toByteArray()))
        // nodes: (lon -10, lat -4) = 100; (-9.5, -4) = 200; (-10, -4.5) = 300; (-9.5, -4.5) = 400
        assertEquals(100.0, g.sample(-10.0, -4.0), 1e-9)
        assertEquals(150.0, g.sample(-9.75, -4.0), 1e-9)
        assertEquals(250.0, g.sample(-9.75, -4.25), 1e-9)
        assertEquals(200.0, g.sample(-10.0, -4.25), 1e-9) // blend of 100 (-4.0) and 300 (-4.5)
        assertEquals(400.0, g.sample(-9.5, -4.5), 1e-9)
    }

    @Test
    fun sampleWrapsLongitudeAndClampsLatitude() {
        val txt = ascii(8, 2, listOf("1 2 3 4 5 6 7 8", "9 10 11 12 13 14 15 16"))
        val g = SedGrid.parseAscii(ByteArrayInputStream(txt.toByteArray()))
        // lon 350 wraps to -10 -> node col 0
        assertEquals(1.0, g.sample(350.0, g.north), 1e-9)
        assertEquals(2.0, g.sample(-9.5 + 360.0, g.north), 1e-9)
        // lat clamp: beyond the last row keeps the edge row
        assertEquals(9.0, g.sample(-10.0, -80.0), 1e-9)
        assertEquals(1.0, g.sample(-10.0, 80.0), 1e-9)
    }

    @Test
    fun truncationFailsLoudly() {
        val txt = ascii(4, 3, listOf("1 2 3 4", "5 6"))
        var thrown = false
        try {
            SedGrid.parseAscii(ByteArrayInputStream(txt.toByteArray()))
        } catch (e: IOException) {
            thrown = true
        }
        assertTrue("truncated input must throw IOException", thrown)
    }

    @Test
    fun statsCountsNonZeroCells() {
        val txt = ascii(2, 2, listOf("0 100", "200 0"))
        val g = SedGrid.parseAscii(ByteArrayInputStream(txt.toByteArray()))
        val (n, mx, mean) = g.stats()
        assertEquals(2, n)
        assertEquals(200, mx)
        assertEquals(150, mean)
    }
}
