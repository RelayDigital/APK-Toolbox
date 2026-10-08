# APK Toolbox

Clone, install, merge and keep Android apps up to date — right on the phone, no root.

APK Toolbox started as a way to get a second copy of an app next to the
original and grew into a small set of tools for APK files:

- **Clone** an app under a new package name and app name, and keep the clone
  in step with the original as it gets updated.
- **Install** split APKs (APKS, XAPK, APKM, ZIP) and export installed apps
  as `.apks` archives.
- **Merge** an app that ships as several APKs into one regular APK.

Nothing is left behind on disk: a result is either installed or saved where
you choose, and temporary files are removed afterwards.

<p align="center">
  <img src="docs/screenshots/apps.jpg" width="24%" alt="Apps" />
  <img src="docs/screenshots/clone.jpg" width="24%" alt="Cloning an app" />
  <img src="docs/screenshots/clones.jpg" width="24%" alt="Clones" />
  <img src="docs/screenshots/clone-details.jpg" width="24%" alt="Clone details" />
</p>
<p align="center">
  <img src="docs/screenshots/install.jpg" width="24%" alt="Install tab" />
  <img src="docs/screenshots/export.jpg" width="24%" alt="Exporting as .apks" />
  <img src="docs/screenshots/merge.jpg" width="24%" alt="Merging splits" />
  <img src="docs/screenshots/settings.jpg" width="24%" alt="Settings" />
</p>

## Requirements

- Android 15 or newer
- No root needed
- [Shizuku](https://shizuku.rikka.app/) is optional — it lets clones update
  without any prompts

## Getting started

Install APK Toolbox and open it. It asks for two things: access to files and
permission to install apps. After that the four tabs are ready to use.

## Apps — cloning

1. Pick an installed app, or tap **Choose APK file**.
2. Adjust the package name and the app name. The defaults are
   `<original>.clone` and "<name> Clone", or the next free number
   (`.clone2`, "Clone 2", …) if you have cloned the app before.
3. Leave **Mark the icon with a dot** on to tell the clone from the original
   on the home screen. Each clone gets its own colour. The dot is drawn on
   top of the app's own icon layers, so it works for apps of any size.
4. Tap **Clone**, then **Install** the result or **Save** it.

The clone is a separate app with its own data, installed side by side with
the original. An app can be cloned as many times as you like.

## Clones — keeping them current

Every clone you have installed is listed here. Tap one to see which app and
version it was made from, to **Update** it to the original's current version
or to **Delete** it. Updating keeps the clone's data and its icon mark.
When several clones are behind, **Update all** brings them up to date one
after another.

To have this done for you, open settings:

- **Check for clone updates** — a background check, from once a day to once a
  year. It survives reboots. On its own it only notifies.
- **Install automatically** — update the clones right away, by one of two
  methods:
  - **Standard** — the system installer. Updates without a prompt only clones
    that APK Toolbox installed itself and that target a recent Android
    version, and only while Google Play Protect app scanning is off.
  - **Shizuku** — installs with shell privileges, with no prompts, for any
    clone. After a reboot the check waits for Shizuku to be started.

Whatever could not be updated automatically is reported in a notification.
**Check now** runs the check at once, whatever the schedule.

<p align="center">
  <img src="docs/screenshots/settings.jpg" width="30%" alt="Standard method" />
  <img src="docs/screenshots/settings-shizuku.jpg" width="30%" alt="Shizuku method" />
</p>

## Install — split APKs in, `.apks` out

- **Choose APKS file** installs a bundle (APKS, XAPK, APKM, ZIP), several
  split APK files or a single APK as one app. Pick which splits to include —
  all of them, or only those this device needs — and optionally re-sign them
  first. OBB expansion files inside an XAPK are copied to where the app
  expects them.
- Tap an installed app to **Export** it as an `.apks` archive. The archive
  carries the icon and metadata in the format
  [SAI](https://github.com/Aefyr/SAI) defined, so other tools recognise it.

## Split — merging into one APK

Turns an app that comes as several APKs into a single regular APK.

- Pick an installed split app, tap **Choose split APK files**, or share /
  open the files into APK Toolbox from another app.
- Choose the splits: all, or only the ABI, screen density and language this
  device uses.
- **Merge**, then install or save the result as `<name>_antisplit.apk`.

Splits whose version or package differs from the base are refused unless you
allow merging them anyway. Apps protected by PairIP are left unsigned, since
a re-signed build of such an app would not start.

Merging decodes the app's resource tables in memory, which takes about twenty
times their size, and Android caps what an app may use. Apps whose tables
come to more than roughly 20 MB are therefore refused with a message rather
than merged; those are better merged on a computer with
[APKEditor](https://github.com/REAndroid/APKEditor).

## Signing key

Everything APK Toolbox produces has to be signed, and the original signature
cannot be kept: it stops matching as soon as the APK is modified. Out of the
box a built-in key is used. It is the same for every copy of the app and its
private half is in this repository, so anyone could sign with it.

In settings you can **Create** a key of your own or **Import** one from a
PKCS#12 (`.p12`, `.pfx`) or BKS keystore. Your key is stored encrypted in the
app's private storage. **Export** it and keep the file: clones can only be
updated with the key they were signed with, and the key is gone if the app is
uninstalled. Clones made with the built-in key keep updating with it.

<p align="center">
  <img src="docs/screenshots/settings-key.jpg" width="30%" alt="Signing key" />
</p>

## Good to know

- A merged APK is signed with a different key than the original from a store,
  so it cannot be installed over it — the original has to be removed first.
- Google Play Protect treats re-signed apps as unknown and may block them,
  including installs you start by hand.
- Cloning changes only the manifest. Apps that check their own package name
  or signature, and services bound to them (Google sign-in, Firebase, in-app
  purchases), may not work in a clone.
- A cloned app made of split APKs is saved as a single `.apks` archive.

## Building

```
./gradlew assembleRelease
```

A release build is shrunk and obfuscated with R8. It is signed if a
`keystore.properties` file (`storeFile`, `storePassword`, `keyAlias`,
`keyPassword`) is present in the project root, and left unsigned otherwise.

## Used projects

⭐ [AntiSplit-M](https://github.com/AbdurazaaqMohammed/AntiSplit-M) by AbdurazaaqMohammed — the split merging feature follows its functionality

- [APKEditor](https://github.com/REAndroid/APKEditor) and [ARSCLib](https://github.com/REAndroid/ARSCLib) by REAndroid — the merge itself
- [SAI](https://github.com/Aefyr/SAI) by Aefyr — the model for installing split APKs and the `.apks` export format
- [Obtainium](https://github.com/ImranR98/Obtainium) by ImranR98 — the background update pipeline is modelled on it
- [Shizuku](https://github.com/RikkaApps/Shizuku) by RikkaApps — installing without prompts
- [apksig](https://android.googlesource.com/platform/tools/apksig/) from the Android Open Source Project — signing APKs

## License

[MIT](LICENSE)
