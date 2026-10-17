package io.github.bl3xand.apkcloner.clone

import io.github.bl3xand.apkcloner.log.AppLog
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.iface.DexFile
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.Instruction
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.instruction.formats.Instruction35c
import org.jf.dexlib2.iface.instruction.formats.Instruction3rc
import org.jf.dexlib2.iface.reference.FieldReference
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.immutable.ImmutableDexFile
import org.jf.dexlib2.immutable.ImmutableMethod
import org.jf.dexlib2.immutable.ImmutableMethodImplementation
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction11n
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction11x
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction12x
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction21c
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction22c
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction23x
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction35c
import org.jf.dexlib2.immutable.reference.ImmutableFieldReference
import org.jf.dexlib2.immutable.ImmutableMethodParameter
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference
import org.jf.dexlib2.immutable.reference.ImmutableStringReference
import org.jf.dexlib2.immutable.reference.ImmutableTypeReference
import org.jf.dexlib2.rewriter.DexFileRewriter
import org.jf.dexlib2.rewriter.DexRewriter
import org.jf.dexlib2.rewriter.MethodRewriter
import org.jf.dexlib2.rewriter.RewriterModule
import org.jf.dexlib2.rewriter.Rewriters
import org.jf.dexlib2.writer.pool.DexPool
import java.io.File

/**
 * SIGNATURE SPOOF - substitute the signature the app sees in itself.
 *
 * Every local signature or integrity check - Java, obfuscated SDK, or a native library reading the
 * certificate over JNI - gets it from PackageManager.getPackageInfo(name, GET_SIGNATURES) and then
 * reads PackageInfo.signatures. A clone is re-signed, so those checks see the cloner's key, compare
 * it against the hash the app carries, and kill the clone.
 *
 * This patch makes the check pass instead of fighting its consequences: every call of
 * PackageManager.getPackageInfo is retargeted, 1:1 with no code shift, to an injected helper that
 * performs the real call and swaps the result's signatures for the ORIGINAL app's certificate
 * (read from the source APK while cloning). The check then hashes the original's certificate and
 * matches, whatever the app calls itself or how many layers of checks it runs.
 *
 * The replacement is deliberately size-preserving - the invoke keeps its format and registers,
 * only its target becomes the helper - so branch targets, try blocks and debug info stay valid
 * without any recomputation; replacing an instruction with a different-sized one corrupts the
 * method for ART's verifier (misplaced try blocks and branch targets).
 *
 * What it does not cover, honestly: checks that hash the APK file itself or a dex CRC (the clone
 * really is a different file - exit suppression is the fallback for those) and server-side
 * attestation (Play Integrity).
 *
 * UNIVERSAL: works for any app. Fail-safe: any failure returns the original bytes.
 */
object SignatureSpoof {

    private const val TAG = "SignatureSpoof"

    // Where the helper class lives. Cross-dex references resolve at runtime, so injecting it into
    // one dex covers calls rewritten in all of them.
    private const val HELPER_CLASS = "Lio/github/bl3xand/apkcloner/spoof/SignatureSource;"
    private const val PACKAGE_MANAGER = "Landroid/content/pm/PackageManager;"
    private const val PACKAGE_INFO = "Landroid/content/pm/PackageInfo;"
    private const val SIGNATURE = "Landroid/content/pm/Signature;"
    private const val SIGNATURE_ARRAY = "[$SIGNATURE"
    private const val FLAGS = "Landroid/content/pm/PackageManager\$PackageInfoFlags;"

    /** The dex entry the helper class is injected into. */
    private const val PRIMARY_DEX = "classes.dex"

    /**
     * Patches [data] (the bytes of one dex file) so getPackageInfo results carry [certificateDer]
     * (the original's signing certificate, DER). Returns the original bytes on any failure.
     */
    fun patchDex(data: ByteArray, dexName: String, certificateDer: ByteArray): ByteArray = try {
        val input = File.createTempFile("dex_in", ".dex")
        val output = File.createTempFile("dex_out", ".dex")
        try {
            input.writeBytes(data)
            val dexFile = DexFileFactory.loadDexFile(input, Opcodes.getDefault())
            val rewritten = DexFileRewriter(SpoofRewriters()).rewrite(dexFile)
            val classes = if (dexName == PRIMARY_DEX) rewritten.classes.plus(helperClass(certificateDer)) else rewritten.classes
            DexPool.writeTo(output.absolutePath, ImmutableDexFile(Opcodes.getDefault(), classes))
            output.readBytes().also {
                if (it.contentEquals(data)) AppLog.debug("$TAG: no getPackageInfo calls in $dexName, unchanged")
                else AppLog.debug("$TAG: patched $dexName -> ${it.size} bytes")
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
    private class SpoofRewriters : DexRewriter(RewriterModule()) {
        override fun getMethodRewriter(): MethodRewriter = SpoofMethodRewriter(this)
    }

    /** Retargets every PackageManager.getPackageInfo call to the helper. */
    private class SpoofMethodRewriter(rewriters: Rewriters) : MethodRewriter(rewriters) {
        override fun rewrite(method: Method): Method {
            val impl = method.implementation ?: return method
            val instructions = impl.instructions.toList()
            if (instructions.none { isPackageInfoCall(it) }) return method

            val patched = instructions.map { if (isPackageInfoCall(it)) retarget(it) else it }
            return ImmutableMethod(
                method.definingClass, method.name, method.parameters, method.returnType,
                method.accessFlags, method.annotations, method.hiddenApiRestrictions,
                ImmutableMethodImplementation(impl.registerCount, patched, impl.tryBlocks, impl.debugItems),
            )
        }
    }

    /** An invoke of PackageManager.getPackageInfo(String, int) or (String, PackageInfoFlags). */
    private fun isPackageInfoCall(instruction: Instruction): Boolean {
        if (instruction !is Instruction35c && instruction !is Instruction3rc) return false
        val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return false
        if (ref.definingClass != PACKAGE_MANAGER || ref.name != "getPackageInfo") return false
        val params = ref.parameterTypes
        return params.size == 2 && params[0] == "Ljava/lang/String;" && (params[1] == "I" || params[1] == FLAGS)
    }

    /** Same format, same registers, same size - only the target becomes the helper. */
    private fun retarget(instruction: Instruction): Instruction {
        val original = instruction as ReferenceInstruction
        val flagsType = (original.reference as MethodReference).parameterTypes[1]
        val i = instruction as Instruction35c
        return ImmutableInstruction35c(
            Opcode.INVOKE_STATIC, i.registerCount,
            i.registerC, i.registerD, i.registerE, i.registerF, i.registerG,
            ImmutableMethodReference(
                HELPER_CLASS, "get",
                listOf(PACKAGE_MANAGER, "Ljava/lang/String;", flagsType),
                PACKAGE_INFO,
            ),
        )
    }

    /**
     * The helper: getPackageInfo through the real manager, with the result's signatures replaced
     * by the original's certificate. The certificate travels as a base64 string constant - small
     * in the dex, and android.util.Base64 exists on every Android the clone can run on.
     */
    private fun helperClass(certificateDer: ByteArray): org.jf.dexlib2.immutable.ImmutableClassDef {
        val certificate = java.util.Base64.getEncoder().encodeToString(certificateDer)
        val access = org.jf.dexlib2.AccessFlags.PUBLIC.value or org.jf.dexlib2.AccessFlags.STATIC.value
        return org.jf.dexlib2.immutable.ImmutableClassDef(
            HELPER_CLASS,
            access,
            "Ljava/lang/Object;",
            emptyList<String>(),
            null,
            emptyList<org.jf.dexlib2.iface.Annotation>(),
            emptyList<org.jf.dexlib2.iface.Field>(),
            listOf(
            helperMethod(access, "I", certificate),
            helperMethod(access, FLAGS, certificate),
        ),
        )
    }

    /** get(PackageManager, String, flags) -> PackageInfo: real call, then spoofed signatures. */
    private fun helperMethod(access: Int, flagsType: String, certificate: String): ImmutableMethod {
        // A method's parameters occupy the LAST registers: with 13 registers and three params,
        // v10=pm, v11=name, v12=flags. Everything below v10 is scratch.
        val instructions = listOf<ImmutableInstruction>(
            ImmutableInstruction35c(
                Opcode.INVOKE_VIRTUAL, 3, 10, 11, 12, 0, 0,
                ImmutableMethodReference(PACKAGE_MANAGER, "getPackageInfo", listOf("Ljava/lang/String;", flagsType), PACKAGE_INFO),
            ),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
            ImmutableInstruction11n(Opcode.CONST_4, 1, 1),
            ImmutableInstruction22c(Opcode.NEW_ARRAY, 2, 1, ImmutableTypeReference(SIGNATURE_ARRAY)),
            ImmutableInstruction21c(Opcode.NEW_INSTANCE, 3, ImmutableTypeReference(SIGNATURE)),
            ImmutableInstruction21c(Opcode.CONST_STRING, 4, ImmutableStringReference(certificate)),
            ImmutableInstruction11n(Opcode.CONST_4, 5, 0),
            ImmutableInstruction35c(
                Opcode.INVOKE_STATIC, 2, 4, 5, 0, 0, 0,
                ImmutableMethodReference("Landroid/util/Base64;", "decode", listOf("Ljava/lang/String;", "I"), "[B"),
            ),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 4),
            ImmutableInstruction35c(
                Opcode.INVOKE_DIRECT, 2, 3, 4, 0, 0, 0,
                ImmutableMethodReference(SIGNATURE, "<init>", listOf("[B"), "V"),
            ),
            ImmutableInstruction11n(Opcode.CONST_4, 5, 0),
            ImmutableInstruction23x(Opcode.APUT_OBJECT, 3, 2, 5),
            ImmutableInstruction22c(
                Opcode.IPUT_OBJECT, 2, 0,
                ImmutableFieldReference(PACKAGE_INFO, "signatures", SIGNATURE_ARRAY),
            ),
            ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
        )
        return ImmutableMethod(
            HELPER_CLASS, "get",
            listOf<org.jf.dexlib2.iface.MethodParameter>(
                ImmutableMethodParameter(PACKAGE_MANAGER, emptySet<org.jf.dexlib2.iface.Annotation>(), null),
                ImmutableMethodParameter("Ljava/lang/String;", emptySet<org.jf.dexlib2.iface.Annotation>(), null),
                ImmutableMethodParameter(flagsType, emptySet<org.jf.dexlib2.iface.Annotation>(), null),
            ),
            PACKAGE_INFO,
            access,
            emptySet<org.jf.dexlib2.iface.Annotation>(),
            emptySet<org.jf.dexlib2.HiddenApiRestriction>(),
            ImmutableMethodImplementation(13, instructions, emptyList<org.jf.dexlib2.iface.TryBlock<org.jf.dexlib2.iface.ExceptionHandler>>(), emptyList<org.jf.dexlib2.iface.debug.DebugItem>()),
        )
    }
}