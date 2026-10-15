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
import org.jf.dexlib2.iface.instruction.TwoRegisterInstruction
import org.jf.dexlib2.iface.reference.FieldReference
import org.jf.dexlib2.immutable.ImmutableDexFile
import org.jf.dexlib2.immutable.ImmutableMethod
import org.jf.dexlib2.immutable.ImmutableMethodImplementation
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction11n
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction11x
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction21c
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction23x
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction31c
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction35c
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
 * certificate over JNI - reads it from the same place: the framework's PackageInfo.signatures
 * field, filled by PackageManager with the signature of whoever installed the app. A clone is
 * re-signed, so those checks see the cloner's key, compare it against the hash the app carries,
 * and kill the clone.
 *
 * This patch makes the check pass instead of fighting its consequences: a tiny helper class,
 * carrying the ORIGINAL app's real signing certificate (read from the source APK while cloning),
 * is injected into classes.dex, and every read of PackageInfo.signatures is rewritten to call it.
 * The check then hashes the original's certificate and matches, whatever the app calls itself or
 * how many layers of checks it runs. The detection is structural - a field reference to a
 * framework class - so it needs no knowledge of the app's names.
 *
 * What it does not cover, honestly: checks that hash the APK file itself or a dex CRC (the clone
 * really is a different file - exit suppression is the fallback for those), server-side
 * attestation (Play Integrity), and the newer PackageInfo.signingInfo API path.
 *
 * UNIVERSAL: works for any app. Fail-safe: any failure returns the original bytes.
 */
object SignatureSpoof {

    private const val TAG = "SignatureSpoof"

    // Where the helper class lives. Cross-dex references resolve at runtime, so injecting it into
    // one dex covers reads rewritten in all of them.
    private const val HELPER_CLASS = "Lio/github/bl3xand/apkcloner/spoof/SignatureSource;"
    private const val PACKAGE_INFO = "Landroid/content/pm/PackageInfo;"
    private const val SIGNATURE = "Landroid/content/pm/Signature;"
    private const val SIGNATURE_ARRAY = "[$SIGNATURE"

    /** The dex entry the helper class is injected into. */
    private const val PRIMARY_DEX = "classes.dex"

    /**
     * Patches [data] (the bytes of one dex file) so reads of PackageInfo.signatures return
     * [certificateDer] (the original's signing certificate, DER). Returns the original bytes on
     * any failure.
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
                if (it.contentEquals(data)) AppLog.debug("$TAG: no signature reads in $dexName, unchanged")
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

    /** Replaces every PackageInfo.signatures field read with a call to the helper. */
    private class SpoofMethodRewriter(rewriters: Rewriters) : MethodRewriter(rewriters) {
        override fun rewrite(method: Method): Method {
            val impl = method.implementation ?: return method
            val instructions = impl.instructions.toList()
            if (instructions.none { isSignaturesRead(it) }) return method

            val patched = ArrayList<Instruction>(instructions.size + 8)
            for (instruction in instructions) {
                if (isSignaturesRead(instruction)) {
                    // iget-object vA, vB, PackageInfo.signatures -> invoke-static {vB} helper; move-result-object vA
                    val dest = (instruction as OneRegisterInstruction).registerA
                    val source = (instruction as TwoRegisterInstruction).registerB
                    patched.add(
                        ImmutableInstruction35c(
                            Opcode.INVOKE_STATIC, 1, source, 0, 0, 0, 0,
                            ImmutableMethodReference(HELPER_CLASS, "get", listOf(PACKAGE_INFO), SIGNATURE_ARRAY),
                        ),
                    )
                    patched.add(ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, dest))
                } else {
                    patched.add(instruction)
                }
            }
            return ImmutableMethod(
                method.definingClass, method.name, method.parameters, method.returnType,
                method.accessFlags, method.annotations, method.hiddenApiRestrictions,
                ImmutableMethodImplementation(impl.registerCount, patched, impl.tryBlocks, impl.debugItems),
            )
        }
    }

    /** An iget-object of the framework's PackageInfo.signatures field. */
    private fun isSignaturesRead(instruction: Instruction): Boolean {
        if (instruction.opcode != Opcode.IGET_OBJECT) return false
        val ref = (instruction as? ReferenceInstruction)?.reference as? FieldReference ?: return false
        return ref.definingClass == PACKAGE_INFO && ref.name == "signatures" && ref.type == SIGNATURE_ARRAY
    }

    /**
     * The helper: one static method returning the original's certificate wrapped the way
     * PackageInfo.signatures would have it. The certificate travels as a base64 string constant -
     * small in the dex, and Base64.decode exists on every Android the clone can run on.
     */
    private fun helperClass(certificateDer: ByteArray) = org.jf.dexlib2.immutable.ImmutableClassDef(
        HELPER_CLASS,
        accessFlags(),
        "Ljava/lang/Object;",
        emptyList<String>(),
        null,
        emptyList<org.jf.dexlib2.iface.Annotation>(),
        emptyList<org.jf.dexlib2.iface.Field>(),
        listOf(
            ImmutableMethod(
                HELPER_CLASS, "get", listOf(org.jf.dexlib2.immutable.ImmutableMethodParameter(PACKAGE_INFO, emptySet(), null)), SIGNATURE_ARRAY,
                accessFlags(),
                emptySet<org.jf.dexlib2.iface.Annotation>(), emptySet<org.jf.dexlib2.HiddenApiRestriction>(),
                ImmutableMethodImplementation(
                    4,
                    listOf(
                        ImmutableInstruction11n(Opcode.CONST_4, 0, 1),
                        ImmutableInstruction31c(Opcode.NEW_ARRAY, 0, ImmutableTypeReference(SIGNATURE_ARRAY)),
                        ImmutableInstruction21c(Opcode.NEW_INSTANCE, 1, ImmutableTypeReference(SIGNATURE)),
                        ImmutableInstruction21c(Opcode.CONST_STRING, 2, ImmutableStringReference(java.util.Base64.getEncoder().encodeToString(certificateDer))),
                        ImmutableInstruction11n(Opcode.CONST_4, 3, 0),
                        ImmutableInstruction35c(
                            Opcode.INVOKE_STATIC, 2, 2, 3, 0, 0, 0,
                            ImmutableMethodReference("Landroid/util/Base64;", "decode", listOf("Ljava/lang/String;", "I"), "[B"),
                        ),
                        ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 2),
                        ImmutableInstruction35c(
                            Opcode.INVOKE_DIRECT, 2, 1, 2, 0, 0, 0,
                            ImmutableMethodReference(SIGNATURE, "<init>", listOf("[B"), "V"),
                        ),
                        ImmutableInstruction11n(Opcode.CONST_4, 3, 0),
                        ImmutableInstruction23x(Opcode.APUT_OBJECT, 1, 0, 3),
                        ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
                    ),
                    emptyList(), emptyList(),
                ),
            ),
        ),
    )

    private fun accessFlags() = org.jf.dexlib2.AccessFlags.PUBLIC.value or org.jf.dexlib2.AccessFlags.STATIC.value or org.jf.dexlib2.AccessFlags.FINAL.value
}