package io.github.st0n89.bathyapp.core

import io.github.st0n89.bathyapp.core.emodnet.SimpleSource
import io.github.st0n89.bathyapp.core.geo.GeoGrid
import io.github.st0n89.bathyapp.core.tiff.GeoTiffReader
import io.github.st0n89.bathyapp.core.tiff.GeoTiffWriter
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeoTiffTest {

    private fun grid5x4(): GeoGrid {
        val nx = 5; val ny = 4
        val v = FloatArray(nx * ny) { i -> (i * 1.5f) - 20f }
        return GeoGrid(nx, ny, v, 5.0, 53.0, 5.5, 53.4)
    }

    @Test
    fun writeReadRoundtrip() {
        val grid = grid5x4()
        val out = ByteArrayOutputStream()
        GeoTiffWriter.write(grid, out)
        val tiff = GeoTiffReader.read(SimpleSource(out.toByteArray()))
        val back = tiff.toGeoGrid()
        assertEquals(grid.nx, back.nx)
        assertEquals(grid.ny, back.ny)
        for (i in grid.values.indices) {
            if (grid.values[i].isNaN()) assertTrue(back.values[i].isNaN())
            else assertEquals(grid.values[i], back.values[i], 1e-4f)
        }
        assertEquals(grid.west, back.west, 1e-9)
        assertEquals(grid.east, back.east, 1e-9)
        assertEquals(grid.south, back.south, 1e-9)
        assertEquals(grid.north, back.north, 1e-9)
    }

    @Test
    fun roundtripNodataPreserved() {
        val grid = grid5x4()
        grid.values[7] = Float.NaN
        val out = ByteArrayOutputStream()
        GeoTiffWriter.write(grid, out)
        val back = GeoTiffReader.read(SimpleSource(out.toByteArray())).toGeoGrid()
        assertTrue(back.values[7].isNaN())
        assertEquals("nan", GeoTiffReader.read(SimpleSource(out.toByteArray())).nodata)
    }

    /**
     * EMODnet-style file: big-endian, tiled float32, ModelTransformation,
     * GeoKeyDirectory with RasterPixelIsArea. Verified against the live WCS.
     */
    @Test
    fun readsEmodnetStyleTiledBigEndian() {
        val nx = 5; val ny = 4; val tw = 2; val th = 2
        val across = (nx + tw - 1) / tw
        val down = (ny + th - 1) / th
        val vals = FloatArray(nx * ny) { i -> -40f + i * 0.25f }

        val bo = ByteOrder.BIG_ENDIAN
        val tileOffsets = mutableListOf<Int>()
        val tileByteCounts = mutableListOf<Int>()
        val outOfLine = ByteArrayOutputStream()
        val tiles = mutableListOf<ByteArray>()
        for (ty in 0 until down) {
            for (tx in 0 until across) {
                val tile = ByteBuffer.allocate(tw * th * 4).order(bo)
                for (r in 0 until th) for (c in 0 until tw) {
                    val gx = tx * tw + c
                    val gy = ty * th + r
                    if (gx < nx && gy < ny) tile.putFloat(vals[gy * nx + gx])
                }
                tiles.add(tile.array().copyOfRange(0, tw * th * 4))
            }
        }
        // layout: header(8) + IFD at 8; values after IFD; tiles after values
        val nTags = 14
        val ifdLen = 2 + nTags * 12 + 4
        val valuesStart = 8 + ifdLen
        val trSize = 16 * 8
        val geoKeySize = 16 * 2
        val offsStart = valuesStart + trSize + geoKeySize
        val cntsStart = offsStart + tiles.size * 4
        val tilesStart = cntsStart + tiles.size * 4
        var off = tilesStart
        for (t in tiles) {
            tileOffsets.add(off)
            tileByteCounts.add(t.size)
            off += t.size
        }

        val header = ByteBuffer.allocate(valuesStart).order(bo)
        header.put('M'.code.toByte()); header.put('M'.code.toByte())
        header.putShort(42)
        header.putInt(8)
        header.putShort(nTags.toShort())

        fun entry(tag: Int, type: Int, count: Int, valueOffset: Long) {
            header.putShort(tag.toShort())
            header.putShort(type.toShort())
            header.putInt(count)
            if (type == 3 && count == 1) {
                // inline SHORT: value in the first two bytes of the field
                header.putShort(valueOffset.toShort())
                header.putShort(0)
            } else {
                header.putInt(valueOffset.toInt())
            }
        }

        entry(256, 3, 1, nx.toLong())                       // ImageWidth
        entry(257, 3, 1, ny.toLong())                       // ImageLength
        entry(258, 3, 1, 32)                                // BitsPerSample
        entry(259, 3, 1, 1)                                 // Compression none
        entry(262, 3, 1, 1)                                 // Photometric
        entry(277, 3, 1, 1)                                 // SamplesPerPixel
        entry(284, 3, 1, 1)                                 // PlanarConfig
        entry(339, 3, 1, 3)                                 // SampleFormat IEEEFP
        entry(322, 3, 1, tw.toLong())                      // TileWidth
        entry(323, 3, 1, th.toLong())                      // TileLength
        entry(324, 4, tiles.size, offsStart.toLong())       // TileOffsets
        entry(325, 4, tiles.size, cntsStart.toLong())       // TileByteCounts
        entry(34264, 12, 16, valuesStart.toLong())          // ModelTransformation
        entry(34735, 3, 16, (valuesStart + trSize).toLong()) // GeoKeyDirectory
        header.putInt(0) // next IFD

        // out-of-line areas
        val tr = doubleArrayOf(
            0.1, 0.0, 0.0, 5.0,
            0.0, -0.1, 0.0, 53.4,
            0.0, 0.0, 0.0, 0.0,
            0.0, 0.0, 0.0, 1.0,
        )
        val trBuf = ByteBuffer.allocate(trSize).order(bo)
        tr.forEach { trBuf.putDouble(it) }
        val geoKeys = shortArrayOf(1, 1, 0, 3, 1024, 0, 1, 2, 1025, 0, 1, 1, 2048, 0, 1, 4326)
        val gkBuf = ByteBuffer.allocate(geoKeySize).order(bo)
        geoKeys.forEach { gkBuf.putShort(it) }

        val offsBuf = ByteBuffer.allocate(tiles.size * 4).order(bo)
        tileOffsets.forEach { offsBuf.putInt(it) }
        val cntsBuf = ByteBuffer.allocate(tiles.size * 4).order(bo)
        tileByteCounts.forEach { cntsBuf.putInt(it) }

        val file = ByteArrayOutputStream()
        file.write(header.array())
        file.write(trBuf.array())
        file.write(gkBuf.array())
        file.write(offsBuf.array())
        file.write(cntsBuf.array())
        for (t in tiles) file.write(t)
        val bytes = file.toByteArray()

        val tiff = GeoTiffReader.read(SimpleSource(bytes))
        assertEquals(nx, tiff.width)
        assertEquals(ny, tiff.height)
        val grid = tiff.toGeoGrid()
        for (i in vals.indices) assertEquals(vals[i], grid.values[i], 1e-4f)
        // area convention: raster (0,0) -> (5.0, 53.4) corner
        assertEquals(5.0, grid.west, 1e-9)
        assertEquals(5.0 + nx * 0.1, grid.east, 1e-9)
        assertEquals(53.4, grid.north, 1e-9)
        assertEquals(53.4 - ny * 0.1, grid.south, 1e-9)
    }

    @Test
    fun lzwRoundtrip() {
        val data = ByteArray(4000) { i -> if (i % 100 < 60) (i % 251).toByte() else 7 }
        val encoded = lzwEncode(data)
        val decoded = GeoTiffReader.lzwDecode(encoded)
        assertTrue(decoded.contentEquals(data))
    }

    @Test
    fun deflateStripRead() {
        // minimal stripped float32 GeoTIFF, AdobeDeflate-compressed strip
        val nx = 6; val ny = 3
        val vals = FloatArray(nx * ny) { i -> i * -0.5f }
        val raw = ByteBuffer.allocate(nx * ny * 4).order(ByteOrder.LITTLE_ENDIAN)
        vals.forEach { raw.putFloat(it) }
        val def = Deflater()
        def.setInput(raw.array())
        def.finish()
        val compBuf = ByteArrayOutputStream()
        val tmp = ByteArray(256)
        while (!def.finished()) {
            val n = def.deflate(tmp)
            compBuf.write(tmp, 0, n)
        }
        val comp = compBuf.toByteArray()

        val nTags = 13
        val ifdLen = 2 + nTags * 12 + 4
        val valuesStart = 8 + ifdLen
        val pixelScale = doubleArrayOf(0.5, 0.4, 0.0)
        val tiePoint = doubleArrayOf(0.0, 0.0, 0.0, 10.0, 40.0, 0.0)
        val psSize = 24
        val tpSize = 48
        val dataStart = valuesStart + psSize + tpSize

        val file = ByteBuffer.allocate(dataStart + comp.size).order(ByteOrder.LITTLE_ENDIAN)
        file.put('I'.code.toByte()); file.put('I'.code.toByte())
        file.putShort(42)
        file.putInt(8)
        file.putShort(nTags.toShort())
        fun entry(tag: Int, type: Int, count: Int, value: Long) {
            file.putShort(tag.toShort())
            file.putShort(type.toShort())
            file.putInt(count)
            if (type == 3 && count == 1) {
                file.putShort(value.toShort())
                file.putShort(0)
            } else {
                file.putInt(value.toInt())
            }
        }
        entry(256, 4, 1, nx.toLong())
        entry(257, 4, 1, ny.toLong())
        entry(258, 3, 1, 32)
        entry(259, 3, 1, 8)          // AdobeDeflate
        entry(262, 3, 1, 1)
        entry(273, 4, 1, dataStart.toLong())
        entry(277, 3, 1, 1)
        entry(278, 4, 1, ny.toLong())
        entry(279, 4, 1, comp.size.toLong())
        entry(284, 3, 1, 1)
        entry(33550, 12, 3, valuesStart.toLong())                 // ModelPixelScale
        entry(33922, 12, 6, (valuesStart + psSize).toLong())      // ModelTiepoint
        entry(339, 3, 1, 3)
        file.putInt(0) // next IFD
        pixelScale.forEach { file.putDouble(it) }
        tiePoint.forEach { file.putDouble(it) }
        file.put(comp)
        val bytes = file.array().copyOfRange(0, file.position())

        val tiff = GeoTiffReader.read(SimpleSource(bytes))
        assertEquals(nx, tiff.width)
        assertEquals(ny, tiff.height)
        for (i in vals.indices) assertEquals(vals[i], tiff.values[i], 1e-4f)
        val grid = tiff.toGeoGrid()
        assertEquals(10.0, grid.west, 1e-9)
        assertEquals(40.0, grid.north, 1e-9)
        assertEquals(10.0 + nx * 0.5, grid.east, 1e-9)
    }

    /** TIFF-flavoured LZW encoder (MSB-first, early change) for tests. */
    private fun lzwEncode(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val prefixTable = HashMap<Long, Int>(4096)
        var codeWidth = 9
        var nextCode = 258
        var bitAcc = 0
        var bitCount = 0

        fun emit(code: Int) {
            bitAcc = (bitAcc shl codeWidth) or code
            bitCount += codeWidth
            while (bitCount >= 8) {
                out.write((bitAcc shr (bitCount - 8)) and 0xFF)
                bitCount -= 8
            }
        }

        fun key(prefix: Int, suffix: Int) = (prefix.toLong() shl 8) or suffix

        var w = data[0].toInt() and 0xFF
        var i = 1
        while (i < data.size) {
            val c = data[i].toInt() and 0xFF
            val k = key(w, c)
            val code = prefixTable[k]
            if (code != null) {
                w = code
            } else {
                emit(w)
                prefixTable[key(w, c)] = nextCode++
                if (nextCode == (1 shl codeWidth) - 1 && codeWidth < 12) codeWidth++
                w = c
            }
            i++
        }
        emit(w)
        emit(257)
        if (bitCount > 0) {
            out.write((bitAcc shl (8 - bitCount)) and 0xFF)
        }
        return out.toByteArray()
    }
}
