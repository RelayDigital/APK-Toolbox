package io.github.bl3xand.apkcloner.clone

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Renames the package of a compiled resource table (resources.arsc) while it is copied.
 *
 * Resources are looked up by number, so a clone works with the table of the original - until
 * the app asks for one by name with its own package name, as `getIdentifier(name, type,
 * getPackageName())` does. A clone answers with its new package name, the table still carries
 * the old one, the lookup returns nothing and the app crashes. The name sits in a fixed-size
 * field at the start of the package chunk, so it can be swapped in passing: nothing else in the
 * table moves, and the table, however large, is never held in memory.
 */
object ResourceTableRenamer {
    const val ENTRY = "resources.arsc"

    private const val RES_TABLE = 0x0002
    private const val RES_STRING_POOL = 0x0001
    private const val RES_TABLE_PACKAGE = 0x0200
    private const val TABLE_HEADER_SIZE = 12
    private const val CHUNK_HEADER_SIZE = 8
    private const val PACKAGE_ID_SIZE = 4

    /** The name is stored as this many UTF-16 units, zero-terminated. */
    private const val NAME_UNITS = 128
    private const val NAME_BYTES = NAME_UNITS * 2
    private const val BUFFER = 1 shl 16

    /**
     * Copies a resource table from [input] to [output]. The first package is renamed to
     * [newPackage] if it is called [oldPackage]; a table whose package was named differently
     * from the app to begin with is left alone, since the app cannot be relying on that name.
     * Anything that does not look like a resource table is copied as it is.
     */
    fun copy(input: InputStream, output: OutputStream, oldPackage: String, newPackage: String) {
        val data = DataInputStream(input)
        val start = ByteArray(TABLE_HEADER_SIZE + CHUNK_HEADER_SIZE)
        val read = readAsMuchAs(data, start)
        output.write(start, 0, read)
        val header = ByteBuffer.wrap(start).order(ByteOrder.LITTLE_ENDIAN)
        val isTable = read == start.size && header.getShort(0).toInt() == RES_TABLE &&
            header.getShort(2).toInt() == TABLE_HEADER_SIZE &&
            header.getShort(TABLE_HEADER_SIZE).toInt() == RES_STRING_POOL
        if (isTable && newPackage.length < NAME_UNITS) {
            // The package chunk follows the table's string pool, whose size its header gives.
            val poolSize = header.getInt(TABLE_HEADER_SIZE + 4).toLong() and 0xFFFFFFFFL
            if (copyExactly(data, output, poolSize - CHUNK_HEADER_SIZE)) {
                val chunk = ByteArray(CHUNK_HEADER_SIZE + PACKAGE_ID_SIZE + NAME_BYTES)
                val got = readAsMuchAs(data, chunk)
                if (got == chunk.size && isPackageNamed(chunk, oldPackage)) writeName(chunk, newPackage)
                output.write(chunk, 0, got)
            }
        }
        data.copyTo(output, BUFFER)
    }

    private fun isPackageNamed(chunk: ByteArray, name: String): Boolean {
        val buffer = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.getShort(0).toInt() != RES_TABLE_PACKAGE) return false
        val stored = StringBuilder()
        var offset = CHUNK_HEADER_SIZE + PACKAGE_ID_SIZE
        while (offset < chunk.size) {
            val unit = buffer.getChar(offset)
            if (unit == '\u0000') break
            stored.append(unit)
            offset += 2
        }
        return stored.toString() == name
    }

    private fun writeName(chunk: ByteArray, name: String) {
        val start = CHUNK_HEADER_SIZE + PACKAGE_ID_SIZE
        chunk.fill(0, start, start + NAME_BYTES)
        val buffer = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
        name.forEachIndexed { index, unit -> buffer.putChar(start + index * 2, unit) }
    }

    private fun readAsMuchAs(input: DataInputStream, into: ByteArray): Int {
        var total = 0
        while (total < into.size) {
            val count = input.read(into, total, into.size - total)
            if (count < 0) break
            total += count
        }
        return total
    }

    /** False when the stream ended early; what was there has been copied. */
    private fun copyExactly(input: DataInputStream, output: OutputStream, count: Long): Boolean {
        if (count < 0) return false
        val buffer = ByteArray(BUFFER)
        var left = count
        while (left > 0) {
            val read = try {
                input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
            } catch (_: EOFException) {
                -1
            }
            if (read < 0) return false
            output.write(buffer, 0, read)
            left -= read
        }
        return true
    }
}
