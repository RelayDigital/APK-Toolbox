package io.github.bl3xand.apkcloner.sources.source

/**
 * Something the user has to settle before an app can be tracked, because the source offers
 * several things side by side. The chosen [Option.value] is stored as the app's [settingKey].
 *
 * With [chosen] set the user has said which one already - by the link that was given, say - and
 * is not asked again.
 */
class TrackingChoice(val settingKey: String, val title: String, val options: List<Option>, val chosen: String? = null) {
    class Option(val value: String, val label: String, val description: String)
}
