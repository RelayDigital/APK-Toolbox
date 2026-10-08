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
4. **Install** the clone, or **Save APK** wherever you like.

Installed clones are listed on the **Clones** tab, outdated ones highlighted.
Tap a clone to see which app and version it was made from, and to **Update**
or **Delete** it. To keep clones current without opening the app, turn on
**Update clones automatically** in settings and choose how often to check,
from once a day to once a year.

## Good to know

- Clones are signed with APK Cloner's own key. The original signature cannot
  be kept: it stops matching as soon as the APK is modified.
- Only the manifest is changed. Apps that check their own package name or
  signature, and services bound to them (Google sign-in, Firebase, in-app
  purchases), may not work in a clone.
- Apps made of split APKs are saved as a single `.apks` archive.

## License

[MIT](LICENSE)
