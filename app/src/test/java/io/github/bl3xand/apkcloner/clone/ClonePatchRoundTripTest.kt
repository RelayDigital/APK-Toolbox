package io.github.bl3xand.apkcloner.clone

import android.content.Context
import com.android.apksig.ApkVerifier
import com.reandroid.arsc.chunk.xml.ResXmlDocument
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyFactory
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.iface.DexFile
import org.jf.dexlib2.iface.instruction.Instruction
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.iface.reference.StringReference
import org.jf.dexlib2.immutable.ImmutableClassDef
import org.jf.dexlib2.immutable.ImmutableDexFile
import org.jf.dexlib2.immutable.ImmutableMethod
import org.jf.dexlib2.immutable.ImmutableMethodImplementation
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction10x
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction21c
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction35c
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference
import org.jf.dexlib2.immutable.reference.ImmutableStringReference
import org.jf.dexlib2.writer.pool.DexPool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The patch workflow of a clone, at the level it really runs at: an APK is built by
 * [ApkCloner.clone] itself - manifest swap, dex patch chain, metadata entry, signing - and
 * then built AGAIN the way a patch change builds it, from the same source with another patch
 * set. The result is read back as the app reads it ([ApkCloner.readMetadata]) and inspected
 * in the dex, so the contract the tracked-clone patch change leans on is what is asserted:
 *
 * - the metadata of a build lists the patch set it was built with (the clone's page counts it
 *   from there, and the untracked background update rebuilds from it);
 * - a change of the set rebuilds the SAME app with the NEW set: a marker of an added patch is
 *   present in the new dex and absent in the old one, a patch kept on stays on;
 * - the dex a patch change must NOT be built from is the already patched one: re-applying the
 *   spoof patch to it re-injects a helper whose own call was rewritten into calling itself -
 *   the silent dead clone the local rebuild would ship. Shown on record here as the reason
 *   the patch change rebuilds from the source instead.
 *
 * The source APK is made on the desktop: the manifest with ResXmlDocument (the same library
 * the app reads manifests with), a synthetic classes.dex (the helper shape DexPatchTest uses),
 * zipped and signed with the app's bundled debug key, read from the built APK's assets - a
 * fixed, public test key by design (SigningKeys: "the same for everybody, and public"), so
 * readCertificate() has a real v2 block to read, exactly as against a release a source offers.
 * The build itself names that key in the CloneRequest, so the run needs no Android storage.
 */
class ClonePatchRoundTripTest {

    private val originalPackage = "com.example.roundtrip"
    private val clonePackage = "com.example.roundtrip.clone"

    // ---- the source app under clone ---------------------------------------------------------

    /** The manifest a real app has: a package, a permission and an application element. */
    private fun manifest(): ByteArray {
        val document = ResXmlDocument()
        val root = document.newElement("manifest")
        root.getOrCreateAttribute(null, null, "package", 0).setValueAsString(originalPackage)
        root.newElement("uses-permission").getOrCreateAndroidAttribute("name", 0x01010003).setValueAsString("android.permission.INTERNET")
        root.newElement("application").getOrCreateAndroidAttribute("name", 0x01010003).setValueAsString(".App")
        document.refreshFull()
        return document.bytes
    }

    /**
     * A synthetic classes.dex: one class whose method holds the three things the patches look
     * for - a const-string with the original package (RENAME_ALIAS rewrites it to the clone's),
     * a PackageManager.getPackageInfo call (SPOOF retargets it to its helper) and a
     * Context.getPackageName call (MIMICRY retargets that one too).
     */
    private fun sourceDex(): ByteArray {
        val instructions = listOf<org.jf.dexlib2.iface.instruction.Instruction>(
            ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(originalPackage)),
            ImmutableInstruction35c(
                Opcode.INVOKE_STATIC, 2, 0, 1, 0, 0, 0,
                ImmutableMethodReference(
                    "Landroid/content/pm/PackageManager;", "getPackageInfo",
                    listOf("Ljava/lang/String;", "I"), "Landroid/content/pm/PackageInfo;",
                ),
            ),
            ImmutableInstruction35c(
                Opcode.INVOKE_VIRTUAL, 1, 2, 0, 0, 0, 0,
                ImmutableMethodReference("Landroid/content/Context;", "getPackageName", emptyList(), "Ljava/lang/String;"),
            ),
        )
        val method = ImmutableMethod(
            "Ltest/Synth;", "a",
            emptyList(), "V",
            org.jf.dexlib2.AccessFlags.PUBLIC.value or org.jf.dexlib2.AccessFlags.STATIC.value,
            emptySet(), emptySet(),
            ImmutableMethodImplementation(
                instructions.size + 1, instructions + ImmutableInstruction10x(Opcode.RETURN_VOID),
                emptyList(), emptyList(),
            ),
        )
        val classDef = ImmutableClassDef(
            "Ltest/Synth;", org.jf.dexlib2.AccessFlags.PUBLIC.value, "Ljava/lang/Object;",
            emptyList(), null, emptyList(), emptyList(), listOf(method),
        )
        val output = File.createTempFile("synth", ".dex")
        try {
            DexPool.writeTo(output.absolutePath, ImmutableDexFile(Opcodes.getDefault(), listOf(classDef)))
            return output.readBytes()
        } finally {
            output.delete()
        }
    }

    /** A signed source APK: the manifest stored (as real APKs have it), one dex, our key. */
    private fun sourceApk(): File {
        val unsigned = File.createTempFile("source", ".apk")
        ZipOutputStream(unsigned.outputStream()).use { zip ->
            val manifestBytes = manifest()
            zip.putNextEntry(
                ZipEntry("AndroidManifest.xml").apply {
                    method = ZipEntry.STORED
                    size = manifestBytes.size.toLong()
                    crc = CRC32().apply { manifestBytes.forEach { update(it.toInt()) } }.value
                },
            )
            zip.write(manifestBytes)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(sourceDex())
            zip.closeEntry()
        }
        val signed = File.createTempFile("signed", ".apk")
        ApkCloner(clonerContext()).sign(unsigned, signed, key)
        unsigned.delete()
        return signed
    }

    // ---- the tools the build itself needs ----------------------------------------------------

    /**
     * The app's bundled debug key, read from the APK the build produced (it is an asset): a
     * fixed public RSA pair, so the test can sign with what the app signs with, and the source
     * APK carries the v2 block readCertificate looks for.
     */
    private val key: SigningKey by lazy {
        val keyDir = System.getProperty("patchTest.keyDir")?.let(::File) ?: File("src/test/resources/signing")
        val privateKey = KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(File(keyDir, "debug.pk8").readBytes()))
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(File(keyDir, "debug.x509.pem").readBytes())) as java.security.cert.X509Certificate
        SigningKey(privateKey, certificate)
    }

    /**
     * A Context that answers nothing: nothing of it is touched while the CloneRequest names its
     * key and asks for no badge (the keys' lazy read never happens, the icon badger is not
     * built). The mockable android.jar leaves Context's methods abstract, so no source-level
     * empty subclass can exist; the class file in the test resources (.bin, so Gradle packs
     * it as a resource instead of compiling it) is that subclass - 198 bytes of a public
     * constructor calling Context's own, built once by hand. Defined into this test's own
     * package, which needs no open modules.
     */
    /**
     * A Context that answers nothing: nothing of it is touched while the CloneRequest names its
     * key and asks for no badge (the keys' lazy read never happens, the icon badger is not
     * built). The mockable android.jar leaves Context's methods abstract, so no source-level
     * empty subclass can exist; the class file in the test resources (.bin, so Gradle packs
     * it as a resource instead of compiling it) is that subclass - 198 bytes of a public
     * constructor calling Context's own, built once by hand. Defined once into this test's
     * own package (a second define would be a LinkageError) and shared by every test.
     */
    private companion object {
        val noOpContext: android.content.Context by lazy {
            val bytes = ClonePatchRoundTripTest::class.java.classLoader!!.getResourceAsStream(
                "classes/io/github/bl3xand/apkcloner/clone/GeneratedNoOpContext.bin",
            )!!.use { it.readBytes() }
            val lookup = java.lang.invoke.MethodHandles.lookup()
            val define = lookup.javaClass.getMethod("defineClass", ByteArray::class.java).apply { isAccessible = true }
            (define.invoke(lookup, bytes) as Class<*>).getDeclaredConstructor().newInstance() as android.content.Context
        }
    }

    private fun clonerContext(): Context = noOpContext

    private fun build(apks: List<File>, patches: Set<String>, dirName: String): File {
        val outputDir = File.createTempFile(dirName, "").let { it.delete(); it.also(File::mkdirs) }
        val built = ApkCloner(clonerContext()).clone(
            CloneRequest(apks, clonePackage, null, key, null, emptySet(), patches),
            outputDir,
        ) { _, _, _ -> }.first()
        return built
    }

    // ---- what is read back out of a built clone ----------------------------------------------

    private fun dexOf(apk: File): DexFile =
        ZipFile(apk).use { zip ->
            val bytes = zip.getInputStream(zip.getEntry("classes.dex")).use { it.readBytes() }
            DexBackedDexFile.fromInputStream(Opcodes.getDefault(), ByteArrayInputStream(bytes))
        }

    /** The const-strings of the app class: what RENAME_ALIAS rewrites. */
    private fun appStrings(apk: File): List<String> =
        dexOf(apk).classes.first { it.type == "Ltest/Synth;" }.methods.flatMap { it.implementation!!.instructions }
            .filterIsInstance<ReferenceInstruction>()
            .filter { it.opcode == Opcode.CONST_STRING }
            .map { (it.reference as StringReference).string }

    /** Whether the spoof helper is in the dex (the SPOOF marker). */
    private fun hasSpoofHelper(apk: File): Boolean =
        dexOf(apk).classes.any { it.type == "Lio/github/bl3xand/apkcloner/spoof/SignatureSource;" }

    /**
     * The spoof helper's calls to itself: zero in a healthy helper (its retry goes to
     * PackageManager). A helper re-injected into an already-patched dex had its own call
     * rewritten too, so it calls get on itself - the infinite recursion of a re-patched dex.
     */
    private fun helperSelfCalls(dex: DexFile): Int =
        dex.classes.firstOrNull { it.type == "Lio/github/bl3xand/apkcloner/spoof/SignatureSource;" }
            ?.methods?.filter { it.name == "get" }?.sumOf { method ->
                method.implementation!!.instructions.count { instruction ->
                    instruction is ReferenceInstruction && (instruction.reference as? MethodReference)
                        ?.let { it.definingClass == "Lio/github/bl3xand/apkcloner/spoof/SignatureSource;" && it.name == "get" } == true
                }
            } ?: 0

    private fun helperSelfCalls(apk: File): Int = helperSelfCalls(dexOf(apk))

    /** Every const-string the spoof helper carries: the identity payload and the retry name. */
    private fun helperStrings(dex: DexFile): List<String> =
        dex.classes.first { it.type == "Lio/github/bl3xand/apkcloner/spoof/SignatureSource;" }
            .methods.flatMap { it.implementation!!.instructions.toList() }
            .filter { it.opcode == Opcode.CONST_STRING }
            .map { (it as ReferenceInstruction).let { r -> (r.reference as StringReference).string } }

    /**
     * Whether the app's own getPackageName call answers through the spoof helper - the marker
     * of the name spoof that mimicry adds on top of the certificate substitution.
     */
    private fun getPackageNameRetargeted(dex: DexFile): Boolean =
        dex.classes.first { it.type == "Ltest/Synth;" }.methods.flatMap { it.implementation!!.instructions.toList() }
            .filterIsInstance<ReferenceInstruction>()
            .mapNotNull { (it.reference as? MethodReference) }
            .any { it.definingClass == "Lio/github/bl3xand/apkcloner/spoof/SignatureSource;" && it.name == "packageName" }

    // ---- the workflow -------------------------------------------------------------------------

    @Test
    fun patchChangeRebuildsFromTheSourceWithTheNewSet() {
        val source = sourceApk()
        assertTrue("the source must verify as signed", ApkVerifier.Builder(source).build().verify().isVerified)
        assertNotNull("the source's certificate must be readable", ApkCloner.readCertificate(source))

        // 1. The first build, as the clone was made: keepAlive only.
        val first = build(listOf(source), setOf(PatchIds.KEEP_ALIVE), "build1")
        val firstMetadata = ApkCloner.readMetadata(first.path)
        assertNotNull("a clone made here records its metadata", firstMetadata)
        assertEquals("metadata lists the patches the clone was built with", setOf(PatchIds.KEEP_ALIVE), firstMetadata!!.patches)
        assertEquals(originalPackage, firstMetadata.originalPackage)
        assertTrue("a build of the named key verifies", ApkVerifier.Builder(first).build().verify().isVerified)

        // The old set is visible in the dex: no alias rewrite (the original package string
        // stands), no spoof helper.
        assertEquals("keepAlive alone rewrites no strings", listOf(originalPackage), appStrings(first))
        assertFalse("keepAlive alone injects no helper", hasSpoofHelper(first))

        // 2. The patch change, as the workflow now runs it: the same source again, with the
        // new set - keepAlive kept, renameAlias added, spoofSignature on top (the one whose
        // helper must not be re-injected into a patched dex).
        val newSet = setOf(PatchIds.KEEP_ALIVE, PatchIds.RENAME_ALIAS, PatchIds.SPOOF_SIGNATURE)
        val second = build(listOf(source), newSet, "build2")
        assertEquals("the rebuilt clone's metadata says the new set", newSet, ApkCloner.readMetadata(second.path)!!.patches)

        // The dex carries exactly the new set:
        // - renameAlias: the app's original-package string is now the clone's;
        assertEquals("the alias patch rewrote the app's package string", listOf(clonePackage), appStrings(second))
        // - spoofSignature: the helper is in, and healthy - its retry calls PackageManager.
        assertTrue("the spoof helper is injected", hasSpoofHelper(second))
        assertEquals("the helper re-queries PackageManager, not itself", 0, helperSelfCalls(second))

        // 3. The set is what it says, not a superset: a build with keepAlive dropped says so.
        val third = build(listOf(source), setOf(PatchIds.RENAME_ALIAS), "build3")
        assertEquals(setOf(PatchIds.RENAME_ALIAS), ApkCloner.readMetadata(third.path)!!.patches)
        assertEquals(listOf(clonePackage), appStrings(third))
        assertFalse("no spoof without the patch", hasSpoofHelper(third))
    }

    @Test
    fun aCertificateThatCannotBeReadIsSaidInTheMetadata() {
        // The same source, but unsigned: readCertificate finds no signer in it, so the identity
        // patches cannot run - and the build says so in the metadata instead of letting the
        // page count a dead patch as on. The picked set is still stored as picked.
        val unsignedSource = File.createTempFile("unsigned-src", ".apk")
        ZipOutputStream(unsignedSource.outputStream()).use { zip ->
            val manifestBytes = manifest()
            zip.putNextEntry(
                ZipEntry("AndroidManifest.xml").apply {
                    method = ZipEntry.STORED
                    size = manifestBytes.size.toLong()
                    crc = CRC32().apply { manifestBytes.forEach { update(it.toInt()) } }.value
                },
            )
            zip.write(manifestBytes)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(sourceDex())
            zip.closeEntry()
        }
        assertTrue("the unsigned source yields no certificate", ApkCloner.readCertificate(unsignedSource) == null)

        val picked = setOf(PatchIds.KEEP_ALIVE, PatchIds.MIMICRY)
        val built = build(listOf(unsignedSource), picked, "nocert")
        val metadata = ApkCloner.readMetadata(built.path)!!
        assertEquals("the picked set is stored as picked", picked, metadata.patches)
        assertEquals("what could be applied is recorded", setOf(PatchIds.KEEP_ALIVE), metadata.patchesApplied)
        assertEquals("the dead patch is named", setOf(PatchIds.MIMICRY), metadata.patchesNotApplied)
        assertFalse("no helper without a certificate", hasSpoofHelper(built))

        // A build that applies everything it was asked for writes no such note: the metadata
        // of an ordinary build reads as it always did.
        val clean = build(listOf(sourceApk()), setOf(PatchIds.KEEP_ALIVE), "clean")
        val cleanMetadata = ApkCloner.readMetadata(clean.path)!!
        assertNull("nothing said when all patches applied", cleanMetadata.patchesApplied)
        assertEquals(emptySet<String>(), cleanMetadata.patchesNotApplied)
    }

    @Test
    fun mimicryAloneDoesTheFullJob() {
        val source = sourceApk()

        // MIMICRY alone: the identity spoof includes the signature substitution - a clone
        // built with only it gets the certificate helper AND the name spoof, the full stack
        // of spoof+mimicry reachable with one checkbox. The stored set stays what was picked.
        val mimicryOnly = build(listOf(source), setOf(PatchIds.MIMICRY), "mimicry")
        assertEquals("the stored set is what was picked", setOf(PatchIds.MIMICRY), ApkCloner.readMetadata(mimicryOnly.path)!!.patches)
        val mimicryDex = dexOf(mimicryOnly)
        assertTrue("the certificate helper is injected", hasSpoofHelper(mimicryOnly))
        // The identity marker: the helper's packageName method answers the ORIGINAL's name.
        val helperStrings = helperStrings(mimicryDex)
        assertTrue("the helper carries the ORIGINAL's package name", originalPackage in helperStrings)
        assertTrue("the helper's retry carries the clone's package name", clonePackage in helperStrings)
        assertTrue("mimicry retargets the app's getPackageName to the helper", getPackageNameRetargeted(mimicryDex))

        // SPOOF alone stays the lighter option: the certificate helper is in, but no name
        // spoof - the app's getPackageName call is left as the Context's own.
        val spoofOnly = build(listOf(source), setOf(PatchIds.SPOOF_SIGNATURE), "spoof")
        assertEquals(setOf(PatchIds.SPOOF_SIGNATURE), ApkCloner.readMetadata(spoofOnly.path)!!.patches)
        val spoofDex = dexOf(spoofOnly)
        assertTrue("the certificate helper is injected", hasSpoofHelper(spoofOnly))
        assertFalse("no name spoof without mimicry", getPackageNameRetargeted(spoofDex))
    }

    @Test
    fun repatchingAPatchedDexIsTheBrokenThingTheRefusalAvoids() {
        val source = sourceApk()

        // The clone as built with the spoof on, and its patched dex.
        val first = build(listOf(source), setOf(PatchIds.SPOOF_SIGNATURE), "buildp")
        assertTrue(hasSpoofHelper(first))
        assertEquals(0, helperSelfCalls(first))

        // What the old local-rebuild path did: run the spoof patch again over the dex that
        // already carries it. SignatureSpoof's own catch swallows the interned-class error and
        // the rewrite instead turns the helper's retry into a call to itself - the silent dead
        // clone the refusal in rebuildInstalledClone steers the patch change away from.
        val patchedDex = ZipFile(first).use { zip ->
            zip.getInputStream(zip.getEntry("classes.dex")).use { it.readBytes() }
        }
        val rePatched = SignatureSpoof.patchDex(
            patchedDex, "classes.dex",
            ApkCloner.readCertificate(source)!!, originalPackage, clonePackage, false,
        )
        val reDex = DexBackedDexFile.fromInputStream(Opcodes.getDefault(), ByteArrayInputStream(rePatched))
        assertTrue(
            "the re-injected helper calls itself - the infinite recursion of a re-patched dex",
            helperSelfCalls(reDex) > 0,
        )
    }
}