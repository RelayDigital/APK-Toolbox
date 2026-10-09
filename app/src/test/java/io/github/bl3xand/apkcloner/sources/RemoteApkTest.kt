package io.github.bl3xand.apkcloner.sources

import io.github.bl3xand.apkcloner.sources.core.RangeReader
import io.github.bl3xand.apkcloner.sources.core.RemoteApk
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteApkTest {
    private fun zip(vararg files: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            for ((name, content) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
    }.toByteArray()

    /** Counts what is asked for, to see that the whole file never is. */
    private class Counting(private val bytes: ByteArray) : RangeReader {
        var read = 0L
        override fun read(offset: Long, length: Int): ByteArray {
            read += length
            return bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
        }
    }

    @Test
    fun aZipWithoutAManifestIsNotAnApk() {
        val bytes = zip("classes.dex" to ByteArray(4096), "res/a.png" to ByteArray(1000))
        assertNull(RemoteApk.peek(bytes.size.toLong(), Counting(bytes)))
    }

    @Test
    fun whatIsNotAZipIsRefusedQuietly() {
        val bytes = ByteArray(5000) { it.toByte() }
        assertNull(RemoteApk.peek(bytes.size.toLong(), Counting(bytes)))
    }

    @Test
    fun aBrokenManifestIsRefusedWithoutReadingTheRest() {
        // Random, so that it does not pack down to nothing.
        val bulk = ByteArray(300_000).also { java.util.Random(1).nextBytes(it) }
        val bytes = zip("AndroidManifest.xml" to "not a manifest".toByteArray(), "lib/big.so" to bulk)
        val reader = Counting(bytes)
        assertNull(RemoteApk.peek(bytes.size.toLong(), reader))
        // The end of the file, the directory and the manifest: nowhere near all of it.
        assertTrue(reader.read < bytes.size)
    }
}
