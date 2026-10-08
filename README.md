# ApkClonner

Clones an Android app on-device under a new package name, so it can be installed next to the
original.

1. Grant "All files access" (clones are saved to `/sdcard/ApkClonner/<package>/`).
2. Pick an installed app from the list, or choose an APK file with the button at the bottom.
3. Set the new package name (defaults to `<original>.clone`), the app name and the signature:
   - **Debug** - re-signed with the bundled debug key (v1/v2/v3). Installs on any device.
   - **Keep original** - the original signature files and signing block are left in place. They
     no longer match the modified APK, so this installs only where signature verification is
     disabled.
4. Clone, then install straight from the app (split APKs are installed as one session).

## How it works

Only the binary `AndroidManifest.xml` is patched: package name, app label, provider authorities,
custom permissions and `sharedUserId` are renamed, and relative class names are pinned to the
original package. Code and resources are untouched.

## Limitations

- Apps that hardcode their package name or provider authorities in code, or verify their own
  signature, may misbehave or refuse to start.
- Services tied to the original package and signature (Google sign-in, Firebase, in-app
  purchases, Play Integrity) will not work in a clone.
- `.apks` / `.xapk` bundles are not supported as input; pick a single `.apk`.

## Build

```
./gradlew assembleDebug
```

Requires Android 15+ (minSdk 35).
