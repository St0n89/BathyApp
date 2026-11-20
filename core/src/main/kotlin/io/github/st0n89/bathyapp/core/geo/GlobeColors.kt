package io.github.st0n89.bathyapp.core.geo

import java.io.InputStream

/**
 * Equirectangular palette texture for the globe (Natural Earth I colors).
 *
 * Plain-text token format like the coastline file: the whole file is one
 * whitespace-separated token stream. Header "NECOLR <w> <h> <paletteCount>",
 * then paletteCount entries "r g b" (0..255), then for each image row a
 * run-length list "count idx count idx ..." whose counts sum to w.
 */
class GlobeColors(val w: Int, val h: Int, val palette: IntArray, val idx: ByteArray) {

    /** Packed opaque ARGB at mask column [mi] and row [mj] (caller clamps). */
    fun sample(mi: Int, mj: Int): Int = palette[idx[mj * w + mi].toInt() and 0xFF]

    companion object {

        fun read(input: InputStream, expectedHeader: String = "NECOLR"): GlobeColors {
            // Manual scanner: the text asset can be several MB with ~1M+ tokens,
            // String.split() would allocate one String per token.
            val s = input.readBytes().toString(Charsets.UTF_8)
            val n = s.length
            var i = 0

            fun skipWhitespace() {
                while (i < n) {
                    val c = s[i]
                    if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++ else break
                }
            }

            fun expectWord(word: String) {
                skipWhitespace()
                require(s.startsWith(word, i)) { "bad file header, expected $word" }
                i += word.length
            }

            fun nextInt(what: String): Int {
                skipWhitespace()
                var v = 0
                var any = false
                while (i < n && s[i] in '0'..'9') {
                    v = v * 10 + (s[i] - '0')
                    any = true
                    i++
                }
                require(any && v <= Int.MAX_VALUE) { "bad $what at offset $i" }
                return v
            }

            expectWord(expectedHeader)
            val w = nextInt("width")
            val h = nextInt("height")
            val p = nextInt("palette size")
            require(w in 1..10_000 && h in 1..10_000) { "bad texture size ${w}x$h" }
            require(p in 1..4096) { "bad palette size: $p" }
            val palette = IntArray(p) { k ->
                val r = nextInt("red")
                val g = nextInt("green")
                val b = nextInt("blue")
                require(r in 0..255 && g in 0..255 && b in 0..255) { "bad palette entry $k" }
                0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
            }
            val idx = ByteArray(w * h)
            var o = 0
            for (row in 0 until h) {
                var x = 0
                while (x < w) {
                    val run = nextInt("run")
                    val v = nextInt("palette index")
                    require(run in 1..w && v in 0 until p) { "bad rle row $row" }
                    require(x + run <= w) { "rle overflow in row $row" }
                    repeat(run) { q -> idx[o + x + q] = v.toByte() }
                    x += run
                }
                o += w
            }
            skipWhitespace()
            require(i == n) { "trailing garbage in texture file" }
            return GlobeColors(w, h, palette, idx)
        }
    }
}
