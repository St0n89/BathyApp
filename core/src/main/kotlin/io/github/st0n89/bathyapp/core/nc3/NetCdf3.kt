package io.github.st0n89.bathyapp.core.nc3

import io.github.st0n89.bathyapp.core.data.Cursor
import io.github.st0n89.bathyapp.core.data.SeekSource
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal, dependency-free reader for the classic NetCDF formats
 * (CDF-1 "classic" and CDF-2 "64-bit offset").
 *
 * This is the format GEBCO distributes its global grids in. NetCDF-4 (HDF5)
 * files are NOT supported.
 */
class NetCdf3File(private val src: SeekSource) : AutoCloseable {

    class NcDim(val name: String, val size: Long)
    class NcVar(
        val name: String,
        val type: Int,
        val vsize: Long,
        val begin: Long,
        val dimIds: IntArray,
        val attributes: Map<String, NcAttribute>,
    )

    val version: Int
    val numRecs: Long
    val dims: MutableList<NcDim> = mutableListOf()
    val globalAttributes: MutableMap<String, NcAttribute> = mutableMapOf()
    val variables: MutableList<NcVar> = mutableListOf()

    init {
        val c = Cursor(src)
        val magic = c.bytes(4)
        require(magic[0] == 'C'.code.toByte() && magic[1] == 'D'.code.toByte() && magic[2] == 'F'.code.toByte()) {
            "not a classic NetCDF file (missing CDF magic)"
        }
        version = magic[3].toInt()
        require(version == 1 || version == 2) {
            if (version == 5) "CDF-5 (64-bit data) files are not supported" else "unsupported NetCDF format version $version (NetCDF-4/HDF5 files are not supported)"
        }
        numRecs = c.u32be()
        readDimList(c)
        readAttList(c, globalAttributes)
        readVarList(c)
    }

    private fun readDimList(c: Cursor) {
        val tag = c.u32be().toInt()
        val count = c.u32be().toInt()
        if (tag == ABSENT && count == ABSENT) return
        require(tag == NC_DIMENSION) { "bad dim_list tag $tag" }
        repeat(count) {
            val name = readName(c)
            dims.add(NcDim(name, c.u32be()))
        }
    }

    private fun readAttList(c: Cursor, out: MutableMap<String, NcAttribute>) {
        val tag = c.u32be().toInt()
        val count = c.u32be().toInt()
        if (tag == ABSENT && count == ABSENT) return
        require(tag == NC_ATTRIBUTE) { "bad att_list tag $tag" }
        repeat(count) {
            val name = readName(c)
            val type = c.u32be().toInt()
            require(type in 1..6) { "bad attribute type $type" }
            val n = c.u32be().toInt()
            val byteCount = n * TYPE_SIZE[type]
            val raw = c.bytes(byteCount)
            pad(c, byteCount)
            out[name] = NcAttribute(type, raw)
        }
    }

    private fun readVarList(c: Cursor) {
        val tag = c.u32be().toInt()
        val count = c.u32be().toInt()
        if (tag == ABSENT && count == ABSENT) return
        require(tag == NC_VARIABLE) { "bad var_list tag $tag" }
        repeat(count) {
            val name = readName(c)
            val ndims = c.u32be().toInt()
            val dimIds = IntArray(ndims) { c.u32be().toInt() }
            val atts = mutableMapOf<String, NcAttribute>()
            readAttList(c, atts)
            val type = c.u32be().toInt()
            require(type in 1..6) { "bad variable type $type" }
            val vsize = c.u32be()
            val begin = if (version == 2) c.bytes(8).let { b ->
                var v = 0L
                for (i in 0..7) v = (v shl 8) or (b[i].toLong() and 0xFF)
                v
            } else c.u32be()
            variables.add(NcVar(name, type, vsize, begin, dimIds, atts))
        }
    }

    private fun readName(c: Cursor): String {
        val n = c.u32be().toInt()
        require(n in 1..4096) { "bad name length $n" }
        val raw = c.bytes(n)
        pad(c, n)
        return String(raw, Charsets.UTF_8)
    }

    private fun pad(c: Cursor, byteCount: Int) {
        val rem = byteCount % 4
        if (rem != 0) c.bytes(4 - rem)
    }

    fun findVar(name: String): NcVar? = variables.firstOrNull { it.name == name }

    fun dimOf(v: NcVar, axis: Int): NcDim = dims[v.dimIds[axis]]

    fun isRecordVar(v: NcVar): Boolean = v.dimIds.any { dims[it].size == 0L }

    /**
     * Reads a whole (small) numeric variable and converts it to doubles.
     * Intended for 1-D coordinate variables.
     */
    fun readAllDoubles(v: NcVar): DoubleArray {
        require(!isRecordVar(v)) { "record variables not supported here: ${v.name}" }
        var n = 1L
        for (d in v.dimIds) n *= dims[d].size
        require(n in 1..8_000_000L) { "variable too large: ${v.name} ($n elements)" }
        val raw = src.readAt(v.begin, (n * TYPE_SIZE[v.type]).toInt())
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
        return DoubleArray(n.toInt()) { convertElement(bb, v.type) }
    }

    private fun convertElement(bb: ByteBuffer, type: Int): Double = when (type) {
        1 -> bb.get().toDouble()
        2 -> (bb.get().toInt() and 0xFF).toDouble()
        3 -> bb.short.toDouble()
        4 -> bb.int.toDouble()
        5 -> bb.float.toDouble()
        else -> bb.double
    }

    /**
     * Reads a strided sub-rectangle of a 2-D non-record variable.
     * Returns values row-major in file order (row 0 of the file first).
     */
    fun read2DSubgrid(
        v: NcVar,
        rowStart: Int,
        rowCount: Int,
        colStart: Int,
        colCount: Int,
        colStride: Int,
    ): FloatArray {
        require(!isRecordVar(v)) { "record variables are not supported as grids: ${v.name}" }
        require(v.dimIds.size == 2) { "${v.name} is not 2-D" }
        val nCols = dims[v.dimIds[1]].size
        val ts = TYPE_SIZE[v.type]
        val fill = fillValue(v)
        val out = FloatArray(rowCount * colCount)
        val rowByteLen = nCols * ts
        for (r in 0 until rowCount) {
            val rowOff = v.begin + (rowStart + r).toLong() * rowByteLen
            val span = ((colCount - 1) * colStride + 1) * ts
            val raw = src.readAt(rowOff + colStart * ts, span)
            val bb = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
            for (k in 0 until colCount) {
                bb.position(k * colStride * ts)
                val d = convertElement(bb, v.type)
                out[r * colCount + k] = if (fill != null && d == fill) Float.NaN else d.toFloat()
            }
        }
        return out
    }

    fun fillValue(v: NcVar): Double? {
        for (key in listOf("_FillValue", "missing_value")) {
            val a = v.attributes[key] ?: continue
            val d = a.firstNumber() ?: continue
            return d
        }
        return null
    }

    override fun close() {
        src.close()
    }

    companion object {
        const val ABSENT = 0
        const val NC_DIMENSION = 10
        const val NC_VARIABLE = 11
        const val NC_ATTRIBUTE = 12
        val TYPE_SIZE = intArrayOf(0, 1, 1, 2, 4, 4, 8)
    }
}

/** Typed attribute value(s). Keeps raw bytes; exposes numeric access. */
class NcAttribute(val type: Int, val raw: ByteArray) {

    val string: String
        get() = String(raw, Charsets.UTF_8).trimEnd('\u0000')

    fun firstNumber(): Double? = when (type) {
        1 -> raw.get(0).toDouble()
        2 -> (raw.get(0).toInt() and 0xFF).toDouble()
        3 -> ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN).short.toDouble()
        4 -> ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN).int.toDouble()
        5 -> ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN).float.toDouble()
        6 -> ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN).double
        else -> null
    }
}
