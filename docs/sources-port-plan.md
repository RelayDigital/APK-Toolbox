# Sources tab — port plan and fact sheet

Reference: Obtainium 1.6.18+2357, commit `af286fa8` (2026-09-13), Flutter, GPL-3.0.
Reference checkout while working: `<scratchpad>/obtainium`.

Goal: the fifth tab ("Sources") reproduces the behaviour of the reference app
one to one, in this project's own Views / Material Expressive design. Background
checks hang off the existing update worker and its interval.

Legend: `[ ]` not started, `[~]` in progress, `[x]` done, `[t]` tested on device.

## 0. Progress log

- 2026-10-08: analysis of the reference finished. Read in full: model, providers,
  services, installers, all 29 sources (~18k lines). UI pages (~11k lines) read
  for behaviour (logic sections in full, layout code skimmed).
  sections 1-21 hold the facts, 22 the decisions, 23-24 the build plan.

- 2026-10-08: implementation started on branch `sources-tab` (nothing committed yet).
  P1 done: `sources/core`, `sources/model`, `sources/form`; 72 unit tests green
  (`VersionNormalizationTest` and `VersionReconciliationTest` are converted from the
  reference tests with `<scratchpad>/dart2kt.py`, `CoreTest` is ours).
  P2: `sources/net/Http.kt` (redirect rules, pinning, insecure mode, RuStore root) and
  `Downloader.kt` written; HTTP exercised by the live tests, the downloader not yet.
  P3 done: GitHub, GitLab, Codeberg, F-Droid, IzzyOnDroid, F-Droid repo, HTML, Direct
  link pass `LiveSourcesTest` against the real sites
  (`./gradlew :app:testDebugUnitTest -Plive=true [-Plive.only=<Source>] --tests '*LiveSourcesTest*'`).
  P4 done (same day): SourceHut, APKPure, Aptoide, Uptodown (auth + download URL),
  itch.io (signed URL flow), Huawei (handshake + search), Tencent, vivo, RuStore
  (signature) all pass the live test. 17 of 29 sources work.
  P5 done (same day): Farsroid, Samsung, LiteAPKs, Apk4Free, CoolApk (bcrypt token),
  SourceForge, Jenkins, APKMirror (id, change log, size), APKCombo, RockMods, Telegram,
  NeutronCode pass the live test. **All 29 sources work against the real sites.**
  `ApkMirrorTest` ports the reference APKMirror and RuStore unit tests (77 unit tests).
  Finding: sites tell clients apart by the default User-Agent (LiteAPKs answers 403 to
  Java's), so requests without their own UA send the reference's `Dart/3.12 (dart:io)`.
  New dependencies so far: jsoup, jbcrypt (CoolApk), junit + org.json for tests.
  Next: P6 data layer, then P7 (needs D2) and the UI (needs D1 for the texts).
  Notes for later phases: `Tr` needs an Android resolver for text keys; the GitHub sort
  dropdown has one composed label ("smartname x releaseDate"); if a test run fails with
  "Unable to delete directory …/binary", run `./gradlew --stop` (stale handle on the
  NTFS mount).

## 1. Data model (`models/app.dart`)

- `AppNames(author, name)`.
- `APKDetails(version, apkUrls: List<(name, url)>, names, releaseDate?, changeLog?,
  releaseUrl?, allAssetUrls = [])`.
- `App`: `id, url, author, name, installedVersion?, latestVersion, apkUrls,
  otherAssetUrls, preferredApkIndex, additionalSettings: Map, lastUpdateCheck?,
  pinned=false, categories=[], releaseDate?, changeLog?, releaseUrl?,
  overrideSource?, allowIdChange=false, pendingRepoRenameUrl?`.
- `finalName = additionalSettings.appName (non blank) ?: name`; same for
  `finalAuthor` with `appAuthor`.
- JSON (must stay byte compatible for import/export with the reference):
  `apkUrls`, `otherAssetUrls`, `additionalSettings` are JSON **strings** inside
  the object; `apkUrls` is a 2D list `[[name,url],...]`; dates are
  microseconds since epoch (`lastUpdateCheck`, `releaseDate`); `categories` list
  (legacy single `category`); defaults: `latestVersion` -> "unknown" text,
  `apkUrls` -> `[["placeholder","placeholder"]]`, `preferredApkIndex` -> -1.
- `isTempId(app)`: id matches `^[0-9]+$` or `^[0-9a-f]{12}$`.
- `isVersionPseudo(app)`: `trackOnly` or (`installedVersion != null` and
  `versionDetection` false).
- `TypedSettings`: `getBool` accepts bool or the string "true"; `getIntOrNull`
  accepts int or numeric string; `getStringOrNull` returns null for empty.
- Split APK sets are stored in one `apkUrls` value joined with `\n`
  (base first) — `splitMultiApkUrl` / `joinMultiApkUrl`.

## 2. Source base class (`app_sources/app_source.dart`)

Flags (defaults): `hosts=[]`, `trustedApkHosts=[]`, `hostChanged=false`,
`hostIdenticalDespiteAnyChange=false`, `name` (class name), `enforceTrackOnly`,
`changeLogIfAnyIsMarkDown=true`, `changeLogPageIsStandardUrl`,
`appIdInferIsOptional`, `inferAppIdFromUrlPath`, `inferAppIdEvenWhenTrackOnly`,
`allowSubDomains`, `naiveStandardVersionDetection`, `allowOverride=true`,
`neverAutoSelect`, `showReleaseDateAsVersionToggle`,
`versionDetectionDisallowed`, `suppressStandardVersionExtraction`,
`excludeCommonSettingKeys=[]`, `urlsAlwaysHaveExtension`,
`allowInsecureRedirects`, `allowIncludeZips`, `allowIncludeTarballs`,
`canSearch`, `includeAdditionalOptsInMainSearch`.
`sourceIdentifier` = class name (persisted in `overrideSource`).

- Host match regex: `^` + (`([^\.]+\.)*` if allowSubDomains else `(www\.)?`) +
  `(host1|host2 with dots escaped)` + `$`.
- `standardizeUrl` = `preStandardizeUrl` then, unless `hostChanged`,
  `sourceSpecificStandardizeURL`.
- `standardizeUrlWithRegex(url, subdomainPrefix, pathPattern)`: regex
  `^https?://<prefix><hostsRegex><path>` case-insensitive; no match ->
  `InvalidURLError(name)`.
- Hooks: `getRequestHeaders(settings, url, forAPKDownload)`,
  `postProcessApp`, `runOnAddAppInputChange(inputUrl)`,
  `assetUrlPrefetchModifier(assetUrl, standardUrl, settings)`,
  `generalReqPrefetchModifier(reqUrl, settings)`,
  `changeLogPageFromStandardUrl`, `resolveDownloadSize`, `getSourceNote`,
  `tryInferringAppId` (default: last path segment when
  `inferAppIdFromUrlPath`), `search(query, querySettings)`,
  `searchQuerySettingFormItems`, `sourceConfigSettingFormItems`.
- `sourceRequest(url, settings, followRedirects=true, postBody)`: merges
  source-level config values into the settings, applies
  `generalReqPrefetchModifier`, passes `enableCertificatePinning` (global) and
  `allowInsecureRedirects` (source flag), GET or POST (JSON body unless String).
- `getSourceConfigValues`: for each source config item — if host was changed
  (override on another host) use only per-app value, else per-app non-empty
  string, else the global setting.

### Common per-app settings (order matters, shown after source-specific ones)

| key | type | default |
|---|---|---|
| trackOnly | switch | false |
| versionExtractionRegEx | text (regex validated) | |
| matchGroupToUse | text, hint `$0` | |
| versionDetection | switch | true |
| releaseDateAsVersion | switch, only if source `showReleaseDateAsVersionToggle`, inserted after versionDetection | false |
| useVersionCodeAsOSVersion | switch | false |
| apkFilterRegEx | text (regex) | |
| invertAPKFilter | switch | false |
| autoApkFilterByArch | switch | true |
| minimumUpdateAgeDays | slider: "" (use global), 0,1,2,3,5,7,14,30 | "" |
| appName | text | |
| appAuthor | text | |
| shizukuPretendToBeGooglePlay | switch | false |
| allowInsecure | switch | false |
| allowedSigningCertHashes | text, up to 4 lines, hint `AA:BB:CC:…`, validated | |
| exemptFromBackgroundUpdates | switch | false |
| skipUpdateNotifications | switch | false |
| about | text | |
| refreshBeforeDownload | switch | false |
| includeZips + zippedApkFilterRegEx | only if `allowIncludeZips` | false |
| includeTarballs + tarballedApkFilterRegEx | only if `allowIncludeTarballs` | false |

- Keys in `excludeCommonSettingKeys` are removed.
- `versionDetectionDisallowed` -> `versionDetection` and
  `useVersionCodeAsOSVersion` forced false and disabled.
- Mass import interface: `MassAppUrlSource { name, requiredArgs,
  getUrlsWithDescriptions(args) }` — only `GitHubStars`.

## 3. Source registry (`providers/source_provider.dart`)

Auto-detection order (hosted sources matched by host first, then hostless in
order; HTML last): GitHub, GitLab, Codeberg, FDroid, FDroidRepo, IzzyOnDroid,
SourceHut, APKPure, Aptoide, Uptodown, ItchIO, HuaweiAppGallery, Tencent,
VivoAppStore, RuStore, Farsroid, SamsungGalaxyStore, LiteAPKs, Apk4Free,
CoolApk, SourceForge, Jenkins, APKMirror, APKCombo, RockMods, TelegramApp,
NeutronCode, DirectAPKLink, HTML. (29 sources + GitHubStars mass source.)

- `getSource(url, overrideSource)`: with override — fresh instance,
  `hosts=[url host]`, `hostChanged=true`, `hostIdenticalDespiteAnyChange` if the
  host was one of the original hosts. Without — host match; else first hostless
  source (not `neverAutoSelect`) whose `sourceSpecificStandardizeURL(url,
  forSelection=true)` does not throw; else `UnsupportedURLError`.
- `generateTempID` = first 12 hex chars of sha256(standardUrl +
  additionalSettings.toString()) (Dart map toString; ours only has to be stable).
- App id resolution: existing id -> `additionalSettings.appId` -> inferred
  (when (!trackOnly || inferAppIdEvenWhenTrackOnly) and (!appIdInferIsOptional
  || inferAppIdIfOptional)) -> temp id.
- `getApp(source, url, settings, currentApp, trackOnlyOverride,
  sourceIsOverriden, inferAppIdIfOptional)`:
  1. `trackOnly` forced when override or `enforceTrackOnly`.
  2. standardize URL, `getLatestAPKDetails`.
  3. New app and not trackOnly: if release younger than effective min age ->
     `MinUpdateAgeError`.
  4. Unless `suppressStandardVersionExtraction`: `extractVersion(regex, group,
     version)`.
  5. `releaseDateAsVersion` && date -> version = microseconds string.
  6. `filterApks(apkUrls, apkFilterRegEx ?: global filter, invertAPKFilter)`;
     empty and not trackOnly -> `NoAPKError`.
  7. `autoApkFilterByArch` -> `filterApksByArch`; empty -> `NoAPKError`.
  8. name = current name (trimmed, if non-empty) else source name.
  9. `preferredApkIndex` = current ?: last index (or 0).
  10. `allowIdChange` = current ?: trackOnly || (appIdInferIsOptional &&
      inferAppIdIfOptional).
  11. `otherAssetUrls` = allAssetUrls minus those whose name is in apkUrls.
  12. `overrideSource` = identifier if overridden else current.
  13. `source.postProcessApp`.
- `getAppsByURLNaive(urls, alreadyAddedUrls, sourceOverride)`: batches of 4
  (`kDefaultFetchConcurrency`), default settings from form items, returns
  `[apps, errorsByUrl]`.

## 4. APK filtering (`services/apk_filter_service.dart`)

- Container extensions: `.apk .xapk .apkm .apks`; archives `.zip`; tarballs
  `.tar.gz .tgz .tar.bz2 .tar.xz`.
- `getApkUrlsFromUrls(urls)`: name = last path segment that looks like an APK
  container, else last segment.
- `filterApks`: regex `hasMatch` on the name, optional invert.
- `filterApksByArch(apkUrls, deviceAbis)`: only when more than one; for each
  device ABI in order build `.*(?:abi|aliases).*` case-insensitive; aliases:
  arm64-v8a -> aarch64, arm64; armeabi-v7a -> armv7, armeabi; x86_64 -> x64.
  Take the first ABI that matches some but not all entries.

## 5. Versions (`services/version_service.dart`, `utils/version_normalization.dart`)

- `extractVersion(regex, group, text)`: null when no regex; uses the **last**
  match; group string: digits -> `$N`; `$N` references substituted, `\$N` kept
  literal; no match / empty -> `NoVersionError`.
- Standard formats: basics `N`, `N.N`, `N.N.N`, `N.N.N.N`; preSuffix `-` or
  `\+`; suffix one of alpha, beta, rc, pre, dev, snapshot, nightly, ose,
  `[0-9]+`; final `\+[0-9]+` or `[0-9]+`. Patterns: basic; basic+suffix;
  basic+pre+suffix; each of those + final. Strict = whole string, loose =
  substring.
- `compareVersionsNumerically(a, b)`: common loose formats; pick the one with
  most digit runs (then longest); compare digit runs; null when none.
- `reconcileVersionDifferences(template, comparison)`: strict formats of
  template ∩ (strict else loose formats of comparison); if first matches equal
  under any common pattern -> `(equal, comparison)` else `(notEqual, template)`;
  null when no common format.
- `versionDetectionPossible(...)`: false for trackOnly, releaseDateAsVersion,
  HTML source without version detection, `versionDetectionDisallowed`, null
  versions, rolling major-only pre-release tag (unless naive). Otherwise
  reconcilable or cosmetically equal (installed vs tracked, installed vs
  latest) or naive.
- `reconcileTrackedVersion(...)`: collapse tracked -> latest when same release;
  same for real installed; if standard detection and real != tracked: adopt
  `reconcile` result when different, or the real version on naive sources when
  not cosmetically equal; collapse again. Returns null when unchanged.
- `isAppUpdateable(app)`: installed != null && installed != latest; when
  `hideDowngrades` setting on, only if not numerically newer.
- Normalization: lower-case, `_` -> `-`, strip leading `v` before a digit, split
  on first `+` into version / metadata; tokens split on non letter/digit;
  packaging words ignored: strip, debug, release, stable, final, standard,
  build, signed, unsigned, universal, nogms; other tokens split into letter and
  digit runs. Pre-release qualifiers: alpha, beta, rc, pre, dev, snapshot,
  nightly, ose.
  - `versionsAreCosmeticallyEqual(a, b)`: same core; metadata must match only
    when both have it.
  - `installedMatchesRemote(installed, remote)`: same core; installed packaging
    ⊇ remote packaging; remote metadata (if any) must equal installed metadata.
  - `isPreReleaseMajorMatch(tag, installed)`: tag matches
    `^(\d+)(?:[._\-\s]?(qualifier)\w*)$` and the number equals installed major.
- Min update age: per-app `minimumUpdateAgeDays` (non-empty string) else
  global; `isReleaseTooYoung(date, days)`; `applyMinAgeSuppression(current,
  fetched)` keeps current latestVersion, releaseDate, changeLog, releaseUrl,
  apkUrls, otherAssetUrls.
- Signing cert hashes: SHA-256 of DER, upper-case colon separated; multi-signer
  -> `apkContentSigners`, else `signingCertificateHistory`;
  `allowedSigningCertHashes` split on whitespace, comma, semicolon; normalised
  from 64 hex chars with or without separators.
- `compareAlphaNumeric(a, b)`: split into digit / non-digit runs, numbers
  compared numerically, number > text, then by part count.
- `formatBytes`: B, KB, MB, GB, TB, 1 decimal above bytes.

## 6. HTTP (`services/http_service.dart`)

- Connection timeout 30 s; manual redirects, max 10 ("tooManyRedirects").
- Redirect https -> http refused ("insecureRedirect") unless `allowInsecure`
  or source `allowInsecureRedirects`.
- Cross-origin redirect: keep only headers accept, accept-charset,
  accept-encoding, accept-language, cache-control, content-length,
  content-type, if-modified-since, if-none-match, if-range, origin, pragma,
  range, referer, user-agent, x-requested-with; drop cookies. Same origin:
  carry response cookies to the next hop.
- `allowInsecure` -> accept bad certificates, except for pinned hosts when
  pinning is enabled.
- Certificate pinning (global setting `enableCertificatePinning`), matched by
  exact host or last two labels: github.com (Sectigo R46/E46 + ISRG X1, X2, YE,
  YR), codeberg.org (ISRG X1, X2, YE, YR), gitlab.com (Sectigo R46/E46),
  rustore.ru (HARICA TLS root 2021 RSA/ECC + Russian Mintsifry root).
  Certificates in `assets/ca-certs/`.
- RuStore without pinning: system roots + Mintsifry root.
- Error mapping: 404 -> `NoReleasesError`; 429/403 -> `RateLimitError`
  (minutes = ceil(retry-after / 60), default 1); else reason phrase or
  "HTTP status code N".
- `ensureAbsoluteUrl(url, reference)`.

## 7. Errors (`custom_errors.dart`)

Codes: RATE_LIMIT(remainingMinutes), INVALID_URL(sourceName),
CREDS_NEEDED(sourceName), NO_RELEASES(note), NO_APK, MIN_UPDATE_AGE(releaseDate,
minAgeDays), RUSTORE_AGGREGATED_APP, NO_VERSION, UNSUPPORTED_URL,
DOWNGRADE(currentVersionCode, newVersionCode), INSTALL_FAILED(errorCode,
message), SIGNING_CERT_MISMATCH(hardBlock, expected, actual),
ID_CHANGED(newId), REPO_RENAMED(oldUrl, newUrl), CHECK_UPDATES_FAILED(updates,
errors), NOT_IMPLEMENTED, MULTI_ERROR, HTTP_ERROR(statusCode), UNEXPECTED.
Every error can carry the offending URL, appended as ` (url)`.
`MultiAppMultiError`: groups app ids by identical error text; output
`<error> [App A, App B and App C]`, groups separated by a blank line.
`list2FriendlyString`: Oxford comma only in English.

## 8. Global settings (`providers/settings_provider.dart`)

Stored in SharedPreferences; key -> default. `*` = UI-only for the Flutter app
(theme etc.), not ported because this app has its own design.

| key | default | notes |
|---|---|---|
| installMethod | system | system / shizuku / external / root (legacy bool `useShizuku`) |
| externalInstallerPackage, externalInstallerComponent | null | external installer target |
| updateInterval | 360 (minutes) | 0 = never; ours: tied to existing interval |
| updateIntervalSliderVal | 6.0 | slider position |
| checkOnStart | false | |
| sortColumn | nameAuthor | added, nameAuthor, authorName, releaseDate |
| sortOrder | ascending | |
| firstRun / welcomeShown / googleVerificationWarningShown | | one-time dialogs |
| showAppWebpage | false | show source web page in app view |
| pinUpdates | true | apps with updates on top |
| buryNonInstalled | false | not-installed apps at the bottom |
| groupBy | none | none / category / source (legacy bool groupByCategory) |
| hideTrackOnlyWarning | false | |
| hideAPKOriginWarning | false | |
| categories | `{}` | JSON map name -> colour int |
| showAppDowngradeError | true | |
| hideDowngrades | true | |
| includePrereleasesByDefault | false | |
| removeOnExternalUninstall | false | |
| checkUpdateOnDetailPage | false | |
| enableBackgroundUpdates | true | background *install* of updates |
| enableCertificatePinning | false | |
| bgUpdatesOnWiFiOnly | false | |
| bgUpdatesWhileChargingOnly | false | |
| disableSwipeActions | false | |
| exportDir | null | SAF tree URI, persisted permission |
| autoExportOnChanges | false | |
| autoExportFileName | null | sanitised: strips `/\:*?"<>|` |
| globalApkFilterRegEx | null | |
| onlyCheckInstalledOrTrackOnlyApps | false | |
| collapseGroupsOnStartup | false | |
| skipBulkUpdateConfirmation | false | |
| minimumUpdateAgeDays | 0 | |
| exportSettings | 1 | 0/1/2 (legacy bool) |
| exportInstalledOnly | false | |
| parallelDownloads | true | |
| appListDensity | standard | standard / compact / dense |
| searchDeselected | all source names | list |
| actionBannerMode | updatesOnly | all / updatesOnly / none |
| beforeNewInstallsShareToAppVerifier | true | |
| verifySigningCertHashes | true | |
| shizukuPretendToBeGooglePlay | false | |
| per-source config keys | | e.g. `github-creds` (generic string/bool get/set by key) |
| *theme, themeColor, colourSchemeMode, useBlackTheme, useSystemFont, forcedLocale, tactileFeedbackEnabled, highlightTouchTargets, alwaysUsePhoneLayout | | Flutter-app look only |

Constants: self id `dev.imranr.obtainium`, temp id
`imranr98_obtainium_github.com`, self URL `https://github.com/ImranR98/Obtainium`.

## 9. Download engine (`providers/apps_provider.dart`)

- Constants: retries 3, retry delay 5 s, progress callback every 500 ms, write
  buffer 32 KB, progress fallback 30 % when length unknown.
- `downloadFileWithRetry`: retry on transport errors, timeouts and HTTP 429 /
  5xx; never on cancellation.
- `downloadFile(fileName, fileNameHasExt, onProgress, destDir, settings,
  useExisting=true, headers, cancellationToken)`:
  1. Probe GET (through the shared redirect handler), read headers, close.
  2. Extension: from `content-disposition` (text after the last `.`, trailing
     quote dropped) else `apk`; if the URL path ends with an APK container /
     zip / tarball extension use the URL's extension; `attachment` -> `apk`.
  3. File name reduced to its basename (no path escape); empty / `.` / `..`
     rejected. Target `<dir>/<name>.<ext>` or `<dir>/<name>` when
     `fileNameHasExt`.
  4. Ranges supported when `accept-ranges: bytes`.
  5. Existing file: reuse if length unknown or no range support, or sizes
     equal; larger than expected -> start over.
  6. `.part` file present: another download may run — poll every 7 s up to 43
     times while it grows; reuse result if it completes.
  7. Resume with `range: bytes=<start>-<len-1>` when possible; if server
     answers 200 instead of 206, restart from zero.
  8. Non-2xx -> `HTTPStatusError(status)`, `.part` removed.
  9. Short stream (received < content-length) -> error, `.part` kept.
  10. Rename `.part` -> final (delete existing first if needed).
  11. Progress callback `(percent, received, total)`, final call `(null, null,
      null)`.
- `checkPartialDownloadHashDynamic`: sizes 1024, 768, 512, 256 (start 1024,
  step -256, lower limit 128): fetch `Range: bytes=0-N` twice in parallel; when
  both hashes agree return it; else `NoVersionError`. Hash = first 8 hex of
  sha256(JSON of the list of byte chunks limited to N **chunks**,
  `response.take(N)`) — only stability matters for us.
- `checkETagHeader`: GET, non-2xx -> null; etag without quotes -> first 12 hex
  of sha256.
- `getDownloadSize(url, headers, allowInsecure, pinning)`: Content-Length or
  null, never throws.
- `realInstalledVersionOf(app, info)`: versionCode string when
  `useVersionCodeAsOSVersion`, else versionName.
- Storage: APKs in external cache dir, icons in `<cache>/icons`; `.part` files
  older than 7 days removed at start (when no downloads run).
- `AppInMemory`: app + download state (progress percent; -1 = installing; null
  = idle; received/total bytes) + installed `PackageInfo` + icon bytes +
  `sourceType`. `needsRefreshBeforeDownload` = `refreshBeforeDownload` setting
  or first apk url is `placeholder`.
- `addAppsByURL(urls, sourceOverride)`: naive fetch, skip already added
  (by URL, then by id), returns `[url/id, error]` pairs.
- Per-app download cancellation tokens; cancel resets progress.
- Auto export debounced 2 s after saves/removals.

### Background task (`bgUpdateCheck(taskId, params, forceAll)`)

1. Load apps and settings. No network (none, or VPN only) -> return.
2. `updateInterval == 0` and not forced -> return.
3. `toCheck` = `params.toCheck` (retry list of `(id, attempt)`) or all apps
   sorted by last check time (respecting `onlyCheckInstalledOrTrackOnlyApps`).
4. Restrictions: Wi-Fi only (wifi or ethernet), charging only -> `canInstall`.
5. Check phase (`_bgRunUpdateCheck`): "checking updates" notification;
   `checkUpdates(specificIds)`; per-app errors: retry while attempt < 4 with a
   one-off task named `retry_<task>_<rand>` after max over apps of
   (rate limit minutes*60 | 15 min for client exceptions, capped at 30 s unless
   rate limit | attempt+1 s), network/charging constraints per settings, input
   `toCheck` with incremented attempts. After 4 attempts non-rate-limit errors
   are reported.
6. For each update: if cannot install now or not silently installable ->
   notify (unless `skipUpdateNotifications`), track-only apps in a separate
   notification; else queue for silent install.
7. Error notifications: one per error group, id = base + 100 + hash.
8. If `canInstall`, `enableBackgroundUpdates` and this is not a retry run: add
   previously known pending updates (installed only) that are silently
   installable.
9. Not a retry run -> install mode: `downloadAndInstallLatestApps(ids, null,
   forceParallelDownloads=true)`; errors -> notification per group (id base +
   200 + hash).
10. Signal foreground instance to reload.

## 10. Download + install pipeline (`providers/apps_provider_install.dart`)

### `downloadApp(app)` -> single APK or directory
1. Progress 0; `NoAPKError` if no URLs; clamp `preferredApkIndex` into range.
2. Source from `app.url` (+ `overrideSource`); merged settings (source config).
3. Chosen entry value split by `\n` (split set); every URL passes
   `generalReqPrefetchModifier` then `assetUrlPrefetchModifier`.
4. File name `<appId>-<hash(downloadUrl)>`; when the source says
   `urlsAlwaysHaveExtension` append the extension of the asset *name* and treat
   the name as complete.
5. Settings for the request: `allowInsecure` (per app), `allowInsecureRedirects`
   (source), `enableCertificatePinning` (global). Headers from
   `getRequestHeaders(forAPKDownload=true)`.
6. Progress with several URLs: `(done*100 + p) / count`; UI/notification only
   when the integer percent changes. After download progress = 90
   ("remaining steps").
7. Kind: `.apk` and single URL -> parse archive info. Otherwise directory
   `<file>-dir`: tarball (by asset name `.tar.gz/.tgz/.tar.bz2/.tar.xz`) ->
   untar; APK with splits -> move base in; anything else -> unzip. Remaining
   split URLs downloaded as `<name>-<i>`, moved in if `.apk`, else unzipped.
8. Candidate APKs = all APK-container files in the dir (recursive); the one whose
   file name starts with the app id goes first; for split sets `base.apk` first.
9. In-archive filter: `tarballedApkFilterRegEx` or `zippedApkFilterRegEx`
   against the path relative to the dir; non-matching files deleted. None left
   -> `NoAPKError`.
10. First APK that parses gives the package info; none -> delete, error
    "couldNotGetIdFromApk".
11. `handleAPKIDChange`: package differs from app id -> if the app exists, id is
    not temporary and `allowIdChange` is false -> `IDChangedError`; else adopt
    the real id (`allowIdChange=false`), rename file to
    `<newId>-<hash>.<ext>`, remove the old record and save the new one.
12. Delete other files starting with `<id>-` in the download dir.
13. Result type: apk / splitApks / xapk / tarball / zip.
14. Always: clear cancellation token, download notification, progress.

### Install
- Installer by mode: stock / shizuku / external / root.
- `canInstallSilently(app)`: false when more than one APK URL (needs manual
  pick); else the installer decides.
- `canInstallSilentlyInBackground(app)`: needs `enableBackgroundUpdates`, not
  `exemptFromBackgroundUpdates`, and `canInstallSilently`.
- Before install — signature verification (`_verifyDownloadedApkSignatures`):
  - hashes of the downloaded APK(s) (union over splits);
  - user list `allowedSigningCertHashes`: if APK hashes unreadable and a list is
    set -> hard block; if any APK hash is not in the list -> hard block (dialog
    without override);
  - else if global `verifySigningCertHashes` and the app is installed and the
    hashes are not all among installed ones -> foreground: dialog with "install
    anyway"; background: blocked;
  - blocked APK files are deleted.
- `installApk(file, additionalAPKs)`:
  - new install in foreground: optionally share the APK to AppVerifier
    (`dev.soupslurpr.appverifier`, `com.roundsalmon4.appverifier`,
    `org.privacyguides.verifiedapps`, `org.privacyguides.verifiedapps.play`)
    when installed and `beforeNewInstallsShareToAppVerifier`; toast with
    instructions.
  - unreadable APK -> delete, "badDownload".
  - downgrade (new versionCode < installed) and
    `com.berdik.letmedowngrade` not installed and `showAppDowngradeError` ->
    delete file, `DowngradeError`.
  - stock in background: record `installedVersion = latestVersion` first
    (result never arrives), fire install, poll the package 20 x 500 ms.
  - stock in foreground: race the installer result against polling the package
    (300 x 1 s); if polling ends unconfirmed while in foreground -> "install
    confirmation error"; overall cap 10 min.
  - error -> delete file, `InstallError(code)`; success -> `installedVersion =
    latestVersion`, delete file; cancelled -> keep file for retry.
- `installApkDir(dir)`: `.obb` files moved to `Android/obb/<id>/`; external
  installer gets the original container (split sets unsupported there);
  otherwise first APK + the rest as additional APKs; directory removed after.
- `shizukuPretendToBeGooglePlay` = global or per-app, passed as install option.
- After a silent background install: notification "updated" /
  "failed to update", or for stock unconfirmed "attempted update". Successful
  install cancels the "updates available" notification.
- `waitForUserToReturnToForeground`: if app went to background before a
  prompting install, show "complete installation" notification and wait up to
  5 min.
- `confirmAppFileUrl(app, pickAnyAsset, evenIfSingleChoice)`: default =
  preferred index; with `pickAnyAsset` pre-select first match of the APK
  filter; picker dialog when several (or forced); if the chosen URL's root host
  differs from the app URL's root host and is not in the source's
  `trustedApkHosts` and `hideAPKOriginWarning` is off -> origin warning dialog.
- `downloadAndInstallLatestApps(ids, interactive, forceParallelDownloads,
  useExisting)`:
  1. per id: refresh first if `needsRefreshBeforeDownload` (failure recorded,
     others continue); non track-only -> confirm file URL and store the picked
     index; track-only -> just mark `installedVersion = latestVersion`.
  2. the app itself is moved to the end of the queue (reference: Obtainium
     ids); ours: this app's own package if tracked.
  3. downloads serial when forced/`parallelDownloads` off, else all in
     parallel; installs are chained one at a time in completion order; self
     update last.
  4. errors collected per app (cancelled downloads are silent), thrown together.
- `downloadAppAssets(ids)`: pick any asset (always shows picker), download to
  `<storage>/Download` with a progress notification and a "downloaded"
  notification; split sets -> one file per URL.
- `uninstallApp(id)`: `ACTION_DELETE package:<id>`.
- Repo rename: `pendingRepoRenameUrl` stored; `acceptRepoRename` switches URL.
- Tarballs: gzip / bzip2 / xz detected by magic bytes, path traversal rejected
  ("invalidArchive").

## 11. Persistence and install-status reconciliation (`apps_provider_lifecycle.dart`)

- One JSON file per app: `<storage>/app_data/<id>.json`, written to a unique
  temp file then renamed. Corrupt JSON -> renamed to `*.corrupt`; other load
  errors keep the file and skip the app.
- `loadApps(singleId?)`: read all, attach installed `PackageInfo` (signing
  certs), source identifier; apps whose source cannot be resolved are removed
  with an "apps removed" notification (transient rate-limit / socket errors
  excluded). Externally uninstalled apps are removed when
  `removeOnExternalUninstall`. Corrected apps saved. Icons loaded afterwards.
- `reconcileInstallStatus(app, installedInfo)`:
  1. not installed but `installedVersion` set and not trackOnly -> null;
     installed but `installedVersion` null -> real installed version.
  2. `reconcileTrackedVersion(...)` (section 5).
  3. installed and `versionDetection` on but detection impossible ->
     `versionDetection = false` saved into the app settings.
- naive detection = per-app `naiveStandardVersionDetection` setting or source
  flag.
- "HTML source without version detection" = source is HTML and
  `versionExtractionRegEx` empty.
- `saveApps(apps, attemptToCorrectInstallStatus=true, onlyIfExists=true,
  reuseInstalledInfo=false)`: when not reusing, app name is replaced by the
  installed app label; reconciles; writes file (only if exists unless
  `onlyIfExists=false`); icon cache `<icons>/<id>.png` (removed when not
  installed); triggers auto export.
- `removeApps(ids)`: delete JSON, cached downloads starting with `<id>-`, icon.
- Remove dialog: when any app is installed and not track-only, two switches —
  "remove from list" (default on) and "uninstall from device" (default off).
- Icon cache is reused unless the package's `lastUpdateTime` is newer.
- `openAppSettings(id)`; `addMissingCategories` assigns a random light colour.

## 12. Update checking (`apps_provider_updates.dart`)

- `fetchUpdate(id)`: null when app missing or has pending repo rename;
  `getApp(..., currentApp)`; if the version changed and the release is younger
  than the min age -> `applyMinAgeSuppression`; keep `preferredApkIndex` if
  still in range else 0.
- TLS handshake failure -> up to 2 retries after 250-750 ms random delay.
- `checkUpdate(id)`: fetch + save; returns the app only when the latest version
  changed.
- `getAppsSortedByUpdateCheckTime(onlyInstalledOrTrackOnly, forceAll)`: apps
  whose `lastUpdateCheck` is null or older than the update interval (all when
  forced), oldest first.
- `checkUpdates(throwErrorsForRetry, specificIds, forceAll)`:
  - one batch at a time (later callers wait, then run their own);
  - progress 0..1 published every 250 ms;
  - 4 concurrent workers; results saved in batches every 3 s;
  - update = latest version changed and `isAppUpdateable`;
  - `RepositoryRenamedError` -> store pending rename (not an error);
  - other errors collected; failed apps still get `lastUpdateCheck = now`;
  - rate-limit / socket errors rethrown immediately when
    `throwErrorsForRetry`;
  - errors -> `CheckUpdatesException(updates, errors)`.
- `findAppIdsWithPendingUpdates(installedOnly, nonInstalledOnly)`: not
  installed, or installed version differs from latest (directly, or under
  `versionExtractionRegEx` first-match comparison) and `isAppUpdateable`.

## 13. Import / export (`apps_provider_import_export.dart`, `app_json_migration.dart`)

- Export JSON (4-space indent): `{schemaVersion: 2, exportedAt: ISO-8601,
  appVersion, apps: [app json...], settings: {prefs...} | null}`.
- `exportSettings`: 0 = no settings, 1 = settings without secrets (keys ending
  `-creds` stripped from prefs **and** from each app's additionalSettings),
  2 = everything.
- `exportInstalledOnly` filters apps.
- File name: `obtainium-export-<ISO time with : replaced by ->[-auto].json`
  (ours: own prefix, but the importer must accept reference files); auto export
  with a custom name writes `<name>.json`; previous `*-auto.json` / custom file
  deleted first. Target is a SAF tree (`exportDir`); picker opens if unset.
- Import accepts: schema object, legacy `{apps:[...], settings}` and the bare
  list. Newer `schemaVersion` -> error. Installed version is re-read from the
  device for each app. Settings applied by value type (int, double, bool, list
  of strings, string). Returns (apps, hadSettings).
- `appIdsInImportJSON(json)` — preview of ids.
- Legacy migrations applied to every stored/imported app (idempotent):
  - defaults for all current form items, then stored values on top;
  - `additionalData` (array by position) -> settings map; `trackOnly`,
    `noVersionDetection` moved in;
  - `versionDetection` string dropdown / `noVersionDetection` /
    `releaseDateAsVersion` -> booleans;
  - `supportFixedAPKURL` true/false -> `defaultPseudoVersioningMethod`
    `partialAPKHash` / `APKLinkHash`;
  - values coerced to the form item type;
  - `preferredApkIndex` null/negative -> 0;
  - `apkUrls` list of strings -> 2D list;
  - `autoApkFilterByArch` missing -> false; `dontSortReleasesList` ->
    `sortMethodChoice = none`;
  - HTML: `sortByFileNamesNotLinks` -> `sortByLastLinkSegment`;
    `intermediateLinkRegex` (+ `intermediateLinkByText`) -> `intermediateLink`
    list; empty intermediate entries dropped; legacy Steam (`app` = steam /
    steam-chat-app), Signal, WhatsApp, VLC sources converted to HTML configs
    (exact values in the reference file);
  - Huawei AppGallery: pseudo versions (`^\d{10,}$`) / `releaseDateAsVersion`
    reset;
  - F-Droid: `https://cloudflare.f-droid.org` -> override FDroid; URL matching
    `^https?://.+/fdroid/([^/]+(/|\?)|[^/]+$)` without override -> FDroidRepo.
  - If migration throws, the raw JSON is parsed as is.

## 14. Installers (`installers/*`, `external_install_bridge.dart`, `MainActivity.kt`)

- Result: success / cancelled / error(code). Platform code 0 = success, 3 or
  null = cancelled (pending), other = error.
- `InstallBaseline(wasInstalled, versionCode, updateTime)`;
  `waitForPackageInstall(id, baseline, attempts, interval=500ms)`: installed
  now and (was not installed | `lastUpdateTime` changed | versionCode changed
  when no update time).
- **Stock**: silent only when: not the app itself; installer of record is this
  app (`getInstallSourceInfo().installingPackageName`); SDK >= 31; installed
  target SDK >= device SDK - 3. Permission = "install unknown apps". Several
  APKs = one session.
  (Ours already has this plus the Play Protect check.)
- **Shizuku**: always silent. Permission states: granted_owner / granted_root /
  granted_adb ok; `services_not_found` -> "shizukuBinderNotFound";
  `old_shizuku`; `old_android_with_adb`; denied -> "cancelled". Install source
  faked as `com.android.vending` when `shizukuPretendToBeGooglePlay`.
- **Root**: `su -c`; check `id -u` == 0; script: `mktemp -d
  /data/local/tmp/<name>.XXXXXX`, trap cleanup, copy APKs as `obt<i>.apk`,
  `uid=$(am get-current-user)` default 0, `pm install -r [-i
  'com.android.vending'] --user "$uid" <files>`. Silent when root available.
- **External**: never silent; hands the original container file to a chosen
  installer app (`ACTION_VIEW` + `EXTRA_RETURN_RESULT`, component set, read
  grant, no NEW_TASK). MIME: apk; xapk/apkm/apks/zip -> `application/zip`;
  tgz/tar.gz -> `application/gzip`; other tarballs -> `application/x-tar`.
  Tracking: result OK -> installed; `RESULT_FIRST_USER` -> failed with
  `android.intent.extra.INSTALL_RESULT`; cancelled only counted after focus
  was lost, then 30 s grace; `PACKAGE_ADDED/REPLACED` for the expected package
  -> installed; hard timeout 5 min. Then verify by package state (60 x 500 ms
  if reported installed, else 2 checks). Target list: activities handling
  `ACTION_VIEW` / `ACTION_INSTALL_PACKAGE` for the APK MIME type, excluding
  self, sorted by label. Split sets from separate URLs unsupported.
- Share intent: `ACTION_SEND text/*` -> first `https?://\S+` (trailing
  `.,;!?)` trimmed) -> treated as "add this URL"; no URL -> toast.
- Deep links scheme `obtainium://` (see main.dart section).

## 15. Notifications (`providers/notifications_provider.dart`)

| notification | id | channel | importance | text |
|---|---|---|---|---|
| complete installation | 1 | COMPLETE_INSTALL | max | "must be open to install apps" |
| updates available | 2 | UPDATES_AVAILABLE | max | one: "X has an update"; many: "X and N more…"; tap with one app opens its page |
| apps updated / not updated (silent) | 3 or hash(id) | APPS_UPDATED | default | "X was (not) updated to Y" |
| checking for updates | 4 | BG_UPDATE_CHECK | min | "N apps" |
| error checking updates | 5 (+100/+200+hash per group) | BG_UPDATE_CHECK_ERROR | high | error text; tap shows a dialog with the full text |
| apps removed | 6 | APPS_REMOVED | max | "X was removed due to error Y" per line |
| track-only updates | 7 | UPDATES_AVAILABLE | max | same wording as updates |
| possibly updated (stock, unconfirmed) | 8 or hash(id) | APPS_POSSIBLY_UPDATED | default | "X may have been updated to Y" |
| downloading X | 100 + hash(key) % 2e9 | APP_DOWNLOADING | low, alert once | progress bar, "received / total", Cancel action (foreground only) |
| downloaded X (asset) | 2000000100 + hash(url) % 140e6 | FILE_DOWNLOADED | default | |

- Progress < 0 -> indeterminate. Notifications grouped per channel.
- Tap payloads: `appIdTap::<id>` opens the app page; otherwise first line =
  dialog title, rest = body.

## 16. App entry, scheduling, deep links (`main.dart`, `pages/home.dart` TBD)

- WorkManager periodic task `obtainiumBgUpdateCheck` every 15 min, network
  connected; cancelled when `updateInterval <= 0`; re-synced when the interval
  changes. Each run only checks apps whose own `lastUpdateCheck` is older than
  the interval (section 9/12). **Ours**: driven by the existing
  `AutoUpdateWorker` and its interval plus a dedicated settings checkbox.
- First run: ask notification permission; reference adds itself as a tracked
  app (settings `versionDetection: true, apkFilterRegEx: 'fdroid',
  invertAPKFilter: true`). Ours: optionally track this app's own GitHub repo.
- Launch from notification handled once.
- 29 UI languages in the reference; ours keeps EN / RU / ZH.

## 17. Sources — one entry each

Format: flags / source config (global) / per-app settings / algorithm.
Each source is ported by re-reading its reference file; this is the checklist.

### 17.1 GitHub (`github.dart`, 992 lines) [x] live-tested
- hosts `github.com`; `appIdInferIsOptional`, `showReleaseDateAsVersionToggle`,
  `allowIncludeZips`, `allowIncludeTarballs`, `canSearch`.
- Source config: `github-creds` (PAT, password field, help URL), `GHReqPrefix`
  (proxy host, hint `gh-proxy.com`, must not contain a scheme),
  `checkRepoRename` (switch, false).
- Per-app: `includePrereleases` (false), `fallbackToOlderReleases` (true),
  `filterReleaseTitlesByRegEx`, `filterReleaseNotesByRegEx`, `verifyLatestTag`
  (false), `sortMethodChoice` dropdown: date (default) / smartname / none /
  smartname-datefallback / name (code default when missing:
  smartname-datefallback), `useLatestAssetDateAsReleaseDate` (false),
  `releaseTitleAsVersion` (false).
- Search setting: `minStarCount` ("0", integer).
- Standard URL: `^https?://(www\.)?github\.com/[^/]+/[^/]+`.
- Headers: `Authorization: Token <pat>` (unless `skipAuth`; token ignored when
  `GHReqPrefix` set; old `user:token` form -> part after `:`); for APK download
  `Accept: application/octet-stream`.
- Request prefix: `https://<GHReqPrefix>/<url without https://>`;
  `undoGHProxyMod` strips it from URLs found in responses.
- API host `https://api.<host>`; repo API = `<api>/repos/<owner>/<repo>`.
- Auth rejection (401/403 with message containing "access token" or "bad
  credentials") -> retry once without token.
- Rename check (when enabled, host is github): GET repo API without following
  redirects; 3xx with Location -> GET it, read `html_url` ->
  `RepositoryRenamedError(old, new)`.
- App id inference: `contents/<path>` for `/app/build.gradle`,
  `android/app/build.gradle`, `src/app/build.gradle`; base64 content; lines
  starting `applicationId "` or `applicationId '`; `${var}` resolved through a
  `def var` line; exactly one distinct result required.
- Releases: `<api>/releases?per_page=100` (fallback `<api>/tags?per_page=100`
  when no releases and trackOnly). With `verifyLatestTag`: also
  `<api>/releases/latest`, prepended if absent, and moved to the newest
  position after sorting.
- Sorting (ascending, then reversed so newest first): `none` = API order;
  `date` = published_at (or `commit.created`), or newest asset `updated_at`
  when `useLatestAssetDateAsReleaseDate`; `name` = alphanumeric compare of
  tag_name ?: name; `smartname` = if both share a loose standard format,
  compare the longest-pattern match alphanumerically; `smartname-datefallback`
  = date when no common format.
- Selection loop (min age pass first, then age 0): stop after the first
  non-skipped unless `fallbackToOlderReleases`; skip prereleases (unless
  included) and drafts (both count as "skipped"); title filter on name (or tag
  when name blank); notes filter on body; too young -> skipped; assets:
  APK-like use `url` (API asset URL) else `browser_download_url`; apply the
  per-app APK filter; no APK left and not trackOnly -> next release.
  Version = release title when `releaseTitleAsVersion` else tag_name ?: name.
  All assets + `<version>.tar.gz` (tarball_url) + `<version>.zip` (zipball_url).
- Names: author = owner, name = repo (rest of path).
- Change log = release body (markdown); change log page `<url>/releases`.
- Errors: `x-ratelimit-remaining: 0` -> RateLimitError(minutes to
  `x-ratelimit-reset`, fallback 3600 s); 401/403 with a JSON message not about
  rate limit -> that message.
- Search: `<api>/search/repositories?q=<q>&per_page=100`, root `items`; result
  `html_url -> [full_name, ("[ARCHIVED] " +) description | "No description"]`,
  filtered by `stargazers_count|stars_count >= minStarCount`.
- Source note when no token: "GitHub rate limit note + add in settings".

### 17.2 GitLab (`gitlab.dart`) [x] live-tested
- hosts `gitlab.com`; `canSearch`, `showReleaseDateAsVersionToggle`.
- Source config: `gitlab-creds` (PAT). Per-app: `fallbackToOlderReleases`.
- Standard URL: cut everything from the first `/-/` segment; regex
  `^https?://(www\.)?<host>/[^/]+(/[^/]+){1,20}` (nested groups).
- Headers: `Referer: https://<host>`.
- PAT (global; per-app only when host overridden) appended to asset URLs and
  API calls as `private_token=<pat>`.
- Project path component = `enc(author)%2Fenc(name)` where author = first path
  segment, name = the rest.
- GET `/api/v4/projects/<p>?<auth>` -> `id` (none -> NoReleases).
- GET `/api/v4/projects/<p>/releases?<auth>&per_page=100` (`repository/tags`
  when trackOnly).
- Per release: assets from `assets.links` where name or URL has an APK
  container extension, or `link_type == package` and name/URL contains the
  token `apk` (`(^|[^a-z])apk([^a-z]|$)`, case-insensitive); name = link name
  ?: last path segment ?: "unknown"; URL = `direct_asset_url ?: url`.
  Plus uploads found in the markdown description: paths starting `/uploads/`
  ending with an APK container extension ->
  `https://<host>/-/project/<id><path>`. Merged by name (uploads win).
  Version = tag_name ?: name. Date = released_at ?: created_at ?:
  commit.created_at. App name = last path segment.
- Min age: prefer eligible releases, else keep all. Take the first; if it has
  no APKs and `fallbackToOlderReleases` (not trackOnly) take the first with
  APKs. No APKs and not trackOnly -> NoAPKError.
- Job artifact URLs `<standardUrl>/-/jobs/<n>/artifacts/file/<x>` -> replace
  `/file/` with `/raw/`.
- Search: `https://<host>/api/v4/projects?search=<q>` -> `https://<host>/
  <path_with_namespace>` -> [name_with_namespace, description].
- Change log page `<url>/-/releases`.

### 17.3 Forgejo / Codeberg (`codeberg.dart`) [x] live-tested
- name "Forgejo (Codeberg)", hosts `codeberg.org`; `canSearch`,
  `includeAdditionalOptsInMainSearch`.
- Source config: `forgejo-creds` (label "Codeberg token" on codeberg.org, else
  "Forgejo token"; legacy per-app `github-creds` honoured).
- Per-app settings: same as GitHub's list. GitHub-only keys `GHReqPrefix`,
  `checkRepoRename` are stripped from requests and from the saved app
  (`postProcessApp`).
- Standard URL: `^https?://(www\.)?<host>/[^/]+/[^/]+`.
- Reuses the GitHub release pipeline with request URL
  `<origin>/api/v1/repos/<owner>/<repo>/(releases|tags)?per_page=100`, no
  GitHub error hook. Headers as GitHub (token).
- Search: instance from query setting `url` (scheme optional, default host),
  `<origin>/api/v1/repos/search?q=<q>&limit=100`, root `data`, min stars
  option; token field persisted only for codeberg.org.
- Change log page `<url>/releases`.

### 17.4 GitHub starred repos — mass import (`githubstars.dart`) [x] written, not yet run
- Arg: user name. Pages `https://api.github.com/users/<u>/starred?
  per_page=100&page=N` until a page has < 100; uses GitHub token / prefix;
  returns `html_url -> [full_name, description]`.

### 17.5 F-Droid (`fdroid.dart`) [x] live-tested
- hosts `f-droid.org`; `naiveStandardVersionDetection`, `canSearch`,
  `inferAppIdFromUrlPath`.
- Per-app: `filterVersionsByRegEx`, `trySelectingSuggestedVersionCode` (true),
  `autoSelectHighestVersionCode` (false).
- Standard URL: `/<lang>/packages/<id>` rewritten to `/packages/<id>`; regex
  `^https?://(www\.)?<host>/+packages/+[^/]+`.
- GET `https://<host>/api/v1/packages/<id>`; APK URL
  `https://<host>/repo/<id>_<versionCode>.apk`.
- Shared parser `getAPKUrlsFromFDroidPackagesAPIResponse`: `packages` list
  (newest first); optional APK filter on the generated file name; suggested
  version code (only when no version filter); version filter picks the first
  `versionName` matching; default first; all releases with that version name;
  several -> `autoSelectHighestVersionCode` takes the first, else suggested;
  `useVersionCodeAsOSVersion` -> version = versionCode. Names: author = source
  name, name = app id.
- Unless host overridden: metadata
  `https://gitlab.com/fdroid/fdroiddata/-/raw/master/metadata/<id>.yml` ->
  `AuthorName: `, `Changelog: ` URL; if it is a GitHub/GitLab `/blob/` link,
  fetch `/raw/` and use the text. Change log truncated to 2048 UTF-16 units +
  `...` (not splitting a surrogate pair).
- Search: `https://search.f-droid.org/?q=<q>`, `.package-header[href]`,
  `.package-name`, `.package-summary`.

### 17.6 IzzyOnDroid (`izzyondroid.dart`) [x] live-tested
- hosts `izzysoft.de`, `allowSubDomains`. Settings = F-Droid's.
- Standard URL: `android.<host>/repo/apk/<id>` or
  `apt.<host>/fdroid/index/apk/<id>`. App id = last path segment.
- GET `https://apt.izzysoft.de/fdroid/api/v1/packages/<id>`; APK prefix
  `https://android.izzysoft.de/frepo/<id>`; shared F-Droid parser.

### 17.7 F-Droid third-party repo (`fdroidrepo.dart`, 553 lines) [x] live-tested
- No hosts, `neverAutoSelect`, `canSearch`,
  `includeAdditionalOptsInMainSearch`, `showReleaseDateAsVersionToggle`.
- Per-app: `appIdOrName` (required, hint "repos have multiple apps"),
  `pickHighestVersionCode` (false), `trySelectingSuggestedVersionCode` (true).
- Standard URL: drop trailing `index.xml` / `index-v2.json`; keep only the
  `appId` query parameter.
- Add-form hook: `?appId=` in the typed URL pre-fills `appIdOrName`.
- `postProcessApp`: real id (or `appId` param) written into the URL query,
  `appIdOrName` and the app id.
- Index: try `index-v2.json` at `<url>`, `<url>/repo`, `<url>/fdroid/repo`
  (JSON: `packages{id: {metadata{name,summary,authorName,changelog localized
  maps}, versions{hash: {file{name}, manifest{versionName, versionCode,
  nativecode}, added, releaseChannels}}}}`); else `index.xml` at the same three
  places (`application[id]`, `package{version, versioncode, apkname,
  nativecode, added}`, `name, summary, author, changelog, marketvercode`).
  Base URL = index URL without the last segment. Versions sorted by
  versionCode desc. Localized string: `en-US`, `en`, else first.
  Timestamps: ms or s.
- Entry lookup: exact id; exact name (case-insensitive); name contains.
- Selection: suggested (`marketvercode`) when enabled; else (v2) drop non-stable
  channels when that leaves something; then highest version code only, or all
  with the newest version name; then keep ABI-compatible (`nativecode` empty or
  intersects device ABIs) if any.
- Result: version name (or code), `<base>/<apkName>`, author ?: source name,
  name, date `added`, change log.
- Search (needs `url` setting): entries whose id / name / summary contain the
  query (all when empty) -> `<base>?appId=<id>`.

### 17.8 HTML (`html.dart`, 492 lines) — catch-all, must stay last [x] live-tested
- No hosts; `suppressStandardVersionExtraction`. Standard URL = input as is.
- Labels of `versionExtractionRegEx` / `matchGroupToUse` differ for this source
  ("version extraction regex", "match group to use").
- Per-app settings, in order:
  1. `intermediateLink` — repeatable sub-form (max 10 levels used): 
     `customLinkFilterRegex` (required, hint `([0-9]+.)*[0-9]+/$`),
     `autoLinkFilterByArch` (false), + common link options.
  2. `customLinkFilterRegex` (hint `download/(.*/)?(android|apk|mobile)`).
  3. common link options: `filterByLinkText`, `matchLinksOutsideATags`,
     `skipSort`, `reverseSort` (label "take first link"),
     `sortByLastLinkSegment`.
  4. `versionExtractWholePage`.
  5. `requestHeader` — repeatable sub-form of `Name: value` strings (validated:
     at least two non-empty `:` parts); default one entry `User-Agent:
     Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko)
     Chrome/114.0.0.0 Mobile Safari/537.36`.
  6. `defaultPseudoVersioningMethod`: partialAPKHash (default) / APKLinkHash /
     ETag.
  7. `zippedApkFilterRegEx`.
- Link grabbing (`grabLinksCommon(body, reqUrl, settings)` -> list of
  (url, text)):
  - `<a href>` with text (or last href segment), made absolute;
  - if none, or `matchLinksOutsideATags`: merge (first wins by URL) — when no
    `<a>` links: all strings of the body parsed as JSON -> URLs by regex
    `(?:(?:http|https|ftp)://)[^\s"'<>()\[\]{}]+` (trailing `.,;:!?` trimmed);
    if none, each string resolved against the request URL first; body not JSON
    -> URLs in raw text. With the option: URLs in raw text + every element
    attribute value that is absolute (http/https/ftp) or starts with `/`.
  - filter: custom regex against URL-decoded link (or link text when
    `filterByLinkText`); without regex — path must look like an APK container;
  - sort ascending by `compareAlphaNumeric` of the URL or of the last non-empty
    segment (`sortByLastLinkSegment`), unless `skipSort`; `reverseSort`
    reverses. The **last** link is taken.
- Flow: follow intermediate levels (each: request, grab with that level's
  settings, optional ABI filter, none -> NoReleases(note = url), take last);
  final page (unless `directAPKLink`): grab, apply APK filter (+invert), none
  -> NoReleases(note). Whole-page string for version extraction = body with
  line breaks replaced by literal `\n`.
- Version: `extractVersion(regex, group, wholePage ? page : decoded link)`;
  else ETag method -> `checkETagHeader` (none -> NoVersion); APKLinkHash ->
  hash of the link string; else partial download hash.
- APK entry name `<hash(link)>-<last path segment or origin>`. Names: author =
  host, name = "App".
- Headers from `requestHeader` list (also for the APK download).
- Note: reference uses Dart `String.hashCode`; ours uses its own stable hash,
  so an imported APKLinkHash app shows one spurious update.

### 17.9 Direct APK link (`direct_apk_link.dart`) [x] live-tested
- Hostless; auto-selected when the URL path ends with an APK container / zip /
  tarball extension. `versionDetectionDisallowed`; excluded common keys:
  versionExtractionRegEx, matchGroupToUse, versionDetection,
  useVersionCodeAsOSVersion, apkFilterRegEx, autoApkFilterByArch.
- Per-app: `requestHeader` sub-form (as HTML), `defaultPseudoVersioningMethod`
  (partialAPKHash / ETag), `zippedApkFilterRegEx`.
- Delegates to HTML with HTML defaults + own values, `directAPKLink = true`,
  `versionDetection = false`.

### 17.10 APKMirror (`apkmirror.dart`, 451 lines) — track-only [x] live-tested
- hosts `apkmirror.com`; `enforceTrackOnly`, `naiveStandardVersionDetection`,
  `showReleaseDateAsVersionToggle`, `inferAppIdEvenWhenTrackOnly`,
  `changeLogIfAnyIsMarkDown = false`.
- Per-app: `fallbackToOlderReleases`, `filterReleaseTitlesByRegEx`.
- Standard URL `/apk/<dev>/<app>`. Names: author = dev slug, name = app slug.
- UA: `APKUpdater-v3.5.9 <OurApp>/<version>` (allow-listed token required for
  HTML pages; RSS is exempt).
- GET `<url>/feed/` (RSS): `item` list; date = first 5 words of `pubDate` +
  " GMT" (HTTP date); title filter; min-age skip with fallback to the youngest;
  release URL = `<link>` of the Nth item taken from the **raw XML**.
- Version from title: strip trailing " by X", `(...)`, `[...]`; token
  `(?:^|\s)v?(\d[\d.\-+_]*)(?=\s|$)`; else cleaned title; else title.
- Change log: release page, first heading starting "What's new in ", following
  siblings until one starting "About " / "Download " or containing
  " screenshots" / " trailer"; lists as `- item`; noise lines dropped
  (advertisement, "verified safe to install…", "scroll to available
  downloads", "a more recent upload may be available below!").
- App id: listing page `og:image` / `twitter:image` file name, last
  `_`-separated segment that looks like a package and has no "apkmirror".
- Download size (for display): release page text `File size: N (B|KB|MB|GB)`,
  else the first `-apk-download/` page under the release URL.
- Change log page `<url>/#whatsnew`. No APK URLs ever.

### 17.11 APKPure (`apkpure.dart`) [x] live-tested
- hosts `apkpure.net`, `apkpure.com`; `allowSubDomains`,
  `naiveStandardVersionDetection`, `showReleaseDateAsVersionToggle`,
  `inferAppIdFromUrlPath`.
- Per-app: `fallbackToOlderReleases`, `stayOneVersionBehind` (false),
  `useFirstApkOfVersion` (true).
- Standard URL: `m.` host -> main host; `(/xx)?/<slug>/<id>`.
- Headers (not for APK download): `Ual-Access-Businessid: projecta`,
  `Ual-Access-ProjectA: {"device_info":{"os_ver":"<sdk>"}}`.
- GET `https://tapi.pureapk.com/v3/get_app_his_version?package_name=<id>&hl=en`
  -> `version_list`, grouped by `version_name` in order.
- Loop versions: skip first when `stayOneVersionBehind`; min-age skip (keep
  youngest as fallback); per version: variants -> name
  `<pkg>-<versionCode>[-<archs>].<type lower>`, URL `asset.url`; ABI filter by
  `native_code` (universal / unlimited = any) when `autoApkFilterByArch`;
  dedupe by name; first only when `useFirstApkOfVersion`. Version
  `version_name`, author `developer`, name `title`, date `update_date`, change
  log `whatsnew`. Errors fall through to older versions only with
  `fallbackToOlderReleases`.

### 17.12 Aptoide (`aptoide.dart`) [x] live-tested
- hosts `aptoide.com`; `allowSubDomains`, `naiveStandardVersionDetection`,
  `showReleaseDateAsVersionToggle`. Standard URL `https?://<sub>.aptoide.com`.
- Page -> `"app"\s*:\s*\{\s*"id"\s*:\s*([0-9]+)` ->
  `https://ws2.aptoide.com/api/7/getApp/app_id/<id>` -> `nodes.meta.data`:
  `name`, `developer.name`, `updated`, `file.vername`, `file.path`, `package`
  (app id).

### 17.13 Uptodown (`uptodown.dart`, 365 lines) [x] live-tested
- hosts `uptodown.com`; `allowSubDomains`, `naiveStandardVersionDetection`,
  `showReleaseDateAsVersionToggle`, `urlsAlwaysHaveExtension`, `canSearch`.
- Standard URL: language subdomain `.xx.uptodown.` -> `.en.uptodown.`; then
  `<host>/android/download`.
- Page: `div.version`, `#detail-app-name` (name, `data-file-id`, `data-code`),
  `#author-link`, `#technical-information tr` (th label -> last td):
  "package name", "date", "file type"; positional fallback (last, -5, -4);
  `#detail-download-button[data-file-id|data-app-id]`.
- APK entry: name `<pkg>.<ext or apk>`, URL `<standardUrl>/<fileId>-x`.
- Download resolution (`assetUrlPrefetchModifier`): open that page, read app
  id + file id, then API on `www.uptodown.app`:
  - auth `POST /eapi/auth/token?identifier=<16 hex>` form body `identifier,
    id_plataforma=13, lang=en, unixtime, hmac` where hmac = HMAC-SHA256(key =
    UTF-8 bytes of the literal string
    `MDGMXUMdvHJBG/vjdFgmqX6LUdy7ecfwvYNd0gyfOCs=`, msg = unixtime) hex; reply
    `token` (JWT, `exp` claim); cached until exp - 60 s;
  - `GET /eapi/apps/<appId>/file/<fileId>/downloadUrl` with `Authorization:
    Bearer`; 401 -> re-auth once; reply `success == 1`, `data.downloadURL`.
  - API headers: `User-Agent: Dalvik/2.1.0 (Linux; U; Android 16; Pixel 8 Pro
    Build/BP4A.260205.001)`, `Identificador: Uptodown_Android`,
    `Identificador-Version: 739`; form content type on the auth call.
  - APK download header: the same User-Agent.
- Date formats `MMM dd, yyyy` then `MMMM dd, yyyy` (English).
- Search: `POST https://en.uptodown.com/android/en/s` form `queryString=<q>`;
  `data.apps[]` with `platformURL == "/android"`: `url`, `name` (tags
  stripped), `author`.

### 17.14 SourceHut (`sourcehut.dart`) [x] live-tested
- hosts `git.sr.ht`; `changeLogPageIsStandardUrl`,
  `showReleaseDateAsVersionToggle`. Per-app: `fallbackToOlderReleases`.
- Standard URL `/<~user>/<repo>` (trailing `/refs` dropped at fetch).
- GET `<url>/refs/rss.xml`, first 6 `item`s whose `guid` starts with
  `<url>/refs`; version = title; date `EEE, dd MMM yyyy HH:mm:ss Z`; each
  release page: `a[href]` ending in APK container -> absolute. Author =
  `author` ?: repo; name = repo. Min-age preference; with fallback keep only
  releases that have APKs (unless trackOnly).

### 17.15 itch.io (`itchio.dart`, 449 lines) [x] live-tested
- hosts `itch.io`, `allowSubDomains`, `appIdInferIsOptional`.
- Standard URL `https?://<sub>.itch.io/<game>` (sub `[a-z0-9-]+`).
- Headers: internal `extraHeaders` map from the settings of a request.
- Page: title = `<title>` before " by "; author = `span.on_follow
  span.full_label` "Follow X" else subdomain; version = highest of
  `[vV](\d+\.\d+(?:\.\d+)*)` / `Version (\d+\.\d+(?:\.\d+)*)` inside
  `div.page_widget` (numeric component compare); date version = newest
  `abbr[title]` in format `dd MMMM yyyy '@' HH:mm 'UTC'` -> `YYYYMMDD`;
  fallback version `latest`.
- Uploads: `div.upload` -> name `div.upload_name strong.name[title]`, id
  `a.download_btn[data-upload_id]`, Android when
  `span.download_platforms span.icon-android` present.
- No upload buttons ("name your price"): CSRF token
  (`name="csrf_token" value="…"` or `csrf_token":"…"`) + `set-cookie`; `POST
  <base>/download_url` (JSON `{csrf_token}`, headers `X-Requested-With:
  XMLHttpRequest`, `Cookie`) -> `{url}` -> GET with cookie -> download page.
- Per Android upload: APK entry (label, `<base>/download/<id>`); label = real
  file name when resolvable: `POST <base>/file/<id>?as_props=1&
  source=game_download` (JSON `{csrf_token}`, headers X-Requested-With,
  `Referer: <base>/download/<id>`, Cookie) -> `{url}`; GET it with `Referer:
  <base>?download`, read `content-disposition` `filename="?([^";]+)"?`.
- Download (`assetUrlPrefetchModifier`): upload id = last URL segment -> fresh
  token/cookies -> same file API -> direct URL (fallback: asset URL).

### 17.16 Huawei AppGallery (`huaweiappgallery.dart`, 373 lines) [x] live-tested
- hosts `appgallery.huawei.com`, `appgallery.cloud.huawei.com`,
  `appgallery.huawei.ru`; `trustedApkHosts` `dbankcloud.com`, `dbankcloud.ru`;
  `canSearch`. Standard URL `(/#)?/(app|appdl)/<id>`.
- Store API `POST https://<host>/hwmarket/api/clientApi`, form body with keys
  sorted, spaces as `+`; headers UA `HiSpace##16.5.1.301##google##Pixel 8 Pro`,
  `Accept: application/json`, form content type.
- Hosts: CN `store-drcn.hispace.dbankcloud.com`, Asia
  `store-dra.hispace.dbankcloud.com`, EU `store-dre.hispace.dbankcloud.com`,
  RU `store-drru.hispace.dbankcloud.ru`; zone table (DR2 country list) in the
  reference file.
- Common params: `ver=1.1, locale=en_US, serviceType=0, ts=<ms>, net=1,
  brand=google, manufacturer=Google, subBrand=0, deviceId=<64 hex>,
  deviceIdType=9`.
- Session: `client.front2` on the EU host (`version=16.5.1,
  versionCode=160501301, packageName=com.huawei.appmarket, zone=1,
  phoneType=Pixel 8 Pro, firmwareVersion=16, isFirstLaunch=1, oobe=0,
  needServiceZone=1`) -> `serviceZone`, `sign`; if no sign (or refresh) repeat
  on the zone host with `needServiceZone=0`. Cached in memory and in prefs
  (`huaweiAppGallery-session` = `host|sign|deviceId|createdMs`), valid 24 h.
- Details: `client.appDetailById` (`sign`, `id`) -> `rtnCode == 0`,
  `detailInfo[0]`: `versionName`, `url`, `package`, `name`, `developer`,
  `releaseDate`. Non-zero code -> refresh session once. Failure ->
  "huaweiAppGalleryApiError". APK name `<package or id>.apk`.
- Search: `client.getTabDetail` (`uri=searchApp|<q>`, `maxResults=25`,
  `reqPageNum=1`, `isSupportPage=1`) -> `layoutData[].dataList[]`
  (`appInfo` or item): `appid|appId`, `name`, `package` ->
  `https://<host0>/app/<id>` -> [name, package].

### 17.17 RuStore (`rustore.dart`, 385 lines) [x] live-tested
- hosts `rustore.ru`; `naiveStandardVersionDetection`,
  `showReleaseDateAsVersionToggle`, `changeLogIfAnyIsMarkDown = false`,
  `inferAppIdFromUrlPath`, `canSearch`. Standard URL `/catalog/app/<pkg>`.
- Endpoints: info `https://backapi.rustore.ru/applicationData/overallInfo/<pkg>`,
  search `https://backapi.rustore.ru/applicationData/apps?query=&pageNumber=0&
  pageSize=20`, download `POST https://backapi.rustore.ru/v3/showcase/apps/
  download-link`, nonce `POST https://api.rustore.ru/v1/secure/nonce`.
- Device headers on every request: `deviceId` (`<16 hex>-<suffix>`, suffix =
  Java-hash combination of "Google", "Pixel 8 Pro", "husky", "husky" — formula
  in the reference), `firmwareVer: 16`, `androidSdkVer: 36`,
  `deviceManufacturerName: Google`, `deviceModelName: Pixel 8 Pro`,
  `deviceModel: Google Pixel 8 Pro`, `firmwareLang: ru`,
  `ruStoreVerCode: 1105002`, `deviceType: mobile|TV`, `User-Agent: RuStore/
  1.105.0.2 (Android 16; SDK 36; arm64-v8a; Google Pixel 8 Pro; ru)`, and for
  info/download calls `X-Client-Signature`.
- Signature: nonce (base64) from the nonce endpoint; signature =
  base64(HMAC-SHA256(key = base64 `K+eeiCbnVFnZ71KEVal0g5siHaX6v6drh8upeLgEPoU=`,
  msg = nonce bytes || base64 `Zh8ggo73gN4LebxZ8mowhkMWNV8w5Pkc+hSiB5GDmRQ=`)).
  Session cached in memory; HTTP 419 -> new session, retry once.
- Info body: `appId`, `appName`, `companyName`, `appVerUpdatedAt`,
  `versionName`, `whatsNew`, `aggregatorInfo`.
- Download body JSON: `{appId, firstInstall: true, withoutSplits: false,
  supportedAbis, sdkVersion, screenDensity}` (no redirects) ->
  `downloadUrls[].url`. One URL: `.zip` -> `.apk`. Several: one entry, URLs
  joined with `\n` (split set). None: `aggregatorInfo` present ->
  RUSTORE_AGGREGATED_APP else NoAPK.
- Body charset auto-detected (fallback UTF-8).
- TLS: Mintsifry root trusted for this host (section 6).
- Search -> `https://rustore.ru/catalog/app/<packageName>` -> [appName, pkg].

### 17.18 Tencent App Store (`tencent.dart`) [x] live-tested
- hosts `sj.qq.com`; naive detection, release-date toggle, id from URL path.
  Standard URL `/appdetail/<pkg>`.
- GET `https://a.app.qq.com/o/simple.jsp?pkgname=<pkg>` (no redirects); line
  starting `window.systemData=` -> JSON `.appDetail`: `versionName`,
  `apkUrl64` ?: `apkUrl`, `appName`, `author`. APK name = `fsname` query param
  ?: `<pkg>_<version>.apk`.

### 17.19 vivo App Store (`vivoappstore.dart`) [x] live-tested
- hosts `h5.appstore.vivo.com.cn`, `h5coml.vivo.com.cn`,
  `detail-browser.vivo.com.cn`; naive detection, `canSearch`,
  `allowOverride = false`, `allowInsecureRedirects`.
- Standard URL `https://detail-browser.vivo.com.cn/v115/index.html?appId=<id>`
  (id from `appId` query param, `/#` removed first).
- Details `https://h5-api.appstore.vivo.com.cn/detailInfo?appId=<id>`: `id`,
  `version_name`, `version_code`, `package_name`, `title_zh`, `developer`,
  `upload_time`. APK `https://appstore.vivo.com.cn/appinfo/downloadApkFile?
  id=<id>`, name `<pkg>_<versionCode>.apk`.
- Search `https://h5-api.appstore.vivo.com.cn/h5appstore/search/result-list?
  app_version=2100&page_index=1&apps_per_page=20&target=local&cfrom=2&key=<q>`
  -> `data.appSearchResponse.value[]` (`id`, `title_zh`, `developer`).

### 17.20 Farsroid (`farsroid.dart`) [x] live-tested
- hosts `farsroid.com`. Per-app: `useFirstApkOfVersion` (true),
  `releaseTitleAsVersion` (false). Standard URL `<sub>.farsroid.com/<slug>`
  (one subdomain required). Author = source name, name = slug.
- Page `.download-links[data-post-id][data-post-version]` ->
  `https://farsroid.com/api/download-box/?post_id=&post_version=` ->
  `data.content` (HTML) -> HTML link grabbing with `skipSort` -> name = last
  path segment; APK filter; ABI filter; first only; `releaseTitleAsVersion`
  -> version = the single file name (else NoVersion).

### 17.21 Samsung Galaxy Store (`samsunggalaxystore.dart`) [x] live-tested
- hosts `galaxystore.samsung.com`, `apps.samsung.com`, `apps.samsung.cn`,
  `galaxyappstore.com`, `apps.galaxyappstore.com`;
  `showReleaseDateAsVersionToggle`. Per-app: `deviceId` (label "device model",
  hint `SM-S948B`), `csc` (hint `DBT`).
- Standard URL `https://apps.galaxyappstore.com/detail/<pkg>` (pkg from
  `appId` query or last path segment). App id = pkg.
- GET `https://vas.samsungapps.com/stub/stubDownload.as?appId=&deviceId=&
  mcc=425&mnc=01&csc=&sdkVer=<device sdk>&systemId=1608665720954&abiType=64&
  extuk=0191d6627f38685f` (XML by regex): `resultCode` must be 1 (else
  `resultMsg`), `downloadURI` CDATA, `versionName`, `productName`. Date from
  14-17 digit timestamp in the APK file name. APK name `<pkg>.apk`.

### 17.22 LiteAPKs (`liteapks.dart`) [x] live-tested
- hosts `liteapks.com`. Standard URL `/<slug>`.
- `<origin>/wp-json/wp/v2/posts?slug=<slug before first dot>` -> `[0].id` ->
  `<origin>/wp-json/v2/posts/<id>` -> `data.title`, `data.publisher`,
  `data.versions[0].version`, `.version_downloads[].version_download_link`.
  APK entry: decoded file name -> `<link>#<standardUrl>`.
- Headers: `Referer: <part after last #>`.
- Download: `<link>?token=<t>#…` where t = base64(base64(unix seconds + 3 h))
  with `=` -> `%3D`.

### 17.23 Apk4Free (`apk4free.dart`) [x] live-tested
- hosts `apk4free.net`. Standard URL `/<slug>/?`.
- Title `h1.main-box-title` cleaned (brackets, APK/MOD/XAPK/HACK words, mod
  descriptors in parentheses, trailing version, separators); version
  `div.version` else `v?(\d+(\.\d+)+)` in the title, else in link text / URL.
- Download page = first `a.downloadAPK` (else any `a`) whose href contains
  `/download/`; there: `a.downloadAPK` (text -> href), else all APK-like links.

### 17.24 CoolApk (`coolapk.dart`) [x] live-tested
- hosts `coolapk.com`; `allowSubDomains`, naive detection,
  `allowOverride = false`, id from URL path. Standard URL `/apk/<pkg>`.
- `https://api2.coolapk.com/v6/apk/detail?id=<pkg>` -> `data`:
  `apkversionname`, `title`, `developername`, `changelog`, `lastupdate` (s),
  `id` (aid); `status == -2` or no data -> NoReleases.
- APK URL = `Location` of `…/v6/apk/download?pn=<pkg>&aid=<aid>` (no
  redirects). Name `<pkg>_<version>.apk`.
- Headers on every request: fixed UA (CoolMarket/12.4.2-2208241-universal),
  `X-App-Id: com.coolapk.market`, `X-Requested-With: XMLHttpRequest`,
  `X-Sdk-Int: 30`, `X-App-Mode: universal`, `X-App-Channel: coolapk`,
  `X-Sdk-Locale: zh-CN`, `X-App-Version: 12.4.2`, `X-Api-Supported: 2208241`,
  `X-App-Code: 2208241`, `X-Api-Version: 12`, `X-Dark-Mode: 0`,
  `X-App-Device: <deviceCode>`, `X-App-Token: <token>`.
- Token: deviceCode = base64("<16 random bytes HEX>; ; ; <random mac>; Google;
  Google; Pixel 5a; SQ1D.220105.007"); ts = unix seconds; token string =
  `token://com.coolapk.market/dcf01e569c1e3db93a3d0fcf191a622c?<md5(ts)>$
  <md5(deviceCode)>&com.coolapk.market`; salt = `$2a$10$` + first 14 chars of
  base64(ts) + `/` + first 6 chars of md5(token) + `u`; bcrypt(md5(base64(
  token)), salt); replace prefix with `$2y`; final = `v2` + base64(result).
  **Needs a bcrypt implementation** (own small one or a library).

### 17.25 SourceForge (`sourceforge.dart`) [x] live-tested
- hosts `sourceforge.net`; `suppressStandardVersionExtraction`.
- Standard URL: `/p/<proj>…` -> `/projects/<proj>`; bare project -> `+/files`;
  final `/projects/<proj>/files(/…)?`.
- `<origin>/projects/<proj>/rss?path=/` -> `guid`s starting with the standard
  URL and ending `/download` after an APK container name. Version = path after
  the standard URL minus the file name and (if more than one segment left) one
  more segment; then own `extractVersion` (failure -> skip). Take the first
  version; all links with it. Name = project.

### 17.26 Jenkins (`jenkins.dart`) [x] live-tested
- Hostless, `neverAutoSelect`, `versionDetectionDisallowed`,
  `showReleaseDateAsVersionToggle`, `changeLogPageIsStandardUrl`.
- Standard URL `https?://<host>/job/<name>`.
- `<url>/lastSuccessfulBuild/api/json`: `number` (version), `timestamp` (ms),
  `artifacts[]` (`fileName`, `relativePath`) -> `<url>/lastSuccessfulBuild/
  artifact/<relativePath>` for APK containers. Author = host, name = job.

### 17.27 APKCombo (`apkcombo.dart`) [x] live-tested
- hosts `apkcombo.com`; release-date toggle, id from URL path. Standard URL
  `/<slug>/<pkg>`.
- Headers: `User-Agent: curl/8.0.1`, `Accept: */*`, `Connection: keep-alive`
  (no Host header).
- Page: `div.version`, `div.app_name`, `div.author`,
  `div.information-table > .item > div.value` [1] = date `MMM d, yyyy`.
- `<url>/download/apk`: `#variants-tab > div > ul > li`; arch = `code` text
  (`,` removed, `:` and space -> `-`); first `a` whose href (unwrapped from
  `/r2?u=`) path is an APK container; `.info .header .vercode`; name
  `<arch>-<vercode>.apk`.
- Download: re-scrape and match by URL path (signed URLs expire); no match ->
  NoAPK.

### 17.28 RockMods (`rockmods.dart`) — track-only [x] live-tested
- hosts `rockmods.net`; `enforceTrackOnly`, naive detection, id from path.
  Standard URL `/apps/<slug>`.
- `<script type="application/ld+json">` with `@type == SoftwareApplication`:
  `name`, `softwareVersion`, `author.name`; name fallback `h1`.

### 17.29 Telegram app (`telegramapp.dart`) [x] live-tested
- hosts `telegram.org`; standard URL always `https://telegram.org`.
- `https://t.me/s/TAndroidAPK`: last
  `.tgme_widget_message_text.js-message_text`, first line, first word =
  version. APK `https://telegram.org/dl/android/apk`, name
  `telegram-<version>.apk`. Names Telegram / Telegram.

### 17.30 NeutronCode (`neutroncode.dart`) [x] live-tested
- hosts `neutroncode.com`; release-date toggle, `changeLogPageIsStandardUrl`.
  Standard URL `/downloads/file/<name>`.
- Page: `.pd-title`, `.pd-filename .pd-float` (file name), `.pd-version-txt`
  next sibling (version), `.pd-date-txt` next sibling (date "D Month YYYY" in
  any order), `.pd-fdesc p` last = change log. APK
  `https://neutroncode.com/download/<filename>`.

## 18. Source capability matrix (for the UI)

- Search-capable: GitHub, GitLab, Codeberg, F-Droid, F-Droid repo (needs URL),
  Uptodown, Huawei AppGallery, RuStore, vivo.
- Extra options shown in the main search: Codeberg, F-Droid repo.
- Override not allowed: vivo, CoolApk. Never auto-selected: F-Droid repo,
  Jenkins.
- Track-only enforced: APKMirror, RockMods.
- Source-level settings (global): GitHub (`github-creds`, `GHReqPrefix`,
  `checkRepoRename`), GitLab (`gitlab-creds`), Codeberg (`forgejo-creds`).
- Needs HTML parsing with CSS selectors: F-Droid search, F-Droid repo v1,
  HTML, APKMirror, Uptodown, SourceHut, itch.io, Farsroid, Apk4Free,
  SourceForge, APKCombo, RockMods, Telegram, NeutronCode -> add **jsoup**.
- Needs crypto: Uptodown (HMAC-SHA256), RuStore (HMAC-SHA256), CoolApk (MD5 +
  bcrypt), temp ids (SHA-256) — all in the JDK except bcrypt.

## 19. Form model and logs

- Form item kinds: text field (`required`, `max` lines, `hint`, `password`,
  autocomplete options, `helpUrl`, validators), dropdown (options key -> label,
  `helpUrl`), switch (`disabled`), slider (stops key -> label), sub-form
  (repeatable list of rows; value = list of maps). Defaults: switch false,
  sub-form [], others "". `ensureType` coerces stored values.
  -> ours: one Kotlin model (`SettingItem`) + one generic renderer for sheets.
- Logs: SQLite table `logs(_id, level 0..3 debug/info/warning/error, message,
  timestamp ms)` with timestamp index; purge > 7 days at start; query / delete
  by date range. Log page in the UI (section 20).
- Category colour: random hue (golden angle), HPLuv(h, 100, 70).

## 20. User-facing functions (reference screens -> our tab)

### 20.1 App list (`pages/apps.dart`, `components/app_list_tile.dart`) [ ]
- Header actions: settings (ours: the existing settings sheet). Pull to refresh
  = `checkUpdates(forceAll)`; thin progress bar while checking; errors shown as
  the grouped multi-error text.
- `checkOnStart`: refresh once when the app is opened.
- Search field (300 ms debounce, filters by name tokens), filter button +
  "clear filter" button when a filter is active.
- Filter sheet: app name, author, app id (substring), "show up-to-date apps"
  (default on), "show non-installed apps" (default on), source dropdown
  (none + all sources), category chips. Name/author split into space-separated
  tokens, all must match case-insensitively. Up-to-date also covers installed
  apps whose "update" is a hidden downgrade.
- Sort (settings): added (insertion order) / name+author / author+name /
  release date (nulls last); asc / desc; stable.
- Reorder: apps with updates first (`pinUpdates`), not installed last
  (`buryNonInstalled`); then pending-repo-rename apps, then pinned, then rest.
- Grouping (settings): none / category (an app appears in each of its
  categories; uncategorised last as "No category") / source ("No source");
  groups sorted case-insensitively, collapsible, optionally all collapsed at
  start (`collapseGroupsOnStartup`).
- Update banner ("Install/update apps" + Update button) when at least 2
  pending items among listed (or selected) apps; modes all / updatesOnly /
  none (`actionBannerMode`). Bulk dialog lists three sections — updates, not
  installed, track-only — with checkboxes, select all / deselect N; defaults:
  updates + track-only (new installs only when there are no updates); skipped
  entirely with `skipBulkUpdateConfirmation`.
- Row: icon (dimmed when not installed; double-tap opens the app; long-press
  opens the alternate detail view), name, "by author", pinned = bold + tinted,
  category colour stripes at the left edge, repo-renamed note; right side:
  update button (download icon, or check mark for track-only = mark updated),
  version text `installed → latest` or installed or "Not installed" (italic
  when pseudo-versioned, primary colour when updatable), second line = release
  date `yyyy-MM-dd` or "Changes" link; tap on it opens the change log.
- During download: progress ring + size text + cancel; during install:
  indeterminate.
- Swipe right = install / update; swipe left = remove (dialog); can be turned
  off (`disableSwipeActions`). Long press = multi-select.
- Density: standard / compact / dense (`appListDensity`).
- Selection actions sheet: remove; categorize (warns when selected apps have
  different categories); pin / unpin; install/update selected; share app URLs
  (one per line); share app config links
  (`https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/<urlencoded
  JSON {id,url,author,name,preferredApkIndex,additionalSettings(string),
  overrideSource}>`); share export JSON file (`…-count-N.json`, no settings);
  download release asset; mark selected as updated (only affects apps without
  version detection).
- Empty states: "No apps" / "No apps for filter".
- Change log: if the stored change log is just a URL -> open it; else dialog
  with latest version, link (release URL or source change-log page) and the
  text (markdown for most sources, plain for APKMirror / RuStore). Relative
  links resolved against the app URL origin.
- FAB: "Add" (or "Actions" while selecting). Ours: the bottom button.

### 20.2 Welcome and deep links (`pages/home.dart`) [ ]
- First-run notes (welcome + link to docs; Google developer-verification
  warning with link to keepandroidopen.org). Ours: one short note for the tab.
- Deep links `obtainium://<action>/<data>` (data = `?url=` or the path):
  - `add/<url>`: existing app with that (standardised) URL -> open it, else
    open the add screen pre-filled;
  - `app/<json>` and `apps/<json array>`: confirmation dialog with the raw
    JSON, then import;
  - `refresh[?id=<id>]`: check all or one app.
  Ours: keep the `obtainium://` scheme only if it does not clash with the
  reference app being installed — decision below (D3).
- Shared text containing a URL -> add screen pre-filled.
- Two-pane layout on wide screens (>= 840 dp): list + detail. Ours: the Fold's
  inner screen could use it later; not required for parity of function.

### 20.3 App detail (`pages/app.dart`, `components/app_detail_widgets.dart`) [ ]
- Header: icon, name, "by author"; categories editor; optional embedded web
  page of the source URL instead of the info view (`showAppWebpage`; long-press
  on the icon in the list opens the opposite view; non-http(s)/ftp navigation
  blocked; load error + retry).
- Repo renamed card: explanation, new URL, buttons Dismiss (clears pending
  rename) / Update URL (`acceptRepoRename`).
- Version block: notes "app is track-only", "pseudo-version in use";
  `<installed> installed[ / latest]` or "Not installed", plus `<latest> latest`
  when different; APK name (one) or "N APKs"; release date (tap = change log)
  or "Changes" link; "Last update check: <time | never>".
- "About" section: markdown from the per-app `about` setting.
- Source block: URL (tap opens, long-press copies), app id; certificate
  hashes of the installed app ("(multiple signers)"), long-press copies;
  "Download release asset" button.
- Primary button: Install / Update, or for track-only Mark installed / Mark
  updated; shows the probed download size (sum for split sets) under the
  label; disabled when up to date, updating or downloads running. During
  download: percent + sizes + cancel; then "Installing".
- Secondary actions: additional options (edit per-app settings, full-screen
  form, saved on back); system app settings (if installed); "more" info dialog
  (when web view is shown); open release page; mark updated (installed,
  differs from latest, no standard version detection, not track-only; asks
  "already up to date?"); reset install status (installed == latest and no
  standard detection or track-only); remove.
- Saving additional options: keeps keys absent from the form; enforces
  trackOnly for such sources (toast "apps from this source are track-only");
  turning `releaseDateAsVersion` on rewrites latest (and installed if it was
  up to date) to the date pseudo-version; turning it off restores the OS
  version name; turning `versionDetection` on also clears
  `releaseDateAsVersion`, then re-checks and sets installed = latest
  (`resetVersion`).
- Opening the page refreshes the app when `checkUpdateOnDetailPage`; pull to
  refresh re-checks this app.
- APK picker dialog ("Pick an APK" / "Select release asset"): radio list of
  names, note with device ABIs. Origin warning dialog: source URL vs APK URL,
  confirm. Signing certificate mismatch dialog: expected vs actual hashes,
  hard block (OK only) or "install anyway".

### 20.4 Add app (`pages/add_app.dart`) [ ]
- URL field ("App source URL") with live validation: typing resolves the
  source; unsupported URL shows the error; Add button enabled when the URL is
  valid (or an override is chosen) and required fields are filled.
- "Override source" dropdown (None + sources with `allowOverride`, plus the
  detected one) — for self-hosted instances. With an override the source's
  global config items (token etc.) are appended to the per-app form.
- When the source changes, the per-app form is rebuilt with defaults +
  `runOnAddAppInputChange(url)` values; defaults forced from global settings:
  `includePrereleases` (when `includePrereleasesByDefault`),
  `shizukuPretendToBeGooglePlay`.
- Source note under the field (e.g. GitHub rate-limit hint).
- Form "Additional options for <source>" (all per-app settings), category
  picker, switch "Try inferring app ID from source code" (sources with
  optional inference, default on), custom "App ID" field for enforced
  track-only sources (validated as a package name).
- Add flow: track-only confirmation dialog (with "don't show again" ->
  `hideTrackOnlyWarning`), release-date-as-version explanation dialog,
  `getApp(...)`; if the id is still temporary and the app is not track-only:
  pick APK, **download it now** to learn the real package name; duplicate id
  -> error "App already added: name (id)"; track-only or version detection
  off -> installed = latest; save; open the detail page.
- Search (when the URL field is empty and no source picked): query field ->
  dialog to pick searchable sources (remembered via `searchDeselected`) ->
  per-source options dialog for sources with
  `includeAdditionalOptsInMainSearch` (their query settings + instance URL
  with autocomplete from already-added apps of that source) -> results from
  all sources interleaved round-robin -> single-select list (title = name,
  subtitle = description) -> fills the URL and sets the override to that
  source. No results -> "No results".
- "Supported sources" dialog: chips with every source (+ "(track-only)",
  "(searchable)"), tap opens the site; note about self-hosted instances.
- Link to crowdsourced configs `https://apps.obtainium.imranr.dev/`.
- Entry points: FAB, shared text, deep link (pre-filled URL).

### 20.5 Import / export (`pages/import_export.dart`) [ ]
- Import from export file: file picker -> must be JSON -> if any app id
  already exists, confirm "N apps will be overwritten" -> import (apps +
  settings) -> add missing categories -> message "Imported N apps[ +
  settings]".
- Import from URL list: multi-line text (validated per line: "Line N:
  <error>"), "import from URLs in a file" (extracts `https?://[^\s"]+`,
  de-duplicated, keeps only URLs some source accepts), then
  `addAppsByURL`; note that imported apps may get wrong ids until installed
  ("importedAppsIdDisclaimer"); errors dialog lists URL -> error.
- Mass sources ("Import GitHub starred repos"): ask args (user name) -> fetch
  -> multi-select list with filter, select all / deselect N -> add -> errors
  dialog.
- Export: pick export directory; export now (confirmation when secrets are
  included); switch "auto export on changes"; custom auto-export file name;
  switch "installed apps only"; dropdown "include settings": none / exclude
  secrets / all. Message "Exported to <path>".
- Selection dialog (shared by search results, mass import, source picking):
  filter field, entries with title + description lines, optional single
  selection, optional links.

### 20.6 Settings (`pages/settings.dart`) [ ]
Reference sections and items (ours: a "Sources" settings screen opened from the
existing settings sheet; look-and-feel items of the Flutter app are not ported):
- **Updates**: background check interval slider (ours: existing interval +
  new checkbox "check sources in background"); "enable background silent
  installs" with two explanation texts; "only on Wi-Fi"; "only while
  charging"; "run background check now"; certificate pinning; check on start;
  check on opening the detail page; only check installed or track-only apps;
  global APK filter regex; remove app when uninstalled externally; include
  prereleases by default; show downgrade error; hide downgrades; skip bulk
  update confirmation; minimum update age slider (none, 1, 2, 3, 5, 7, 14, 30
  days); parallel downloads; share new installs to AppVerifier (+ link);
  verify signing certificate hashes (+ help text); install method (system /
  Shizuku / external / root) with Shizuku and root permission checks and the
  external-installer chooser; "Shizuku: pretend to be Google Play".
- **Source-specific**: every source's `sourceConfigSettingFormItems` (GitHub
  token + request prefix + rename check, GitLab token, Codeberg token), each
  with a help link.
- **Appearance** (list behaviour only): sort by, sort order, show web page in
  app view, pin updates, move non-installed to bottom, group by, don't show
  track-only warnings, collapse groups on startup, don't show APK origin
  warnings, disable swipe actions, list density, action banner mode.
  Not ported: theme, colour, black theme, system font, language, highlight
  touch targets, phone layout, haptics.
- **Categories**: manager (create, rename, recolour, delete; deleting removes
  the category from apps).
- **Import/Export** (20.5). **App logs** (20.7). Links: source code, wiki.

### 20.7 Logs (`pages/logs.dart`) [ ]
- List of entries (time, level, message), filter by last N days, copy all,
  share, clear (with confirmation), empty state.

## 21. Reference test suites to port as JVM unit tests

`test/version_normalization_test.dart`, `version_reconciliation_test.dart`,
`min_update_age_test.dart`, `multi_apk_url_test.dart`, `apkmirror_test.dart`,
`codeberg_search_test.dart`, `rustore_apk_urls_test.dart`,
`external_install_bridge_test.dart`, `icon_cache_test.dart`. Plus our own live
smoke tests: one known app per source fetched from the real site on the PC
(source code is kept free of Android classes so it runs on the JVM).

## 22. Decisions

| # | Topic | Chosen default | Status |
|---|---|---|---|
| D1 | Licence. The reference is GPL-3.0; a one-to-one port (logic, regexes, constants, texts) is a derivative work, so the app has to be distributed under GPL-3.0 once this tab ships. | Switch `LICENSE` to GPL-3.0 before release; reuse the reference EN/RU/ZH texts. | **needs the owner's yes** |
| D2 | Install methods. Reference: system / Shizuku / external installer / root, used for foreground and background. Ours today: Standard / Shizuku for background, stock for manual. | Sources tab follows the reference: the chosen method is used for its installs in foreground too; External and Root added as methods for this tab. | default, can be changed |
| D3 | Deep links `obtainium://add|app|apps|refresh`. | Register the scheme so config links from the crowdsourced site work (Android shows a chooser if the reference app is also installed). | default |
| D4 | Tracking this app itself on first run. | Offer once, do not add silently. | default |
| D5 | UI languages. | EN / RU / ZH only. | fixed by project |
| D6 | TV layout, two-pane, theme/colour/font/language/haptics settings. | Not ported — this app has its own design. | fixed by project |
| D7 | Background schedule. | No separate 15-minute task: sources are checked by `AutoUpdateWorker` on the existing interval when the new checkbox is on; per-app "due" logic uses the same interval. Retry tasks kept. | fixed by request |
| D8 | Storage location. | App-private `files/sources/app_data/<id>.json`, same JSON as the reference so exports are interchangeable; downloads in external cache. | default |

## 23. Architecture in this project

Package `io.github.bl3xand.apkcloner.sources`:

| package | content | Android-free |
|---|---|---|
| `sources.model` | `TrackedApp`, `ApkDetails`, `AppNames`, JSON codec, legacy migrations | yes |
| `sources.core` | errors, version service + normalization, APK filter, URL utils, min-age, alphanumeric compare, cert-hash utils | yes |
| `sources.net` | HTTP client (redirect rules, pinning, insecure mode), downloader with resume | yes |
| `sources.form` | setting item model, defaults, validation | yes |
| `sources.source` | `AppSource` base + 29 sources + `GitHubStars`, registry | yes (through a `Platform` interface: ABIs, SDK, app version, density, prefs) |
| `sources.data` | repository (load / save / remove, reconcile with installed packages), import / export, logs DB, settings | no |
| `sources.install` | download-and-install orchestration, signature checks, external and root installers, OBB | no |
| `sources.work` | background check / install, retries, notifications | no |
| `sources.ui` | tab list, detail, add, search, form renderer, settings screen, import / export, categories, logs, dialogs | no |

New dependencies: `org.jsoup:jsoup` (HTML), `org.tukaani:xz` and
`org.apache.commons:commons-compress` (tar / bzip2 / xz), a bcrypt
implementation (CoolApk token), a small markdown renderer for change logs;
tests: `junit`, `org.json:json` (real JSON on the JVM).
New permissions: `INTERNET`, `ACCESS_NETWORK_STATE` (network / Wi-Fi checks),
`RECEIVE_BOOT_COMPLETED` is not needed (WorkManager).

## 24. Implementation phases

- [x] P1 Core, Android-free: model + JSON, errors, versions, filters, URL
      utils, min-age, form model; reference unit tests ported and green.
- [x] P2 Network: HTTP client, pinning, downloader (resume, concurrent
      `.part`, retries), partial hash, ETag.
- [x] P3 Sources batch A: GitHub, GitLab, Codeberg, F-Droid, IzzyOnDroid,
      F-Droid repo, HTML, Direct link, GitHub stars + live smoke tests.
- [x] P4 Sources batch B: SourceHut, APKPure, Aptoide, Uptodown, itch.io,
      Huawei, Tencent, vivo, RuStore.
- [x] P5 Sources batch C: Farsroid, Samsung, LiteAPKs, Apk4Free, CoolApk,
      SourceForge, Jenkins, APKMirror, APKCombo, RockMods, Telegram,
      NeutronCode.
- [x] P6 Data layer: repository, reconcile, settings, categories, logs,
      import / export, migrations.
- [x] P7 Download + install pipeline, signature checks, installers
      (external, root), OBB, asset download.
- [x] P8 Background: worker integration, retries, restrictions,
      notifications.
- [x] P9 UI: tab + list (search, filter, sort, group, banner, swipe,
      selection actions).
- [x] P10 UI: add app, search, override, generic form, track-only dialogs.
- [x] P11 UI: detail, change log, APK picker, warnings, additional options.
- [x] P12 UI: sources settings, source-specific settings, categories,
      import / export, logs.
- [x] P13 Deep links, share target, strings EN / RU / ZH.
- [x] P14 R8 rules, release build, device tests per function, clean-up.

## 25. What changed after the port: one app, not two

The tab was first built screen for screen after the reference app, then
reworked so that it reads as a part of APK Toolbox. Decisions taken on the way:

- **One schedule, one installer.** The tab has no update interval, Wi-Fi or
  charging rules or install method of its own. `AppSettings` holds them for
  tracked apps and clones alike, and `Installer.choose` is the only place
  that picks how anything is installed: root when switched on and granted,
  the system installer for what the user starts by hand, otherwise the method
  from settings. Play Protect holds back only clones, which are re-signed.
- **Waiting instead of skipping.** With "Wait for Wi-Fi" or "Wait for the
  charger" an update found at the wrong moment is installed by a one-off job
  once the condition holds.
- **A manual check reports everything.** "Check now" names every update that
  is waiting, not only those found by this run, and says "No updates" only
  when there is nothing anywhere.
- **Settings left out of the UI.** Theme, font and language; list density;
  swipe gestures and the switch for them; the long press on an icon; config
  share links and the crowdsourced-config button; the in-app web page of a
  source; checking on start and on opening an app; "only check installed";
  removing an entry when the app is uninstalled elsewhere; auto-export;
  exporting settings; AppVerifier hand-off; the welcome screen. Their stored
  values keep the reference defaults, so imported lists behave the same.
- **One sheet for everything.** Questions, menus and dialogs go through
  `showSheet`: a title, the content, up to two buttons of a fixed size, and
  the card of the app on top where a sheet concerns one app. Per-app options
  are grouped (`OptionGroups`) and every one can carry a short title, a
  description and a field hint from our own texts (`optTitle.*`, `opt.*`,
  `optHint.*`).
- **Spacing by what is seen.** `Spacing` holds the few measures the screens
  are built from; a divider is placed by the visible space around it
  (`addDivider`, `addHeading`), with the insets of buttons, fields and rows
  measured on a device.
- **Texts.** The reference texts are shipped as they are; `own_<lang>.json`
  overrides and adds. `TextsTest` keeps the three languages in step and
  checks that every text the code asks for exists.

