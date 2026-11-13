package io.github.st0n89.bathyapp.core

import io.github.st0n89.bathyapp.core.data.FileChannelSource
import io.github.st0n89.bathyapp.core.nc3.NcGrid
import io.github.st0n89.bathyapp.core.nc3.NetCdf3File
import java.io.File
import java.io.RandomAccessFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NetCdf3Test {

    private fun tempFile(): File = File.createTempFile("test", ".nc")

    private fun open(file: File): NetCdf3File {
        val raf = RandomAccessFile(file, "r")
        return NetCdf3File(FileChannelSource(raf.channel))
    }

    private val nLat = 8
    private val nLon = 10

    /** elevation(fileRow, col) = -100 + 10*row + 3*col, nodata at (3, 2). */
    private fun elev(row: Int, col: Int): Short =
        if (row == 3 && col == 2) (-32768).toShort() else (-100 + 10 * row + 3 * col).toShort()

    /**
     * GEBCO-style fixture: ascending 1-degree regional coordinate variables,
     * int16 elevation with _FillValue, plus a record variable to make sure
     * header parsing handles the record case (numrecs, interleaving).
     */
    private fun writeGebcoLike(file: File) {
        val w = Nc3Writer(cdf2 = true)
        w.dim("latitude", nLat.toLong())
        w.dim("longitude", nLon.toLong())
        w.dim("time", 0L)
        w.globalAtt(Nc3Writer.Att("title", 2, "BathyApp test grid"))
        w.globalAtt(Nc3Writer.Att("geospatial_lat_min", 6, 50.0))
        w.globalAtt(Nc3Writer.Att("geospatial_lat_max", 6, 57.0))
        w.globalAtt(Nc3Writer.Att("geospatial_lon_min", 6, 5.0))
        w.globalAtt(Nc3Writer.Att("geospatial_lon_max", 6, 14.0))
        w.variable(
            Nc3Writer.Var(
                "latitude", 6, listOf("latitude"),
                listOf(Nc3Writer.Att("units", 2, "degrees_north")),
                DoubleArray(nLat) { 50.0 + it },
            ),
        )
        w.variable(
            Nc3Writer.Var(
                "longitude", 6, listOf("longitude"),
                listOf(Nc3Writer.Att("units", 2, "degrees_east")),
                DoubleArray(nLon) { 5.0 + it },
            ),
        )
        val flat = ShortArray(nLat * nLon) { idx -> elev(idx / nLon, idx % nLon) }
        w.variable(
            Nc3Writer.Var(
                "elevation", 3, listOf("latitude", "longitude"),
                listOf(Nc3Writer.Att("_FillValue", 3, (-32768).toShort())),
                flat,
            ),
        )
        val rec0 = Nc3Writer.encode(5, floatArrayOf(5.5f))
        val rec1 = Nc3Writer.encode(5, floatArrayOf(7.25f))
        w.variable(Nc3Writer.Var("wind", 5, listOf("time"), emptyList(), FloatArray(0), listOf(rec0, rec1)))
        w.write(file)
    }

    @Test
    fun parsesHeaderDimsAndVariables() {
        val f = tempFile()
        writeGebcoLike(f)
        open(f).use { nc ->
            assertEquals(2, nc.version)
            assertEquals(2L, nc.numRecs)
            assertEquals(listOf("latitude", "longitude", "time"), nc.dims.map { it.name })
            assertEquals(listOf(8L, 10L, 0L), nc.dims.map { it.size })
            assertEquals("BathyApp test grid", nc.globalAttributes["title"]?.string)
            assertEquals(4, nc.variables.size)
            val elev = nc.findVar("elevation")!!
            assertEquals(3, elev.type)
            assertEquals(-32768.0, elev.attributes["_FillValue"]!!.firstNumber())
            assertEquals(8L, nc.dimOf(elev, 0).size)
        }
    }

    @Test
    fun detectsGridAndReadsFullSubgridNorthFirst() {
        val f = tempFile()
        writeGebcoLike(f)
        open(f).use { nc ->
            val grid = NcGrid.open(nc)
            assertEquals("elevation", grid.dataVar.name)
            assertEquals(8, grid.nRows)
            assertEquals(10, grid.nCols)
            assertEquals(49.5, grid.south)
            assertEquals(57.5, grid.north)
            assertEquals(4.5, grid.west)
            assertEquals(14.5, grid.east)

            val sub = grid.readSubgrid(4.5, 49.5, 14.5, 57.5, maxCols = 10)
            // output row 0 = file row 7 (north): -100 + 70 + 3*col
            assertEquals(-30f, sub[0, 0])
            assertEquals(-3f, sub[0, 9])
            // output last row = file row 0 (south)
            assertEquals(-100f, sub[sub.ny - 1, 0])
            assertEquals(-73f, sub[sub.ny - 1, 9])
            // file row 3, col 2 is nodata; output row = 8 - 1 - 3 = 4
            assertTrue(sub[4, 2].isNaN())
            assertEquals(4.5, sub.west)
            assertEquals(14.5, sub.east)
        }
    }

    @Test
    fun subgridRespectsBboxAndNodata() {
        val f = tempFile()
        writeGebcoLike(f)
        open(f).use { nc ->
            val grid = NcGrid.open(nc)
            // southern half: lats 50..55 -> file rows 0..5
            val sub = grid.readSubgrid(4.5, 49.5, 14.5, 55.5, maxCols = 10)
            assertEquals(6, sub.ny)
            assertEquals(10, sub.nx)
            assertEquals(49.5, sub.south)
            assertEquals(55.5, sub.north)
            // nodata at file (3, 2); output row = 6 - 1 - 3 = 2
            assertTrue(sub[2, 2].isNaN())
        }
    }

    @Test
    fun readStridedSubgrid() {
        val f = tempFile()
        writeGebcoLike(f)
        open(f).use { nc ->
            val grid = NcGrid.open(nc)
            val sub = grid.readSubgrid(4.5, 49.5, 14.5, 57.5, maxCols = 5)
            assertEquals(5, sub.nx) // stride 2
            assertEquals(8, sub.ny)
            // output col 0 = file col 0; output row 0 = file row 7
            assertEquals(-30f, sub[0, 0])
            // output col 1 = file col 2; nodata at file (3,2); output row = 4
            assertTrue(sub[4, 1].isNaN())
        }
    }

    @Test
    fun rejectsNonNetcdfFiles() {
        val f = tempFile()
        f.writeText("this is not a netcdf file at all")
        assertFailsWith<IllegalArgumentException> { open(f).close() }
    }

    @Test
    fun coordinateValuesRoundTrip() {
        val f = tempFile()
        writeGebcoLike(f)
        open(f).use { nc ->
            val lat = nc.readAllDoubles(nc.findVar("latitude")!!)
            assertEquals(8, lat.size)
            assertEquals(50.0, lat[0])
            assertEquals(57.0, lat[7])
            val lon = nc.readAllDoubles(nc.findVar("longitude")!!)
            assertEquals(5.0, lon[0])
            assertEquals(14.0, lon[9])
        }
    }
}
