package io.github.st0n89.bathyapp.core.tiff

import io.github.st0n89.bathyapp.core.data.Cursor
import io.github.st0n89.bathyapp.core.data.SeekSource
import io.github.st0n89.bathyapp.core.geo.GeoGrid
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater

/**
 * GeoTIFF reader/writer, dependency-free.
 *
 * Reader supports what bathymetry services actually emit (EMODnet WCS,
 * GEBCO GeoTIFF exports): classic TIFF, both byte orders, strip or tile
 * layout, 8/16/32 bit int/uint/float single-band data, uncompressed,
 * Deflate (zlib and Adobe), LZW and PackBits compression, georeferencing
 * via ModelTransformation or ModelPixelScale+ModelTiepoint.
 *
 * Writer emits a simple, maximally compatible uncompressed float32 GeoTIFF
 * with ModelPixelScale/ModelTiepoint and EPSG:4326 GeoKeys.
 */
class GeoTiff(
    val width: Int,
    val height: Int,
    val values: FloatArray,
    /**
     * Affine transform: lon = tr[0]*col + tr[1]*row + tr[3],
     * lat = tr[4]*col + tr[5]*row + tr[7] (raster coordinate convention,
     * i.e. pixel corner for RasterPixelIsArea).
     */
    val transform: DoubleArray,
    val nodata: String?,
    val pixelIsArea: Boolean,
) {
    fun toGeoGrid(): GeoGrid {
        require(transform[1] == 0.0 && transform[4] == 0.0) {
            "rotated GeoTIFF rasters are not supported"
        }
        var a = transform[0]
        var e = transform[5]
        var c = transform[3]
        var f = transform[7]
        var vals = values

        if (e > 0) {
            // rows run south -> north; flip to north-first
            vals = flipRows(vals, width, height)
            f = f + e * (height - 1) // latitude of new row 0 (old last row)
            e = -e
        }

        val half = if (pixelIsArea) 0.5 else 0.0
        val lonCenter0 = c + half * a
        val latCenter0 = f + half * e
        val west = lonCenter0 - a / 2.0
        val east = west + width * a
        val north = latCenter0 - e / 2.0
        val south = north - height * (-e)
        require(a > 0) { "pixel scale must be positive, got $a" }
        require(e < 0) { "unexpected row latitude step $e" }
        return GeoGrid(width, height, vals, west, south, east, north)
    }

    private fun flipRows(src: FloatArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(src.size)
        for (r in 0 until h) {
            System.arraycopy(src, (h - 1 - r) * w, out, r * w, w)
        }
        return out
    }
}

object GeoTiffReader {

    private val TYPE_SIZE = intArrayOf(0, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8)

    private class TiffTag(val type: Int, val count: Int, val bytes: ByteArray) {
        val bb: ByteBuffer get() = ByteBuffer.wrap(bytes).order(bbOrder)
        lateinit var bbOrder: ByteOrder

        fun withOrder(order: ByteOrder): TiffTag {
            bbOrder = order
            return this
        }
    }

    fun read(src: SeekSource): GeoTiff {
        val head = src.readAt(0, 8)
        val little = when {
            head[0] == 'I'.code.toByte() && head[1] == 'I'.code.toByte() -> true
            head[0] == 'M'.code.toByte() && head[1] == 'M'.code.toByte() -> false
            else -> throw IllegalArgumentException("not a TIFF file (bad byte-order mark)")
        }
        val order = if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val ver = readU16(head, 2, little)
        require(ver == 42) { "not a classic TIFF (version $ver; BigTIFF is not supported)" }
        val ifdOff = readU32(head, 4, little)

        val c = Cursor(src)
        c.seek(ifdOff)
        val nEntries = if (little) c.i16le() else c.i16be()
        require(nEntries in 1..4096) { "bad IFD entry count $nEntries" }
        val tags = HashMap<Int, TiffTag>()
        repeat(nEntries) {
            val tag = if (little) c.i16le() else c.i16be()
            val type = if (little) c.i16le() else c.i16be()
            require(type in 1..12) { "bad TIFF value type $type" }
            val count = if (little) c.u32le() else c.u32be()
            val sz = (TYPE_SIZE[type] * count).toInt()
            require(count in 0..16_000_000L) { "tag $tag has bad count $count" }
            val valueOff = if (little) c.u32le() else c.u32be()
            val entryValueOffset = ifdOff + 2 + it * 12L + 8
            val bytes = if (sz <= 4) {
                src.readAt(entryValueOffset, sz)
            } else {
                src.readAt(valueOff, sz)
            }
            tags[tag] = TiffTag(type, count.toInt(), bytes).withOrder(order)
        }

        fun longs(tag: Int): LongArray? {
            val t = tags[tag] ?: return null
            val bb = t.bb
            return when (t.type) {
                3 -> LongArray(t.count) { bb.short.toLong() and 0xFFFF }
                4 -> LongArray(t.count) { bb.int.toLong() and 0xFFFFFFFFL }
                8 -> LongArray(t.count) { bb.short.toLong() }
                9 -> LongArray(t.count) { bb.int.toLong() }
                else -> throw IllegalArgumentException("tag $tag has unexpected type ${t.type}")
            }
        }

        fun doubles(tag: Int): DoubleArray? {
            val t = tags[tag] ?: return null
            require(t.type == 12) { "tag $tag is not DOUBLE" }
            val bb = t.bb
            return DoubleArray(t.count) { bb.double }
        }

        fun ascii(tag: Int): String? {
            val t = tags[tag] ?: return null
            val end = t.bytes.indexOf(0.toByte())
            return String(if (end >= 0) t.bytes.copyOfRange(0, end) else t.bytes, Charsets.UTF_8)
        }

        val width = longs(256)?.get(0)?.toInt()
            ?: throw IllegalArgumentException("TIFF without ImageWidth")
        val height = longs(257)?.get(0)?.toInt()
            ?: throw IllegalArgumentException("TIFF without ImageLength")
        val bits = longs(258) ?: throw IllegalArgumentException("TIFF without BitsPerSample")
        require(bits.size == 1) { "multi-band TIFF not supported" }
        val compression = (longs(259)?.get(0) ?: 1L).toInt()
        val samples = (longs(277)?.get(0) ?: 1L).toInt()
        require(samples == 1) { "only single-band TIFFs are supported ($samples bands found)" }
        val planar = (longs(284)?.get(0) ?: 1L).toInt()
        require(planar == 1) { "planar TIFF not supported" }
        val sampleFormat = (longs(339)?.get(0) ?: 1L).toInt()

        val bytesPerSample = when (bits[0]) {
            8L -> 1
            16L -> 2
            32L -> 4
            else -> throw IllegalArgumentException("unsupported bit depth ${bits[0]}")
        }

        val values = FloatArray(width * height) { Float.NaN }

        fun readValue(bb: ByteBuffer): Float = when (bytesPerSample) {
            1 -> when (sampleFormat) {
                2 -> (bb.get().toInt() and 0xFF).toFloat()
                else -> bb.get().toFloat()
            }
            2 -> when (sampleFormat) {
                1 -> bb.short.toFloat()
                else -> (bb.short.toInt() and 0xFFFF).toFloat()
            }
            else -> when (sampleFormat) {
                3 -> bb.float
                2 -> (bb.int.toLong() and 0xFFFFFFFFL).toFloat()
                else -> bb.int.toFloat()
            }
        }

        fun storeBlock(raw: ByteArray, destRow0: Int, destCol0: Int, blockW: Int, blockH: Int) {
            val dec = when (compression) {
                1 -> raw
                8, 32946 -> inflate(raw, zlib = compression == 8)
                5 -> lzwDecode(raw)
                32773 -> packBitsDecode(raw)
                else -> throw IllegalArgumentException("unsupported TIFF compression $compression")
            }
            val bb = ByteBuffer.wrap(dec).order(order)
            for (br in 0 until blockH) {
                val r = destRow0 + br
                for (bc in 0 until blockW) {
                    if (bb.remaining() < bytesPerSample) return
                    val v = readValue(bb)
                    val col = destCol0 + bc
                    if (r < height && col < width) values[r * width + col] = v
                }
            }
        }

        val tileOffsets = longs(324)
        val tileByteCounts = longs(325)
        val stripOffsets = longs(273)
        val stripByteCounts = longs(279)

        if (tileOffsets != null && tileOffsets.isNotEmpty()) {
            require(tileByteCounts != null && tileByteCounts.size == tileOffsets.size) { "bad tile byte counts" }
            val tileW = longs(322)?.get(0)?.toInt() ?: throw IllegalArgumentException("tiled TIFF without TileWidth")
            val tileH = longs(323)?.get(0)?.toInt() ?: throw IllegalArgumentException("tiled TIFF without TileLength")
            val across = (width + tileW - 1) / tileW
            for (idx in tileOffsets.indices) {
                val raw = src.readAt(tileOffsets[idx], tileByteCounts!![idx].toInt())
                val tx = idx % across
                val ty = idx / across
                storeBlock(raw, ty * tileH, tx * tileW, tileW, tileH)
            }
        } else if (stripOffsets != null && stripOffsets.isNotEmpty()) {
            require(stripByteCounts != null && stripByteCounts.size == stripOffsets.size) { "bad strip byte counts" }
            val rowsPerStrip = (longs(278)?.get(0) ?: height.toLong()).toInt()
            for (idx in stripOffsets.indices) {
                val raw = src.readAt(stripOffsets[idx], stripByteCounts!![idx].toInt())
                storeBlock(raw, idx * rowsPerStrip, 0, width, rowsPerStrip)
            }
        } else {
            throw IllegalArgumentException("TIFF has neither tiles nor strips")
        }

        val transform = doubles(34264)
        val pixelScale = doubles(33550)
        val tiePoint = doubles(33922)
        val geoKeys = longs(34735)

        var pixelIsArea = true
        if (geoKeys != null && geoKeys.size >= 4) {
            val nKeys = geoKeys[3].toInt()
            for (k in 0 until nKeys) {
                val base = 4 + k * 4
                if (base + 3 >= geoKeys.size) break
                if (geoKeys[base].toInt() == 1025) pixelIsArea = geoKeys[base + 3].toInt() == 1
            }
        }

        val tr: DoubleArray = if (transform != null && transform.size >= 16) {
            doubleArrayOf(transform[0], transform[1], 0.0, transform[3], transform[4], transform[5], 0.0, transform[7])
        } else if (pixelScale != null && pixelScale.size >= 2 && tiePoint != null && tiePoint.size >= 6) {
            val sx = pixelScale[0]
            val sy = pixelScale[1]
            doubleArrayOf(sx, 0.0, 0.0, tiePoint[3] - tiePoint[0] * sx, 0.0, sy, 0.0, tiePoint[4] - tiePoint[1] * sy)
        } else {
            throw IllegalArgumentException("GeoTIFF has no georeferencing (ModelTransformation or ModelPixelScale/ModelTiepoint missing)")
        }

        return GeoTiff(width, height, values, tr, ascii(42113), pixelIsArea)
    }

    private fun readU16(b: ByteArray, off: Int, little: Boolean): Int =
        if (little) ((b[off + 1].toInt() and 0xFF) shl 8) or (b[off].toInt() and 0xFF)
        else ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun readU32(b: ByteArray, off: Int, little: Boolean): Long {
        var v = 0L
        if (little) {
            for (i in 3 downTo 0) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        } else {
            for (i in 0..3) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        }
        return v
    }

    private fun inflate(raw: ByteArray, zlib: Boolean): ByteArray {
        fun attempt(nowrap: Boolean): ByteArray {
            val inf = Inflater(nowrap)
            inf.setInput(raw)
            val out = ByteArrayOutputStream(raw.size * 4 + 256)
            val buf = ByteArray(64 * 1024)
            try {
                while (!inf.finished()) {
                    val n = inf.inflate(buf)
                    if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                    out.write(buf, 0, n)
                }
            } finally {
                inf.end()
            }
            return out.toByteArray()
        }
        val first = attempt(!zlib)
        return if (first.isNotEmpty()) first else attempt(false)
    }

    /** TIFF-flavoured LZW (MSB-first, clear=256, EOI=257, early code-width change). */
    fun lzwDecode(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(raw.size * 4 + 256)
        val prefix = IntArray(4096) { -1 }
        val suffix = IntArray(4096)
        val first = IntArray(4096)
        val stack = ByteArray(8192)
        var codeWidth = 9
        var nextCode = 258
        var prev = -1
        var bitBuf = 0
        var bitCount = 0
        var pos = 0

        fun resetTable() {
            for (i in 0..255) {
                suffix[i] = i
                first[i] = i
            }
            codeWidth = 9
            nextCode = 258
            prev = -1
        }
        resetTable()

        while (true) {
            while (bitCount < codeWidth) {
                if (pos >= raw.size) return out.toByteArray()
                bitBuf = (bitBuf shl 8) or (raw[pos++].toInt() and 0xFF)
                bitCount += 8
            }
            val code = (bitBuf shr (bitCount - codeWidth)) and ((1 shl codeWidth) - 1)
            bitCount -= codeWidth

            if (code == 257) return out.toByteArray()
            if (code == 256) {
                resetTable()
                continue
            }

            var cur = code
            if (prev == -1) {
                if (code > 255) return out.toByteArray()
                out.write(code)
                prev = code
                continue
            }

            if (code < nextCode) {
                var cc = code
                var len = 0
                while (cc != -1) {
                    stack[len++] = suffix[cc].toByte()
                    cc = prefix[cc]
                }
                for (i in len - 1 downTo 0) out.write(stack[i].toInt() and 0xFF)
            } else if (code == nextCode) {
                var cc = prev
                var len = 0
                while (cc != -1) {
                    stack[len++] = suffix[cc].toByte()
                    cc = prefix[cc]
                }
                for (i in len - 1 downTo 0) out.write(stack[i].toInt() and 0xFF)
                out.write(first[prev])
                len += 1
            } else {
                return out.toByteArray() // corrupt stream
            }

            val firstChar = if (code < nextCode) first[code] else first[prev]
            if (nextCode < 4096) {
                prefix[nextCode] = prev
                suffix[nextCode] = firstChar
                first[nextCode] = first[prev]
                nextCode++
            }
            if (nextCode == (1 shl codeWidth) - 1 && codeWidth < 12) codeWidth++
            prev = cur
        }
    }

    fun packBitsDecode(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(raw.size * 2 + 16)
        var i = 0
        while (i < raw.size) {
            val n = raw[i++].toInt()
            if (n >= 0) {
                val count = n + 1
                out.write(raw, i, count)
                i += count
            } else if (n != -128) {
                val count = 1 - n
                val b = raw[i++].toInt() and 0xFF
                repeat(count) { out.write(b) }
            }
        }
        return out.toByteArray()
    }
}

object GeoTiffWriter {

    private val TYPE_SIZE = intArrayOf(0, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8)

    private class Entry(val tag: Int, val type: Int, val longValues: LongArray?, val blob: ByteArray?)

    /** Writes an uncompressed single-band float32 GeoTIFF (EPSG:4326, pixel-is-area). */
    fun write(grid: GeoGrid, out: OutputStream, nodata: String = "nan") {
        val nx = grid.nx
        val ny = grid.ny

        val pixelScale = doubleArrayOf(grid.dx, grid.dy, 0.0)
        val tiePoint = doubleArrayOf(0.0, 0.0, 0.0, grid.west, grid.north, 0.0)
        val geoKeys = shortArrayOf(
            1, 1, 0, 3,
            1024, 0, 1, 2,    // GTModelTypeGeoKey = ModelTypeGeographic
            1025, 0, 1, 1,    // GTRasterTypeGeoKey = RasterPixelIsArea
            2048, 0, 1, 4326, // GeographicTypeGeoKey = EPSG:4326
        )
        val nodataAscii = (nodata + "\u0000").toByteArray(Charsets.US_ASCII)
        val dataLen = nx * ny * 4L

        val entries = mutableListOf<Entry>()
        entries.add(Entry(256, 4, longArrayOf(nx.toLong()), null))            // ImageWidth
        entries.add(Entry(257, 4, longArrayOf(ny.toLong()), null))            // ImageLength
        entries.add(Entry(258, 3, longArrayOf(32), null))                     // BitsPerSample
        entries.add(Entry(259, 3, longArrayOf(1), null))                      // Compression = none
        entries.add(Entry(262, 3, longArrayOf(1), null))                      // Photometric = MinIsBlack
        entries.add(Entry(273, 4, longArrayOf(0), null))                       // StripOffsets (patched)
        entries.add(Entry(277, 3, longArrayOf(1), null))                      // SamplesPerPixel
        entries.add(Entry(278, 4, longArrayOf(ny.toLong()), null))            // RowsPerStrip
        entries.add(Entry(279, 4, longArrayOf(dataLen), null))                // StripByteCounts
        entries.add(Entry(284, 3, longArrayOf(1), null))                      // PlanarConfiguration
        entries.add(Entry(33550, 12, null, doublesToBytes(pixelScale)))       // ModelPixelScale
        entries.add(Entry(33922, 12, null, doublesToBytes(tiePoint)))         // ModelTiepoint
        entries.add(Entry(339, 3, null, shortsToBytes(shortArrayOf(3))))      // SampleFormat = IEEEFP
        entries.add(Entry(34735, 3, null, shortsToBytes(geoKeys)))             // GeoKeyDirectory
        entries.add(Entry(42113, 2, null, nodataAscii))                       // GDAL_NODATA
        entries.sortBy { it.tag }

        val stripOffsetsIdx = entries.indexOfFirst { it.tag == 273 }

        val n = entries.size
        val ifdLen = 2 + n * 12 + 4
        var cursor = 8L + ifdLen
        val blobOffsets = LongArray(n)
        for (i in 0 until n) {
            val e = entries[i]
            if (e.blob != null) {
                blobOffsets[i] = cursor
                cursor += (e.blob.size + 1) / 2 * 2
            }
        }
        val dataOffset = (cursor + 3) / 4 * 4

        val bo = ByteArrayOutputStream((dataOffset + 1024).toInt())
        fun le16(v: Int) {
            bo.write(v and 0xFF)
            bo.write((v shr 8) and 0xFF)
        }
        fun le32(v: Long) {
            bo.write((v and 0xFF).toInt())
            bo.write(((v shr 8) and 0xFF).toInt())
            bo.write(((v shr 16) and 0xFF).toInt())
            bo.write(((v shr 24) and 0xFF).toInt())
        }
        bo.write('I'.code)
        bo.write('I'.code)
        le16(42)
        le32(8)
        le16(n)
        for (i in 0 until n) {
            val e = entries[i]
            val count = if (e.blob != null) e.blob.size / TYPE_SIZE[e.type] else e.longValues!!.size
            le16(e.tag)
            le16(e.type)
            le32(count.toLong())
            if (e.blob != null) {
                le32(blobOffsets[i])
            } else {
                val v = e.longValues!!
                if (e.type == 3 && v.size == 1) {
                    le32(v[0]) // SHORT inline in low half
                } else {
                    require(v.size == 1) { "unexpected multi-value inline entry" }
                    le32(v[0])
                }
            }
        }
        le32(0) // next IFD
        for (i in 0 until n) {
            val e = entries[i]
            if (e.blob != null) {
                bo.write(e.blob)
                if (e.blob.size % 2 == 1) bo.write(0)
            }
        }
        while (bo.size() < dataOffset) bo.write(0)

        val header = bo.toByteArray()
        val patchPos = 8 + 2 + stripOffsetsIdx * 12 + 8
        val v = dataOffset
        header[patchPos] = (v and 0xFF).toByte()
        header[patchPos + 1] = ((v shr 8) and 0xFF).toByte()
        header[patchPos + 2] = ((v shr 16) and 0xFF).toByte()
        header[patchPos + 3] = ((v shr 24) and 0xFF).toByte()
        out.write(header)
        out.flush()

        // pixel data: row-major, north-first, float32, little-endian
        val row = ByteArray(nx * 4)
        for (r in 0 until ny) {
            var p = 0
            for (cIdx in 0 until nx) {
                val val_ = grid.values[r * nx + cIdx]
                val bits = val_.toRawBits()
                row[p++] = (bits and 0xFF).toByte()
                row[p++] = ((bits shr 8) and 0xFF).toByte()
                row[p++] = ((bits shr 16) and 0xFF).toByte()
                row[p++] = ((bits shr 24) and 0xFF).toByte()
            }
            out.write(row)
        }
        out.flush()
    }

    private fun doublesToBytes(a: DoubleArray): ByteArray {
        val bb = ByteBuffer.allocate(a.size * 8).order(ByteOrder.LITTLE_ENDIAN)
        a.forEach { bb.putDouble(it) }
        return bb.array()
    }

    private fun shortsToBytes(a: ShortArray): ByteArray {
        val bb = ByteBuffer.allocate(a.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        a.forEach { bb.putShort(it) }
        return bb.array()
    }

    private fun shortArrayOf(vararg v: Int): ShortArray = ShortArray(v.size) { v[it].toShort() }
}
