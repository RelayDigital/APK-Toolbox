package io.github.bl3xand.apkcloner.sources.data

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The loadApps swap, said without Android: what a snapshot read from the files may and may not
 * put back over the memory. The repository itself needs a Context to stand up, so the swap's
 * decision is re-run here against a real apps directory - the same file layout loadApps walks
 * and the same rule for what survives a mutation it saw while reading.
 *
 * Two halves of one guarantee, and both were needed:
 * - removeApps bumps the mutation counter (a load that read the files before the removal
 *   must know something changed), and
 * - the overlay half of the swap (the path a mutation makes loadApps take) drops ids that
 *   have no file on disk any more. The counter alone would send the load down the overlay
 *   path - where the stale snapshot still puts the removed app back, a ghost row pointing
 *   at no file.
 */
class SourcesRepositorySwapTest {
    private class Apps(apps: Map<String, String>) {
        val dir: File = Files.createTempDirectory("apps").toFile()
        val entries = LinkedHashMap<String, String>()

        /** A stand-in for the repository's counter: writes and removals both move it. */
        var storageMutations = 0

        init {
            for ((id, name) in apps) save(id, name)
        }

        /** saveApps' locked half: the file, the counter and the memory entry all together. */
        fun save(id: String, name: String) {
            synchronized(this) {
                File(dir, "$id.json").writeText(name)
                storageMutations++
                entries[id] = name
            }
        }

        /** removeApps' locked half, counter included (the fix for the missing increment). */
        fun removeApps(ids: List<String>) {
            synchronized(this) {
                for (id in ids) {
                    File(dir, "$id.json").delete()
                    entries.remove(id)
                    storageMutations++
                }
            }
        }

        /**
         * loadApps' swap, as the repository runs it: the snapshot (the files as they were when
         * the read started, before any interleaved removal) is laid over the memory when a
         * mutation was seen, and what has no file on disk any more goes - both halves of the
         * fix.
         */
        fun swap(snapshot: Map<String, String>) {
            synchronized(this) {
                for ((id, name) in snapshot) entries[id] = name
                val onDisk = dir.listFiles { file -> file.name.lowercase().endsWith(".json") }
                    ?.mapTo(HashSet()) { it.name.removeSuffix(".json") } ?: emptySet()
                entries.keys.retainAll { it in onDisk }
            }
        }

        /** The files as loadApps would read them now, ids to contents. */
        fun readFiles(): Map<String, String> =
            dir.listFiles { file -> file.name.lowercase().endsWith(".json") }
                ?.associate { it.name.removeSuffix(".json") to it.readText() } ?: emptyMap()
    }

    @Test
    fun aRemovalBetweenTheReadAndTheSwapDoesNotResurrectTheApp() {
        val case = Apps(mapOf("one" to "One", "two" to "Two"))
        // The read: a snapshot of both files, taken before the removal lands.
        val snapshot = case.readFiles()
        assertEquals(setOf("one", "two"), snapshot.keys)
        // The removal completes while the load is between its read and its swap.
        val mutationsBefore = case.storageMutations
        case.removeApps(listOf("two"))
        assertTrue("removeApps must bump the mutation counter", case.storageMutations > mutationsBefore)
        assertFalse(case.entries.containsKey("two"))
        // The swap still happens, with the stale snapshot in hand.
        case.swap(snapshot)
        assertTrue("the removed app must not come back from the snapshot", !case.entries.containsKey("two"))
        assertEquals(setOf("one"), case.entries.keys)
        assertTrue(File(case.dir, "one.json").exists())
        assertFalse(File(case.dir, "two.json").exists())
    }

    @Test
    fun anAppSavedBetweenTheReadAndTheSwapKeepsItsRow() {
        val case = Apps(mapOf("one" to "One"))
        val snapshot = case.readFiles()
        // A save of a new app lands between the read and the swap: its writer's row must
        // survive the swap, not be dropped as a file the snapshot never saw.
        case.save("two", "Two")
        case.swap(snapshot)
        assertEquals(setOf("one", "two"), case.entries.keys)
        assertEquals("Two", case.entries["two"])
    }

    @Test
    fun anAppRemovedBeforeTheReadIsNotBroughtBackByTheSnapshotThatPredatesIt() {
        val case = Apps(mapOf("one" to "One", "two" to "Two"))
        // A load that read both files, then sat on the snapshot while a whole save of a
        // re-added app and its removal went by: what is on disk decides.
        val snapshot = case.readFiles()
        case.save("three", "Three")
        case.removeApps(listOf("two", "three"))
        case.swap(snapshot)
        assertEquals(setOf("one"), case.entries.keys)
    }
}