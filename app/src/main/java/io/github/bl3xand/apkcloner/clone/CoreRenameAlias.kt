package io.github.bl3xand.apkcloner.clone

import io.github.bl3xand.apkcloner.log.AppLog
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.iface.DexFile
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.Instruction
import org.jf.dexlib2.iface.instruction.OneRegisterInstruction
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.StringReference
import org.jf.dexlib2.immutable.ImmutableMethod
import org.jf.dexlib2.immutable.ImmutableMethodImplementation
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction21c
import org.jf.dexlib2.immutable.reference.ImmutableStringReference
import org.jf.dexlib2.rewriter.DexFileRewriter
import org.jf.dexlib2.rewriter.DexRewriter
import org.jf.dexlib2.rewriter.MethodRewriter
import org.jf.dexlib2.rewriter.RewriterModule
import org.jf.dexlib2.rewriter.Rewriters
import org.jf.dexlib2.writer.pool.DexPool
import java.io.File

/**
 * CORE_RENAME_ALIAS — universal rename-alias dex patch.
 *
 * When an app is cloned, the package is renamed A -> B ONLY in the AndroidManifest
 * (package attr + component names). The dex code keeps the ORIGINAL package name A in its
 * STRING CONSTANTS that are passed as package-name arguments to the framework
 * (createPackageContext(A), new ComponentName(A,...), bindService(Intent(A...)),
 * PackageManager.getPackageInfo(A), ...). Those stale A-strings make the runtime look up the
 * OLD package and break the core/plugin package-name binding (CoreApi$CoreNotReadyException:
 * apiProxy null).
 *
 * This patch rewrites every `const-string` (21c) whose string equals the ORIGINAL package name A
 * to the clone package name B. TYPE DESCRIPTORS use the slash form (com/xiaomi/smarthome/...) and
 * are a different string entry, so they are left untouched and internal class identities stay
 * valid. Fail-safe: if A is not present as an exact string, the dex is returned unchanged.
 *
 * UNIVERSAL: works for any renamed app, not just Xiaomi. Runs over every classes*.dex.
 */
object CoreRenameAlias {

    private const val TAG = "CoreRenameAlias"

    /** Set by [patchDexScoped] to avoid per-call allocation. */
    private var originalPackage: String = ""
    private var clonePackage: String = ""

    /** Patches [data] (the bytes of one dex file). Returns the original bytes on any failure. */
    fun patchDex(data: ByteArray): ByteArray = try {
        val input = File.createTempFile("dex_in", ".dex")
        val output = File.createTempFile("dex_out", ".dex")
        try {
            input.writeBytes(data)
            val dexFile = DexFileFactory.loadDexFile(input, Opcodes.getDefault())
            val rewritten = DexFileRewriter(CoreRenameAliasRewriters()).rewrite(dexFile)
            DexPool.writeTo(output.absolutePath, rewritten)
            output.readBytes().also {
                if (it.contentEquals(data)) AppLog.debug("$TAG: no original package string, unchanged")
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

    /** Convenience wrapper that sets the package names for the current thread before patching. */
    fun patchDexScoped(data: ByteArray, originalPackage: String, clonePackage: String): ByteArray {
        this.originalPackage = originalPackage
        this.clonePackage = clonePackage
        return patchDex(data)
    }

    /** A [Rewriters] that rewrites only methods; everything else uses the default rewriter module. */
    private class CoreRenameAliasRewriters : DexRewriter(RewriterModule()) {
        override fun getMethodRewriter(): MethodRewriter = AliasMethodRewriter(this)
    }

    /** Rewrites the original-package const-string instructions to the clone package name. */
    private class AliasMethodRewriter(rewriters: Rewriters) : MethodRewriter(rewriters) {
        override fun rewrite(method: Method): Method {
            val impl = method.implementation ?: return method
            val instructions = impl.instructions.toList()
            if (instructions.none { isOriginalPackageString(it) }) return method

            val patched = instructions.map { if (isOriginalPackageString(it)) alias(it) else it }
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

    /** A `const-string` (21c) whose string is exactly the original package name. */
    private fun isOriginalPackageString(instruction: Instruction): Boolean {
        if (instruction.opcode != Opcode.CONST_STRING) return false
        val ref = (instruction as? ReferenceInstruction)?.reference as? StringReference ?: return false
        return ref.string == originalPackage
    }

    /** Rebuilds the 21c const-string with a new string reference, keeping the same register. */
    private fun alias(instruction: Instruction): Instruction {
        val reg = (instruction as? OneRegisterInstruction)?.registerA ?: 0
        return ImmutableInstruction21c(Opcode.CONST_STRING, reg, ImmutableStringReference(clonePackage))
    }
}
