package io.github.bl3xand.apkcloner.log

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RingLogTest {
    private lateinit var folder: File

    @Before
    fun setUp() {
        folder = Files.createTempDirectory("ringlog").toFile()
    }

    @After
    fun tearDown() {
        folder.deleteRecursively()
    }

    private fun ring(bytes: Long = 256, count: Int = 3) = RingLog(folder, segmentBytes = bytes, segmentCount = count)

    @Test
    fun entriesSurviveReopening() {
        ring().append(LogLevel.INFO, 1000L, "first")
        ring().append(LogLevel.WARNING, 2000L, "second")
        val read = ring().read()
        assertEquals(
            listOf(Triple(LogLevel.INFO, 1000L, "first"), Triple(LogLevel.WARNING, 2000L, "second")),
            read.map { Triple(it.level, it.timestamp, it.message) },
        )
    }

    @Test
    fun aTornTailIsCutAndWritingResumes() {
        val first = ring()
        first.append(LogLevel.INFO, 1000L, "whole")
        first.append(LogLevel.INFO, 2000L, "torn")
        // The kill: bytes of a record that was only partly written, with no chance to reopen.
        val segment = folder.listFiles()!!.single()
        FileOutputStream(segment, true).use { it.write(byteArrayOf(7, 0, 0, 0, 1)) }
        // Reading does not mend anything: the whole records are there, the junk tail is skipped.
        fun whole() = ring().read().map { Triple(it.level, it.timestamp, it.message) }
        assertEquals(
            listOf(Triple(LogLevel.INFO, 1000L, "whole"), Triple(LogLevel.INFO, 2000L, "torn")),
            whole(),
        )
        // Opening for writing cuts the torn tail away; writing goes on after the cut, in the
        // same segment, without the junk bytes.
        val again = ring()
        again.append(LogLevel.INFO, 3000L, "after")
        assertEquals(
            listOf(
                Triple(LogLevel.INFO, 1000L, "whole"),
                Triple(LogLevel.INFO, 2000L, "torn"),
                Triple(LogLevel.INFO, 3000L, "after"),
            ),
            whole(),
        )
    }

    @Test
    fun theRingTurnsOverAndKeepsTheLatest() {
        val log = ring()
        // Enough entries that the segments turn over several times.
        for (i in 0 until 100) log.append(LogLevel.INFO, i.toLong(), "entry $i")
        val read = log.read()
        assertTrue("the ring should be near full, not empty", read.size >= 20)
        // Only the newest entries remain; none of the oldest.
        assertTrue("entry 0 still present", read.none { it.message == "entry 0" })
        assertTrue("entry 99 missing", read.any { it.message == "entry 99" })
        // They come back in the order they were written.
        val stamps = read.map { it.timestamp }
        assertEquals(stamps, stamps.sorted())
    }

    @Test
    fun aMessageOverTheCapIsCutNotLost() {
        val log = ring()
        log.append(LogLevel.INFO, 1000L, "x".repeat(100_000))
        val read = log.read()
        assertEquals(1, read.size)
        // Cut to what the segment can hold, not empty and not the whole 100_000.
        val cut = read[0].message.length
        assertTrue("message lost", cut >= 1)
        assertTrue("message not cut: $cut", cut < 100_000)
    }

    @Test
    fun aDirectoryThatVanishesDoesNotThrow() {
        val log = ring()
        folder.deleteRecursively()
        // The segment write fails; the ring moves on and tries again rather than throwing.
        log.append(LogLevel.INFO, 1000L, "into nothing")
        folder.mkdirs()
        log.append(LogLevel.INFO, 2000L, "after recovery")
    }

    @Test
    fun aFileThatIsNotOursIsIgnored() {
        File(folder, "applog.0.bin").writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        File(folder, "applog.2.bin").writeBytes(
            byteArrayOf(0x41, 0x50, 0x4B, 0x31) + byteArrayOf(0, 0, 0, 0, 0, 0, 0, 9) + byteArrayOf(0, 0, 0, 0),
        )
        val log = ring()
        log.append(LogLevel.INFO, 1000L, "after junk")
        // The junk segment is ignored: only the real one is read.
        assertTrue(log.read().any { it.message == "after junk" })
    }

    @Test
    fun aFullSegmentCannotSwallowItsHeader() {
        // A segment so small only the header fits: writing still works and nothing is lost.
        val log = ring(bytes = 20, count = 2)
        log.append(LogLevel.INFO, 1000L, "does not fit")
        log.append(LogLevel.INFO, 2000L, "neither does this")
        val read = log.read()
        assertTrue("entries kept: ${read.size}", read.isNotEmpty())
    }

    @Test
    fun levelsComeBackAsTheyWere() {
        val log = ring()
        for (level in LogLevel.entries) log.append(level, 1000L, level.name)
        val read = log.read()
        assertEquals(LogLevel.entries.map { it.name }, read.map { it.message })
        assertEquals(LogLevel.entries.toList(), read.map { it.level })
    }
}