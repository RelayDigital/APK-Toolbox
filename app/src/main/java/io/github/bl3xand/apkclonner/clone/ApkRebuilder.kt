package io.github.bl3xand.apkclonner.clone

import java.io.BufferedOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Copies an APK entry by entry, swapping in a patched manifest. */
object ApkRebuilder {

    private const val MANIFEST = "AndroidManifest.xml"
    private const val ALIGNMENT_EXTRA_ID = 0xD935
    private const val LOCAL_HEADER_SIZE = 30
    private const val EOCD_SIGNATURE = 0x06054b50
    private const val SIGNING_BLOCK_MAGIC = "APK Sig Block 42"
    private val SIGNATURE_FILE = Regex("META-INF/([^/]+\\.(SF|RSA|DSA|EC)|MANIFEST\\.MF)", RegexOption.IGNORE_CASE)

    fun rebuild(source: File, target: File, patcher: ManifestPatcher, keepSignature: Boolean) {
        val counter = CountingOutputStream(BufferedOutputStream(target.outputStream(), 1 shl 16))
        ZipFile(source).use { zip ->
            ZipOutputStream(counter).use { out ->
                val seen = HashSet<String>()
                for (entry in zip.entries()) {
                    if (entry.isDirectory || !seen.add(entry.name)) continue
                    if (!keepSignature && SIGNATURE_FILE.matches(entry.name)) continue

                    val copy = ZipEntry(entry.name).apply { if (entry.time != -1L) time = entry.time }
                    if (entry.name == MANIFEST) {
                        out.putNextEntry(copy)
                        out.write(patcher.patch(zip.getInputStream(entry).use { it.readBytes() }))
                    } else if (entry.method == ZipEntry.STORED) {
                        // resources.arsc and uncompressed native libraries are mmap-ed straight
                        // out of the APK, so they must stay stored and aligned.
                        copy.method = ZipEntry.STORED
                        copy.size = entry.size
                        copy.compressedSize = entry.size
                        copy.crc = entry.crc
                        val alignment = if (entry.name.endsWith(".so")) 16384 else 4
                        val nameLength = entry.name.toByteArray(Charsets.UTF_8).size
                        copy.extra = alignmentExtra(counter.count + LOCAL_HEADER_SIZE + nameLength, alignment)
                        out.putNextEntry(copy)
                        zip.getInputStream(entry).use { it.copyTo(out, 1 shl 16) }
                    } else {
                        out.putNextEntry(copy)
                        zip.getInputStream(entry).use { it.copyTo(out, 1 shl 16) }
                    }
                    out.closeEntry()
                }
            }
        }
        if (keepSignature) transplantSigningBlock(source, target)
    }

    /** Same extra field zipalign writes: id, size, alignment, then zero padding. */
    private fun alignmentExtra(dataOffsetWithoutExtra: Long, alignment: Int): ByteArray {
        val padding = ((alignment - (dataOffsetWithoutExtra + 6) % alignment) % alignment).toInt()
        return ByteBuffer.allocate(6 + padding).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(ALIGNMENT_EXTRA_ID.toShort())
            .putShort((2 + padding).toShort())
            .putShort(alignment.toShort())
            .array()
    }

    /**
     * Carries the original v2/v3 signing block over. Its digests no longer match the modified
     * contents, so this only helps where the platform's signature verification is patched out.
     */
    private fun transplantSigningBlock(source: File, target: File) {
        val block = RandomAccessFile(source, "r").use { file ->
            val directory = centralDirectoryOffset(file)
            if (directory < 32) return
            val footer = ByteArray(24)
            file.seek(directory - 24)
            file.readFully(footer)
            if (String(footer, 8, 16, Charsets.US_ASCII) != SIGNING_BLOCK_MAGIC) return
            val size = ByteBuffer.wrap(footer).order(ByteOrder.LITTLE_ENDIAN).getLong(0)
            if (size <= 0 || size + 8 > directory || size > Int.MAX_VALUE - 8) return
            ByteArray((size + 8).toInt()).also {
                file.seek(directory - size - 8)
                file.readFully(it)
            }
        }
        RandomAccessFile(target, "rw").use { file ->
            val directory = centralDirectoryOffset(file)
            val tail = ByteArray((file.length() - directory).toInt())
            file.seek(directory)
            file.readFully(tail)
            val eocd = tail.size - (file.length() - eocdOffset(file)).toInt()
            ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN).putInt(eocd + 16, (directory + block.size).toInt())
            file.seek(directory)
            file.write(block)
            file.write(tail)
        }
    }

    private fun centralDirectoryOffset(file: RandomAccessFile): Long {
        file.seek(eocdOffset(file) + 16)
        return Integer.reverseBytes(file.readInt()).toLong() and 0xFFFFFFFFL
    }

    private fun eocdOffset(file: RandomAccessFile): Long {
        val scan = minOf(file.length(), 65557L).toInt()
        val bytes = ByteArray(scan)
        file.seek(file.length() - scan)
        file.readFully(bytes)
        val view = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in scan - 22 downTo 0) {
            if (view.getInt(i) == EOCD_SIGNATURE) return file.length() - scan + i
        }
        error("Not a zip archive")
    }

    private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        var count = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }
    }
}
