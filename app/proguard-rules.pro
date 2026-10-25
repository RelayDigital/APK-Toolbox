# PrivilegedUserService is instantiated reflectively by Shizuku, in a separate process, via the
# class name baked into ComponentName(...) in ShizukuBridge. R8 has no visibility into that call
# site, so without this it would strip or rename the class (or its no-arg constructor) and the
# Shizuku install method would silently stop working in a release build.
-keep class io.github.bl3xand.apkcloner.shizuku.PrivilegedUserService { *; }

# The AIDL-generated interface/Stub/Proxy this service is addressed through, both from this app's
# own process (ShizukuBridge) and across the Binder IPC boundary from the privileged process.
-keep class io.github.bl3xand.apkcloner.shizuku.IPrivilegedService { *; }
-keep class io.github.bl3xand.apkcloner.shizuku.IPrivilegedService$Stub { *; }
-keep class io.github.bl3xand.apkcloner.shizuku.IPrivilegedService$Stub$Proxy { *; }

# MainViewModel is constructed reflectively by androidx.lifecycle's ViewModelProvider (matched by
# constructor signature), not a `new` call R8 can trace back to this class.
-keep class io.github.bl3xand.apkcloner.ui.main.MainViewModel {
    <init>(android.app.Application);
}

# apksig builds and parses signature structures (PKCS#7, X.509) by reflecting over fields
# annotated with its own ASN.1 annotations. Renamed or removed, signing fails at run time with
# "Failed to sign using signer".
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keep class com.android.apksig.** { *; }

# apksig and ARSCLib are desktop libraries: they mention JDK and tooling classes that Android
# does not have, on code paths this app never reaches.
-dontwarn com.android.apksig.**
-dontwarn com.reandroid.**
-dontwarn java.awt.**
-dontwarn javax.**

# Constructed reflectively by ViewModelProvider, like MainViewModel.
-keep class io.github.bl3xand.apkcloner.sources.ui.SourcesViewModel {
    <init>(android.app.Application);
}

# jsoup loads its HTML entity tables as resources addressed by the package name.
-keeppackagenames org.jsoup.nodes

# Optional parts of the HTML, archive and compression libraries that are never used here
# (other archive formats, desktop-only helpers).
-dontwarn org.jsoup.**
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
-dontwarn org.jspecify.**

# TDLib's native code finds the Java classes of its API, their fields and constructors by name.
-keep class org.drinkless.tdlib.** { *; }

# dexlib2 walks its own class hierarchy by name (Opcodes lookup, immutable instruction
# factories, DexPool writers) and guava's immutables underneath it. R8 renaming any of it
# makes every dex patch (SignatureSpoof, KeepAlive, SignatureBypass, CoreRenameAlias) throw
# at run time into its fail-safe catch, silently producing an unpatched clone.
-keep class org.jf.dexlib2.** { *; }
-keep class com.google.common.collect.** { *; }
-dontwarn org.jf.dexlib2.**

# The dex patchers are structurally identical (same patchDex scaffold), and R8 merges them into
# one method switch - which broke the per-patch dispatch: clones came out with some patches
# silently missing. Keeping them apart keeps each patch's rewrite honest.
-keep class io.github.bl3xand.apkcloner.clone.dex.SignatureBypass { *; }
-keep class io.github.bl3xand.apkcloner.clone.dex.SignatureBypass$* { *; }
-keep class io.github.bl3xand.apkcloner.clone.dex.KeepAlive { *; }
-keep class io.github.bl3xand.apkcloner.clone.dex.KeepAlive$* { *; }
-keep class io.github.bl3xand.apkcloner.clone.dex.CoreRenameAlias { *; }
-keep class io.github.bl3xand.apkcloner.clone.dex.CoreRenameAlias$* { *; }
-keep class io.github.bl3xand.apkcloner.clone.dex.SignatureSpoof { *; }
-keep class io.github.bl3xand.apkcloner.clone.dex.SignatureSpoof$* { *; }

# The patch chain holder (see its doc): kept so R8 cannot merge or reshuffle its captures.
-keep class io.github.bl3xand.apkcloner.clone.build.DexPatchChain { *; }
