package io.github.bl3xand.apkcloner.clone.dex

import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reproduces the Xiaomi 11.6.501 failure on the desktop. The app's base APK has 13 dex
 * files and its classes.dex sits at 65532 of the 65536 method references the dex format
 * allows; injecting the spoof helper's methods there overflowed the method table, the dex
 * write failed ("writing code_item for method LongSparseArray;->clone()" - the method
 * being written when the overflow hit), and the fail-safe left classes.dex unpatched -
 * without the helper, while the other twelve dexes had their calls retargeted to it. The
 * clone then dies in DumpLogProvider.onCreate with NoClassDefFoundError: SignatureSource,
 * the crash seen on two phones.
 *
 * The fix: the build reads every dex's method count up front and homes the helper in a
 * dex with room - the primary one when it fits, the emptiest one otherwise. These tests
 * pin that contract against the real APK. They run only when it is named:
 * -Pxiaomi.apk=/path/to/original-base.apk
 */
class XiaomiDexWriteFailureTest {

    private val apk = System.getProperty("xiaomi.apk")?.let(::File)?.takeIf { it.exists() }

    private fun dexEntry(name: String): ByteArray =
        ZipFile(apk!!).use { it.getInputStream(it.getEntry(name)).readBytes() }

    /** The dex header's method_ids_size, little-endian at offset 88. */
    private fun methodCount(bytes: ByteArray): Int =
        (((bytes[91].toLong() and 0xff) shl 24) or ((bytes[90].toLong() and 0xff) shl 16) or
            ((bytes[89].toLong() and 0xff) shl 8) or (bytes[88].toLong() and 0xff)).toInt()

    private fun dexNames(): List<String> = ZipFile(apk!!).use { zip ->
        zip.entries().toList().filter { it.name.matches(Regex("classes\\d*\\.dex")) }.map { it.name }
    }

    @Test
    fun theApkIsTheExpectedOne() {
        if (apk == null) return
        assertEquals("classes.dex should be at the method limit", 65_532, methodCount(dexEntry("classes.dex")))
        assertTrue("the app should be multi-dex", dexNames().size > 10)
    }

    @Test
    fun helperDexPicksADexWithRoom() {
        if (apk == null) return
        val names = dexNames()
        val counts = names.map { methodCount(dexEntry(it)) }
        val home = SignatureSpoof.helperDex(names, counts)
        assertTrue("a home must be found - this APK has twelve dexes with room", home != null)
        assertFalse("the home must not be the full primary dex", home == "classes.dex")
        val homeCount = counts[names.indexOf(home)]
        assertTrue("the home dex must have room for the helper", homeCount + 10 <= 65_536)
    }

    @Test
    fun theFullPrimaryDexComesOutPatchedWithoutTheHelper() {
        if (apk == null) return
        // The real build's treatment of the full primary dex: retargets applied, helper
        // NOT homed here (it went to a dex with room). It must come out patched - a
        // fail-safe no-op here would strip the retargets while other dexes keep them.
        val patched = SignatureSpoof.patchDex(
            dexEntry("classes.dex"), "classes.dex", byteArrayOf(1, 2, 3),
            "com.xiaomi.smarthome", "com.xiaomi.smarthome.clone",
            helperDex = "classes5.dex",
        )
        assertFalse("the primary dex must come out patched", patched.contentEquals(dexEntry("classes.dex")))
        val f = File.createTempFile("chk", ".dex")
        try {
            f.writeBytes(patched)
            assertFalse(
                "the helper is homed elsewhere; the primary must not carry it (that is what overflowed)",
                org.jf.dexlib2.DexFileFactory.loadDexFile(f, org.jf.dexlib2.Opcodes.getDefault())
                    .classes.any { it.type == "Lio/github/bl3xand/apkcloner/spoof/SignatureSource;" },
            )
        } finally {
            f.delete()
        }
    }

    @Test
    fun theHelperDexComesOutPatchedWithTheHelper() {
        if (apk == null) return
        val names = dexNames()
        val counts = names.map { methodCount(dexEntry(it)) }
        val home = SignatureSpoof.helperDex(names, counts) ?: return
        val patched = SignatureSpoof.patchDex(
            dexEntry(home), home, byteArrayOf(1, 2, 3),
            "com.xiaomi.smarthome", "com.xiaomi.smarthome.clone",
            helperDex = home,
        )
        assertFalse("the home dex must come out patched", patched.contentEquals(dexEntry(home)))
        val f = File.createTempFile("chk", ".dex")
        try {
            f.writeBytes(patched)
            assertTrue(
                "the home dex must define the helper",
                org.jf.dexlib2.DexFileFactory.loadDexFile(f, org.jf.dexlib2.Opcodes.getDefault())
                    .classes.any { it.type == "Lio/github/bl3xand/apkcloner/spoof/SignatureSource;" },
            )
        } finally {
            f.delete()
        }
    }
}