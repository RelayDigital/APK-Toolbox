package io.github.bl3xand.apkcloner.ui

import android.text.Html
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.widget.TextView

/**
 * Enough of Markdown for release notes: headings, emphasis, code, links and lists. Anything
 * else is shown as written.
 */
fun markdownToSpanned(markdown: String, baseUrl: String? = null): CharSequence {
    fun inline(text: String): String {
        var result = TextUtils.htmlEncode(text)
        result = Regex("!\\[([^\\]]*)\\]\\(([^)\\s]+)[^)]*\\)").replace(result) { it.groupValues[1] }
        result = Regex("\\[([^\\]]+)\\]\\(([^)\\s]+)[^)]*\\)").replace(result) { match ->
            var href = match.groupValues[2]
            if (!href.startsWith("http://") && !href.startsWith("https://") && baseUrl != null) href = "$baseUrl/$href"
            "<a href=\"$href\">${match.groupValues[1]}</a>"
        }
        result = Regex("(?<![\"=>/\\w])(https?://[^\\s<)]+)").replace(result) { "<a href=\"${it.value}\">${it.value}</a>" }
        result = Regex("\\*\\*(.+?)\\*\\*|__(.+?)__").replace(result) { "<b>${it.groupValues[1] + it.groupValues[2]}</b>" }
        result = Regex("(?<![\\w*])\\*([^*\\s][^*]*)\\*(?![\\w*])").replace(result) { "<i>${it.groupValues[1]}</i>" }
        result = Regex("`([^`]+)`").replace(result) { "<tt>${it.groupValues[1]}</tt>" }
        return result
    }
    val html = StringBuilder()
    for (rawLine in markdown.replace("\r\n", "\n").split('\n')) {
        val line = rawLine.trimEnd()
        val heading = Regex("^(#{1,6})\\s+(.*)$").find(line)
        val bullet = Regex("^(\\s*)[-*+]\\s+(.*)$").find(line)
        when {
            heading != null -> html.append("<b>${inline(heading.groupValues[2])}</b><br>")
            bullet != null -> html.append("${"&nbsp;&nbsp;".repeat(bullet.groupValues[1].length / 2)}• ${inline(bullet.groupValues[2])}<br>")
            line.isBlank() -> html.append("<br>")
            else -> html.append(inline(line)).append("<br>")
        }
    }
    return Html.fromHtml(html.toString().removeSuffix("<br>"), Html.FROM_HTML_MODE_COMPACT)
}

fun TextView.enableLinks() {
    movementMethod = LinkMovementMethod.getInstance()
}
