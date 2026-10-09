package io.github.bl3xand.apkcloner.ui

import io.github.bl3xand.apkcloner.data.ApkSource
import io.github.bl3xand.apkcloner.data.CloneInfo
import kotlinx.coroutines.flow.update

data class MainUiState(
    val hasFileAccess: Boolean = false,
    val canInstall: Boolean = false,
    val loading: Boolean = false,
    val apps: List<ApkSource> = emptyList(),
    val clones: List<CloneInfo> = emptyList(),
    /** Installed apps that consist of more than one APK. */
    val splitApps: List<ApkSource> = emptyList(),
    /** Package of the clone currently being rebuilt for an update. */
    val updatingClone: String? = null,
    /** Package of the clone currently being uninstalled (its card shows its own progress). */
    val uninstallingClone: String? = null,
    /** How many installed clones are behind their original, whatever the search shows. */
    val outdatedClones: Int = 0,
) {
    val permissionsGranted: Boolean get() = hasFileAccess && canInstall
}
