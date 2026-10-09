package io.github.bl3xand.apkcloner.ui

import androidx.annotation.StringRes
import io.github.bl3xand.apkcloner.data.ApkSource

sealed interface MainEvent {
    data class SourceReady(val source: ApkSource) : MainEvent
    data object SplitSourceReady : MainEvent
    data object FilesReceived : MainEvent
    data class Message(@StringRes val text: Int, val argument: String = "") : MainEvent
}
