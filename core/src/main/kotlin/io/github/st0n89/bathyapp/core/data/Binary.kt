package io.github.st0n89.bathyapp.core.data

import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Random-access byte source. Implemented on top of a [FileChannel] so it works
 * both on the JVM (tests, plain files) and on Android (ParcelFileDescriptor of
 * a SAF document URI).
 */
interface SeekSource : AutoCloseable {
    val size: Long

    /** Reads exactly [count] bytes starting at [position]. */
    fun readAt(position: Long, count: Int): ByteArray
}

class FileChannelSource(private val channel: FileChannel) : SeekSource {
    override val size: Long
        get() = channel.size()

    override fun readAt(position: Long, count: Int): ByteArray {
        require(count >= 0) { "negative count" }
        val buffer = ByteBuffer.allocate(count)
        while (buffer.hasRemaining()) {
            val n = channel.read(buffer, position + buffer.position())
            if (n < 0) throw EOFException("unexpected end of file at ${position + buffer.position()}")
        }
        return buffer.array()
    }

    override fun close() {
        channel.close()
    }
}

/** Sequential reader with cursor helpers for binary formats. */
class Cursor(private val src: SeekSource) {
    var position: Long = 0L

    fun seek(position: Long) {
        this.position = position
    }

    fun bytes(count: Int): ByteArray {
        val b = src.readAt(position, count)
        position += count
        return b
    }

    fun u8(): Int = bytes(1)[0].toInt() and 0xFF

    fun i16be(): Int {
        val b = bytes(2)
        return ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
    }

    fun i16le(): Int {
        val b = bytes(2)
        return ((b[1].toInt() and 0xFF) shl 8) or (b[0].toInt() and 0xFF)
    }

    fun u32be(): Long {
        val b = bytes(4)
        var v = 0L
        for (i in 0..3) v = (v shl 8) or (b[i].toLong() and 0xFF)
        return v
    }

    fun u32le(): Long {
        val b = bytes(4)
        var v = 0L
        for (i in 3 downTo 0) v = (v shl 8) or (b[i].toLong() and 0xFF)
        return v
    }

    fun i32be(): Int = u32be().toInt()
    fun i32le(): Int = u32le().toInt()

    fun f32be(): Float = Float.fromBits(i32be())
    fun f32le(): Float = Float.fromBits(i32le())

    fun f64be(): Double = Double.fromBits(readI64be())
    fun f64le(): Double = Double.fromBits(readI64le())

    private fun readI64be(): Long {
        val b = bytes(8)
        var v = 0L
        for (i in 0..7) v = (v shl 8) or (b[i].toLong() and 0xFF)
        return v
    }

    private fun readI64le(): Long {
        val b = bytes(8)
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[i].toLong() and 0xFF)
        return v
    }

    fun string(count: Int): String {
        val b = bytes(count)
        val end = b.indexOf(0.toByte())
        return String(if (end >= 0) b.copyOfRange(0, end) else b, Charsets.UTF_8)
    }
}
