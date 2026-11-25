package io.github.st0n89.bathyapp.core.sediment

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream
import java.io.ByteArrayInputStream
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Global sediment-thickness grid (GlobSed v3, 5 arc minutes, metres).
 *
 * ESRI ASCII source representation: rows are north-first (row 0 is the
 * northernmost), columns west-first; node (i, j) sits at
 * lon = west + i*lonStep, lat = north - j*latStep (cell-centre
 * registration: GlobSed writes xllcorner = -180-cell/2 etc., so the
 * corner-based centres land exactly on +/-180 and +/-90).
 *
 * Values are total sediment thickness in metres, quantised to whole
 * metres. No-data (land, "1.#QNAN" tokens, NODATA_value, negatives) is
 * stored as 0 m, which classifies as exposed/hard bottom — the same
 * treatment as genuinely zero-thickness ocean floor.
 */
class SedGrid(
    val nx: Int,
    val ny: Int,
    val values: ShortArray,
    val west: Double,
    val north: Double,
    val lonStep: Double,
    val latStep: Double,
) {
    init {
        require(values.size == nx * ny) { "values size ${values.size} != nx*ny $nx*$ny" }
        require(nx >= 2 && ny >= 2) { "grid too small" }
        require(lonStep > 0 && latStep > 0) { "steps must be positive" }
    }

    val east: Double = west + (nx - 1) * lonStep
    val south: Double = north - (ny - 1) * latStep

    operator fun get(row: Int, col: Int): Short = values[row * nx + col]

    /** Longitude wrapped into [west, west+360). */
    private fun wrapLon(lon: Double): Double {
        var l = (lon - west) % 360.0
        if (l < 0) l += 360.0
        return l
    }

    /**
     * Bilinear sediment thickness [m] at (lon, lat). Longitude wraps
     * around the globe; latitude is clamped to the grid. No-data is 0.
     */
    fun sample(lon: Double, lat: Double): Double {
        val lx = wrapLon(lon) / lonStep
        val yClamped = max(south, min(north, lat))
        val fy = (north - yClamped) / latStep
        val x = max(0.0, min(lx, (nx - 1).toDouble()))
        val y = max(0.0, min(fy, (ny - 1).toDouble()))
        val i0 = floor(x).toInt().coerceAtMost(nx - 2)
        val j0 = floor(y).toInt().coerceAtMost(ny - 2)
        val tx = x - i0
        val ty = y - j0
        val v00 = get(j0, i0).toDouble()
        val v10 = get(j0, i0 + 1).toDouble()
        val v01 = get(j0 + 1, i0).toDouble()
        val v11 = get(j0 + 1, i0 + 1).toDouble()
        val top = v00 + (v10 - v00) * tx
        val bot = v01 + (v11 - v01) * tx
        return top + (bot - top) * ty
    }

    /** (cells with sediment > 0, max thickness m, mean thickness m over non-zero cells) */
    fun stats(): Triple<Int, Int, Int> {
        var n = 0
        var mx = 0
        var sum = 0L
        for (v in values) {
            if (v > 0) {
                n++
                if (v > mx) mx = v.toInt()
                sum += v.toLong()
            }
        }
        return Triple(n, mx, if (n > 0) (sum / n).toInt() else 0)
    }

    companion object {
        /**
         * Degenerate all-zero grid standing in for "no GlobSed data yet":
         * every sample returns 0 m (hard bottom), so bathymetry-only 2-D
         * section previews work before the sediment download. Only the
         * final env.jl export requires the real GlobSed grid.
         */
        fun empty(): SedGrid = SedGrid(2, 2, ShortArray(4), -180.0, 90.0, 360.0, 180.0)

        /**
         * Parses an ESRI ASCII grid (GlobSed layout) straight from a
         * stream. Header keys are scanned from the first bytes; data
         * starts after the header (rows may wrap lines): nx*ny values
         * are read in north-first row-major order. Tokens that are not
         * plain numbers ("1.#QNAN", "nan", ...) or negative values map
         * to no-data = 0.
         *
         * [onRow] is called after each completed grid row (0-based,
         * north-first) for progress reporting.
         */
        fun parseAscii(input: InputStream, onRow: ((Int) -> Unit)? = null): SedGrid {
            val bin = if (input is BufferedInputStream) input else BufferedInputStream(input, 1 shl 16)

            // ---- header: scan the first bytes for the standard ESRI keys ----
            val headChunk = ByteArray(1 shl 16)
            var headLen = 0
            while (headLen < headChunk.size) {
                val r = bin.read(headChunk, headLen, headChunk.size - headLen)
                if (r < 0) break
                headLen += r
            }
            val headText = String(headChunk, 0, headLen, Charsets.US_ASCII)
            var pos = 0
            val header = HashMap<String, Double>()
            var dataStart = -1
            while (pos < headLen) {
                val nl = headText.indexOf('\n', pos)
                if (nl < 0) throw IOException("ESRI header exceeds 64 KiB chunk")
                val line = headText.substring(pos, nl).trim()
                val sp = line.indexOf(' ')
                val key = if (sp > 0) line.substring(0, sp).trim().lowercase() else ""
                if (key in ESRI_KEYS) {
                    val value = line.substring(sp + 1).trim().toDoubleOrNull()
                        ?: throw IOException("ESRI header '$key' has non-numeric value")
                    header[normalizeKey(key)] = value
                    pos = nl + 1
                } else {
                    dataStart = pos
                    break
                }
            }
            if (dataStart < 0) throw IOException("ESRI data not found within 64 KiB")
            val nx = header["ncols"]?.toInt() ?: throw IOException("ncols missing")
            val ny = header["nrows"]?.toInt() ?: throw IOException("nrows missing")
            val xll = header["xllcorner"] ?: header["xllcenter"] ?: throw IOException("xll corner/center missing")
            val yll = header["yllcorner"] ?: header["yllcenter"] ?: throw IOException("yll corner/center missing")
            val cell = header["cellsize"] ?: throw IOException("cellsize missing")
            require(nx in 2..200_000 && ny in 2..200_000) { "implausible grid size $nx x $ny" }
            require(cell > 0) { "cellsize must be positive" }
            val west: Double = if (header.containsKey("xllcorner")) xll + 0.5 * cell else xll
            val southEdge: Double = if (header.containsKey("yllcorner")) yll + 0.5 * cell else yll
            val north = southEdge + (ny - 1) * cell

            // ---- data: everything after the header, re-attached to the buffered tail ----
            val head = ByteArrayInputStream(headChunk, dataStart, headLen - dataStart)
            val dataStream = SequenceInputStream(head, bin)
            val scanner = AsciiNumberScanner(dataStream)
            val values = ShortArray(nx * ny)
            var idx = 0
            val total = nx * ny
            while (idx < total) {
                val v = scanner.next()
                if (v.isNaN()) throw IOException("ESRI data truncated: got $idx of $total values")
                // no-data (NaN tokens, negatives, over-range) -> 0 m
                val m = if (v < 0.0 || v > 32767.0) 0 else Math.round(v).toInt()
                values[idx++] = m.toShort()
                if (onRow != null && idx % nx == 0) onRow(idx / nx - 1)
            }
            return SedGrid(nx, ny, values, west, north, cell, cell)
        }

        private val ESRI_KEYS = setOf("ncols", "nrows", "xllcorner", "yllcorner", "xllcenter", "yllcenter", "cellsize", "nodata_value")

        private fun normalizeKey(key: String): String =
            if (key == "nodata_value") "nodata" else key
    }
}

/**
 * Byte-level ASCII number scanner. Fast path: sign + digits + single
 * '.' + fraction digits, accumulated exactly in a long mantissa.
 * Anything else (exponent, QNaN/QNAN text, "nan", "NaN", ...) falls
 * back to a token string and Double parsing; unparsable tokens become
 * -1.0 (no-data). Whitespace and ',' separate tokens.
 * Returns NaN at end of stream (distinguishable: thickness is never NaN).
 */
internal class AsciiNumberScanner(input: InputStream) {
    private val bin = if (input is BufferedInputStream) input else BufferedInputStream(input, 1 shl 16)
    private val token = ByteArray(64)

    fun next(): Double {
        var c = bin.read()
        while (c >= 0 && (c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code || c == ','.code)) c = bin.read()
        if (c < 0) return Double.NaN
        var len = 0
        token[len++] = c.toByte()
        var mant = 0L
        var sign = 1
        var fracDigits = -1
        var clean = true
        when (c) {
            '-'.code -> sign = -1
            in '0'.code..'9'.code -> mant = (c - '0'.code).toLong()
            else -> clean = false
        }
        c = bin.read()
        while (c >= 0 && c != ' '.code && c != '\n'.code && c != '\r'.code && c != '\t'.code && c != ','.code) {
            if (len < token.size) token[len++] = c.toByte()
            when {
                !clean -> {}
                c == '.'.code && fracDigits == -1 -> fracDigits = 0
                c in '0'.code..'9'.code -> {
                    mant = mant * 10 + (c - '0'.code)
                    if (fracDigits >= 0) fracDigits++
                }
                else -> clean = false // exponent or QNaN text
            }
            c = bin.read()
        }
        if (!clean) {
            val s = String(token, 0, len, Charsets.US_ASCII).trim()
            return s.toDoubleOrNull() ?: NODATA
        }
        val v = if (fracDigits > 0) mant / pow10(fracDigits) else mant.toDouble()
        return sign * v
    }

    private fun pow10(n: Int): Double {
        var r = 1.0
        repeat(n) { r *= 10.0 }
        return r
    }

    companion object {
        /** Marker value for unparsable tokens (no-data). Never a legal thickness. */
        const val NODATA: Double = -1.0
    }
}
