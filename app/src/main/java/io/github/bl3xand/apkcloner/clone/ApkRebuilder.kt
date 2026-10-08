package io.github.bl3xand.apkcloner.clone

import java.io.BufferedOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Copies an APK entry by entry, swapping in a patched manifest. The result is unsigned. */
object ApkRebuilder {

    private const val MANIFEST = "AndroidManifest.xml"
    private const val ALIGNMENT_EXTRA_ID = 0xD935
    private const val LOCAL_HEADER_SIZE = 30
    private val SIGNATURE_FILE = Regex("META-INF/([^/]+\\.(SF|RSA|DSA|EC)|MANIFEST\\.MF)", RegexOption.IGNORE_CASE)

    /** [extraEntry] is evaluated after the manifest was patched, so it may depend on the patcher. */
    fun rebuild(source: File, target: File, patcher: ManifestPatcher, extraEntry: (() -> Pair<String, ByteArray>)?) {
        val counter = CountingOutputStream(BufferedOutputStream(target.outputStream(), 1 shl 16))
        ZipFile(source).use { zip ->
            ZipOutputStream(counter).use { out ->
                val seen = HashSet<String>()
                var manifestFound = false
                for (entry in zip.entries()) {
                    if (entry.isDirectory || !seen.add(entry.name)) continue
                    if (SIGNATURE_FILE.matches(entry.name) || entry.name.startsWith("META-INF/apkclon")) continue

                    val copy = ZipEntry(entry.name).apply { if (entry.time != -1L) time = entry.time }
                    if (entry.name == MANIFEST) {
                        manifestFound = true
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
                require(manifestFound) { "No AndroidManifest.xml in ${source.name}" }
                extraEntry?.invoke()?.let { (name, data) ->
                    out.putNextEntry(ZipEntry(name))
                    out.write(data)
                    out.closeEntry()
                }
            }
        }
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
