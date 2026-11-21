package io.github.st0n89.bathyapp.core.geo

import java.io.InputStream

/**
 * Equirectangular palette texture for the globe (Natural Earth I colors).
 *
 * Plain-text token format like the coastline file. Single file:
 * "NECOLR <w> <h> <paletteCount>" then paletteCount entries "r g b" (0..255),
 * then for each image row a run-length list "count idx ..." whose counts sum to w.
 *
 * Sharded variant for large textures (identical decoded output): the master
 * file starts "NECOLR2 <w> <h> <paletteCount> <shardCount>", carries the
 * palette and the FIRST rows; the shardCount-1 follower files (assetPath with
 * "_s<i>" inserted before the extension, i starting at 1) start
 * "NECOLRS <w> <h>" and carry the remaining rows in order.
 */
class GlobeColors(val w: Int, val h: Int, val palette: IntArray, val idx: ByteArray) {

    /** Packed opaque ARGB at mask column [mi] and row [mj] (caller clamps). */
    fun sample(mi: Int, mj: Int): Int = palette[idx[mj * w + mi].toInt() and 0xFF]

    /**
     * Bilinear texture lookup at fractional mask coordinates: pixel centers sit
     * at integers, [x] in [0, w-1], [y] in [0, h-1] (values are clamped).
     */
    fun sampleBilinear(x: Double, y: Double): Int = bilinear(idx, w, h, x, y)

    // ------------------------------------------------------------------
    // Mip levels (software mimicking): the software renderer samples the full
    // texture through small buffers (a 400-px motion buffer against a 5400-px
    // hemisphere). Single-point bilinear lookups at that ratio alias badly: the
    // baked hillshade relief crawls and shimmers while the view moves. Sampled
    // from a box-filtered mip level instead, the result is stable. Levels are
    // palette-indexed, so each 2x2 average is snapped back to the nearest
    // palette colour via a 32^3 lookup lattice.
    // ------------------------------------------------------------------

    /** Level 0 is [idx] itself; each higher level halves both axes. >= 1. */
    val lodCount: Int

    private val lodData: Array<ByteArray>
    private val lodWidths: IntArray
    private val lodHeights: IntArray

    init {
        val levels = ArrayList<ByteArray>()
        val ws = ArrayList<Int>()
        val hs = ArrayList<Int>()
        levels.add(idx); ws.add(w); hs.add(h)
        if (w >= 16 && h >= 16) {
            val latN = 32
            val lattice = IntArray(latN * latN * latN)
            for (r in 0 until latN) {
                for (g in 0 until latN) {
                    for (b in 0 until latN) {
                        val rr = r * 8 + 4
                        val gg = g * 8 + 4
                        val bb = b * 8 + 4
                        var best = 0
                        var bestD = Int.MAX_VALUE
                        for (k in palette.indices) {
                            val c = palette[k]
                            val dr = rr - ((c shr 16) and 0xFF)
                            val dg = gg - ((c shr 8) and 0xFF)
                            val db = bb - (c and 0xFF)
                            val d = dr * dr + dg * dg + db * db
                            if (d < bestD) { bestD = d; best = k }
                        }
                        lattice[(r * latN + g) * latN + b] = best
                    }
                }
            }
            var cw = w
            var ch = h
            var cur = idx
            while (cw >= 16 && ch >= 16) {
                val nw = cw / 2
                val nh = ch / 2
                val nxt = ByteArray(nw * nh)
                for (j in 0 until nh) {
                    val o0 = (2 * j) * cw
                    val o1 = o0 + cw
                    val no = j * nw
                    for (i in 0 until nw) {
                        val c0 = palette[cur[o0 + 2 * i].toInt() and 0xFF]
                        val c1 = palette[cur[o0 + 2 * i + 1].toInt() and 0xFF]
                        val c2 = palette[cur[o1 + 2 * i].toInt() and 0xFF]
                        val c3 = palette[cur[o1 + 2 * i + 1].toInt() and 0xFF]
                        val rs = ((c0 shr 16) and 0xFF) + ((c1 shr 16) and 0xFF) +
                            ((c2 shr 16) and 0xFF) + ((c3 shr 16) and 0xFF)
                        val gs = ((c0 shr 8) and 0xFF) + ((c1 shr 8) and 0xFF) +
                            ((c2 shr 8) and 0xFF) + ((c3 shr 8) and 0xFF)
                        val bs = (c0 and 0xFF) + (c1 and 0xFF) + (c2 and 0xFF) + (c3 and 0xFF)
                        val v = lattice[(((rs ushr 5) * latN + (gs ushr 5)) * latN + (bs ushr 5))]
                        nxt[no + i] = v.toByte()
                    }
                }
                levels.add(nxt); ws.add(nw); hs.add(nh)
                cw = nw; ch = nh; cur = nxt
            }
        }
        lodData = levels.toTypedArray()
        lodWidths = ws.toIntArray()
        lodHeights = hs.toIntArray()
        lodCount = lodData.size
    }

    /**
     * Bilinear lookup on mip level [level] (0 = full resolution). [x]/[y] are
     * level-0 texture coordinates with pixel centers at integers, exactly like
     * [sampleBilinear]; they are mapped onto the level's own grid.
     */
    fun sampleBilinear(x: Double, y: Double, level: Int): Int {
        require(level in 0 until lodCount) { "bad mip level $level/$lodCount" }
        if (level == 0) return bilinear(idx, w, h, x, y)
        val scale = 1 shl level
        val lw = lodWidths[level]
        val lh = lodHeights[level]
        return bilinear(lodData[level], lw, lh, x / scale - 0.5, y / scale - 0.5)
    }

    /** Shared bilinear core over a palette-indexed level buffer. */
    private fun bilinear(src: ByteArray, sw: Int, sh: Int, x: Double, y: Double): Int {
        val xf = x.coerceIn(0.0, (sw - 1).toDouble())
        val yf = y.coerceIn(0.0, (sh - 1).toDouble())
        val x0 = xf.toInt()
        val y0 = yf.toInt()
        val x1 = if (x0 + 1 < sw) x0 + 1 else x0
        val y1 = if (y0 + 1 < sh) y0 + 1 else y0
        val fx = xf - x0
        val fy = yf - y0
        val c00 = palette[src[y0 * sw + x0].toInt() and 0xFF]
        val c10 = palette[src[y0 * sw + x1].toInt() and 0xFF]
        val c01 = palette[src[y1 * sw + x0].toInt() and 0xFF]
        val c11 = palette[src[y1 * sw + x1].toInt() and 0xFF]
        fun top(a: Int, b: Int): Int = a + (((b - a) * fx + 0.5).toInt())
        fun pick(c: Int, shift: Int): Int = (c shr shift) and 0xFF
        val r0 = top(pick(c00, 16), pick(c10, 16))
        val r1 = top(pick(c01, 16), pick(c11, 16))
        val g0 = top(pick(c00, 8), pick(c10, 8))
        val g1 = top(pick(c01, 8), pick(c11, 8))
        val b0 = top(c00 and 0xFF, c10 and 0xFF)
        val b1 = top(c01 and 0xFF, c11 and 0xFF)
        val r = r0 + (((r1 - r0) * fy + 0.5).toInt())
        val g = g0 + (((g1 - g0) * fy + 0.5).toInt())
        val b = b0 + (((b1 - b0) * fy + 0.5).toInt())
        return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
    }

    companion object {

        /**
         * Whitespace-separated manual scanner: the text asset can be several MB
         * with more than a million tokens; String.split() would allocate one
         * String per token.
         */
        private class Scanner(val s: String) {
            val n = s.length
            var i = 0

            fun skipWhitespace() {
                while (i < n) {
                    val c = s[i]
                    if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++ else break
                }
            }

            fun hasMore(): Boolean {
                skipWhitespace()
                return i < n
            }

            fun nextWord(): String {
                skipWhitespace()
                val start = i
                while (i < n) {
                    val c = s[i]
                    if (c == ' ' || c == '\t' || c == '\n' || c == '\r') break
                    i++
                }
                require(i > start) { "bad token at offset $i" }
                return s.substring(start, i)
            }

            fun nextInt(what: String): Int {
                skipWhitespace()
                var v = 0L
                var any = false
                while (i < n && s[i] in '0'..'9') {
                    v = v * 10 + (s[i] - '0')
                    require(v <= Int.MAX_VALUE) { "bad $what at offset $i" }
                    any = true
                    i++
                }
                require(any) { "bad $what at offset $i" }
                return v.toInt()
            }
        }

        private fun parsePalette(sc: Scanner, p: Int): IntArray = IntArray(p) { k ->
            val r = sc.nextInt("red")
            val g = sc.nextInt("green")
            val b = sc.nextInt("blue")
            require(r in 0..255 && g in 0..255 && b in 0..255) { "bad palette entry $k" }
            0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
        }

        /** Fills RLE rows into [idx] starting at [row]; returns the new row count. */
        private fun fillRows(sc: Scanner, idx: ByteArray, w: Int, h: Int, fromRow: Int, paletteSize: Int): Int {
            var row = fromRow
            while (row < h && sc.hasMore()) {
                var x = 0
                val off = row * w
                while (x < w) {
                    val run = sc.nextInt("run")
                    val v = sc.nextInt("palette index")
                    require(run in 1..w && v < paletteSize) { "bad rle row $row" }
                    require(x + run <= w) { "rle overflow in row $row" }
                    repeat(run) { q -> idx[off + x + q] = v.toByte() }
                    x += run
                }
                row++
            }
            return row
        }


        private fun parseHeader0(sc: Scanner): Triple<Int, Int, Int> {
            val w = sc.nextInt("width")
            val h = sc.nextInt("height")
            val p = sc.nextInt("palette size")
            require(w in 1..19200 && h in 1..19200) { "bad texture size ${w}x$h" }
            require(w.toLong() * h <= 80_000_000L) { "texture too large: ${w}x$h" }
            require(p in 1..4096) { "bad palette size: $p" }
            return Triple(w, h, p)
        }

        fun read(input: InputStream, expectedHeader: String = "NECOLR"): GlobeColors {
            val sc = Scanner(input.readBytes().toString(Charsets.UTF_8))
            val header = sc.nextWord()
            require(header == expectedHeader) { "bad file header, expected $expectedHeader" }
            val (w, h, p) = parseHeader0(sc)
            val palette = parsePalette(sc, p)
            val idx = ByteArray(w * h)
            val rows = fillRows(sc, idx, w, h, 0, p)
            require(rows == h) { "expected $h rows, got $rows" }
            require(!sc.hasMore()) { "trailing garbage in texture file" }
            return GlobeColors(w, h, palette, idx)
        }

        /**
         * Reads a texture asset: single "NECOLR" file or "NECOLR2" master plus
         * "_s<i>" shard files, all via [open]. [assetPath] is also the key for
         * the shard paths (e.g. "ne1_50m.txt" -> "ne1_50m_s1.txt").
         */
        fun read(assetPath: String, open: (String) -> InputStream): GlobeColors {
            val masterText = open(assetPath).use { it.readBytes().toString(Charsets.UTF_8) }
            val sc = Scanner(masterText)
            val header = sc.nextWord()
            if (header == "NECOLR") return read(masterText.byteInputStream())

            val (w, h, p) = parseHeader0(sc)
            val shardCount = sc.nextInt("shard count")
            require(shardCount in 1..64) { "bad shard count: $shardCount" }
            val palette = parsePalette(sc, p)
            val idx = ByteArray(w * h)
            var row = fillRows(sc, idx, w, h, 0, p)
            require(row < h || shardCount == 1) { "extra rows in master shard" }

            val stem = assetPath.substringBeforeLast('.')
            val ext = assetPath.substringAfterLast('.', "txt")
            for (s in 1 until shardCount) {
                val shardText = open("$stem" + "_s$s.$ext").use { it.readBytes().toString(Charsets.UTF_8) }
                val ssc = Scanner(shardText)
                val shardHeader = ssc.nextWord()
                require(shardHeader == "NECOLRS") { "bad shard header, expected NECOLRS" }
                val sw = ssc.nextInt("width")
                val sh = ssc.nextInt("height")
                require(sw == w && sh == h) { "shard $s size mismatch: ${sw}x$sh" }
                row = fillRows(ssc, idx, w, h, row, p)
                require(!ssc.hasMore()) { "trailing garbage in shard $s" }
            }
            require(row == h) { "expected $h rows, got $row" }
            return GlobeColors(w, h, palette, idx)
        }
    }
}
