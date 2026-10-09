package io.github.bl3xand.apkcloner.sources.core

class RateLimitError(val remainingMinutes: Int) :
    SourceError(code = "RATE_LIMIT", data = mapOf("remainingMinutes" to remainingMinutes))
