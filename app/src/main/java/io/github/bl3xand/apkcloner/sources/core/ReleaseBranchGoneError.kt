package io.github.bl3xand.apkcloner.sources.core

/**
 * The kind of file an app was set to follow is not offered by the source any more. Caught at
 * check time, where it only marks the app; reaching the user directly (add, search) it reads as
 * the reason the source has nothing to say about that app.
 */
class ReleaseBranchGoneError : SourceError(code = "RELEASE_BRANCH_GONE")