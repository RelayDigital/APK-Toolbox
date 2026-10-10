package io.github.bl3xand.apkcloner.sources.ui

/** What a tracked app can be picked by in the filter. An app can be of several kinds at once. */
enum class AppKind(val label: String) {
    UPDATE_AVAILABLE("fltUpdateAvailable"),
    NOT_INSTALLED("fltNotInstalled"),
    NOT_UPDATED("fltNoUpdates"),
    ORIGINAL("fltOriginals"),
    CLONE("fltClones"),
}
