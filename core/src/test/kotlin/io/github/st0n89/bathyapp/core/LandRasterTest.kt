package io.github.st0n89.bathyapp.core

import io.github.st0n89.bathyapp.core.geo.Coastlines
import io.github.st0n89.bathyapp.core.geo.LandRaster
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LandRasterTest {

    private fun landFile(vararg rings: IntArray): Coastlines {
        val sb = StringBuilder("NELAND ${rings.size}\n")
        for (r in rings) {
            sb.append(r.size / 2)
            for (i in 0 until r.size step 2) sb.append(' ').append(r[i]).append(' ').append(r[i + 1])
            sb.append('\n')
        }
        return Coastlines.read(ByteArrayInputStream(sb.toString().toByteArray(Charsets.UTF_8)), "NELAND")
    }

    @Test
    fun squareFillsInside() {
        // square 0..10 lon, 0..10 lat (closed ring)
        val c = landFile(intArrayOf(0, 0, 10, 0, 10, 10, 0, 10, 0, 0))
        val mask = LandRaster.rasterize(c, 36, 18)
        assertTrue(LandRasterIsLand(mask, 36, 18, 5.0, 5.0))
        assertFalse(LandRasterIsLand(mask, 36, 18, 20.0, 5.0))
        assertFalse(LandRasterIsLand(mask, 36, 18, 5.0, -5.0))
        assertFalse(LandRasterIsLand(mask, 36, 18, 5.0, 20.0))
    }

    @Test
    fun holeCutsOut() {
        val outer = intArrayOf(0, 0, 20, 0, 20, 20, 0, 20, 0, 0)
        val hole = intArrayOf(5, 5, 10, 5, 10, 10, 5, 10, 5, 5)
        val c = landFile(outer, hole)
        val mask = LandRaster.rasterize(c, 72, 36)
        assertTrue(LandRasterIsLand(mask, 72, 36, 2.0, 8.0))   // ring between outer and hole
        assertFalse(LandRasterIsLand(mask, 72, 36, 7.0, 7.0)) // inside the hole
        assertTrue(LandRasterIsLand(mask, 72, 36, 15.0, 15.0))
    }

    private fun LandRasterIsLand(mask: ByteArray, w: Int, h: Int, lon: Double, lat: Double): Boolean {
        val x = ((lon + 180.0) * w / 360.0).toInt().coerceIn(0, w - 1)
        val y = ((90.0 - lat) * h / 180.0).toInt().coerceIn(0, h - 1)
        return mask[y * w + x] != 0.toByte()
    }
}
