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
import org.jf.dexlib2.immutable.ImmutableMethodParameter
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction10x
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction21c
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction35c
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference
import org.jf.dexlib2.immutable.reference.ImmutableStringReference
import org.jf.dexlib2.writer.pool.DexPool
import java.io.File

/**
 * Unit tests for the dex patchers, on synthetic dex files built with dexlib2 itself: each test
 * assembles a minimal dex whose code says exactly what the test wants to say, runs a patch over
 * it and inspects the result. Fail-safety (garbage in, same bytes out) is covered too.
 */
class DexPatchTest {

    // ---- helpers to build and read synthetic dex files ----

    private fun dex(vararg methods: Triple<String, String, List<ImmutableInstruction>>): ByteArray {
        val classDef = org.jf.dexlib2.immutable.ImmutableClassDef(
            "Ltest/Synth;",
            AccessFlags.PUBLIC.value,
            "Ljava/lang/Object;",
            emptyList<String>(),
            null,
            emptyList<org.jf.dexlib2.iface.Annotation>(),
            emptyList<org.jf.dexlib2.iface.Field>(),
            methods.map { (name, descriptor, instructions) ->
                ImmutableMethod(
                    "Ltest/Synth;", name,
                    listOf<org.jf.dexlib2.iface.MethodParameter>(ImmutableMethodParameter("I", emptySet(), null)),
                    "V",
                    AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                    emptySet(), emptySet(),
                    ImmutableMethodImplementation(instructions.size + 2, instructions + ImmutableInstruction10x(Opcode.RETURN_VOID), emptyList(), emptyList()),
                )
            },
        )
        val output = File.createTempFile("synth", ".dex")
        try {
            DexPool.writeTo(output.absolutePath, ImmutableDexFile(Opcodes.getDefault(), listOf(classDef)))
            return output.readBytes()
        } finally {
            output.delete()
        }
    }

    private fun load(data: ByteArray): DexFile {
        val input = File.createTempFile("synth_in", ".dex")
        try {
            input.writeBytes(data)
            return DexFileFactory.loadDexFile(input, Opcodes.getDefault())
        } finally {
            input.delete()
        }
    }

    /** All (class, name, opcode) of every method-reference instruction in the dex. */
    private fun methodCalls(data: ByteArray): List<Triple<String, String, Opcode>> =
        load(data).classes.flatMap { it.methods.toList() }.flatMap { it.implementation!!.instructions.toList() }
            .filterIsInstance<ReferenceInstruction>()
            .map { it.reference as MethodReference }
            .map { Triple(it.definingClass, it.name, Opcode.INVOKE_STATIC) }

    /** All strings of every const-string instruction in the dex. */
    private fun stringConstants(data: ByteArray): List<String> =
        load(data).classes.flatMap { it.methods.toList() }.flatMap { it.implementation!!.instructions.toList() }
            .filter { it.opcode == Opcode.CONST_STRING }
            .map { (it as ReferenceInstruction).reference as StringReference }
            .map { it.string }

    private fun invokeStatic(clazz: String, name: String, params: List<String> = listOf("I"), ret: String = "V") =
        ImmutableInstruction35c(Opcode.INVOKE_STATIC, params.size.coerceAtMost(5), 0, 0, 0, 0, 0, ImmutableMethodReference(clazz, name, params, ret))

    private fun constString(s: String) = ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(s))

    /** The instructions of every method of every class (or of [only] one class) in the dex. */
    private fun instructionsOf(data: ByteArray, only: String? = null): List<Instruction> =
        load(data).classes.filter { only == null || it.type == only }
            .flatMap { it.methods.toList() }.flatMap { it.implementation!!.instructions.toList() }

    // ---- KeepAlive ----

    @org.junit.Test
    fun keepAliveNopsSystemExit() {
        val before = dex(Triple("a", "(I)V", listOf(invokeStatic("Ljava/lang/System;", "exit"))))
        val after = KeepAlive.patchDex(before)
        val calls = methodCalls(after)
        org.junit.Assert.assertTrue("System.exit must be gone", calls.none { it.first == "Ljava/lang/System;" && it.second == "exit" })
        // the no-op replacement keeps the instruction count: the invoke became a 10x nop
        val nops = load(after).classes.flatMap { it.methods.toList() }.flatMap { it.implementation!!.instructions.toList() }
            .count { it.opcode == Opcode.NOP }
        org.junit.Assert.assertTrue("expected a nop where the invoke was", nops >= 1)
    }

    @org.junit.Test
    fun keepAliveNopsProcessKillAndFinish() {
        val before = dex(
            Triple("a", "(I)V", listOf(invokeStatic("Landroid/os/Process;", "killProcess"))),
            Triple("b", "(I)V", listOf(invokeStatic("Landroid/app/Activity;", "finish", listOf(), "V"))),
        )
        val after = KeepAlive.patchDex(before)
        val calls = methodCalls(after)
        org.junit.Assert.assertTrue(calls.none { it.second == "killProcess" || it.second == "finish" })
    }

    @org.junit.Test
    fun keepAliveLeavesOtherCallsAlone() {
        val before = dex(Triple("a", "(I)V", listOf(invokeStatic("Ljava/lang/Math;", "abs", listOf("I"), "I"))))
        org.junit.Assert.assertArrayEquals("no self-exit calls: bytes must be unchanged", before, KeepAlive.patchDex(before))
    }

    @org.junit.Test
    fun keepAliveFailSafeOnGarbage() {
        val garbage = byteArrayOf(1, 2, 3, 4)
        org.junit.Assert.assertArrayEquals(garbage, KeepAlive.patchDex(garbage))
    }

    // ---- SignatureBypass ----

    @org.junit.Test
    fun bypassNopsTheGuardExitAndKeepsTheDexLoadable() {
        // A guard method: reads the signature, then kills the process on mismatch.
        val before = dex(
            Triple("a", "(I)V", listOf(
                invokeStatic("Landroid/content/pm/PackageManager;", "getPackageSignatures", listOf("Ljava/lang/String;", "I"), "[Landroid/content/pm/Signature;"),
                invokeStatic("Ljava/lang/System;", "exit"),
            )),
        )
        val after = SignatureBypass.patchDex(before)
        val calls = methodCalls(after)
        org.junit.Assert.assertTrue("the guard's System.exit must be gone", calls.none { it.first == "Ljava/lang/System;" && it.second == "exit" })
        org.junit.Assert.assertTrue("the signature read must stay", calls.any { it.second == "getPackageSignatures" })
        // Size-preserving no-ops: one 10x nop per code unit of the replaced invoke (3 for a 35c).
        val nops = instructionsOf(after).count { it.opcode == Opcode.NOP }
        org.junit.Assert.assertEquals("3 nops replace the 3-unit invoke", 3, nops)
        // The patched dex has to stay a dex ART can load and verify, not just bytes.
        load(after).classes.forEach { cls ->
            cls.methods.forEach { m ->
                m.implementation?.let { impl ->
                    impl.instructions.forEach { require(it.codeUnits > 0) }
                    impl.tryBlocks.forEach { require(it.startCodeAddress >= 0 && it.codeUnitCount > 0) }
                }
            }
        }
    }

    @org.junit.Test
    fun bypassLeavesOtherExitsAlone() {
        // System.exit WITHOUT a signature read in the same method must not be touched.
        val before = dex(
            Triple("a", "(I)V", listOf(invokeStatic("Ljava/lang/System;", "exit"))),
            Triple("b", "(I)V", listOf(
                invokeStatic("Landroid/content/pm/Signature;", "getMessageDigest", listOf("Ljava/lang/String;"), "[B"),
                invokeStatic("Ljava/lang/Math;", "abs", listOf("I"), "I"),
            )),
        )
        org.junit.Assert.assertArrayEquals("no guard method: bytes must be unchanged", before, SignatureBypass.patchDex(before))
    }

    @org.junit.Test
    fun bypassFailSafeOnGarbage() {
        val garbage = byteArrayOf(5, 5, 5)
        org.junit.Assert.assertArrayEquals(garbage, SignatureBypass.patchDex(garbage))
    }

    // ---- SignatureSpoof ----

    @org.junit.Test
    fun spoofRetargetsGetPackageInfo() {
        val before = dex(
            Triple("a", "(I)V", listOf(invokeStatic("Landroid/content/pm/PackageManager;", "getPackageInfo", listOf("Ljava/lang/String;", "I"), "Landroid/content/pm/PackageInfo;"))),
        )
        val after = SignatureSpoof.patchDex(before, "classes.dex", byteArrayOf(1, 2, 3), "com.example.app", "com.example.app.clone")
        val calls = load(after).classes.flatMap { it.methods.toList() }.flatMap { it.implementation!!.instructions.toList() }
            .filterIsInstance<ReferenceInstruction>()
            .mapNotNull { (it.reference as? MethodReference) }
        org.junit.Assert.assertTrue(
            "getPackageInfo must be retargeted to the helper",
            calls.any { it.definingClass == "Lio/github/bl3xand/apkcloner/spoof/SignatureSource;" && it.name == "get" },
        )
        org.junit.Assert.assertTrue("original target must be gone",
            load(after).classes.filter { it.type != "Lio/github/bl3xand/apkcloner/spoof/SignatureSource;" }
                .flatMap { it.methods.toList() }.flatMap { it.implementation!!.instructions.toList() }
                .filterIsInstance<ReferenceInstruction>()
                .none { (it.reference as? MethodReference)?.name == "getPackageInfo" })
    }

    @org.junit.Test
    fun spoofInjectsHelperOnlyIntoPrimaryDex() {
        val before = dex(Triple("a", "(I)V", listOf(constString("x"))))
        val primary = SignatureSpoof.patchDex(before, "classes.dex", byteArrayOf(1), "com.example.app", "com.example.app.clone", spoofName = true)
        val secondary = SignatureSpoof.patchDex(before, "classes2.dex", byteArrayOf(1), "com.example.app", "com.example.app.clone")
        org.junit.Assert.assertTrue(load(primary).classes.any { it.type == "Lio/github/bl3xand/apkcloner/spoof/SignatureSource;" })
        org.junit.Assert.assertFalse(load(secondary).classes.any { it.type == "Lio/github/bl3xand/apkcloner/spoof/SignatureSource;" })
    }

    @org.junit.Test
    fun spoofHelperReturnsQueriedPackageOnSuccess() {
        // The helper's try must RETURN the successful getPackageInfo result: without a return in
        // the try, control falls into the NameNotFoundException handler, which re-queries with the
        // CLONE's package - so a query for the original's name (the mimicry case) would come back
        // with the clone's own PackageInfo, the wrong package for every other query too.
        val before = dex(
            Triple("a", "(I)V", listOf(invokeStatic("Landroid/content/pm/PackageManager;", "getPackageInfo", listOf("Ljava/lang/String;", "I"), "Landroid/content/pm/PackageInfo;"))),
        )
        val after = SignatureSpoof.patchDex(before, "classes.dex", byteArrayOf(1, 2, 3), "com.example.app", "com.example.app.clone")
        val gets = load(after).classes.first { it.type == "Lio/github/bl3xand/apkcloner/spoof/SignatureSource;" }
            .methods.filter { it.name == "get" }
        org.junit.Assert.assertEquals("one helper get() per flags type", 2, gets.size)
        for (get in gets) {
            val impl = get.implementation!!
            // (instruction, code-unit address) of the whole method, to reason about the try layout.
            val withAddr = impl.instructions.toList().zip(impl.instructions.toList().runningFold(0) { addr, ins -> addr + ins.codeUnits }.dropLast(1))
            val retBeforeHandler = withAddr.indexOfFirst { it.first.opcode == Opcode.RETURN_OBJECT }
            val handlerStart = withAddr.indexOfFirst { it.first.opcode == Opcode.CONST_STRING }
            org.junit.Assert.assertTrue(
                "the try's success path must return before the handler re-queries",
                retBeforeHandler in 0 until handlerStart,
            )
            // Units: invoke=3 + move-result=1 + return=1, so the handler starts at unit 5 and the
            // try covers 0..5. ImmutableTryBlock is (start, codeUnitCount, handlers).
            org.junit.Assert.assertEquals(1, impl.tryBlocks.count())
            val tryBlock = impl.tryBlocks.first()
            org.junit.Assert.assertEquals(0, tryBlock.startCodeAddress)
            org.junit.Assert.assertEquals(5, tryBlock.codeUnitCount)
            org.junit.Assert.assertEquals(5, tryBlock.exceptionHandlers.first().handlerCodeAddress)
            org.junit.Assert.assertEquals(
                "Landroid/content/pm/PackageManager\$NameNotFoundException;",
                tryBlock.exceptionHandlers.first().exceptionType,
            )
            // The handler's re-query must ask for the CLONE's package (the always-visible self),
            // and the spoofing tail must still write the signatures field before returning.
            val strings = withAddr.filter { it.first.opcode == Opcode.CONST_STRING }
                .map { ((it.first as ReferenceInstruction).reference as StringReference).string }
            org.junit.Assert.assertEquals(listOf("com.example.app.clone"), strings.filter { it.startsWith("com.example.") })
            org.junit.Assert.assertTrue(
                "the tail still substitutes the signature",
                withAddr.any { it.first.opcode == Opcode.IPUT_OBJECT && (it.first as ReferenceInstruction).let { r -> (r.reference as org.jf.dexlib2.iface.reference.FieldReference).name == "signatures" } },
            )
        }
    }

    @org.junit.Test
    fun spoofFailSafeOnGarbage() {
        val garbage = byteArrayOf(9, 9)
        org.junit.Assert.assertArrayEquals(garbage, SignatureSpoof.patchDex(garbage, "classes.dex", byteArrayOf(1), "com.example.app", "com.example.app.clone"))
    }

    @org.junit.Test
    fun spoofRetargetsGetPackageName() {
        val before = dex(
            Triple("a", "(I)V", listOf(
                ImmutableInstruction35c(
                    Opcode.INVOKE_VIRTUAL, 1, 0, 0, 0, 0, 0,
                    ImmutableMethodReference("Landroid/content/Context;", "getPackageName", listOf(), "Ljava/lang/String;"),
                ),
            )),
        )
        val after = SignatureSpoof.patchDex(before, "classes.dex", byteArrayOf(1), "com.example.app", "com.example.app.clone", spoofName = true)
        val calls = load(after).classes.flatMap { it.methods.toList() }.flatMap { it.implementation!!.instructions.toList() }
            .filterIsInstance<ReferenceInstruction>()
            .mapNotNull { (it.reference as? MethodReference) }
        org.junit.Assert.assertTrue(
            "getPackageName must be retargeted to the helper",
            calls.any { it.definingClass == "Lio/github/bl3xand/apkcloner/spoof/SignatureSource;" && it.name == "packageName" },
        )
        val helper = load(after).classes.first { it.type == "Lio/github/bl3xand/apkcloner/spoof/SignatureSource;" }
        val strings = helper.methods.flatMap { it.implementation!!.instructions.toList() }
            .filter { it.opcode == Opcode.CONST_STRING }
            .map { ((it as ReferenceInstruction).reference as StringReference).string }
        org.junit.Assert.assertTrue("helper carries the original package name", strings.contains("com.example.app"))
    }

    // ---- CoreRenameAlias ----

    @org.junit.Test
    fun renameAliasRewritesPackageStringOnly() {
        val before = dex(
            Triple("a", "(I)V", listOf(constString("com.example.app"), constString("com.example.app.extra"))),
        )
        val after = CoreRenameAlias.patchDexScoped(before, "com.example.app", "com.example.app.clone")
        val strings = stringConstants(after)
        org.junit.Assert.assertEquals(listOf("com.example.app.clone", "com.example.app.extra"), strings)
    }

    @org.junit.Test
    fun renameAliasNoOriginalStringMeansUnchanged() {
        val before = dex(Triple("a", "(I)V", listOf(constString("something.else"))))
        org.junit.Assert.assertArrayEquals(before, CoreRenameAlias.patchDexScoped(before, "com.example.app", "com.example.app.clone"))
    }

    @org.junit.Test
    fun renameAliasFailSafeOnGarbage() {
        val garbage = byteArrayOf(7)
        org.junit.Assert.assertArrayEquals(garbage, CoreRenameAlias.patchDexScoped(garbage, "a.b", "a.b.c"))
    }

}
