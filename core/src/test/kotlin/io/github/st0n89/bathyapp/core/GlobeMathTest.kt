package io.github.st0n89.bathyapp.core

import io.github.st0n89.bathyapp.core.geo.Coastlines
import io.github.st0n89.bathyapp.core.geo.GlobeMath
import java.io.ByteArrayInputStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GlobeMathTest {

    private fun close(a: Double, b: Double, tol: Double = 1e-6) = abs(a - b) <= tol

    @Test
    fun projectRoundtripVisiblePoints() {
        val lat0 = 52.0
        val lon0 = 11.0
        var checked = 0
        for (lat in -80..80 step 20) {
            for (dlon in -80..80 step 20) {
                val lon = lon0 + dlon
                val p = GlobeMath.projectUnit(lat.toDouble(), lon.toDouble(), lat0, lon0) ?: continue
                val back = GlobeMath.unprojectUnit(p.first, p.second, lat0, lon0)!!
                assertTrue(close(back.first, lat.toDouble(), 1e-7), "lat $lat dlon $dlon -> ${back.first}")
                assertTrue(close(back.second, lon.toDouble(), 1e-7), "lon recon for dlon $dlon -> ${back.second}")
                checked++
            }
        }
        assertTrue(checked > 30, "expected a decent number of visible points, got $checked")
    }

    @Test
    fun projectCenterIsOrigin() {
        val p = GlobeMath.projectUnit(30.0, 45.0, 30.0, 45.0)!!
        assertTrue(close(p.first, 0.0))
        assertTrue(close(p.second, 0.0))
    }

    @Test
    fun projectEquatorEast() {
        // lat0 = 0, lon0 = 0: equator 45E -> x = sin(45), y = 0
        val p = GlobeMath.projectUnit(0.0, 45.0, 0.0, 0.0)!!
        assertTrue(close(p.first, kotlin.math.sin(kotlin.math.PI / 4)))
        assertTrue(close(p.second, 0.0))
    }

    @Test
    fun projectNorthPoleVisibleFromMidLat() {
        // pole is visible iff same hemisphere: from lat0 = 45 the pole sits at y = cos? sin(90)-... compute:
        val p = GlobeMath.projectUnit(90.0, 123.0, 45.0, 20.0)!!
        assertTrue(close(p.first, 0.0, 1e-9))
        assertTrue(close(p.second, kotlin.math.cos(Math.toRadians(45.0)) * kotlin.math.sin(Math.toRadians(90.0)) - kotlin.math.sin(Math.toRadians(45.0)) * 0.0, 1e-9))
        val back = GlobeMath.unprojectUnit(p.first, p.second, 45.0, 20.0)!!
        assertTrue(close(back.first, 90.0, 1e-7))
    }

    @Test
    fun farSideIsNull() {
        // antipode is never visible
        assertNull(GlobeMath.projectUnit(-30.0, 100.0, 30.0, -80.0))
        assertFalse(GlobeMath.visible(-30.0, 100.0, 30.0, -80.0))
    }

    @Test
    fun unprojectOutsideDiskIsNull() {
        assertNull(GlobeMath.unprojectUnit(1.2, 0.0, 0.0, 0.0))
        assertNull(GlobeMath.unprojectUnit(0.0, -1.01, 0.0, 0.0))
    }

    @Test
    fun unprojectAtCenterReturnsCenter() {
        val back = GlobeMath.unprojectUnit(0.0, 0.0, -33.0, 97.0)!!
        assertTrue(close(back.first, -33.0))
        assertTrue(close(back.second, 97.0))
    }

    @Test
    fun bboxAroundNormal() {
        val b = GlobeMath.bboxAround(50.0, 10.0, 6.0, 4.0)
        assertEquals(7.0, b[0], 1e-9)
        assertEquals(48.0, b[1], 1e-9)
        assertEquals(13.0, b[2], 1e-9)
        assertEquals(52.0, b[3], 1e-9)
    }

    @Test
    fun bboxAroundClampsAtDateline() {
        val b = GlobeMath.bboxAround(0.0, 179.0, 6.0, 4.0)
        assertEquals(174.0, b[0], 1e-9)
        assertEquals(180.0, b[2], 1e-9)
        assertEquals(6.0, b[2] - b[0], 1e-9)
        assertEquals(-2.0, b[1], 1e-9)
        assertEquals(2.0, b[3], 1e-9)
    }

    @Test
    fun bboxAroundClampsAtSouthPoleKeepsSpan() {
        val b = GlobeMath.bboxAround(-89.0, 0.0, 6.0, 4.0)
        assertEquals(-90.0, b[1], 1e-9)
        assertEquals(4.0, b[3] - b[1], 1e-9)
    }

    @Test
    fun bboxAroundRejectsBadSpans() {
        assertFailsWith<IllegalArgumentException> { GlobeMath.bboxAround(0.0, 0.0, 0.0, 4.0) }
        assertFailsWith<IllegalArgumentException> { GlobeMath.bboxAround(0.0, 0.0, 6.0, 0.001) }
    }

    @Test
    fun normLonWraps() {
        assertEquals(-180.0, GlobeMath.normLon(180.0), 1e-9)
        assertEquals(0.0, GlobeMath.normLon(360.0), 1e-9)
        assertEquals(-10.0, GlobeMath.normLon(350.0), 1e-9)
        assertEquals(10.0, GlobeMath.normLon(-350.0), 1e-9)
    }
}

class CoastlinesTest {

    @Test
    fun readSimpleFile() {
        val text = """
            NECOAST 2
            3 1.0 2.0 3.0 4.0 5.0 6.0
            2 7.0 8.0 9.0 10.0
        """.trimIndent()
        val c = Coastlines.read(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
        assertEquals(2, c.polylines.size)
        assertEquals(5, c.pointCount)
        assertEquals(3.0f, c.polylines[0][2])
        assertEquals(9.0f, c.polylines[1][2])
    }

    @Test
    fun readRejectsGarbage() {
        try {
            Coastlines.read(ByteArrayInputStream("NOTACOAST 1\n1 0.0 0.0\n".toByteArray(Charsets.UTF_8)))
            throw AssertionError("expected failure")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
