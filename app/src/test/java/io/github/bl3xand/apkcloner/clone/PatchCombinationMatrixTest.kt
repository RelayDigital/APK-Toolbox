package io.github.bl3xand.apkcloner.clone

import org.jf.dexlib2.AccessFlags
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.iface.DexFile
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.Instruction
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.iface.reference.StringReference
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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The patch COMBINATION matrix, on synthetic dex files (the DexPatchTest style): every patch
 * alone, the meaningful pairs and triples, and the full stacks, each applied through the real
 * [DexPatchChain] so the chain's order is part of what is asserted. What each case asserts is
 * the presence or absence of every other patch's byte-level marker - the influence map as a
 * table, one test per row, so a coupling that appears later fails the row it appears in.
 *
 * The markers, shared with DexPatchTest / ClonePatchRoundTripTest:
 * - BYPASS / KEEP_ALIVE: a kill/self-exit invoke replaced by 10x nops (same instruction count
 *   in code units), other invokes untouched;
 * - RENAME_ALIAS: the app's const-string of the original package becomes the clone package;
 * - SPOOF_SIGNATURE: the app's getPackageInfo invoke retargets to the helper's `get`, and the
 *   helper class exists with its PackageManager retry (never itself);
 * - MIMICRY: on top of the spoof, the app's getPackageName invoke retargets to the helper's
 *   `packageName`, whose const-string is the ORIGINAL package.
 */
class PatchCombinationMatrixTest {

    private val original = "com.example.app"
    private val clone = "com.example.app.clone"
    private val helperClass = "Lio/github/bl3xand/apkcloner/spoof/SignatureSource;"
    private val certificate = byteArrayOf(1, 2, 3, 4)

    // ---- one dex that carries every patch's trigger at once ------------------------------

    /**
     * Ltest/Synth; with four methods, one trigger per patch:
     * - `guard`: a signature read then System.exit (BYPASS's target; KEEP_ALIVE's too);
     * - `exit`: System.exit alone with NO signature read (KEEP_ALIVE's alone - BYPASS must not
     *   touch this one, which is the semantic difference between the two);
     * - `alias`: the original package as a const-string (RENAME_ALIAS's target);
     * - `probe`: a getPackageInfo invoke and a Context.getPackageName invoke (SPOOF's and
     *   MIMICRY's targets).
     */
    private fun matrixDex(): ByteArray {
        fun method(name: String, instructions: List<Instruction>) = ImmutableMethod(
            "Ltest/Synth;", name, emptyList(), "V",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            emptySet(), emptySet(),
            ImmutableMethodImplementation(
                instructions.size + 2, instructions + ImmutableInstruction10x(Opcode.RETURN_VOID),
                emptyList(), emptyList(),
            ),
        )

        fun invokeStatic(clazz: String, name: String, params: List<String> = listOf("I"), ret: String = "V") =
            ImmutableInstruction35c(Opcode.INVOKE_STATIC, params.size.coerceAtMost(5), 0, 0, 0, 0, 0, ImmutableMethodReference(clazz, name, params, ret))

        fun invokeVirtual(clazz: String, name: String, params: List<String> = listOf(), ret: String = "V") =
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1 + params.size.coerceAtMost(4), 2, 0, 0, 0, 0, ImmutableMethodReference(clazz, name, params, ret))

        fun constString(s: String) = ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(s))

        val classDef = org.jf.dexlib2.immutable.ImmutableClassDef(
            "Ltest/Synth;", AccessFlags.PUBLIC.value, "Ljava/lang/Object;",
            emptyList<String>(), null, emptyList(), emptyList(),
            listOf(
                method("guard", listOf(
                    invokeStatic("Landroid/content/pm/PackageManager;", "getPackageSignatures", listOf("Ljava/lang/String;", "I"), "[Landroid/content/pm/Signature;"),
                    invokeStatic("Ljava/lang/System;", "exit"),
                )),
                method("exit", listOf(invokeStatic("Ljava/lang/System;", "exit"))),
                method("alias", listOf(constString(original))),
                method("probe", listOf(
                    invokeStatic("Landroid/content/pm/PackageManager;", "getPackageInfo", listOf("Ljava/lang/String;", "I"), "Landroid/content/pm/PackageInfo;"),
                    invokeVirtual("Landroid/content/Context;", "getPackageName", listOf(), "Ljava/lang/String;"),
                )),
            ),
        )
        val output = File.createTempFile("matrix", ".dex")
        return try {
            DexPool.writeTo(output.absolutePath, ImmutableDexFile(Opcodes.getDefault(), listOf(classDef)))
            output.readBytes()
        } finally {
            output.delete()
        }
    }

    /**
     * The chain, handed the certificate exactly as ApkCloner hands it: read only when the set
     * says SPOOF_SIGNATURE or MIMICRY (ApkCloner.kt: `if (request.spoofSignature || request.mimicry)`),
     * null otherwise - the chain itself runs the spoof whenever it HOLDS a certificate, so the
     * gating is the caller's, and the matrix must reproduce it to mean "patch X alone".
     */
    private fun apply(patches: Set<String>, dex: ByteArray = matrixDex()): DexFile {
        val cert = if (PatchIds.SPOOF_SIGNATURE in patches || PatchIds.MIMICRY in patches) certificate else null
        val bytes = DexPatchChain(patches, cert, { original }, clone).apply("classes.dex", dex)
        val input = File.createTempFile("matrix_in", ".dex")
        return try {
            input.writeBytes(bytes)
            DexFileFactory.loadDexFile(input, Opcodes.getDefault())
        } finally {
            input.delete()
        }
    }

    // ---- marker readers -------------------------------------------------------------------

    /** (class, name) of every method-reference instruction in the SYNTH class only. */
    private fun appCalls(dex: DexFile): List<Pair<String, String>> =
        dex.classes.first { it.type == "Ltest/Synth;" }.methods
            .flatMap { it.implementation!!.instructions.toList() }
            .filterIsInstance<ReferenceInstruction>()
            .mapNotNull { it.reference as? MethodReference }
            .map { it.definingClass to it.name }

    /** The (class, name) of every method invoke of one method of the SYNTH class. */
    private fun callsOf(dex: DexFile, method: String): List<Pair<String, String>> =
        dex.classes.first { it.type == "Ltest/Synth;" }.methods.first { it.name == method }
            .implementation!!.instructions.toList()
            .filterIsInstance<ReferenceInstruction>()
            .mapNotNull { it.reference as? MethodReference }
            .map { it.definingClass to it.name }

    /** (method name, opcode) pairs of the SYNTH class - a nopped invoke shows as (name, NOP). */
    private fun appInstructions(dex: DexFile): List<Pair<String, Opcode>> =
        dex.classes.first { it.type == "Ltest/Synth;" }.methods
            .flatMap { m -> m.implementation!!.instructions.toList().map { m.name to it.opcode } }

    private fun appStrings(dex: DexFile): List<String> =
        dex.classes.first { it.type == "Ltest/Synth;" }.methods
            .flatMap { it.implementation!!.instructions.toList() }
            .filter { it.opcode == Opcode.CONST_STRING }
            .map { (it as ReferenceInstruction).reference as StringReference }
            .map { it.string }

    private fun hasHelper(dex: DexFile): Boolean = dex.classes.any { it.type == helperClass }

    /** Every const-string of the helper class: the mimicry payload and the retry name. */
    private fun helperStrings(dex: DexFile): List<String> =
        dex.classes.firstOrNull { it.type == helperClass }?.methods
            ?.flatMap { it.implementation!!.instructions.toList() }
            ?.filter { it.opcode == Opcode.CONST_STRING }
            ?.map { (it as ReferenceInstruction).reference as StringReference }
            ?.map { it.string } ?: emptyList()

    /** The helper's calls to its own `get`: zero unless a re-patch corrupted it. */
    private fun helperSelfCalls(dex: DexFile): Int =
        dex.classes.firstOrNull { it.type == helperClass }?.methods
            ?.filter { it.name == "get" }
            ?.sumOf { m -> m.implementation!!.instructions.count { i ->
                val r = (i as? ReferenceInstruction)?.reference as? MethodReference
                r?.definingClass == helperClass && r.name == "get"
            } } ?: 0

    // ---- the matrix ------------------------------------------------------------------------

    private fun assertGuardNopped(dex: DexFile) {
        val guardCalls = callsOf(dex, "guard")
        assertTrue("the guard keeps its signature read", guardCalls.any { it.second == "getPackageSignatures" })
        assertFalse("the guard's exit is gone", guardCalls.any { it.first == "Ljava/lang/System;" && it.second == "exit" })
    }

    private fun assertGuardIntact(dex: DexFile) {
        val guardCalls = callsOf(dex, "guard")
        assertTrue("the guard is untouched (exit still present)", guardCalls.any { it.first == "Ljava/lang/System;" && it.second == "exit" })
        assertTrue("the guard keeps its signature read", guardCalls.any { it.second == "getPackageSignatures" })
    }

    /** KEEP_ALIVE (and BYPASS, as its subset) nop the exit method too; BYPASS-only must not. */
    private fun assertPlainExitNopped(dex: DexFile) {
        assertFalse("the plain exit (no signature read) is nopped", callsOf(dex, "exit").any { it.first == "Ljava/lang/System;" && it.second == "exit" })
    }

    private fun assertPlainExitIntact(dex: DexFile) {
        assertTrue("the plain exit (no signature read) is untouched", callsOf(dex, "exit").any { it.first == "Ljava/lang/System;" && it.second == "exit" })
    }

    private fun assertAliasRewritten(dex: DexFile) =
        assertEquals("the original-package string is the clone's now", listOf(clone), appStrings(dex))

    private fun assertAliasIntact(dex: DexFile) =
        assertEquals("the original-package string stands", listOf(original), appStrings(dex))

    private fun assertSpoofOn(dex: DexFile) {
        assertTrue("the helper class is injected", hasHelper(dex))
        assertTrue(
            "getPackageInfo is retargeted to the helper's get",
            appCalls(dex).any { it.first == helperClass && it.second == "get" },
        )
        assertEquals("the helper is healthy (no self-calls)", 0, helperSelfCalls(dex))
    }

    private fun assertSpoofOff(dex: DexFile) {
        assertFalse("no helper class", hasHelper(dex))
        assertFalse(
            "getPackageInfo is not retargeted",
            appCalls(dex).any { it.first == helperClass && it.second == "get" },
        )
    }

    private fun assertMimicryOn(dex: DexFile) {
        assertTrue(
            "getPackageName is retargeted to the helper's packageName",
            appCalls(dex).any { it.first == helperClass && it.second == "packageName" },
        )
        assertTrue("the helper carries the ORIGINAL package", original in helperStrings(dex))
    }

    private fun assertMimicryOff(dex: DexFile) {
        assertFalse(
            "getPackageName is not retargeted",
            appCalls(dex).any { it.first == helperClass && it.second == "packageName" },
        )
        // Spoof-only still injects the helper; its `packageName` method simply is not called
        // by app code. What mimicry adds is the CALL retarget, not the helper's existence.
        if (hasHelper(dex)) {
            assertTrue(
                "the injected-but-unused packageName payload still says the ORIGINAL",
                original in helperStrings(dex),
            )
        }
    }

    // The five patches alone.

    @Test
    fun bypassAloneNopsOnlyTheGuard() {
        val dex = apply(setOf(PatchIds.BYPASS))
        assertGuardNopped(dex)
        assertPlainExitIntact(dex)
        assertAliasIntact(dex)
        assertSpoofOff(dex)
    }

    @Test
    fun keepAliveAloneNopsEveryExit() {
        val dex = apply(setOf(PatchIds.KEEP_ALIVE))
        assertGuardNopped(dex)
        assertPlainExitNopped(dex)
        assertAliasIntact(dex)
        assertSpoofOff(dex)
    }

    @Test
    fun renameAliasAloneRewritesOnlyStrings() {
        val dex = apply(setOf(PatchIds.RENAME_ALIAS))
        assertGuardIntact(dex)
        assertPlainExitIntact(dex)
        assertAliasRewritten(dex)
        assertSpoofOff(dex)
    }

    @Test
    fun spoofAloneRetargetsOnlyPackageInfo() {
        val dex = apply(setOf(PatchIds.SPOOF_SIGNATURE))
        assertGuardIntact(dex)
        assertPlainExitIntact(dex)
        assertAliasIntact(dex)
        assertSpoofOn(dex)
        assertMimicryOff(dex)
    }

    @Test
    fun mimicryAloneDoesTheFullSpoof() {
        // MIMICRY implies its companion: the certificate read in ApkCloner treats the set
        // {mimicry} as the full identity stack, and the picker never offers the bare one.
        // The chain is handed the certificate all the same and applies both spoofs.
        val dex = apply(setOf(PatchIds.MIMICRY))
        assertSpoofOn(dex)
        assertMimicryOn(dex)
        assertAliasIntact(dex)
        assertGuardIntact(dex)
    }

    // The pairs that interact.

    @Test
    fun bypassPlusKeepAliveIsKeepAlive() {
        // BYPASS's kill-call set is a subset of KEEP_ALIVE's self-exit set, and the guard
        // precondition adds nothing KEEP_ALIVE does not already cover - the union is KEEP_ALIVE,
        // byte for byte what KEEP_ALIVE alone produces.
        val both = apply(setOf(PatchIds.BYPASS, PatchIds.KEEP_ALIVE))
        val keepAliveOnly = apply(setOf(PatchIds.KEEP_ALIVE))
        assertGuardNopped(both)
        assertPlainExitNopped(both)
        // Same markers in the same shape: the string table and instruction stream of the SYNTH
        // class are identical.
        assertEquals(appInstructions(keepAliveOnly), appInstructions(both))
        assertEquals(appStrings(keepAliveOnly), appStrings(both))
    }

    @Test
    fun renameAliasPlusSpoofComposes() {
        val dex = apply(setOf(PatchIds.RENAME_ALIAS, PatchIds.SPOOF_SIGNATURE))
        assertAliasRewritten(dex)
        assertSpoofOn(dex)
        assertMimicryOff(dex)
        assertGuardIntact(dex)
    }

    @Test
    fun renameAliasPlusMimicryKeepsThePayloadOriginal() {
        // The historical trap: the alias rewriter rewrites every const-string equal to the
        // original package, and the mimicry helper's payload IS that string. With the chain
        // order RENAME before SPOOF the helper is injected after the rewrite, so the payload
        // survives - and the app's own alias string is rewritten.
        val dex = apply(setOf(PatchIds.RENAME_ALIAS, PatchIds.MIMICRY))
        assertAliasRewritten(dex)
        assertSpoofOn(dex)
        assertMimicryOn(dex)
    }

    @Test
    fun spoofPlusMimicryIsMimicry() {
        // Explicitly picking both is the same dex as mimicry alone: the mimicry flag is the
        // only difference between the two sets, and it is on in both.
        val both = apply(setOf(PatchIds.SPOOF_SIGNATURE, PatchIds.MIMICRY))
        val mimicryOnly = apply(setOf(PatchIds.MIMICRY))
        assertSpoofOn(both)
        assertMimicryOn(both)
        assertEquals(appInstructions(mimicryOnly), appInstructions(both))
        assertEquals(helperStrings(mimicryOnly), helperStrings(both))
    }

    // The stacks.

    @Test
    fun keepAliveSpoofMimicryTheNsgStack() {
        val dex = apply(setOf(PatchIds.KEEP_ALIVE, PatchIds.SPOOF_SIGNATURE, PatchIds.MIMICRY))
        assertGuardNopped(dex)
        assertPlainExitNopped(dex)
        assertAliasIntact(dex)
        assertSpoofOn(dex)
        assertMimicryOn(dex)
    }

    @Test
    fun renameAliasSpoofMimicryTheFullIdentityStack() {
        val dex = apply(setOf(PatchIds.RENAME_ALIAS, PatchIds.SPOOF_SIGNATURE, PatchIds.MIMICRY))
        assertGuardIntact(dex)
        assertPlainExitIntact(dex)
        assertAliasRewritten(dex)
        assertSpoofOn(dex)
        assertMimicryOn(dex)
    }

    @Test
    fun bypassRenameAliasSpoofMimicryTheEverythingStack() {
        // BYPASS without KEEP_ALIVE: only the GUARD is nopped (BYPASS's own scope); the plain
        // exit survives - BYPASS is not a synonym of KEEP_ALIVE when picked alone, only a
        // subset of its effects. Everything on at once still produces a loadable dex with every
        // other marker present.
        val dex = apply(setOf(PatchIds.BYPASS, PatchIds.RENAME_ALIAS, PatchIds.SPOOF_SIGNATURE, PatchIds.MIMICRY))
        assertGuardNopped(dex)
        assertPlainExitIntact(dex)
        assertAliasRewritten(dex)
        assertSpoofOn(dex)
        assertMimicryOn(dex)
    }

    // Hidden couplings, proven as documented failures.

    @Test
    fun wrongOrderRenameAfterSpoofInvertsTheMimicryPayload() {
        // The failure mode the chain's order exists to prevent, stated as a test: if the alias
        // rewriter ran AFTER the spoof's helper was injected (the old chain order), it would
        // rewrite the helper's payload string too - the mimicry would answer the CLONE's name,
        // exactly what an unpatched clone reports, while every marker of a "successful" build
        // stayed green. Shown here by running the two patches in the wrong order by hand.
        val dexBytes = DexPatchChain(setOf(PatchIds.MIMICRY), certificate, { original }, clone).apply("classes.dex", matrixDex())
        val wronglyOrdered = CoreRenameAlias.patchDex(dexBytes, original, clone)
        val input = File.createTempFile("wrong", ".dex")
        val dex = try {
            input.writeBytes(wronglyOrdered)
            DexFileFactory.loadDexFile(input, Opcodes.getDefault())
        } finally {
            input.delete()
        }
        assertAliasRewritten(dex)
        assertFalse(
            "the helper's ORIGINAL-package payload was rewritten to the clone's - mimicry inverted",
            original in helperStrings(dex),
        )
        assertTrue("the rewritten payload is the clone's", clone in helperStrings(dex))
    }

    @Test
    fun secondRebuildFromTheSourceNotThePatchedDex() {
        // What a rebuild must NOT do: run the chain over a dex that already carries the helper.
        // The re-injected helper's own retry gets rewritten to call itself - infinite recursion
        // (ClonePatchRoundTripTest shows the same on a real build; this is the chain-level
        // minimal statement of it, at the exact order the chain runs).
        val chain = DexPatchChain(setOf(PatchIds.SPOOF_SIGNATURE, PatchIds.MIMICRY), certificate, { original }, clone)
        val first = chain.apply("classes.dex", matrixDex())
        val second = chain.apply("classes.dex", first)
        val input = File.createTempFile("second", ".dex")
        val dex = try {
            input.writeBytes(second)
            DexFileFactory.loadDexFile(input, Opcodes.getDefault())
        } finally {
            input.delete()
        }
        assertTrue("the re-patched helper calls itself", helperSelfCalls(dex) > 0)
    }

    @Test
    fun withoutACertificateTheSpoofAndMimicryAreNoOps() {
        // A source whose certificate cannot be read (null in the chain): the set can still SAY
        // spoof/mimicry - the metadata will record it - but the dex gets neither marker. The
        // silent gap between the recorded set and the built dex, stated as a row of the matrix.
        val bytes = DexPatchChain(setOf(PatchIds.SPOOF_SIGNATURE, PatchIds.MIMICRY), null, { original }, clone).apply("classes.dex", matrixDex())
        val input = File.createTempFile("nocert", ".dex")
        val dex = try {
            input.writeBytes(bytes)
            DexFileFactory.loadDexFile(input, Opcodes.getDefault())
        } finally {
            input.delete()
        }
        assertSpoofOff(dex)
        assertMimicryOff(dex)
        assertAliasIntact(dex)
        assertGuardIntact(dex)
    }

    @Test
    fun theEmptySetChangesNothing() {
        val before = matrixDex()
        val after = DexPatchChain(emptySet(), null, { original }, clone).apply("classes.dex", before)
        assertTrue("no patches, no certificate: the dex bytes are unchanged", after.contentEquals(before))
    }
}