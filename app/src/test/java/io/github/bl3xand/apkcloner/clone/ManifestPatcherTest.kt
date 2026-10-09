package io.github.bl3xand.apkcloner.clone

import com.reandroid.arsc.chunk.xml.ResXmlDocument
import com.reandroid.arsc.chunk.xml.ResXmlElement
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestPatcherTest {
    private val nameAttribute = 0x01010003

    /** A small compiled manifest: a package, some permissions and an application. */
    private fun manifest(vararg permissions: String): ByteArray {
        val document = ResXmlDocument()
        val root = document.newElement("manifest")
        root.getOrCreateAttribute(null, null, "package", 0).setValueAsString("com.example.app")
        for (permission in permissions) {
            root.newElement("uses-permission").getOrCreateAndroidAttribute("name", nameAttribute).setValueAsString(permission)
        }
        root.newElement("application").getOrCreateAndroidAttribute("name", nameAttribute).setValueAsString(".App")
        document.refreshFull()
        return document.bytes
    }

    private fun parse(bytes: ByteArray): ResXmlElement =
        ResXmlDocument().also { it.readBytes(ByteArrayInputStream(bytes)) }.documentElement

    private fun permissionsOf(root: ResXmlElement): List<String> =
        root.getElements("uses-permission").asSequence().map { it.searchAttributeByResourceId(nameAttribute).valueAsString }.toList()

    @Test
    fun renamesThePackage() {
        val patcher = ManifestPatcher("com.example.app.clone", null)
        val root = parse(patcher.patch(manifest("android.permission.INTERNET")))
        assertEquals("com.example.app", patcher.oldPackage)
        assertEquals("com.example.app.clone", root.searchAttributeByName("package").valueAsString)
        assertEquals(listOf("android.permission.INTERNET"), permissionsOf(root))
    }

    @Test
    fun leavesOutThePermissionsAskedFor() {
        val source = manifest("android.permission.INTERNET", "android.permission.CAMERA", "android.permission.RECORD_AUDIO")
        val patcher = ManifestPatcher("com.example.app.clone", null, setOf("android.permission.INTERNET", "android.permission.RECORD_AUDIO"))
        val root = parse(patcher.patch(source))
        assertEquals(listOf("android.permission.CAMERA"), permissionsOf(root))
        // Everything after the removed elements is still there and still well formed.
        assertTrue(root.getElements("application").hasNext())
    }

    @Test
    fun removingNothingKeepsEveryPermission() {
        val source = manifest("android.permission.INTERNET", "android.permission.CAMERA")
        val root = parse(ManifestPatcher("com.example.app.clone", null, setOf("android.permission.NFC")).patch(source))
        assertEquals(listOf("android.permission.INTERNET", "android.permission.CAMERA"), permissionsOf(root))
        assertFalse(permissionsOf(root).contains("android.permission.NFC"))
    }
}
