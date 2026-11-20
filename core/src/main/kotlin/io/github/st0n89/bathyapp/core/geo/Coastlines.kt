package io.github.st0n89.bathyapp.core.geo

import java.io.InputStream

/**
 * Compact coastline store for the globe picker.
 *
 * Plain-text format so the asset survives any transport losslessly: the
 * whole file is one whitespace-separated token stream. First two tokens
 * are "NECOAST <lineCount>", then per polyline "<pointCount>" followed by
 * that many "<lon> <lat>" decimal-degree pairs. Newlines are cosmetic.
 * Polylines are [lon, lat] pairs interleaved in a FloatArray.
 */
class Coastlines(val polylines: Array<FloatArray>) {

    /** Total vertex count across all polylines. */
    val pointCount: Int = polylines.sumOf { it.size / 2 }

    companion object {

        private val SPLIT_TOKEN = Regex("\\s+")

        /**
         * Reads the token stream. [expectedHeader] defaults to the coastline
         * file; the land file uses the same format with header NELAND.
         */
        fun read(input: InputStream, expectedHeader: String = "NECOAST"): Coastlines {
            val tokens = input.readBytes()
                .toString(Charsets.UTF_8)
                .split(SPLIT_TOKEN)
                .filter { it.isNotEmpty() }
            var i = 0

            fun next(): String {
                require(i < tokens.size) { "truncated coastline file" }
                return tokens[i++]
            }

            require(next() == expectedHeader) { "bad file header, expected $expectedHeader" }
            val n = next().toIntOrNull()
                ?: throw IllegalArgumentException("bad line count")
            require(n in 0..1_000_000) { "bad line count: $n" }
            val lines = Array(n) {
                val pts = next().toIntOrNull()
                    ?: throw IllegalArgumentException("bad point count")
                require(pts in 2..1_000_000) { "bad point count: $pts" }
                val v = FloatArray(pts * 2)
                for (j in 0 until pts * 2) {
                    v[j] = next().toFloatOrNull()
                        ?: throw IllegalArgumentException("bad coordinate: ${tokens[i]}")
                }
                v
            }
            require(i == tokens.size) { "trailing garbage in coastline file" }
            return Coastlines(lines)
        }
    }
}
