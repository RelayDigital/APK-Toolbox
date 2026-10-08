# APK Cloner

Clone any Android app under a new package name, right on the phone.

Need a second copy of an app next to the original — another account, a
sandbox to experiment in? APK Cloner rewrites the package name and app name
inside the APK, re-signs it and installs it side by side with the original.
When the original gets updated, the clone can follow it to the same version,
keeping its data — in one tap, or automatically in the background.

## Requirements

- Android 15 or newer
- No root needed

## Setup

1. Install APK Cloner and open it — grant file access and the permission to
   install apps when asked.
2. Pick an installed app, or tap **Choose APK file**.
3. Adjust the package name (`<original>.clone` by default) and the app name,
   tap **Clone**.
4. **Install** the clone, or **Save** the APK wherever you like.

Installed clones are listed on the **Clones** tab. Tap one to see which app
and version it was made from, and to **Update** or **Delete** it.

## Keeping clones up to date

Turn on **Check for clone updates** in settings and choose how often, from
once a day to once a year. By default you only get a notification. With
**Install automatically** the clones are updated right away, by one of two
methods:

- **Standard** — the system installer. Updates without a prompt only clones
  that APK Cloner installed itself and that target a recent Android version,
  and only while Google Play Protect app scanning is off.
- **Shizuku** — installs with shell privileges through
  [Shizuku](https://shizuku.rikka.app/): no prompts, for any clone. After a
  reboot the check waits for Shizuku to be started.

Whatever cannot be updated automatically is reported in a notification.
Installing and updating by hand always goes through the system installer.

## Good to know

- Clones are signed with APK Cloner's own key. The original signature cannot
  be kept: it stops matching as soon as the APK is modified.
- Google Play Protect treats re-signed apps as unknown and may block them,
  including installs you start by hand.
- Only the manifest is changed. Apps that check their own package name or
  signature, and services bound to them (Google sign-in, Firebase, in-app
  purchases), may not work in a clone.
- Apps made of split APKs are saved as a single `.apks` archive.

## License

[MIT](LICENSE)
