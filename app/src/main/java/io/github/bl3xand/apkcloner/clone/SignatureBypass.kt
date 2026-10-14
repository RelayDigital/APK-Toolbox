package io.github.bl3xand.apkcloner.clone

import io.github.bl3xand.apkcloner.log.AppLog
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.iface.DexFile
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.FiveRegisterInstruction
import org.jf.dexlib2.iface.instruction.Instruction
import org.jf.dexlib2.iface.instruction.OneRegisterInstruction
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.instruction.RegisterRangeInstruction
import org.jf.dexlib2.iface.instruction.ThreeRegisterInstruction
import org.jf.dexlib2.iface.instruction.TwoRegisterInstruction
import org.jf.dexlib2.iface.instruction.VariableRegisterInstruction
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.immutable.ImmutableMethod
import org.jf.dexlib2.immutable.ImmutableMethodImplementation
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction10x
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction20t
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction22x
import org.jf.dexlib2.rewriter.DexFileRewriter
import org.jf.dexlib2.rewriter.DexRewriter
import org.jf.dexlib2.rewriter.MethodRewriter
import org.jf.dexlib2.rewriter.RewriterModule
import org.jf.dexlib2.rewriter.Rewriters
import org.jf.dexlib2.writer.pool.DexPool
import java.io.File

/**
 * Opt-in "bypass integrity/signature check" patcher.
 *
 * Some apps read their own signature at startup and call a terminal guard - [System.exit],
 * [Activity.finish] and friends - when it does not match the one they expect. A clone is re-signed
 * with APK Toolbox's key, so the check fails and the app dies before it shows. This patch finds the
 * guard methods - the ones that both read a signature and call a terminal guard - and turns each
 * guard call into a no-op, so execution falls through to whatever follows instead of being skipped
 * (return) or aborted (throw), which would cut code that must still run.
 *
 * It is opt-in and fail-safe: if anything goes wrong the original dex bytes are returned unchanged,
 * so a patch that does not apply never breaks the clone.
 */
object SignatureBypass {

    private const val TAG = "SignatureBypass"

    // Terminal guards that kill the process or the activity - the end of a signature check.
    // Neutralised surgically: the invoke is replaced with a no-op so execution falls through to
    // whatever follows, instead of being skipped (return-void) or aborted (throw), which would
    // cut code that must still run. Matched by class + name, descriptor ignored.
    private val KILL_CALLS: Set<Pair<String, String>> = setOf(
        "java/lang/System" to "exit",
        "android/app/Activity" to "finish",
        "android/app/Activity" to "finishAffinity",
        "android/app/Activity" to "finishAndRemoveTask",
    )

    // Methods that read the app's own signature - the tell-tale companion to a terminal guard.
    // Matched by defining class + method name, descriptor ignored (one reading per name is enough).
    private val SIGNATURE_READS: Set<String> = setOf(
        "android/content/pm/PackageInfo" to "getSignatures",
        "android/content/pm/PackageInfo" to "getPackageSignatures",
        "android/content/pm/PackageManager" to "getPackageSignatures",
        "android/content/pm/Signature" to "getMessageDigest",
    ).map { "${it.first}|${it.second}" }.toSet()

    /** Patches [data] (the bytes of one dex file). Returns the original bytes on any failure. */
    fun patchDex(data: ByteArray): ByteArray = try {
        val input = File.createTempFile("dex_in", ".dex")
        val output = File.createTempFile("dex_out", ".dex")
        try {
            input.writeBytes(data)
            val dexFile = DexFileFactory.loadDexFile(input, Opcodes.getDefault())
            val rewritten = DexFileRewriter(SignatureBypassRewriters()).rewrite(dexFile)
            DexPool.writeTo(output.absolutePath, rewritten)
            output.readBytes().also {
                if (it.contentEquals(data)) AppLog.debug("$TAG: no guard found in $input, unchanged")
                else AppLog.debug("$TAG: patched $input -> ${it.size} bytes")
            }
        } finally {
            input.delete()
            output.delete()
        }
    } catch (e: Throwable) {
        AppLog.warn("$TAG: patch failed, dex left as-is: ${e.message ?: e}")
        data
    }

    /** A [Rewriters] that rewrites only methods; everything else uses the default rewriter module. */
    private class SignatureBypassRewriters : DexRewriter(RewriterModule()) {
        override fun getMethodRewriter(): MethodRewriter = BypassMethodRewriter(this)
    }

    /** Rewrites the guard methods, leaves every other method untouched. */
    private class BypassMethodRewriter(rewriters: Rewriters) : MethodRewriter(rewriters) {
        override fun rewrite(method: Method): Method {
            val impl = method.implementation ?: return method
            // Guard methods are void. Require BOTH a signature read and a terminal guard so we only
            // touch real sig-check guards and never an unrelated System.exit elsewhere.
            if (method.returnType != "V") return method
            if (!impl.instructions.any { readsSignature(it) }) return method
            if (!impl.instructions.any { isKillCall(it) }) return method

            val instructions = impl.instructions.toList()
            val patched = ArrayList<Instruction>(instructions.size)
            var changed = false
            for (instruction in instructions) {
                if (isKillCall(instruction)) {
                    patched.add(nop(instruction))
                    changed = true
                } else {
                    patched.add(instruction)
                }
            }
            if (!changed) return method

            val newImpl = ImmutableMethodImplementation(
                impl.registerCount,
                patched,
                impl.tryBlocks,
                impl.debugItems,
            )
            return ImmutableMethod(
                method.definingClass,
                method.name,
                method.parameters,
                method.returnType,
                method.accessFlags,
                method.annotations,
                method.hiddenApiRestrictions,
                newImpl,
            )
        }
    }

    /** An invoke whose target is one of [KILL_CALLS] (class + name, descriptor ignored). */
    private fun isKillCall(instruction: Instruction): Boolean {
        val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return false
        return (ref.definingClass to ref.name) in KILL_CALLS
    }

    /** An invoke or field access that reads the app's own signature. */
    private fun readsSignature(instruction: Instruction): Boolean {
        val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return false
        return "${ref.definingClass}|${ref.name}" in SIGNATURE_READS
    }

    /**
     * Replaces a terminal-guard invoke with a no-op that keeps the method valid and lets execution
     * fall through to the next instruction. Uses `const/4 vA, 0x0` on the first operand register
     * (or v0 when the guard takes no operands) - a valid instruction that consumes the register and
     * continues, instead of return-void which would skip code that must still run.
     */
    private fun nop(instruction: Instruction): Instruction {
        val reg = when (instruction) {
            is RegisterRangeInstruction -> instruction.startRegister
            is OneRegisterInstruction -> instruction.registerA
            else -> 0
        }
        return ImmutableInstruction20t(Opcode.CONST_4, reg shl 12)
    }
}
