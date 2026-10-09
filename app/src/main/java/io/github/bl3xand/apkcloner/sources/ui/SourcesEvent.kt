package io.github.bl3xand.apkcloner.sources.ui

sealed interface SourcesEvent {
    data class Error(val error: Any) : SourcesEvent
    data class Message(val text: String) : SourcesEvent
    data class OpenApp(val id: String) : SourcesEvent
}
