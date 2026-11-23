package io.github.st0n89.bathyapp.core

import io.github.st0n89.bathyapp.core.geo.GeoGrid
import io.github.st0n89.bathyapp.core.seismic.BottomClass
import io.github.st0n89.bathyapp.core.seismic.BottomClasses
import io.github.st0n89.bathyapp.core.seismic.FdtdExport
import io.github.st0n89.bathyapp.core.seismic.FdtdExportResult
import io.github.st0n89.bathyapp.core.seismic.FdtdReceiver
import io.github.st0n89.bathyapp.core.seismic.FdtdShot
import io.github.st0n89.bathyapp.core.seismic.FdtdWaveform
import io.github.st0n89.bathyapp.core.sediment.SedGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class FdtdExportTest {

    // ---- fixtures ----------------------------------------------------------

    /** Water GeoGrid: 1000 m deep ocean, 60x40 cells over -0.5..0.5 E / -0.3..0.3 N. If [landRow] >= 0 that whole row is land. */
    private fun waterGrid(landRow: Int = -1): GeoGrid {
        val nx = 60
        val ny = 40
        val v = FloatArray(nx * ny) { -1000f }
        if (landRow >= 0) for (c in 0 until nx) v[landRow * nx + c] = 5f
        return GeoGrid(nx, ny, v, -0.5, -0.3, 0.5, 0.3)
    }

    /** SedGrid: 300 m sediment everywhere (7x7 nodes over -0.6..0.6 @ 0.2 deg). */
    private fun sedGrid(thickness: Int = 300): SedGrid {
        val nx = 7
        val ny = 7
        val v = ShortArray(nx * ny) { thickness.toShort() }
        return SedGrid(nx, ny, v, -0.6, 0.6, 0.2, 0.2)
    }

    private val shot = FdtdShot(lon = 0.0, lat = 0.0, depthM = 50.0, f0 = 25.0, waveform = FdtdWaveform.HANN)

    /** Receiver ~5 km east at the equator (0.045 deg lon). */
    private val receiver = FdtdReceiver(lon = 0.045, lat = 0.0, depthM = 900.0)

    private fun envOf(r: FdtdExportResult) = r.envText
    private fun srcOf(r: FdtdExportResult) = r.sourceText

    private fun doubleVar(text: String, name: String): Double {
        val m = Regex("$name\\s*=\\s*([0-9.eE+-]+)").find(text) ?: throw AssertionError("$name not found")
        return m.groupValues[1].toDouble()
    }

    private fun intVar(text: String, name: String): Int {
        val m = Regex("$name\\s*=\\s*(\\d+)").find(text) ?: throw AssertionError("$name not found")
        return m.groupValues[1].toInt()
    }

    private fun arrayTokens(text: String, name: String): List<Double> {
        val m = Regex("$name\\s*=\\s*Float32\\[([^]]*)]").find(text) ?: throw AssertionError("$name Float32 array not found")
        return m.groupValues[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { it.toDouble() }
    }

    // ------------------------------------------------------------------------

    @Test
    fun suiteContractMarkersPresent() {
        val r = FdtdExport.build(waterGrid(), sedGrid(), shot, listOf(receiver), "GEBCO test")
        val env = envOf(r)
        val src = srcOf(r)
        assertTrue("env must end with ElasticGrid", env.contains("grid = ElasticGrid(nx, nz, dx, dz, lam, mu, rho, Qp, Qs)"))
        assertTrue("env must define all contract vars", listOf(
            "dx = ", "dz = ", "xmax = ", "zmax = ", "water_depth = ", "pml_cells = ", "x = collect", "z = collect",
        ).all { env.contains(it) })
        assertTrue("recommended usage must be included", env.contains("run_fdtd(grid, sources, bc, tmax; monopoles = monopoles"))
        assertTrue("source must define monopoles Int contract", src.contains("monopoles = MonopoleSource[MonopoleSource(ix, iz, pulse_rate)]"))
        assertTrue("source must define bc", src.contains("bc = BC(:pml, :pml, :free, :pml)"))
        assertTrue("source must carry SL vars for run.jl", src.contains("SL_REF_RANGE"))
        // there is NO literal receiver list: the comprehension converts to Int indices
        assertTrue(src.contains("Receiver[Receiver(Int(round(rx / dx)) + 1, Int(round(rz / dz)) + 1"))
        assertTrue(src.contains("for (rx, rz) in zip(receiver_x, receiver_z)]"))
    }

    @Test
    fun gridSizingFromF0AndLine() {
        val r = FdtdExport.build(waterGrid(), sedGrid(), shot, listOf(receiver), "GEBCO test")
        val env = envOf(r)
        // 10 ppw at 25 Hz in water: dx = ceil(1500/250) = 6 m (dz = dx)
        assertEquals(6.0, doubleVar(env, "dx"), 1e-12)
        assertEquals(6.0, doubleVar(env, "dz"), 1e-12)
        val nx = intVar(env, "nx")
        val nz = intVar(env, "nz")
        assertTrue("nx sensible ($nx)", nx in 200..8000)
        assertTrue("nz sensible ($nz)", nz in 100..2000)
        assertEquals(nx, arrayTokens(env, "bathy").size)
        assertEquals(nx, arrayTokens(env, "sed_base").size)
        assertEquals(nx, arrayTokens(env, "sed_vp").size)
        assertEquals(nx, arrayTokens(env, "sed_Qs").size)
        // margins: far receiver at ~5 km, source ix must be interior
        val ix = intVar(srcOf(r), "ix")
        val iz = intVar(srcOf(r), "iz")
        assertTrue("ix interior ($ix of $nx)", ix in 16..(nx - 16))
        assertEquals(9, iz) // 50 m / 6 m + 1
    }

    @Test
    fun deepSedimentClassifiesMudAndShelfAsSand() {
        // deep water + 300 m sediment -> MUD everywhere on the slice
        val r = FdtdExport.build(waterGrid(), sedGrid(300), shot, listOf(receiver), "x")
        assertEquals(nxOf(r).toInt(), r.plan.classCounts[BottomClass.MUD]!!.toInt())
        // shelf grid (200 m water) + sediment -> SAND everywhere
        val shelf = GeoGrid(60, 40, FloatArray(60 * 40) { -150f }, -0.5, -0.3, 0.5, 0.3)
        val r2 = FdtdExport.build(shelf, sedGrid(300), shot.copy(depthM = 20.0), listOf(receiver.copy(depthM = 140.0)), "x")
        assertEquals(nxOf(r2).toInt(), r2.plan.classCounts[BottomClass.SAND]!!.toInt())
        // zero sediment -> ROCK
        val r3 = FdtdExport.build(waterGrid(), sedGrid(0), shot, listOf(receiver), "x")
        assertEquals(nxOf(r3).toInt(), r3.plan.classCounts[BottomClass.ROCK]!!.toInt())
    }

    private fun nxOf(r: FdtdExportResult): Int = intVar(envOf(r), "nx")

    @Test
    fun classificationBoundaryValues() {
        // thickness exactly at the threshold -> rock
        assertEquals(BottomClass.ROCK, BottomClasses.classify(1000.0, 25.0))
        assertEquals(BottomClass.MUD, BottomClasses.classify(1000.0, 25.1))
        // shelf boundary
        assertEquals(BottomClass.SAND, BottomClasses.classify(200.0, 100.0))
        assertEquals(BottomClass.MUD, BottomClasses.classify(200.1, 100.0))
    }

    @Test
    fun sourceOnLandRejected() {
        val g = waterGrid(landRow = 0) // land at the northernmost cell
        var thrown = false
        try {
            FdtdExport.build(g, sedGrid(), shot.copy(lat = 0.2925), listOf(receiver), "x")
        } catch (e: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test
    fun sourceDepthOutsideWaterRejected() {
        var thrown = false
        try {
            FdtdExport.build(waterGrid(), sedGrid(), shot.copy(depthM = 5000.0), listOf(receiver), "x")
        } catch (e: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test
    fun coarseningKicksInForLongLineHighF0() {
        // far receiver at 2.0 deg (~222 km), f0 500 Hz -> 10 ppw gives dx = 0.3 m,
        // far too many cells; the loop must coarsen and mark coarsened
        val far = FdtdReceiver(lon = 2.0, lat = 0.0, depthM = 900.0)
        val g = GeoGrid(60, 40, FloatArray(60 * 40) { -1000f }, -0.1, -0.05, 2.5, 0.05)
        val r = FdtdExport.build(g, sedGrid(500), shot, listOf(far), "x")
        assertTrue("expected coarsening", r.plan.coarsened)
        assertTrue("dx grew beyond target", r.plan.dx > 1.0)
        assertTrue("cell budget held", r.plan.nx.toLong() * r.plan.nz <= 3_000_000L)
        assertTrue(envOf(r).contains("auto-coarsened"))
    }

    @Test
    fun crossTrackWarnsWhenOffLine() {
        // two receivers forming a T: the cross one is far off the line through the far one
        val east = FdtdReceiver(lon = 0.045, lat = 0.0, depthM = 900.0)
        val north = FdtdReceiver(lon = 0.0, lat = 0.045, depthM = 900.0)
        val r = FdtdExport.build(waterGrid(), sedGrid(), shot, listOf(east, north), "x")
        assertTrue(r.plan.warnings.any { it.startsWith("2.5-D") })
        assertTrue("cross offsets recorded", r.plan.maxCrossTrackM > 100.0)
    }

    @Test
    fun hannAndRickerPulseEmission() {
        val rHann = FdtdExport.build(waterGrid(), sedGrid(), shot, listOf(receiver), "x")
        assertTrue(srcOf(rHann).contains("T_pulse = 4.0 / f0"))
        assertFalse(srcOf(rHann).contains("t_rick"))
        val rRick = FdtdExport.build(waterGrid(), sedGrid(), shot.copy(waveform = FdtdWaveform.RICKER), listOf(receiver), "x")
        assertTrue(srcOf(rRick).contains("t_rick = 1.0 / f0"))
        assertTrue(srcOf(rRick).contains("exp(-tau * tau)"))
    }

    @Test
    fun provenanceStrings() {
        val r = FdtdExport.build(waterGrid(), sedGrid(), shot, listOf(receiver), "GEBCO 2026 test crop")
        val env = envOf(r)
        assertTrue(env.contains("BathyApp"))
        assertTrue(env.contains("GlobSed v3"))
        assertTrue(env.contains("GEBCO 2026 test crop"))
        assertTrue(env.contains("Classes    : mud/sand/rock ="))
        assertTrue(env.contains("free surface at z = 0"))
        assertEquals(1000.0, r.plan.bathyMaxM, 1e-6) // grid bilinear samples ~ -999..-1000
    }

    @Test
    fun receiverBehindSourceExtendsDomain() {
        // receiver west of the shot: domain must still contain it on the left margin
        val recs = listOf(FdtdReceiver(lon = -0.03, lat = 0.0, depthM = 100.0), receiver)
        val r = FdtdExport.build(waterGrid(), sedGrid(), shot, recs, "x")
        val src = srcOf(r)
        val rx = arrayTokens(src, "receiver_x")
        assertEquals(2, rx.size)
        assertTrue("west receiver must be left of the source x", rx[0] < doubleVar(src, "source_x"))
        assertTrue("east receiver right of source", rx[1] > doubleVar(src, "source_x"))
    }

    @Test
    fun parseSmallRealAsciiThroughSedGrid() {
        // integration: tiny ascii grid built like the GlobSed header (corner registration),
        // a 2-row crop from the top of the world: yll = 90 - 2.5*cell
        val txt = "ncols         4\n" +
            "nrows         2\n" +
            "xllcorner     -180.04166666667\n" +
            "yllcorner     89.791666666666\n" +
            "cellsize      0.083333333333\n" +
            "NODATA_value  -9999\n" +
            "10 20 30 40\n50 1.#QNAN 70 80\n"
        val g = SedGrid.parseAscii(ByteArrayInputStream(txt.toByteArray()))
        assertEquals(-180.0, g.west, 1e-9)
        assertEquals(90.0 - 0.083333333333, g.north, 1e-9)
        assertEquals(90.0 - 2.0 * 0.083333333333, g.south, 1e-9)
        assertEquals(0, g[1, 1].toInt()) // QNAN -> 0
        assertEquals(2, g.ny)
        assertEquals(4, g.nx)
    }
}
