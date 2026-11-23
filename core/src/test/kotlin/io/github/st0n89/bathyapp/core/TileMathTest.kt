package io.github.st0n89.bathyapp.core

import io.github.st0n89.bathyapp.core.geo.TileMath
import io.github.st0n89.bathyapp.core.geo.TileWindow
import io.github.st0n89.bathyapp.core.geo.composeTileOverlay
import kotlin.math.abs
import kotlin.math.PI
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class TileMathTest {

    // ---------------- mercator basics ----------------

    @Test
    fun lonRoundtripAtSeveralZooms() {
        for (z in 0..17) {
            for (lon in listOf(-180.0, -90.0, -13.5, 0.0, 9.99, 123.45, 180.0)) {
                val back = TileMath.pxXToLon(TileMath.lonToPxX(lon, z), z)
                assertTrue("z=$z lon=$lon back=$back", abs(back - lon) < 360.0 / TileMath.worldPx(z))
            }
        }
    }

    @Test
    fun equatorAndPolesMapToCanonicalRows() {
        assertEquals(TileMath.worldPx(5) / 2.0, TileMath.latToPxY(0.0, 5), 1e-6)
        // clamped pole sits at the very top/bottom row of the world
        assertEquals(0.0, TileMath.latToPxY(85.05112878, 5), 1e-3)
        assertEquals(TileMath.worldPx(5), TileMath.latToPxY(-85.05112878, 5), 1e-3)
        // tolerance shrinks with zoom
        assertEquals(0.0, TileMath.latToPxY(89.9, 7), 0.6)
    }

    @Test
    fun latRoundtripWithinMercatorLimits() {
        for (lat in listOf(-84.0, -45.0, -0.5, 30.0, 53.55, 85.0)) {
            val back = TileMath.pxYToLat(TileMath.latToPxY(lat, 9), 9)
            assertEquals(lat, back, 1e-4)
        }
    }

    @Test
    fun tileIndexWrapsBothDirections() {
        assertEquals(3, TileMath.tileIndex(-1, 2))
        assertEquals(0, TileMath.tileIndex(4, 2))
        assertEquals((1 shl 10) - 1, TileMath.tileIndex(-1, 10))
        assertEquals(0, TileMath.tileIndex(1 shl 10, 10))
        // x periodicity covers any continuous-longitude tile math
        assertEquals(3, TileMath.tileIndex(99, 3))
        assertEquals(5, TileMath.tileIndex(13, 3))
    }

    // ---------------- window selection ----------------

    @Test
    fun chooseWindowPicksLargestZoomInsideCaps() {
        // ~2.5 deg wide window: tiles must fit the 64-tile / 2048px caps
        val w = TileMath.chooseWindow(8.0, 52.0, 10.5, 54.5, minZoom = 6)
        assertNotNull(w)
        assertTrue(w!!.zoom in 6..17)
        val wPx = w.eastPxX - w.westPxX
        val hPx = w.southPxY - w.northPxY
        assertTrue(wPx in 256.0..2048.0)
        assertTrue(hPx in 256.0..2048.0)
        // tile-aligned bounds
        assertEquals(0.0, w.westPxX % TileMath.TILE_PX, 1e-9)
        assertEquals(0.0, w.eastPxX % TileMath.TILE_PX, 1e-9)
        // one zoom more would break the caps (that's why this one was chosen)
        val z = w.zoom
        val x0 = TileMath.lonToPxX(8.0, z + 1)
        val x1 = TileMath.lonToPxX(10.5, z + 1)
        val y0 = TileMath.latToPxY(54.5, z + 1)
        val y1 = TileMath.latToPxY(52.0, z + 1)
        val tilesX = (java.lang.Math.floor((x1 - 1e-9) / 256.0).toInt() -
            java.lang.Math.floor(x0 / 256.0).toInt() + 1)
        val tilesY = (java.lang.Math.floor((y1 - 1e-9) / 256.0).toInt() -
            java.lang.Math.floor((y0 + 1e-9) / 256.0).toInt() + 1)
        val exceeds = tilesX * tilesY > 64 ||
            tilesX * 256 > 2048 || tilesY * 256 > 2048
        assertTrue("next zoom should exceed caps", exceeds)
    }

    @Test
    fun chooseWindowReturnsNullForWholeGlobe() {
        // whole-globe windows must stay offline: too many tiles at any useful zoom
        assertNull(TileMath.chooseWindow(-20.0, -80.0, 200.0, 80.0, minZoom = 6))
    }

    @Test
    fun chooseWindowHandlesContinuousDatelineLongitudes() {
        // a window around lon 175 spanning across the dateline, in continuous space
        val w = TileMath.chooseWindow(170.0, 10.0, 195.0, 20.0, minZoom = 6)
        assertNotNull(w)
        // pixel bounds grow monotonically with longitude, no wrap jump
        assertTrue(w!!.eastPxX > w.westPxX)
        // bbox recovered from pixels crosses 180 continuously
        val eastLon = TileMath.pxXToLon(w.eastPxX, w.zoom)
        assertTrue(eastLon > 180.0 || eastLon >= 194.9)
    }

    // ---------------- overlay composition ----------------

    /** Fake tile source: every tile uniformly coloured by (x*7 + y*13 + 1). */
    private fun fakeSource(available: (Int, Int) -> Boolean = { _, _ -> true }):
        (Int, Int, Int) -> IntArray? = { _, x, y ->
        if (available(x, y)) {
            val c = ((x * 7 + y * 13 + 1) % 200) + 40
            IntArray(256 * 256) { 0xFF000000.toInt() or (c shl 16) or (c shl 8) or c }
        } else {
            null
        }
    }

    @Test
    fun composeOverlayFillsGeoAlignedRegion() {
        val z = 8
        val x0 = 130; val y0 = 84 // tile coords of a small window near Hamburg
        val w = TileWindow(
            zoom = z,
            westPxX = x0 * 256.0, northPxY = y0 * 256.0,
            eastPxX = (x0 + 1) * 256.0, southPxY = (y0 + 1) * 256.0,
        )
        val ovl = composeTileOverlay(w, fakeSource())
        assertEquals(256, ovl.width)
        assertEquals(256, ovl.height)
        // bbox matches the tile's geo extent
        assertEquals(TileMath.pxXToLon(x0 * 256.0, z), ovl.west, 1e-9)
        assertEquals(TileMath.pxXToLon((x0 + 1) * 256.0, z), ovl.east, 1e-9)
        assertEquals(TileMath.pxYToLat(y0 * 256.0, z), ovl.north, 1e-9)
        assertEquals(TileMath.pxYToLat((y0 + 1) * 256.0, z), ovl.south, 1e-9)
        // fully filled, uniform colour (c = (130*7 + 84*13 + 1) % 200 + 40 = 43)
        assertEquals(1.0, ovl.filledFraction, 1e-9)
        val p = ovl.px[123 * 256 + 45]
        assertEquals(
            (0xFF000000.toInt() or (43 shl 16) or (43 shl 8) or 43),
            p,
        )
        // centre sampling helpers are consistent with the bbox
        val cx = ovl.toX((ovl.west + ovl.east) / 2)
        val cy = ovl.toY((ovl.north + ovl.south) / 2)
        assertEquals(127.5, cx, 1e-6)
        assertEquals(127.5, cy, 1e-6)
        assertTrue(ovl.latIn((ovl.north + ovl.south) / 2))
        assertTrue(ovl.lonIn((ovl.west + ovl.east) / 2))
    }

    @Test
    fun composeOverlayWrapsDatelineTiles() {
        // window around lon 180 spanning the dateline, in continuous space (170..190)
        val w = TileMath.chooseWindow(170.0, -15.0, 190.0, 15.0, minZoom = 4)
        assertNotNull(w)
        val ovl = composeTileOverlay(w!!, fakeSource())
        // every pixel got a tile through the wrap, including tiles "east of 180"
        assertEquals(1.0, ovl.filledFraction, 1e-9)
        assertTrue(ovl.west < 180.0)
        assertTrue(ovl.east > 180.0)
    }

    @Test
    fun composeOverlayMarksMissingTilesAsTransparent() {
        val z = 6
        val w = TileWindow(
            zoom = z,
            westPxX = 32 * 256.0, northPxY = 20 * 256.0,
            eastPxX = 34 * 256.0, southPxY = 21 * 256.0,
        )
        // 2 tiles wide: right half offline
        val ovl = composeTileOverlay(w) { zz, x, _ -> if (zz == z && x == 32) fakeSource()(zz, x, 0) else null }
        assertEquals(0.5, ovl.filledFraction, 0.02)
        // left half non-zero, right half zero
        assertTrue(ovl.px[128 * 512 + 100] != 0)
        assertEquals(0, ovl.px[128 * 512 + 400])
    }

    @Test
    fun latToPxYIsMonotonicSouthward() {
        var prev = TileMath.latToPxY(85.0, 6)
        for (lat in 80 downTo -80 step 10) {
            val y = TileMath.latToPxY(lat.toDouble(), 6)
            assertTrue(y > prev)
            prev = y
        }
        // metre resolution decreases with latitude
        assertTrue(TileMath.metresPerPx(0.0, 10) > TileMath.metresPerPx(60.0, 10))
    }

    @Test
    fun mercatorConsistentWithSphereGeometry() {
        // equatorial circumference at zoom z covers exactly 2^z * 256 tile pixels
        val circumference = 2.0 * PI * 6378137.0
        val metresPerPx0 = 156543.03392 / (1L shl 10)
        assertEquals(TileMath.worldPx(10), circumference / metresPerPx0, 1.0)
        // 45 deg of latitude near the equator maps into the upper half of the world
        val yEq = TileMath.latToPxY(0.0, 1)
        val y45 = TileMath.latToPxY(45.0, 1)
        assertTrue(y45 < yEq)
    }
}
