package io.github.st0n89.bathyapp.core

import io.github.st0n89.bathyapp.core.geo.GlobeColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GlobeColorsTest {

    private fun tex(text: String): GlobeColors =
        GlobeColors.read(text.trimIndent().byteInputStream())

    @Test
    fun parsesHeaderPaletteAndRle() {
        val t = tex(
            """
            NECOLR 2 2 2
            255 0 0
            0 0 255
            2 0
            1 0 1 1
            """
        )
        assertEquals(2, t.w)
        assertEquals(2, t.h)
        val red = 0xFF000000.toInt() or (255 shl 16)
        val blue = 0xFF0000FF.toInt()
        assertEquals(red, t.sample(0, 0))
        assertEquals(red, t.sample(1, 0))
        assertEquals(red, t.sample(0, 1))
        assertEquals(blue, t.sample(1, 1))
    }

    @Test
    fun longRunsExpandExactly() {
        val t = tex(
            """
            NECOLR 3 1 1
            10 20 30
            3 0
            """
        )
        assertEquals(3, t.w)
        assertEquals(1, t.h)
        assertEquals(3, t.idx.size)
        for (k in 0 until 3) assertEquals(0, t.idx[k].toInt())
        assertEquals(0xFF0A141E.toInt(), t.sample(2, 0))
    }

    @Test
    fun badHeaderIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            tex("NELAND 1 1 1\n0 0 0\n1 0\n")
        }
    }

    @Test
    fun runOverflowIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            tex("NECOLR 2 1 1\n0 0 0\n3 0\n")
        }
        assertFailsWith<IllegalArgumentException> {
            tex("NECOLR 2 1 1\n0 0 0\n1 0 2 0\n")
        }
    }

    @Test
    fun shardedTextureDecodesIdentically() {
        // 4x2 texture split into two shards: rows 0-1 in the master, 2-3 in the shard.
        val whole = """
            NECOLR 4 4 2
            255 0 0
            0 0 255
            4 0
            2 0 2 1
            4 1
            3 0 1 1
        """.trimIndent()
        val master = """
            NECOLR2 4 4 2 2
            255 0 0
            0 0 255
            4 0
            2 0 2 1
        """.trimIndent()
        val shard1 = """
            NECOLRS 4 4
            4 1
            3 0 1 1
        """.trimIndent()
        val files = mapOf("ne1.txt" to master, "ne1_s1.txt" to shard1)
        val ref = GlobeColors.read(whole.byteInputStream())
        val t = GlobeColors.read("ne1.txt") { p ->
            files[p]?.byteInputStream() ?: error("missing asset $p")
        }
        assertEquals(ref.w, t.w)
        assertEquals(ref.h, t.h)
        assertTrue(t.idx.contentEquals(ref.idx))
        assertTrue(t.palette.contentEquals(ref.palette))
    }

    @Test
    fun missingShardIsRejected() {
        val master = """
            NECOLR2 2 2 1 2
            0 0 0
            2 0
        """.trimIndent()
        assertFailsWith<IllegalStateException> {
            GlobeColors.read("ne1.txt") { p ->
                if (p == "ne1.txt") master.byteInputStream() else error("missing asset $p")
            }
        }
    }

    @Test
    fun bilinearMatchesNearestAtCentresAndBlendsBetween() {
        val t = tex(
            """
            NECOLR 2 2 2
            255 0 0
            0 255 0
            2 0
            2 1
            """
        )
        val red = t.sample(0, 0)
        val green = t.sample(0, 1)
        // integer coords: exact pixel colours
        assertEquals(red, t.sampleBilinear(0.0, 0.0))
        assertEquals(green, t.sampleBilinear(0.0, 1.0))
        // halfway between the two rows: r and g channels average
        val mid = t.sampleBilinear(0.0, 0.5)
        val mR = (mid shr 16) and 0xFF
        val mG = (mid shr 8) and 0xFF
        val mB = mid and 0xFF
        assertEquals(128, mR)
        assertEquals(128, mG)
        assertEquals(0, mB)
        // clamps beyond the edges instead of crashing
        assertEquals(red, t.sampleBilinear(-5.0, -5.0))
        assertEquals(green, t.sampleBilinear(3.0, 1.5))
    }
}
