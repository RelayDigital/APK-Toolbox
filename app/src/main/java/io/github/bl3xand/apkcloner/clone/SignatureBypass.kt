package io.github.bl3xand.apkcloner.clone

import io.github.bl3xand.apkcloner.log.AppLog
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.iface.DexFile
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.MethodImplementation
import org.jf.dexlib2.iface.instruction.Instruction
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.immutable.ImmutableMethod
import org.jf.dexlib2.immutable.ImmutableMethodImplementation
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction10x
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
 * Some apps read their own signature at startup and call [System.exit] when it does not match the
 * one they expect. A clone is re-signed with APK Toolbox's key, so the check fails and the app dies
 * before it shows. This patch finds the guard methods - the ones that both read a signature and call
 * System.exit - and turns their System.exit into a plain return, so the check-fail path falls
 * through instead of killing the process.
 *
 * It is opt-in and fail-safe: if anything goes wrong the original dex bytes are returned unchanged,
 * so a patch that does not apply never breaks the clone.
 */
object SignatureBypass {

    private const val TAG = "SignatureBypass"

    // java/lang/System -> exit(I)V, the call that terminates the clone.
    private val SYSTEM_EXIT = "java/lang/System" to "exit"

    // Methods that read the app's own signature - the tell-tale companion to a System.exit guard.
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
            val callsExit = impl.instructions.any { isSystemExit(it) }
            if (!callsExit || method.returnType != "V") return method
            val readsSignature = impl.instructions.any { readsSignature(it) }
            if (!readsSignature) return method

            val instructions = impl.instructions.toList()
            val patched = ArrayList<Instruction>(instructions.size)
            var changed = false
            for (instruction in instructions) {
                if (isSystemExit(instruction)) {
                    patched.add(ImmutableInstruction10x(Opcode.RETURN_VOID))
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

    /** An invoke whose target is java/lang/System.exit(I)V. */
    private fun isSystemExit(instruction: Instruction): Boolean {
        val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return false
        if (ref.definingClass != SYSTEM_EXIT.first || ref.name != SYSTEM_EXIT.second) return false
        val descriptor = "(" + ref.parameterTypes.joinToString(",") { it.toString() } + ")" + ref.returnType
        return descriptor == "(I)V"
    }

    /** An invoke or field access that reads the app's own signature. */
    private fun readsSignature(instruction: Instruction): Boolean {
        val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return false
        return "${ref.definingClass}|${ref.name}" in SIGNATURE_READS
    }
}
