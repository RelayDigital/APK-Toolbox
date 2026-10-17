package io.github.bl3xand.apkcloner.clone

import io.github.bl3xand.apkcloner.log.AppLog
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.iface.DexFile
import org.jf.dexlib2.iface.Method
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
 * KEEP_ALIVE - universal self-exit neutralizer.
 *
 * Some apps kill their own process when a startup condition fails - no account signed in, a
 * forced-update check, a plugin watchdog - with System.exit, Runtime.exit or Process.killProcess.
 * A clone hits those conditions far more often than the original (it starts fresh, without the
 * account the original was logged in as), and dies instead of showing its login screen.
 *
 * This patch replaces every invoke of those calls with size-preserving no-ops, so execution falls through
 * to whatever follows (usually a plain return). Unlike the signature-check bypass it has no
 * precondition on what else the method does: the exit helpers are routinely tiny obfuscated
 * wrappers with no markers at all, so any heuristic would miss them. What is lost is spelled out
 * in the picker: an app that legitimately exits at logout keeps its process until the user
 * swipes it away, and the system still kills a crashing app (uncaughtException does not depend
 * on these calls).
 *
 * UNIVERSAL: works for any app. Runs over every classes*.dex. Fail-safe: any failure returns the
 * original bytes.
 */
object KeepAlive {

    private const val TAG = "KeepAlive"

    // The ways an app can end itself or throw the user out: killing the process, or finishing
    // every activity. Matched by class + name, descriptor ignored. A method reference's defining
    // class is the descriptor form (Ljava/lang/System;), so the set stores that form.
    private val SELF_EXIT_CALLS: Set<Pair<String, String>> = setOf(
        "Ljava/lang/System;" to "exit",
        "Ljava/lang/Runtime;" to "exit",
        "Landroid/os/Process;" to "killProcess",
        "Landroid/app/Activity;" to "finish",
        "Landroid/app/Activity;" to "finishAffinity",
        "Landroid/app/Activity;" to "finishAndRemoveTask",
    )

    /** Patches [data] (the bytes of one dex file). Returns the original bytes on any failure. */
    fun patchDex(data: ByteArray): ByteArray = try {
        val input = File.createTempFile("dex_in", ".dex")
        val output = File.createTempFile("dex_out", ".dex")
        try {
            input.writeBytes(data)
            val dexFile = DexFileFactory.loadDexFile(input, Opcodes.getDefault())
            val rewritten = DexFileRewriter(KeepAliveRewriters()).rewrite(dexFile)
            DexPool.writeTo(output.absolutePath, rewritten)
            output.readBytes().also {
                if (it.contentEquals(data)) AppLog.debug("$TAG: no self-exit calls, unchanged")
                else AppLog.debug("$TAG: patched ${output.name} -> ${it.size} bytes")
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
    private class KeepAliveRewriters : DexRewriter(RewriterModule()) {
        override fun getMethodRewriter(): MethodRewriter = ExitNopRewriter(this)
    }

    /** Replaces every self-exit invoke with a no-op; everything else in the method is kept. */
    private class ExitNopRewriter(rewriters: Rewriters) : MethodRewriter(rewriters) {
        override fun rewrite(method: Method): Method {
            val impl = method.implementation ?: return method
            val instructions = impl.instructions.toList()
            if (instructions.none { isSelfExit(it) }) return method

            val patched = instructions.flatMap { if (isSelfExit(it)) nops(it) else listOf(it) }
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

    /** An invoke whose target is one of the self-exit calls. */
    private fun isSelfExit(instruction: Instruction): Boolean {
        val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return false
        return (ref.definingClass to ref.name) in SELF_EXIT_CALLS
    }

    /**
     * No-ops replacing [instruction], sized to keep every later offset valid: an invoke is 3-5
     * code units, a single nop is 1, so a lone nop shifts try-block starts and branch targets
     * into the middle of instructions and the dex fails verification ("Invalid try_item
     * start_addr"). One nop per code unit keeps the method byte-for-byte the same length.
     */
    private fun nops(instruction: Instruction): List<org.jf.dexlib2.immutable.instruction.ImmutableInstruction> =
        List(instruction.codeUnits) { ImmutableInstruction10x(Opcode.NOP) }
}