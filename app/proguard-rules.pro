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
-keep class io.github.bl3xand.apkcloner.ui.MainViewModel {
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
