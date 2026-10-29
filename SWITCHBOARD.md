# Switchboard Stage A integration

Derived from bl3xand/APK-Toolbox v1.1.6, commit 3f79a694bf85f61d432e820d138cf125005623ff.
GPL-3.0 license and upstream attribution retained in LICENSE and README.md.
Changes on switchboard/minimal-engine are the complete corresponding integration source.
Distribute the exact source checkout (including Gradle wrapper and build inputs) alongside any fork APK.

The fork uses a separate application ID to keep its configuration and private signing key
separate from upstream APK Toolbox. Build with Java 21, Android SDK 36 and `./gradlew :app:assembleDebug`.
The debug APK is a development artifact, not a production release.

`SwitchboardBuildActivity` is guarded by Android's DUMP permission (ADB shell/system callers).
It accepts only `com.instagram.android.dev01` and a 32-character lowercase hexadecimal job ID.
It reads the installed original's base and every split and checks source version 453.0.0.55.79.
It uses existing ApkCloner, IconBadger and SigningKeys without DEX patches or removed permissions.
A custom private key is generated if absent and reused; the public demo key is never selected.
Its encoded icon badge is checked after building. The output is a split set and JSON build record
under the app's external-files directory. No original or clone is installed, removed or updated.

The private clone signing key stays encrypted in the app's private storage. Export it through
APK Toolbox's signing-key UI to a password-protected backup before device/app replacement;
never place the key, password or Instagram APKs in this repository. Keep the backup password private.
Reinstalling this fork under another signer may require its removal and lose its encrypted key.
Preserve the same fork signing key for updates, or import the secured clone-key backup.

Build-only invocation (launches the fork's activity):
`adb -s SERIAL shell am start -n io.github.bl3xand.apkcloner.switchboard/io.github.bl3xand.apkcloner.switchboard.SwitchboardBuildActivity --es target_package com.instagram.android.dev01 --es job_id GENERATED_32_HEX`

Do not install over an existing dev01 signed with another key. Preserve its package/crash inventory
and obtain explicit approval before any uninstall/data loss. This branch does not implement
batch generation; enable later stages only after actual Stage A launch, login and persistence tests.
