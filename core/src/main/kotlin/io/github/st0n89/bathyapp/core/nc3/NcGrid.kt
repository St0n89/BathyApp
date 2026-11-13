package io.github.st0n89.bathyapp.core.nc3

import io.github.st0n89.bathyapp.core.geo.GeoGrid
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Bathymetry grid detection and subgrid extraction on top of a classic
 * NetCDF file (GEBCO style: latitude/longitude coordinate variables plus a
 * 2-D elevation variable, values in metres, positive up).
 */
class NcGrid private constructor(
    private val file: NetCdf3File,
    val dataVar: NetCdf3File.NcVar,
    val latitudes: DoubleArray?,
    val longitudes: DoubleArray?,
) {
    val nRows: Int = file.dimOf(dataVar, 0).size.toInt()
    val nCols: Int = file.dimOf(dataVar, 1).size.toInt()

    /** Estimated outer bounds of the grid. */
    val west: Double
    val east: Double
    val south: Double
    val north: Double

    init {
        if (latitudes != null && latitudes.size >= 2 && longitudes != null && longitudes.size >= 2) {
            val dLat = latitudes[1] - latitudes[0]
            val dLon = longitudes[1] - longitudes[0]
            require(abs(dLat) > 0.0 && abs(dLon) > 0.0) { "degenerate coordinate variables" }
            south = (latitudes.min() - dLat / 2).coerceAtLeast(-90.0)
            north = (latitudes.max() + dLat / 2).coerceAtMost(90.0)
            west = longitudes.min() - dLon / 2
            east = longitudes.max() + dLon / 2
        } else {
            south = attrDouble("geospatial_lat_min") ?: error("no latitude information")
            north = attrDouble("geospatial_lat_max") ?: error("no latitude information")
            west = attrDouble("geospatial_lon_min") ?: error("no longitude information")
            east = attrDouble("geospatial_lon_max") ?: error("no longitude information")
        }
    }

    private fun attrDouble(name: String): Double? = file.globalAttributes[name]?.firstNumber()

    /**
     * Extracts a subgrid for [west]/[south]/[east]/[north] (cell edges are
     * clipped against the grid), sub-sampled to at most [maxCols] columns.
     */
    fun readSubgrid(west: Double, south: Double, east: Double, north: Double, maxCols: Int): GeoGrid {
        val lonA = longitudes
        val latA = latitudes
        val colStart: Int
        val colEnd: Int
        val rowStart: Int
        val rowEnd: Int
        if (lonA != null && latA != null) {
            val dLon = lonA[1] - lonA[0]
            val dLat = latA[1] - latA[0]
            val i0 = ((west - lonA[0]) / dLon).toInt()
            val i1 = ((east - lonA[0]) / dLon).toInt()
            colStart = max(0, min(i0, i1))
            colEnd = min(nCols - 1, max(i0, i1))
            val j0 = ((south - latA[0]) / dLat).toInt()
            val j1 = ((north - latA[0]) / dLat).toInt()
            rowStart = max(0, min(j0, j1))
            rowEnd = min(nRows - 1, max(j0, j1))
        } else {
            colStart = max(0, floorDiv(west - this.west, (this.east - this.west) / nCols).toInt())
            colEnd = min(nCols - 1, ceilDiv(east - this.west, (this.east - this.west) / nCols).toInt() - 1)
            rowStart = max(0, floorDiv(south - this.south, (this.north - this.south) / nRows).toInt())
            rowEnd = min(nRows - 1, ceilDiv(north - this.south, (this.north - this.south) / nRows).toInt() - 1)
        }
        require(colEnd >= colStart && rowEnd >= rowStart) { "bounding box outside the grid" }

        val nColsIn = colEnd - colStart + 1
        val nRowsIn = rowEnd - rowStart + 1
        val stride = max(1, ceil(nColsIn / maxCols.toDouble()).toInt())
        val outCols = ((nColsIn - 1) / stride) + 1
        val outRows = nRowsIn

        val raw = file.read2DSubgrid(dataVar, rowStart, outRows, colStart, outCols, stride)

        val subWest: Double
        val subEast: Double
        val subSouth: Double
        val subNorth: Double
        if (lonA != null && latA != null) {
            val dLon = lonA[1] - lonA[0]
            val dLat = latA[1] - latA[0]
            subWest = lonA[colStart] - dLon / 2
            subEast = lonA[colStart + (outCols - 1) * stride] + dLon * stride / 2
            val latFirst = latA[rowStart]
            val latLast = latA[rowEnd]
            val top = max(latFirst, latLast)
            val bottom = min(latFirst, latLast)
            subSouth = bottom - abs(dLat) / 2
            subNorth = top + abs(dLat) / 2
        } else {
            val dLon = (this.east - this.west) / nCols
            val dLat = (this.north - this.south) / nRows
            subWest = this.west + colStart * dLon
            subEast = subWest + (outCols * stride) * dLon
            subSouth = this.south + rowStart * dLat
            subNorth = subSouth + nRowsIn * dLat
        }

        // raw rows are in file order; flip to north-first if the file stores south first
        val northFirst: Boolean
        if (latA != null) {
            northFirst = latA[1] < latA[0] // descending latitude means row 0 is northernmost
        } else {
            northFirst = false // assume ascending (GEBCO style) for attr-derived grids
        }
        val out = if (northFirst) raw else flipRows(raw, outCols, outRows)

        return GeoGrid(outCols, outRows, out, subWest, subSouth, subEast, subNorth)
    }

    private fun flipRows(src: FloatArray, w: Int, h: Int): FloatArray {
        val dst = FloatArray(src.size)
        for (r in 0 until h) {
            System.arraycopy(src, (h - 1 - r) * w, dst, r * w, w)
        }
        return dst
    }

    private fun floorDiv(a: Double, b: Double): Double = kotlin.math.floor(a / b)

    private fun ceilDiv(a: Double, b: Double): Double = kotlin.math.ceil(a / b)

    companion object {
        private val PREFERRED_NAMES = listOf("elevation", "depth", "z", "bathymetry", "band1", "values")

        /** Detects the 2-D data variable and coordinate variables of a grid NetCDF. */
        fun open(file: NetCdf3File): NcGrid {
            val candidates = file.variables.filter { it.dimIds.size == 2 && !file.isRecordVar(it) }
            require(candidates.isNotEmpty()) { "no 2-D data variable found" }
            val dataVar = candidates.maxByOrNull { c ->
                val score = 10_000L * (if (PREFERRED_NAMES.any { c.name.equals(it, ignoreCase = true) }) 1 else 0)
                score + file.dimOf(c, 0).size * file.dimOf(c, 1).size
            }!!
            val rowDimName = file.dimOf(dataVar, 0).name
            val colDimName = file.dimOf(dataVar, 1).name
            val latVar = file.findVar(rowDimName)
            val lonVar = file.findVar(colDimName)
            var latitudes: DoubleArray? = null
            var longitudes: DoubleArray? = null
            if (latVar != null && latVar.dimIds.size == 1 && !file.isRecordVar(latVar) && file.dimOf(latVar, 0).size >= 2) {
                latitudes = file.readAllDoubles(latVar)
            }
            if (lonVar != null && lonVar.dimIds.size == 1 && !file.isRecordVar(lonVar) && file.dimOf(lonVar, 0).size >= 2) {
                longitudes = file.readAllDoubles(lonVar)
            }
            if (latitudes == null && file.globalAttributes.containsKey("geospatial_lat_min")) {
                // fall back to global attrs in open(); handled by init
            }
            return NcGrid(file, dataVar, latitudes, longitudes)
        }
    }
}
