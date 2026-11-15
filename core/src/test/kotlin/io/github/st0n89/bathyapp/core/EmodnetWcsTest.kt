package io.github.st0n89.bathyapp.core

import io.github.st0n89.bathyapp.core.emodnet.EmodnetWcs
import io.github.st0n89.bathyapp.core.emodnet.EmodnetWcs.NATIVE_CELL_LIMIT
import io.github.st0n89.bathyapp.core.emodnet.EmodnetWcs.NATIVE_RES_DEG
import io.github.st0n89.bathyapp.core.geo.GeoGrid
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EmodnetWcsTest {

    @Test
    fun buildUrlAutoRows() {
        val url = EmodnetWcs.buildUrl("emodnet__mean", 0.0, 51.0, 2.0, 52.0, 512)
        assertTrue(url.startsWith(EmodnetWcs.BASE_URL))
        assertTrue(url.contains("coverageid=emodnet__mean"))
        assertTrue(url.contains("subset=Long(0.000000,2.000000)"))
        assertTrue(url.contains("subset=Lat(51.000000,52.000000)"))
        assertTrue(url.contains("scaleSize=i(512),j(256)"))
        assertTrue(url.contains("format=image/tiff"))
    }

    @Test
    fun buildUrlExplicitRows() {
        val url = EmodnetWcs.buildUrl("emodnet__mean", 0.0, 51.0, 2.0, 52.0, 512, 100)
        assertTrue(url.contains("scaleSize=i(512),j(100)"))
    }

    @Test
    fun buildUrlValidation() {
        assertFailsWith<IllegalArgumentException> { EmodnetWcs.buildUrl("emodnet__mean", 2.0, 51.0, 0.0, 52.0, 512) }
        assertFailsWith<IllegalArgumentException> { EmodnetWcs.buildUrl("emodnet__mean", 0.0, 52.0, 2.0, 51.0, 512) }
        assertFailsWith<IllegalArgumentException> { EmodnetWcs.buildUrl("emodnet__mean", 0.0, 51.0, 2.0, 52.0, 1) }
        assertFailsWith<IllegalArgumentException> { EmodnetWcs.buildUrl("emodnet__mean", 0.0, 51.0, 2.0, 52.0, 512, 1) }
    }

    @Test
    fun tileCountsSmallBboxSingleTile() {
        // 1x1 deg = 921_600 native cells -> no tiling
        assertEquals(1, EmodnetWcs.tileCountX(0.0, 51.0, 1.0, 52.0))
        assertEquals(1, EmodnetWcs.tileCountY(0.0, 51.0, 1.0, 52.0))
    }

    @Test
    fun tileCountsLargeBbox() {
        // 6x4 deg = 22.1M native cells -> 2x2 tiles
        assertEquals(2, EmodnetWcs.tileCountX(0.0, 48.0, 6.0, 52.0))
        assertEquals(2, EmodnetWcs.tileCountY(0.0, 48.0, 6.0, 52.0))
    }

    @Test
    fun tileCountsFullExtentStayUnderLimit() {
        val west = EmodnetWcs.EXTENT_WEST
        val east = EmodnetWcs.EXTENT_EAST
        val south = EmodnetWcs.EXTENT_SOUTH
        val north = EmodnetWcs.EXTENT_NORTH
        val tx = EmodnetWcs.tileCountX(west, south, east, north)
        val ty = EmodnetWcs.tileCountY(west, south, east, north)
        assertTrue(tx >= 1 && ty >= 1)
        val perTile = ((east - west) / tx / NATIVE_RES_DEG * (north - south) / ty / NATIVE_RES_DEG).toLong()
        assertTrue(perTile <= NATIVE_CELL_LIMIT, "per-tile native cells $perTile exceed limit $NATIVE_CELL_LIMIT")
    }

    @Test
    fun tileCountsFormulaMatchesDefinition() {
        // recompute the definition for a mid-size bbox and compare
        val west = -10.0; val east = 20.0; val south = 40.0; val north = 62.0
        val native = ((east - west) / NATIVE_RES_DEG * (north - south) / NATIVE_RES_DEG).toLong()
        if (native > NATIVE_CELL_LIMIT) {
            val f = native.toDouble() / NATIVE_CELL_LIMIT
            val aspect = (east - west) / (north - south)
            val expTx = ceil(sqrt(f * aspect)).toInt()
            val expTy = ceil(sqrt(f / aspect)).toInt()
            assertEquals(expTx, EmodnetWcs.tileCountX(west, south, east, north))
            assertEquals(expTy, EmodnetWcs.tileCountY(west, south, east, north))
        } else {
            assertEquals(1, EmodnetWcs.tileCountX(west, south, east, north))
            assertEquals(1, EmodnetWcs.tileCountY(west, south, east, north))
        }
    }

    @Test
    fun splitEvenlyBalanced() {
        assertEquals(listOf(4, 3, 3), EmodnetWcs.splitEvenly(10, 3).toList())
        assertEquals(listOf(4, 3), EmodnetWcs.splitEvenly(7, 2).toList())
        assertEquals(listOf(2, 2), EmodnetWcs.splitEvenly(4, 2).toList())
        assertEquals(listOf(5), EmodnetWcs.splitEvenly(5, 1).toList())
    }

    @Test
    fun splitEvenlyMinSizeAndSum() {
        // more parts than half the total: clamp so each part stays >= 2
        val a = EmodnetWcs.splitEvenly(4, 3)
        assertEquals(2, a.size)
        assertEquals(4, a.sum())
        assertTrue(a.all { it >= 2 })
        val b = EmodnetWcs.splitEvenly(100, 7)
        assertEquals(7, b.size)
        assertEquals(100, b.sum())
        assertTrue(b.all { it >= 2 })
        assertTrue(b.max() - b.min() <= 1)
    }

    private fun constGrid(value: Float, nx: Int, ny: Int, west: Double, south: Double, east: Double, north: Double): GeoGrid =
        GeoGrid(nx, ny, FloatArray(nx * ny) { value }, west, south, east, north)

    @Test
    fun stitchUniformQuadrants() {
        // 2x2 tiles of constant values 10/20/30/40 over 0..4E, 50..52N
        val tiles: Array<Array<GeoGrid?>> = arrayOf(
            arrayOf(constGrid(10f, 2, 2, 0.0, 51.0, 2.0, 52.0), constGrid(20f, 2, 2, 2.0, 51.0, 4.0, 52.0)),
            arrayOf(constGrid(30f, 2, 2, 0.0, 50.0, 2.0, 51.0), constGrid(40f, 2, 2, 2.0, 50.0, 4.0, 51.0)),
        )
        val g = EmodnetWcs.stitch(tiles, 0.0, 50.0, 4.0, 52.0, 8, 8)
        assertEquals(8, g.nx)
        assertEquals(8, g.ny)
        assertTrue(g.values.all { !it.isNaN() })
        assertEquals(10f, g.sample(1.0, 51.5), 1e-4f)
        assertEquals(20f, g.sample(3.0, 51.5), 1e-4f)
        assertEquals(30f, g.sample(1.0, 50.5), 1e-4f)
        assertEquals(40f, g.sample(3.0, 50.5), 1e-4f)
    }

    @Test
    fun stitchUnevenTileDimsNoNaN() {
        // tiles with differing dimensions must still stitch cleanly
        val tiles: Array<Array<GeoGrid?>> = arrayOf(
            arrayOf(constGrid(10f, 3, 2, 0.0, 51.0, 2.0, 52.0), constGrid(20f, 2, 2, 2.0, 51.0, 4.0, 52.0)),
            arrayOf(constGrid(30f, 2, 3, 0.0, 50.0, 2.0, 51.0), constGrid(40f, 5, 4, 2.0, 50.0, 4.0, 51.0)),
        )
        val g = EmodnetWcs.stitch(tiles, 0.0, 50.0, 4.0, 52.0, 12, 10)
        assertTrue(g.values.all { !it.isNaN() })
        assertEquals(10f, g.sample(0.5, 51.75), 1e-4f)
        assertEquals(20f, g.sample(3.5, 51.75), 1e-4f)
        assertEquals(30f, g.sample(0.5, 50.25), 1e-4f)
        assertEquals(40f, g.sample(3.5, 50.25), 1e-4f)
    }

    @Test
    fun loadGridRejectsOutsideEurope() {
        // must fail fast without any network access
        val ex = assertFailsWith<IllegalStateException> {
            EmodnetWcs.loadGrid("emodnet__mean", 100.0, -50.0, 110.0, -40.0, 512)
        }
        assertTrue(ex.message!!.contains("EMODnet"))
    }
}
