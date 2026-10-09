package io.github.bl3xand.apkcloner.clone

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ResourceTableRenamerTest {
    private val poolBody = ByteArray(40) { (it + 1).toByte() }
    private val tail = ByteArray(300) { (it * 7).toByte() }

    /** A table header, a string pool, then a package chunk named [packageName]. */
    private fun table(packageName: String): ByteArray {
        val buffer = ByteBuffer.allocate(12 + 8 + poolBody.size + 8 + 4 + 256 + tail.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(0x0002).putShort(12).putInt(buffer.capacity()).putInt(1)
        buffer.putShort(0x0001).putShort(28).putInt(8 + poolBody.size).put(poolBody)
        buffer.putShort(0x0200).putShort(288).putInt(8 + 4 + 256 + tail.size).putInt(0x7f)
        val name = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN)
        packageName.forEach { name.putChar(it) }
        buffer.put(name.array()).put(tail)
        return buffer.array()
    }

    private fun rename(source: ByteArray, old: String, new: String): ByteArray =
        ByteArrayOutputStream().also { ResourceTableRenamer.copy(source.inputStream(), it, old, new) }.toByteArray()

    @Test
    fun renamesThePackageAndNothingElse() {
        assertArrayEquals(table("com.example.app.clone"), rename(table("com.example.app"), "com.example.app", "com.example.app.clone"))
    }

    @Test
    fun aShorterNameLeavesNoTraceOfTheLongerOne() {
        assertArrayEquals(table("a.b"), rename(table("com.example.application"), "com.example.application", "a.b"))
    }

    @Test
    fun aTableNamedDifferentlyFromTheAppIsLeftAlone() {
        val source = table("com.vendor.resources")
        assertArrayEquals(source, rename(source, "com.example.app", "com.example.app.clone"))
    }

    @Test
    fun aNameThatDoesNotFitIsNotWritten() {
        val source = table("com.example.app")
        assertArrayEquals(source, rename(source, "com.example.app", "x".repeat(128)))
    }

    @Test
    fun whatIsNotATableIsCopiedUnchanged() {
        for (source in listOf(ByteArray(0), ByteArray(5) { 1 }, ByteArray(500) { it.toByte() }, table("p").copyOf(40))) {
            assertArrayEquals(source, rename(source, "p", "q"))
        }
    }

    @Test
    fun theSizeNeverChanges() {
        val source = table("com.example.app")
        assertEquals(source.size, rename(source, "com.example.app", "com.example.app.clone2").size)
    }
}
