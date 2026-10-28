package io.github.bl3xand.apkcloner.ui.apps

import java.io.File

sealed interface CloneState {
    data object Idle : CloneState
    /** [percent] of the build's pre-counted steps done; the bar fills instead of wandering. */
    data class Running(val percent: Int) : CloneState
    data class Done(val apks: List<File>) : CloneState
    data class Failed(val message: String) : CloneState
}