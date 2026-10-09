package io.github.bl3xand.apkcloner.ui

import java.io.File

sealed interface CloneState {
    data object Idle : CloneState
    data class Running(val file: String, val index: Int, val total: Int) : CloneState
    data class Done(val apks: List<File>) : CloneState
    data class Failed(val message: String) : CloneState
}
