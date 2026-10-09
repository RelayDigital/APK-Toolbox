package io.github.bl3xand.apkcloner.sources.ui

import io.github.bl3xand.apkcloner.sources.data.AppEntry

/**
 * An app being added whose package is taken by a differently-signed build. It gets a page of its
 * own, but is tracked only once that build is removed: until then the list - and whatever was
 * tracked under this package before - stays exactly as it was.
 */
class AppCandidate(val entry: AppEntry, val apkHashes: Set<String>)
