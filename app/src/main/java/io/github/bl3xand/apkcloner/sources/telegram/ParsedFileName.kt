package io.github.bl3xand.apkcloner.sources.telegram

/**
 * A file name read the way a release is named.
 *
 * [family] is what every release of the same thing has in common, whatever its version: the name
 * of the app, and after a bar the words that set one build of it apart from another (a clone, a
 * beta, an architecture). [title] says the same for a person to read, [name] is the app alone,
 * and [version] is null when the name carries none.
 */
class ParsedFileName(val family: String, val title: String, val name: String, val version: String?)
