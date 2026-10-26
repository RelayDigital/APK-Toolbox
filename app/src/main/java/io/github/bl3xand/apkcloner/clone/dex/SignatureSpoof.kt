package io.github.bl3xand.apkcloner.clone.dex

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
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction10t
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction11n
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction11x
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction12x
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction21c
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction22t
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

        /** An invoke of PackageManager.getPackageInfo, of a Context-typed getPackageName()/getOpPackageName(), of a PackageManager component lookup (whose name the package spoof changes), or of getApplicationInfo (whose name the spoof hands libraries the clone cannot see). */
        private fun isSpoofTarget(instruction: Instruction): Boolean =
            isPackageInfoCall(instruction) || (spoofName && (isPackageNameCall(instruction) || isComponentLookupCall(instruction) || isApplicationInfoCall(instruction)))

        /** An invoke of PackageManager.getApplicationInfo(String, int|flags) - the metadata bootstrap libraries run at startup. */
        private fun isApplicationInfoCall(instruction: Instruction): Boolean {
            if (instruction !is Instruction35c && instruction !is Instruction3rc) return false
            val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return false
            if (ref.definingClass != PACKAGE_MANAGER || ref.name != "getApplicationInfo") return false
            val params = ref.parameterTypes
            return params.size == 2 && params[0] == "Ljava/lang/String;" && (params[1] == "I" || params[1] == FLAGS)
        }

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
                "getApplicationInfo" -> {
                    val flagsType = method.parameterTypes[1]
                    ImmutableMethodReference(
                        HELPER_CLASS, "applicationInfo",
                        listOf(PACKAGE_MANAGER, "Ljava/lang/String;", flagsType),
                        "Landroid/content/pm/ApplicationInfo;",
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
            helperMethod(access, "I", certificate, originalPackage, clonePackage),
            helperMethod(access, FLAGS, certificate, originalPackage, clonePackage),
            applicationInfoMethod(access, "I", originalPackage, clonePackage),
            applicationInfoMethod(access, FLAGS, originalPackage, clonePackage),
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
     * applicationInfo(PackageManager, String, flags) -> ApplicationInfo: the name the spoof
     * reports is answered with the always-visible self, every other name goes to the real
     * manager untouched. The name spoof makes libraries bootstrap themselves through
     * getApplicationInfo(getPackageName(), GET_META_DATA) - FacebookSdk's app id, the ads
     * SDK's metadata - and on Android 11+ the clone cannot SEE the original's name, so the
     * metadata load crashed whole apps. No exceptions, no retries: the substitution is decided
     * BEFORE the single call, from the two names the helper itself owns.
     */
    private fun applicationInfoMethod(access: Int, flagsType: String, originalPackage: String, clonePackage: String): ImmutableMethod {
        val AI = "Landroid/content/pm/ApplicationInfo;"
        val getRef = ImmutableMethodReference(PACKAGE_MANAGER, "getApplicationInfo", listOf("Ljava/lang/String;", flagsType), AI)
        // v4 = name; if (v4 != clonePackage) goto 5 (checkOriginal); else goto 14 (answerClone).
        // Registers: 6 total, params last: v3 = pm, v4 = name, v5 = flags; v0-v2 scratch.
        // Code-unit layout (const-string=2, 22t=2, goto=1, invoke=3, 11x=1):
        //   0-1  const-string v1, clonePackage
        //   2-3  if-ne v4, v1 -> 5      offset 3: a name that is not the clone's
        //   4    goto -> 14             offset 10: the clone's own name: answer with self
        //   5-6  const-string v1, originalPackage
        //   7-8  if-eq v4, v1 -> 14     offset 7: the original's name: answer with self
        //   9-13 pm.getApplicationInfo(v4, v5); return   - every other name, untouched
        //   14-20 const-string v4, clonePackage; pm.getApplicationInfo(v4, v5); return
        val code = mutableListOf<ImmutableInstruction>()
        code += ImmutableInstruction21c(Opcode.CONST_STRING, 1, ImmutableStringReference(clonePackage))
        code += ImmutableInstruction22t(Opcode.IF_NE, 4, 1, 3)
        code += ImmutableInstruction10t(Opcode.GOTO, 10)
        code += ImmutableInstruction21c(Opcode.CONST_STRING, 1, ImmutableStringReference(originalPackage))
        code += ImmutableInstruction22t(Opcode.IF_EQ, 4, 1, 7)
        // A name this helper does not own: the real manager answers, exception included.
        code += ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 3, 3, 4, 5, 0, 0, getRef)
        code += ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0)
        code += ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)
        // answerClone (unit 14): pm.getApplicationInfo(clonePackage, flags) - always visible.
        code += ImmutableInstruction21c(Opcode.CONST_STRING, 4, ImmutableStringReference(clonePackage))
        code += ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 3, 3, 4, 5, 0, 0, getRef)
        code += ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0)
        code += ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)
        return ImmutableMethod(
            HELPER_CLASS, "applicationInfo",
            listOf(
                ImmutableMethodParameter(PACKAGE_MANAGER, emptySet(), null),
                ImmutableMethodParameter("Ljava/lang/String;", emptySet(), null),
                ImmutableMethodParameter(flagsType, emptySet(), null),
            ),
            AI,
            access,
            emptySet<org.jf.dexlib2.iface.Annotation>(),
            emptySet<org.jf.dexlib2.HiddenApiRestriction>(),
            ImmutableMethodImplementation(
                6, code,
                emptyList<ImmutableTryBlock>(),
                emptyList<org.jf.dexlib2.iface.debug.DebugItem>(),
            ),
        )
    }

    /**
     * get(PackageManager, String, flags) -> PackageInfo: real call, then spoofed signatures -
     * on BOTH outcomes of the real call. The success path must fall through to the same
     * substitution tail the NameNotFound retry uses: a self-check that queries the app by its
     * own (visible) package name goes through the SUCCESS path, and returning the real
     * PackageInfo un-substituted there hands it the CLONER's certificate - the classic check
     * the patch exists for.
     *
     * The retry (NameNotFoundException) answers for exactly two names: the CLONE's own package
     * and the ORIGINAL's. The clone's name is the always-visible self, and covers the mimicry
     * case: with the package-name spoof on, the app asks by the ORIGINAL's name, which the
     * clone may not see (Android 11+ package visibility filters it), and the retry must answer
     * clone-self data with the substituted certificate. The original's name is the same query
     * when the original happens to be visible. A query about any OTHER package that the app
     * cannot see or that is absent rethrows NameNotFoundException - exactly what the real
     * manager would have thrown - so apps that enumerate packages or branch on a package's
     * absence keep their unpatched behavior instead of getting clone-self answers.
     *
     * Code shape (register map: 13 registers, three params in the LAST ones: v10=pm, v11=name,
     * v12=flags; v0-v9 scratch), in code-unit addresses - the handler's rethrow is deliberately
     * NOT covered by the try, so it propagates (the same shape d8 emits for a conditional
     * rethrow inside a catch):
     *
     * ```
     * try {
     *   v0 = pm.getPackageInfo(v11, v12);   // units 0..3 (invoke=3, move=1)
     *   goto tail;                          // unit 4: over the handler, into the tail
     * } catch (NameNotFoundException -> unit 5) {
     *   v9 = exception;                     // unit 5: MOVE_EXCEPTION opens the handler
     *   if (v11 != clonePackage) goto 10;   // units 6..9: is it the clone's name?
     *   if (v11 == originalPackage) goto 15;// units 10..13: or the original's?
     *   throw v9;                           // unit 14: neither - propagate NameNotFound
     *   retry: v11 = clonePackage;          // units 15..17 (const-string=2, move=1)
     *   v0 = pm.getPackageInfo(v11, v12);   // units 18..21, flags passed through
     * }
     * tail: // units 22..29: v0.signatures = { new Signature(Base64.decode(cert)) }; return v0;
     * ```
     *
     * The gotos are load-bearing: without the first, the success path falls INTO the handler;
     * without the retry's conditional jumps the handler would answer clone-self for every
     * absent package. The single RETURN at the end is shared by both surviving paths - the
     * substitution tail applies to the successful result and the retry alike.
     */
    private fun helperMethod(access: Int, flagsType: String, certificate: String, originalPackage: String, clonePackage: String): ImmutableMethod {
        val code = mutableListOf<ImmutableInstruction>()
        // try { v0 = pm.getPackageInfo(v11, v12); goto tail; } -> units 0..4 (invoke=3, move=1,
        // goto=1). NO return here: control jumps to the shared substitution tail, so the
        // success result gets its signatures overwritten like the retry's does. The 10t
        // argument is a code OFFSET: +18 from unit 4 lands on the tail at unit 22.
        code += ImmutableInstruction35c(
            Opcode.INVOKE_VIRTUAL, 3, 10, 11, 12, 0, 0,
            ImmutableMethodReference(PACKAGE_MANAGER, "getPackageInfo", listOf("Ljava/lang/String;", flagsType), PACKAGE_INFO),
        )
        code += ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0)
        code += ImmutableInstruction10t(Opcode.GOTO, 18)
        // catch (NameNotFoundException) at unit 5. MOVE_EXCEPTION must open a handler; the
        // caught exception lives in v9 for the rethrow below.
        code += ImmutableInstruction11x(Opcode.MOVE_EXCEPTION, 9)
        // The name guard, in two comparisons (a String's value is a constant here; comparing
        // register to register keeps it one instruction each). NOTE: the 22t branch argument is
        // a code OFFSET from the branch instruction itself, not an absolute address - the
        // targets below are absolute units, so the offsets are (target - own address).
        // if (v11 != clonePackage) goto 10 (compare with the original's name next);
        code += ImmutableInstruction21c(Opcode.CONST_STRING, 1, ImmutableStringReference(clonePackage))
        code += ImmutableInstruction22t(Opcode.IF_NE, 11, 1, 2)
        // if (v11 == originalPackage) goto 15 (the retry);
        code += ImmutableInstruction21c(Opcode.CONST_STRING, 1, ImmutableStringReference(originalPackage))
        code += ImmutableInstruction22t(Opcode.IF_EQ, 11, 1, 3)
        // throw v9 (unit 14): the queried name is neither the clone's nor the original's - a
        // foreign absent/invisible package keeps its NameNotFoundException.
        code += ImmutableInstruction11x(Opcode.THROW, 9)
        // retry (unit 15): v1 = clonePackage; v11 = v1 (name := clonePackage): plain
        // MOVE_OBJECT (12x) - both registers are < 16; then v0 = pm.getPackageInfo(v11, v12),
        // flags passed through unchanged (the int variant's flags and the PackageInfoFlags
        // object both flow as they were).
        code += ImmutableInstruction21c(Opcode.CONST_STRING, 1, ImmutableStringReference(clonePackage))
        code += ImmutableInstruction12x(Opcode.MOVE_OBJECT, 11, 1)
        code += ImmutableInstruction35c(
            Opcode.INVOKE_VIRTUAL, 3, 10, 11, 12, 0, 0,
            ImmutableMethodReference(PACKAGE_MANAGER, "getPackageInfo", listOf("Ljava/lang/String;", flagsType), PACKAGE_INFO),
        )
        // Substitution tail (units 22..29), shared by both surviving paths: v0.signatures =
        // { new Signature(Base64.decode(certificate)) }.
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
                // The try covers the real call and the goto over the handler (units 0..5);
                // the handler starts at unit 5, and its own rethrow at unit 14 is outside the
                // covered range, so it propagates out of the method.
                listOf(ImmutableTryBlock(0, 5, listOf(ImmutableExceptionHandler(NAME_NOT_FOUND, 5)))),
                emptyList<org.jf.dexlib2.iface.debug.DebugItem>(),
            ),
        )
    }
}