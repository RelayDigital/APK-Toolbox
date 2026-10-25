package io.github.bl3xand.apkcloner.sources.core

import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.cert.CertificateFactory
import java.util.zip.Inflater

/**
 * Reads the package name and the signer of an APK that is somewhere else, fetching only the
 * parts that say so: the end of the file, its directory, the manifest and the signing block.
 * For an app of hundreds of megabytes that is a megabyte or two.
 *
 * An APK is a zip: the directory at its end lists every file and where it starts, and the
 * signatures sit in a block right before the directory.
 */
object RemoteApk {
    private const val END_OF_DIRECTORY = 0x06054b50
    private const val DIRECTORY_ENTRY = 0x02014b50
    private const val END_RECORD_SIZE = 22
    private const val MAX_COMMENT = 0xFFFF
    private const val ENTRY_HEADER_SIZE = 46
    private const val LOCAL_HEADER_SIZE = 30
    private const val DEFLATED = 8
    private const val ZIP64_MARK = 0xFFFFFFFFL

    private const val MANIFEST = "AndroidManifest.xml"
    private val SIGNATURE_FILE = Regex("""META-INF/[^/]+\.(RSA|DSA|EC)""", RegexOption.IGNORE_CASE)

    /** Larger than any directory, manifest or signing block is; more than this is not fetched. */
    private const val MAX_PART = 16 * 1024 * 1024

    private const val SIGNING_BLOCK_MAGIC = "APK Sig Block 42"
    private const val SIGNING_BLOCK_FOOTER = 24
    private const val SCHEME_V2 = 0x7109871a
    private const val SCHEME_V3 = 0xf05368c0.toInt()
    private const val SCHEME_V31 = 0x1b93ad61

    private class Entry(val name: String, val method: Int, val compressedSize: Long, val size: Long, val headerOffset: Long)

    /** What the APK of [size] bytes behind [reader] says about itself, or null if it cannot be read this way. */
    fun peek(size: Long, reader: RangeReader): ApkPeek? = runCatching {
        val tailLength = minOf(size, (END_RECORD_SIZE + MAX_COMMENT).toLong()).toInt()
        val tail = little(reader.read(size - tailLength, tailLength))
        val end = (tail.capacity() - END_RECORD_SIZE downTo 0).firstOrNull { tail.getInt(it) == END_OF_DIRECTORY } ?: return null
        val directorySize = tail.getInt(end + 12).toLong() and ZIP64_MARK
        val directoryOffset = tail.getInt(end + 16).toLong() and ZIP64_MARK
        if (directoryOffset == ZIP64_MARK || directorySize > MAX_PART) return null

        val entries = entries(little(reader.read(directoryOffset, directorySize.toInt())))
        val manifest = entries.firstOrNull { it.name == MANIFEST } ?: return null
        val block = AndroidManifestBlock.load(ByteArrayInputStream(content(manifest, reader)))
        val packageName = block.packageName?.takeIf { it.isNotBlank() } ?: return null

        // Who signed it is good to know and not worth failing over.
        val signers = runCatching { blockSigners(directoryOffset, reader) }.getOrNull().orEmpty()
            .ifEmpty { runCatching { jarSigners(entries, reader) }.getOrNull().orEmpty() }
        val permissions = runCatching { block.usesPermissions.filterNotNull().toSet() }.getOrDefault(emptySet())
        ApkPeek(packageName, signers.map(CertHashes::format).toSet(), block.versionName, block.versionCode?.toLong(), permissions)
    }.getOrNull()

    /**
     * The same for a file that is here already. The system reads an APK better - but not every
     * one: the base of a set of splits it refuses by itself, as it could not be installed alone.
     */
    fun peek(file: File): ApkPeek? = runCatching {
        RandomAccessFile(file, "r").use { input ->
            peek(input.length()) { offset, length ->
                ByteArray(length).also {
                    input.seek(offset)
                    input.readFully(it)
                }
            }
        }
    }.getOrNull()

    private fun little(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    private fun bytesAt(buffer: ByteBuffer, position: Int, length: Int): ByteArray {
        val view = buffer.duplicate()
        view.position(position)
        return ByteArray(length).also { view.get(it) }
    }

    private fun entries(directory: ByteBuffer): List<Entry> {
        val entries = ArrayList<Entry>()
        var at = 0
        while (at + ENTRY_HEADER_SIZE <= directory.capacity() && directory.getInt(at) == DIRECTORY_ENTRY) {
            val nameLength = directory.getShort(at + 28).toInt() and 0xFFFF
            val extraLength = directory.getShort(at + 30).toInt() and 0xFFFF
            val commentLength = directory.getShort(at + 32).toInt() and 0xFFFF
            val name = bytesAt(directory, at + ENTRY_HEADER_SIZE, nameLength).toString(Charsets.UTF_8)
            entries.add(
                Entry(
                    name, directory.getShort(at + 10).toInt() and 0xFFFF,
                    directory.getInt(at + 20).toLong() and ZIP64_MARK, directory.getInt(at + 24).toLong() and ZIP64_MARK,
                    directory.getInt(at + 42).toLong() and ZIP64_MARK,
                ),
            )
            at += ENTRY_HEADER_SIZE + nameLength + extraLength + commentLength
        }
        return entries
    }

    /** The bytes of one file of the zip, unpacked. */
    private fun content(entry: Entry, reader: RangeReader): ByteArray {
        require(entry.compressedSize <= MAX_PART && entry.size <= MAX_PART)
        // The header in front of the data has a name and extras of its own length.
        val header = little(reader.read(entry.headerOffset, LOCAL_HEADER_SIZE))
        val dataOffset = entry.headerOffset + LOCAL_HEADER_SIZE +
            (header.getShort(26).toInt() and 0xFFFF) + (header.getShort(28).toInt() and 0xFFFF)
        val packed = reader.read(dataOffset, entry.compressedSize.toInt())
        if (entry.method != DEFLATED) return packed
        val inflater = Inflater(true)
        try {
            inflater.setInput(packed)
            val unpacked = ByteArray(entry.size.toInt())
            var done = 0
            while (done < unpacked.size && !inflater.finished()) {
                val read = inflater.inflate(unpacked, done, unpacked.size - done)
                if (read == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                done += read
            }
            return unpacked
        } finally {
            inflater.end()
        }
    }

    /** The certificates of the signers named in the signing block (schemes 2 and 3), newest scheme first. */
    private fun blockSigners(directoryOffset: Long, reader: RangeReader): List<ByteArray> {
        if (directoryOffset < SIGNING_BLOCK_FOOTER) return emptyList()
        val footer = little(reader.read(directoryOffset - SIGNING_BLOCK_FOOTER, SIGNING_BLOCK_FOOTER))
        val magic = bytesAt(footer, 8, SIGNING_BLOCK_MAGIC.length).toString(Charsets.US_ASCII)
        val blockSize = footer.getLong(0)
        if (magic != SIGNING_BLOCK_MAGIC || blockSize !in SIGNING_BLOCK_FOOTER..MAX_PART.toLong()) return emptyList()
        // The block: its size, then pairs of an id and a value, then the size again and the magic.
        val block = little(reader.read(directoryOffset - blockSize, (blockSize - SIGNING_BLOCK_FOOTER).toInt()))
        val schemes = HashMap<Int, ByteBuffer>()
        while (block.remaining() >= 12) {
            val length = block.getLong()
            if (length < 4 || length > block.remaining()) break
            val id = block.getInt()
            schemes[id] = slice(block, (length - 4).toInt())
        }
        val scheme = schemes[SCHEME_V31] ?: schemes[SCHEME_V3] ?: schemes[SCHEME_V2] ?: return emptyList()
        // signers -> signer -> signed data -> (digests, certificates, ...); the first certificate is the signer's.
        val signers = prefixed(scheme)
        val certificates = ArrayList<ByteArray>()
        while (signers.remaining() >= 4) {
            val signedData = prefixed(prefixed(signers))
            prefixed(signedData)
            val chain = prefixed(signedData)
            if (chain.remaining() >= 4) {
                val certificate = prefixed(chain)
                certificates.add(ByteArray(certificate.remaining()).also { certificate.get(it) })
            }
        }
        return certificates
    }

    /** The next stretch of [buffer] that says its own length in front, as a buffer of its own. */
    private fun prefixed(buffer: ByteBuffer): ByteBuffer = slice(buffer, buffer.getInt())

    private fun slice(buffer: ByteBuffer, length: Int): ByteBuffer {
        require(length >= 0 && length <= buffer.remaining())
        val part = buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
        part.limit(length)
        buffer.position(buffer.position() + length)
        return part
    }

    /** The certificates of an APK signed the old way only: in a file under META-INF. */
    private fun jarSigners(entries: List<Entry>, reader: RangeReader): List<ByteArray> {
        val file = entries.firstOrNull { SIGNATURE_FILE.matches(it.name) } ?: return emptyList()
        val certificates = CertificateFactory.getInstance("X.509").generateCertificates(ByteArrayInputStream(content(file, reader)))
        return listOfNotNull(certificates.firstOrNull()?.encoded)
    }
}
