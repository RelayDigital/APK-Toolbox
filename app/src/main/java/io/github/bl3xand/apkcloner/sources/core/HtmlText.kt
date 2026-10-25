package io.github.bl3xand.apkcloner.sources.core

private val LINE_BREAK = Regex("<\\s*(br\\s*/?|/p|/div|/li|/h[1-6])\\s*>", RegexOption.IGNORE_CASE)
private val LIST_ITEM = Regex("<\\s*li[^>]*>", RegexOption.IGNORE_CASE)
private val TAG = Regex("<[^>]+>")
private val NUMERIC_ENTITY = Regex("&#(x?)([0-9a-fA-F]+);")
private val SPACES_BEFORE_BREAK = Regex("[ \\t\\u00A0]+\\n")
private val EMPTY_LINES = Regex("\\n{3,}")
private val ENTITIES = mapOf("&nbsp;" to " ", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&apos;" to "'", "&amp;" to "&")

/**
 * The text of release notes that a store hands out as a piece of a web page: line breaks for the
 * tags that end a line, a bullet for a list item, no other tags, entities as the characters they
 * stand for. Notes that are plain text already come back as they were.
 */
fun htmlToText(html: String): String {
    var text = html.replace(LINE_BREAK, "\n").replace(LIST_ITEM, "• ").replace(TAG, "")
    text = NUMERIC_ENTITY.replace(text) { match ->
        val code = match.groupValues[2].toIntOrNull(if (match.groupValues[1].isEmpty()) 10 else 16)
        code?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) } ?: match.value
    }
    // The ampersand last, so that "&amp;lt;" stays the "&lt;" it was written as.
    ENTITIES.forEach { (entity, char) -> text = text.replace(entity, char) }
    return text.replace("\r", "").replace(SPACES_BEFORE_BREAK, "\n").replace(EMPTY_LINES, "\n\n").trim()
}
