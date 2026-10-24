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
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction22x
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction22c
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction23x
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction35c
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction3rc
import org.jf.dexlib2.immutable.reference.ImmutableFieldReference
import org.jf.dexlib2.immutable.ImmutableMethodParameter
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference
import org.jf.dexlib2.immutable.reference.ImmutableStringReference
import org.jf.dexlib2.immutable.ImmutableTryBlock
import org.jf.dexlib2.immutable.ImmutableExceptionHandler
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
 * Full mimicry also needs the NAME: every Context.getPackageName()/getOpPackageName() call is
 * retargeted the same way to return the original's package name, so whatever the app reports
 * about itself - to its own checks, to servers, to analytics - is the original's identity, not
 * the clone's renamed one.
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
    private const val CONTEXT = "Landroid/content/Context;"
    private const val NAME_NOT_FOUND = "Landroid/content/pm/PackageManager\$NameNotFoundException;"

    /** The dex entry the helper class is injected into. */
    private const val PRIMARY_DEX = "classes.dex"

    /**
     * Patches [data] (the bytes of one dex file) so getPackageInfo results carry [certificateDer]
     * (the original's signing certificate, DER), and every Context.getPackageName() /
     * getOpPackageName() returns [originalPackage]. Returns the original bytes on any failure.
     */
    fun patchDex(data: ByteArray, dexName: String, certificateDer: ByteArray, originalPackage: String, clonePackage: String, spoofName: Boolean = false): ByteArray = try {
        val input = File.createTempFile("dex_in", ".dex")
        val output = File.createTempFile("dex_out", ".dex")
        try {
            input.writeBytes(data)
            val dexFile = DexFileFactory.loadDexFile(input, Opcodes.getDefault())
            val rewritten = DexFileRewriter(SpoofRewriters(hierarchyOf(dexFile), spoofName)).rewrite(dexFile)
            val classes = if (dexName == PRIMARY_DEX) rewritten.classes.plus(helperClass(certificateDer, originalPackage, clonePackage)) else rewritten.classes
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
    private class SpoofRewriters(private val superTypes: Map<String, String>, private val spoofName: Boolean) : DexRewriter(RewriterModule()) {
        override fun getMethodRewriter(): MethodRewriter = SpoofMethodRewriter(this, superTypes, spoofName)
    }

    /** The [type -> superclass] pairs of the dex, for telling Context subtypes from same-named methods. */
    private fun hierarchyOf(dexFile: DexFile): Map<String, String> =
        dexFile.classes.associate { it.type to (it.superclass ?: "") }

    /** Retargets PackageManager.getPackageInfo and Context.getPackageName calls to the helper. */
    private class SpoofMethodRewriter(rewriters: Rewriters, private val superTypes: Map<String, String>, private val spoofName: Boolean) : MethodRewriter(rewriters) {
        override fun rewrite(method: Method): Method {
            val impl = method.implementation ?: return method
            val instructions = impl.instructions.toList()
            if (instructions.none { isSpoofTarget(it) }) return method

            val patched = instructions.map { if (isSpoofTarget(it)) retarget(it) else it }
            return ImmutableMethod(
                method.definingClass, method.name, method.parameters, method.returnType,
                method.accessFlags, method.annotations, method.hiddenApiRestrictions,
                ImmutableMethodImplementation(impl.registerCount, patched, impl.tryBlocks, impl.debugItems),
            )
        }

        /** Whether [type], through the recorded hierarchy plus known framework bases, is a Context. */
        private fun isContextType(type: String): Boolean {
            var t = type
            var hops = 0
            while (hops++ < 100) {
                when (t) {
                    CONTEXT -> return true
                    // Common framework bases; anything else must resolve through this dex's classes.
                    "Landroid/app/Application;", "Landroid/app/Activity;", "Landroid/app/Service;",
                    "Landroid/content/ContextWrapper;", "Landroid/content/BroadcastReceiver;",
                    "Landroid/app/Dialog;", "Landroid/webkit/WebView;" -> t = CONTEXT
                    else -> t = superTypes[t] ?: return false
                }
            }
            return false
        }

        /** An invoke of PackageManager.getPackageInfo, of a Context-typed getPackageName()/getOpPackageName(), or of a PackageManager component lookup (whose name the package spoof changes). */
        private fun isSpoofTarget(instruction: Instruction): Boolean =
            isPackageInfoCall(instruction) || (spoofName && (isPackageNameCall(instruction) || isComponentLookupCall(instruction)))

        /** A PackageManager lookup by ComponentName - getProviderInfo/getActivityInfo/getServiceInfo: (ComponentName, int|flags). */
        private fun isComponentLookupCall(instruction: Instruction): Boolean {
            if (instruction !is Instruction35c && instruction !is Instruction3rc) return false
            val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return false
            if (ref.definingClass != PACKAGE_MANAGER) return false
            if (ref.name !in setOf("getProviderInfo", "getActivityInfo", "getServiceInfo")) return false
            val params = ref.parameterTypes
            return params.size == 2 && params[0] == "Landroid/content/ComponentName;" && (params[1] == "I" || params[1] == FLAGS)
        }

        /** An invoke of PackageManager.getPackageInfo(String, int) or (String, PackageInfoFlags). */
        private fun isPackageInfoCall(instruction: Instruction): Boolean {
            if (instruction !is Instruction35c && instruction !is Instruction3rc) return false
            val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return false
            if (ref.definingClass != PACKAGE_MANAGER || ref.name != "getPackageInfo") return false
            val params = ref.parameterTypes
            return params.size == 2 && params[0] == "Ljava/lang/String;" && (params[1] == "I" || params[1] == FLAGS)
        }

        /** A receiver-typed getPackageName()/getOpPackageName(): no params, String result, on a Context. */
        private fun isPackageNameCall(instruction: Instruction): Boolean {
            if (instruction !is Instruction35c && instruction !is Instruction3rc) return false
            val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return false
            if (ref.name != "getPackageName" && ref.name != "getOpPackageName") return false
            if (ref.parameterTypes.isNotEmpty() || ref.returnType != "Ljava/lang/String;") return false
            return isContextType(ref.definingClass)
        }

        /** Same format, same registers, same size - only the target becomes the helper. */
        private fun retarget(instruction: Instruction): Instruction {
            val original = instruction as ReferenceInstruction
            val method = original.reference as MethodReference
            val newRef = when (method.name) {
                "getPackageInfo" -> {
                    val flagsType = method.parameterTypes[1]
                    ImmutableMethodReference(
                        HELPER_CLASS, "get",
                        listOf(PACKAGE_MANAGER, "Ljava/lang/String;", flagsType),
                        PACKAGE_INFO,
                    )
                }
                "getProviderInfo", "getActivityInfo", "getServiceInfo" -> {
                    val flagsType = method.parameterTypes[1]
                    val ret = method.returnType
                    ImmutableMethodReference(
                        HELPER_CLASS, "component",
                        listOf(PACKAGE_MANAGER, "Landroid/content/ComponentName;", flagsType),
                        ret,
                    )
                }
                else -> {
                    // getPackageName/getOpPackageName: always the Context descriptor - the receiver
                    // register holds a Context at runtime, whatever static type the call was written against.
                    ImmutableMethodReference(
                        HELPER_CLASS, "packageName",
                        listOf(CONTEXT), "Ljava/lang/String;",
                    )
                }
            }
            return when (instruction) {
                // A 35c invoke keeps its register slots; the receiver stays where it was.
                is Instruction35c -> ImmutableInstruction35c(
                    Opcode.INVOKE_STATIC, instruction.registerCount,
                    instruction.registerC, instruction.registerD, instruction.registerE, instruction.registerF, instruction.registerG,
                    newRef,
                )
                // A range invoke keeps its register range (start..start+count-1). The constructor
                // takes (opcode, startRegister, registerCount, ref) - count and start are swapped
                // relative to the getters, easy to mix up.
                is Instruction3rc -> ImmutableInstruction3rc(
                    Opcode.INVOKE_STATIC_RANGE, instruction.startRegister, instruction.registerCount,
                    newRef,
                )
                else -> throw IllegalArgumentException("unexpected instruction format: $instruction")
            }
        }
    }

    /**
     * The helper: getPackageInfo through the real manager, with the result's signatures replaced
     * by the original's certificate. The certificate travels as a base64 string constant - small
     * in the dex, and android.util.Base64 exists on every Android the clone can run on.
     */
    private fun helperClass(certificateDer: ByteArray, originalPackage: String, clonePackage: String): org.jf.dexlib2.immutable.ImmutableClassDef {
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
            helperMethod(access, "I", certificate, clonePackage),
            helperMethod(access, FLAGS, certificate, clonePackage),
            packageNameMethod(access, originalPackage),
            componentLookupMethod(access, "I", "getProviderInfo", "Landroid/content/pm/ProviderInfo;", clonePackage),
            componentLookupMethod(access, FLAGS, "getProviderInfo", "Landroid/content/pm/ProviderInfo;", clonePackage),
            componentLookupMethod(access, "I", "getActivityInfo", "Landroid/content/pm/ActivityInfo;", clonePackage),
            componentLookupMethod(access, FLAGS, "getActivityInfo", "Landroid/content/pm/ActivityInfo;", clonePackage),
            componentLookupMethod(access, "I", "getServiceInfo", "Landroid/content/pm/ServiceInfo;", clonePackage),
            componentLookupMethod(access, FLAGS, "getServiceInfo", "Landroid/content/pm/ServiceInfo;", clonePackage),
        ),
        )
    }

    /** packageName(Context) -> String: the original's package name, whatever the clone is called. */
    private fun packageNameMethod(access: Int, originalPackage: String): ImmutableMethod {
        // Params occupy the LAST registers: with 3 registers and one param, v2 = context.
        val instructions = listOf<ImmutableInstruction>(
            ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(originalPackage)),
            ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
        )
        return ImmutableMethod(
            HELPER_CLASS, "packageName",
            listOf<org.jf.dexlib2.iface.MethodParameter>(
                ImmutableMethodParameter("Landroid/content/Context;", emptySet<org.jf.dexlib2.iface.Annotation>(), null),
            ),
            "Ljava/lang/String;",
            access,
            emptySet<org.jf.dexlib2.iface.Annotation>(),
            emptySet<org.jf.dexlib2.HiddenApiRestriction>(),
            ImmutableMethodImplementation(3, instructions, emptyList<org.jf.dexlib2.iface.TryBlock<org.jf.dexlib2.iface.ExceptionHandler>>(), emptyList<org.jf.dexlib2.iface.debug.DebugItem>()),
        )
    }

    /**
     * component(pm, ComponentName, flags): the lookup as asked, falling back to the clone's REAL
     * package. The package-name spoof makes app code build component names with the ORIGINAL
     * package; lookups for the clone's own components (androidx.startup and friends) would then
     * fail. The try/catch maps the component to the real package and retries.
     *
     * Registers: 6 total, params last: v3 = pm, v4 = component, v5 = flags; v0-v2 scratch.
     * Code units: invoke=3, move-result-object=1, return-object=1; try block covers the first
     * lookup (0..5), handler starts at unit 5.
     */
    private fun componentLookupMethod(access: Int, flagsType: String, lookup: String, returnType: String, realPackage: String): ImmutableMethod {
        val CN = "Landroid/content/ComponentName;"
        val lookupRef = ImmutableMethodReference(PACKAGE_MANAGER, lookup, listOf(CN, flagsType), returnType)
        val code = mutableListOf<ImmutableInstruction>()
        // try { return pm.lookup(component, flags); }
        code += ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 3, 3, 4, 5, 0, 0, lookupRef)
        code += ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0)
        code += ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)
        // catch (NameNotFoundException): v0 = component.getClassName()
        code += ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 4, 0, 0, 0, 0,
            ImmutableMethodReference(CN, "getClassName", listOf(), "Ljava/lang/String;"))
        code += ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0)
        // v1 = realPackage; v2 = new ComponentName(v1, v0)
        code += ImmutableInstruction21c(Opcode.CONST_STRING, 1, ImmutableStringReference(realPackage))
        code += ImmutableInstruction21c(Opcode.NEW_INSTANCE, 2, ImmutableTypeReference(CN))
        code += ImmutableInstruction35c(Opcode.INVOKE_DIRECT, 3, 2, 1, 0, 0, 0,
            ImmutableMethodReference(CN, "<init>", listOf("Ljava/lang/String;", "Ljava/lang/String;"), "V"))
        // return pm.lookup(v2, flags)
        code += ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 3, 3, 2, 5, 0, 0, lookupRef)
        code += ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0)
        code += ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)
        return ImmutableMethod(
            HELPER_CLASS, "component",
            listOf<org.jf.dexlib2.iface.MethodParameter>(
                ImmutableMethodParameter(PACKAGE_MANAGER, emptySet<org.jf.dexlib2.iface.Annotation>(), null),
                ImmutableMethodParameter(CN, emptySet<org.jf.dexlib2.iface.Annotation>(), null),
                ImmutableMethodParameter(flagsType, emptySet<org.jf.dexlib2.iface.Annotation>(), null),
            ),
            returnType,
            access,
            emptySet<org.jf.dexlib2.iface.Annotation>(),
            emptySet<org.jf.dexlib2.HiddenApiRestriction>(),
            ImmutableMethodImplementation(
                6, code,
                listOf(ImmutableTryBlock(0, 5, listOf(ImmutableExceptionHandler(NAME_NOT_FOUND, 5)))),
                emptyList<org.jf.dexlib2.iface.debug.DebugItem>(),
            ),
        )
    }

    /**
     * get(PackageManager, String, flags) -> PackageInfo: real call, then spoofed signatures.
     * With the package-name spoof the asked-for name can be the ORIGINAL's, which the clone may
     * not see (Android 11+ package visibility filters it) - then the real call is retried with
     * the clone's own package, which is always visible to itself.
     */
    private fun helperMethod(access: Int, flagsType: String, certificate: String, clonePackage: String): ImmutableMethod {
        // A method's parameters occupy the LAST registers: with 13 registers and three params,
        // v10=pm, v11=name, v12=flags. Everything below v10 is scratch.
        val code = mutableListOf<ImmutableInstruction>()
        // try { return pm.getPackageInfo(v11, v12); }  -> units 0..4 (invoke=3, move=1, return=1).
        // The return INSIDE the try is what keeps the success path out of the handler below:
        // without it, control falls through into the catch block and the successful result is
        // thrown away for a re-query with the clone's package - the wrong PackageInfo for every
        // other package the app asks about.
        code += ImmutableInstruction35c(
            Opcode.INVOKE_VIRTUAL, 3, 10, 11, 12, 0, 0,
            ImmutableMethodReference(PACKAGE_MANAGER, "getPackageInfo", listOf("Ljava/lang/String;", flagsType), PACKAGE_INFO),
        )
        code += ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0)
        code += ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)
        // catch (NameNotFoundException) at unit 5: retry with the clone's own package,
        // v1 = clonePackage; v11 = v1 (name := clonePackage): plain MOVE_OBJECT (12x) - both
        // registers are < 16; then v0 = pm.getPackageInfo(clonePackage, v12).
        code += ImmutableInstruction21c(Opcode.CONST_STRING, 1, ImmutableStringReference(clonePackage))
        code += ImmutableInstruction12x(Opcode.MOVE_OBJECT, 11, 1)
        code += ImmutableInstruction35c(
            Opcode.INVOKE_VIRTUAL, 3, 10, 11, 12, 0, 0,
            ImmutableMethodReference(PACKAGE_MANAGER, "getPackageInfo", listOf("Ljava/lang/String;", flagsType), PACKAGE_INFO),
        )
        val instructions = code + listOf<ImmutableInstruction>(
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
            ImmutableMethodImplementation(
                13, instructions,
                listOf(ImmutableTryBlock(0, 5, listOf(ImmutableExceptionHandler(NAME_NOT_FOUND, 5)))),
                emptyList<org.jf.dexlib2.iface.debug.DebugItem>(),
            ),
        )
    }
}