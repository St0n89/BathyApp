package io.github.st0n89.bathyapp.core

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal classic-NetCDF (CDF-1/CDF-2) writer used to build test fixtures.
 * This is a straight port of a roundtrip-validated Python reference writer.
 */
class Nc3Writer(private val cdf2: Boolean = true) {

    class Att(val name: String, val type: Int, val values: Any)
    class Var(
        val name: String,
        val type: Int,
        val dims: List<String>,
        val atts: List<Att>,
        val data: Any,
        // for record variables: List<ByteArray>, one already-encoded slice per record
        val recordSlices: List<ByteArray>? = null,
    )

    private val dims = mutableListOf<Pair<String, Long>>()
    private val gatts = mutableListOf<Att>()
    private val vars = mutableListOf<Var>()

    fun dim(name: String, size: Long) {
        dims.add(name to size)
    }

    fun globalAtt(att: Att) {
        gatts.add(att)
    }

    fun variable(v: Var) {
        vars.add(v)
    }

    companion object {
        val TYPE_SIZE = intArrayOf(0, 1, 1, 2, 4, 4, 8)

        fun pad4(n: Int) = n + (4 - n % 4) % 4

        fun encode(type: Int, values: Any): ByteArray {
            val raw: ByteArray = when (type) {
                1 -> when (values) {
                    is ByteArray -> values
                    is IntArray -> ByteBuffer.allocate(values.size).order(ByteOrder.BIG_ENDIAN).apply { values.forEach { put(it.toByte()) } }.array()
                    else -> throw IllegalArgumentException("byte values expected")
                }
                2 -> (values as String).toByteArray(Charsets.UTF_8)
                3 -> when (values) {
                    is ShortArray -> ByteBuffer.allocate(values.size * 2).order(ByteOrder.BIG_ENDIAN).apply { values.forEach { putShort(it) } }.array()
                    is IntArray -> ByteBuffer.allocate(values.size * 2).order(ByteOrder.BIG_ENDIAN).apply { values.forEach { putShort(it.toShort()) } }.array()
                    is Short -> ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(values).array()
                    is Int -> ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(values.toShort()).array()
                    else -> throw IllegalArgumentException("short values expected")
                }
                4 -> when (values) {
                    is IntArray -> ByteBuffer.allocate(values.size * 4).order(ByteOrder.BIG_ENDIAN).apply { values.forEach { putInt(it) } }.array()
                    is LongArray -> ByteBuffer.allocate(values.size * 4).order(ByteOrder.BIG_ENDIAN).apply { values.forEach { putInt(it.toInt()) } }.array()
                    is Int -> ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(values).array()
                    is Long -> ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(values.toInt()).array()
                    else -> throw IllegalArgumentException("int values expected")
                }
                5 -> when (values) {
                    is FloatArray -> ByteBuffer.allocate(values.size * 4).order(ByteOrder.BIG_ENDIAN).apply { values.forEach { putFloat(it) } }.array()
                    is Float -> ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putFloat(values).array()
                    else -> throw IllegalArgumentException("float values expected")
                }
                6 -> when (values) {
                    is DoubleArray -> ByteBuffer.allocate(values.size * 8).order(ByteOrder.BIG_ENDIAN).apply { values.forEach { putDouble(it) } }.array()
                    is Double -> ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putDouble(values).array()
                    else -> throw IllegalArgumentException("double values expected")
                }
                else -> throw IllegalArgumentException("bad type $type")
            }
            val out = ByteArray(pad4(raw.size))
            System.arraycopy(raw, 0, out, 0, raw.size)
            return out
        }

        fun countOf(type: Int, values: Any): Int = when (type) {
            2 -> (values as String).length
            else -> when (values) {
                is ShortArray -> values.size
                is IntArray -> values.size
                is LongArray -> values.size
                is FloatArray -> values.size
                is DoubleArray -> values.size
                is ByteArray -> values.size
                is List<*> -> values.size
                is Short, is Int, is Long, is Float, is Double, is Byte -> 1
                else -> throw IllegalArgumentException("cannot count $values")
            }
        }

        fun nameBytes(n: String): ByteArray {
            val b = n.toByteArray(Charsets.UTF_8)
            val out = ByteArray(4 + pad4(b.size))
            ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN).putInt(b.size)
            System.arraycopy(b, 0, out, 4, b.size)
            return out
        }

        fun cat(a: ByteArray, b: ByteArray): ByteArray {
            val out = ByteArray(a.size + b.size)
            System.arraycopy(a, 0, out, 0, a.size)
            System.arraycopy(b, 0, out, a.size, b.size)
            return out
        }

        fun be32(vararg vals: Int): ByteArray {
            val bb = ByteBuffer.allocate(vals.size * 4).order(ByteOrder.BIG_ENDIAN)
            vals.forEach { bb.putInt(it) }
            return bb.array()
        }
    }

    fun write(file: File) {
        val dimId = mutableMapOf<String, Int>()
        dims.forEachIndexed { i, d -> dimId[d.first] = i }

        fun attsBlock(list: List<Att>): ByteArray {
            if (list.isEmpty()) return be32(0, 0)
            var out = be32(12, list.size)
            for (a in list) {
                out = cat(out, nameBytes(a.name))
                out = cat(out, be32(a.type, countOf(a.type, a.values)))
                out = cat(out, encode(a.type, a.values))
            }
            return out
        }

        // classify variables
        val recDimName = dims.firstOrNull { it.second == 0L }?.first
        val isRecord = vars.map { v -> v.dims.contains(recDimName) }

        var numRecs = 0
        if (recDimName != null) {
            numRecs = vars.first { it.dims.contains(recDimName) }.recordSlices!!.size
        }

        fun vsizeOf(v: Var, record: Boolean): Long {
            var n = 1L
            for (d in v.dims) {
                val sz = dims[dimId[d]!!.toInt()].second
                if (sz != 0L) n *= sz
            }
            return pad4((n * TYPE_SIZE[v.type]).toInt()).toLong()
        }

        val vsizes = vars.mapIndexed { i, v -> vsizeOf(v, isRecord[i]) }

        // header with placeholder begins, to compute size
        fun buildHeader(begins: List<Long>): ByteArray {
            var out = cat(be32(10, dims.size), ByteArray(0))
            for ((name, size) in dims) {
                out = cat(out, nameBytes(name))
                out = cat(out, be32(size.toInt()))
            }
            out = cat(out, attsBlock(gatts))
            out = cat(out, be32(11, vars.size))
            for ((i, v) in vars.withIndex()) {
                out = cat(out, nameBytes(v.name))
                out = cat(out, be32(v.dims.size))
                for (d in v.dims) out = cat(out, be32(dimId[d]!!))
                out = cat(out, attsBlock(v.atts))
                out = cat(out, be32(v.type, vsizes[i].toInt()))
                if (cdf2) {
                    val bb = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
                    bb.putLong(begins[i])
                    out = cat(out, bb.array())
                } else {
                    out = cat(out, be32(begins[i].toInt()))
                }
            }
            return out
        }

        val dummy = buildHeader(List(vars.size) { 0L })
        var base = 4 + 4 + dummy.size.toLong()
        if (base % 4 != 0L) base += 4 - base % 4

        val begins = mutableListOf<Long>()
        var cur = base
        for ((i, v) in vars.withIndex()) {
            if (!isRecord[i]) {
                begins.add(cur)
                cur += vsizes[i]
            } else {
                begins.add(-1) // patched after the non-record pass
            }
        }
        for ((i, v) in vars.withIndex()) {
            if (isRecord[i]) begins[i] = cur // record section starts here
        }

        val header = buildHeader(begins)

        // Layout: non-record variables first, each occupying vsize bytes
        // (data at block start, padding after), then interleaved records.
        val body = java.io.ByteArrayOutputStream()
        for ((i, v) in vars.withIndex()) {
            if (!isRecord[i]) {
                val raw = encode(v.type, v.data)
                body.write(raw)
                if (raw.size < vsizes[i]) body.write(ByteArray((vsizes[i] - raw.size).toInt()))
            }
        }
        for (r in 0 until numRecs) {
            for ((i, v) in vars.withIndex()) {
                if (isRecord[i]) {
                    body.write(v.recordSlices!![r])
                }
            }
        }

        FileOutputStream(file).use { fos ->
            fos.write(if (cdf2) byteArrayOf('C'.code.toByte(), 'D'.code.toByte(), 'F'.code.toByte(), 2)
            else byteArrayOf('C'.code.toByte(), 'D'.code.toByte(), 'F'.code.toByte(), 1))
            val bb = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
            bb.putInt(numRecs)
            fos.write(bb.array())
            fos.write(header)
            fos.write(body.toByteArray())
        }
    }
}
