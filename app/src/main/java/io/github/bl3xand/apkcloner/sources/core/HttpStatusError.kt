package io.github.bl3xand.apkcloner.sources.core

/** A non-2xx response during a file download; the status decides whether a retry makes sense. */
class HttpStatusError(val statusCode: Int, message: String) : SourceError(message, code = "HTTP_ERROR")
