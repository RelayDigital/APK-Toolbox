package io.github.bl3xand.apkcloner.sources.core

class RepositoryRenamedError(val oldUrl: String, val newUrl: String) :
    SourceError(code = "REPO_RENAMED", data = mapOf("oldUrl" to oldUrl, "newUrl" to newUrl))
