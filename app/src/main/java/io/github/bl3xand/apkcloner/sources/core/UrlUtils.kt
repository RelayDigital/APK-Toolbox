package io.github.bl3xand.apkcloner.sources.core

/** Makes a typed URL well-formed: adds https:// and collapses duplicate slashes in the path. */
fun preStandardizeUrl(input: String): String {
    var url = input
    val firstDot = url.indexOf('.')
    if (!(firstDot >= 0 && firstDot != url.length - 1) && !url.contains('[')) {
        throw UnsupportedUrlError()
    }
    if (!url.lowercase().startsWith("http://") && !url.lowercase().startsWith("https://")) {
        url = "https://$url"
    }
    val uri = Url.tryParse(url)
    val trailingSlash = ((uri?.path?.endsWith("/") ?: false) ||
        ((uri?.path?.isEmpty() ?: false) && url.endsWith("/"))) &&
        (uri?.queryParameters?.isEmpty() ?: false)

    // Only the scheme/host/path part is normalised; slashes in a query or fragment stay.
    var splitIndex = url.length
    val queryStart = url.indexOf('?')
    if (queryStart in 0 until splitIndex) splitIndex = queryStart
    val fragmentStart = url.indexOf('#')
    if (fragmentStart in 0 until splitIndex) splitIndex = fragmentStart
    var mainPart = url.substring(0, splitIndex)
    val rest = url.substring(splitIndex)
    mainPart = mainPart.split('/').filter { it.isNotEmpty() }.joinToString("/").replaceFirst(":/", "://")
    return mainPart + (if (trailingSlash) "/" else "") + rest
}

/** A regex alternation of host names with the dots escaped. */
fun sourceRegex(hosts: List<String>): String = "(${hosts.joinToString("|").replace(".", "\\.")})"

fun capitalizeFirst(s: String): String = if (s.isEmpty()) s else s[0].uppercase() + s.substring(1)
