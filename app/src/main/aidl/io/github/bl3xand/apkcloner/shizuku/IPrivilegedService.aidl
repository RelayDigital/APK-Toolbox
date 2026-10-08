package io.github.bl3xand.apkcloner.shizuku;

// Runs inside the privileged (shell UID) process Shizuku spawns, where `pm` may install
// packages without asking the user.
interface IPrivilegedService {
    // Installs the APKs (base plus splits) as one session. Returns an empty string on success,
    // otherwise the reason reported by the package manager.
    String install(in ParcelFileDescriptor[] apks, in String[] names, in long[] sizes,
            String installerPackage, int userId);
}
