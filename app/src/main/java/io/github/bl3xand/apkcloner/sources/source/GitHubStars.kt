package io.github.bl3xand.apkcloner.sources.source

import io.github.bl3xand.apkcloner.sources.core.SourceError
import io.github.bl3xand.apkcloner.sources.core.Tr
import io.github.bl3xand.apkcloner.sources.model.JsonValues
import io.github.bl3xand.apkcloner.sources.model.asList
import io.github.bl3xand.apkcloner.sources.model.dig
import io.github.bl3xand.apkcloner.sources.net.Http

/** Bulk import: every repository a GitHub user has starred. */
class GitHubStars : MassAppUrlSource {
    override val name: String get() = Tr.get("githubStarredRepos")
    override val requiredArgs: List<String> get() = listOf(Tr.get("uname"))

    private val gh = GitHub()

    private fun onePage(username: String, page: Int): Map<String, List<String>> {
        val sourceConfig = gh.getSourceConfigValues(emptyMap())
        val res = gh.sourceRequest(
            "https://api.github.com/users/$username/starred?per_page=100&page=$page", sourceConfig,
        )
        if (res.statusCode != 200) {
            gh.rateLimitErrorCheck(res)
            throw Http.errorFor(res)
        }
        val results = LinkedHashMap<String, List<String>>()
        for (repo in JsonValues.parse(res.body).asList() ?: emptyList()) {
            var htmlUrl = repo.dig("html_url") as String
            if ((sourceConfig["GHReqPrefix"] ?: "").isNotEmpty()) htmlUrl = gh.undoGHProxyMod(htmlUrl, sourceConfig)
            results[htmlUrl] = listOf(
                repo.dig("full_name") as String,
                (repo.dig("description") as? String) ?: Tr.get("noDescription"),
            )
        }
        return results
    }

    override fun getUrlsWithDescriptions(args: List<String>): Map<String, List<String>> {
        if (args.size != requiredArgs.size) throw SourceError(Tr.get("wrongArgNum"))
        val results = LinkedHashMap<String, List<String>>()
        var page = 1
        while (true) {
            val pageUrls = onePage(args[0], page++)
            results.putAll(pageUrls)
            if (pageUrls.size < 100) break
        }
        return results
    }
}
