package io.github.bl3xand.apkcloner.sources.source

import io.github.bl3xand.apkcloner.sources.core.SourceEnv
import io.github.bl3xand.apkcloner.sources.core.SourceError
import io.github.bl3xand.apkcloner.sources.core.Tr
import io.github.bl3xand.apkcloner.sources.core.Url
import io.github.bl3xand.apkcloner.sources.core.rethrowOrWrap
import io.github.bl3xand.apkcloner.sources.form.SettingItem
import io.github.bl3xand.apkcloner.sources.form.TextItem
import io.github.bl3xand.apkcloner.sources.model.ApkDetails
import io.github.bl3xand.apkcloner.sources.model.TrackedApp

/** Forgejo instances, codeberg.org by default. Releases follow GitHub's API shape. */
class Codeberg : AppSource("Codeberg") {
    private val gh = GitHub(hostChanged = true)

    init {
        fixedName = "Forgejo (Codeberg)"
        hosts = listOf(DEFAULT_HOST)
        canSearch = true
        includeAdditionalOptsInMainSearch = true
    }

    override val sourceConfigSettingFormItems: List<SettingItem>
        get() = listOf(tokenFormItem(isDefaultHost(hosts.firstOrNull())))

    override val additionalSourceAppSpecificSettingFormItems: List<List<SettingItem>>
        get() = gh.additionalSourceAppSpecificSettingFormItems

    private fun tokenFormItem(defaultHost: Boolean, value: String = "") = TextItem(
        TOKEN_KEY, if (defaultHost) "codebergTokenLabel" else "forgejoTokenLabel",
        password = true, required = false, value = value,
        helpUrl = "https://forgejo.org/docs/latest/user/api-usage/#authentication",
    )

    /**
     * Settings for a delegated request: GitHub-only keys removed and the effective token placed
     * where the GitHub header code looks for it.
     */
    private fun requestSettings(settings: Map<String, Any?>): Map<String, Any?> {
        val cleaned = LinkedHashMap(settings)
        githubOnlySettingKeys.forEach { cleaned.remove(it) }
        var token = getSourceConfigValues(cleaned)[TOKEN_KEY]
        // Older data stored the token under GitHub's key.
        if (token.isNullOrEmpty()) token = cleaned["github-creds"] as? String
        if (!token.isNullOrEmpty()) cleaned["github-creds"] = token
        return cleaned
    }

    override fun getRequestHeaders(
        additionalSettings: Map<String, Any?>,
        url: String,
        forAPKDownload: Boolean,
    ): Map<String, String>? = gh.getRequestHeaders(requestSettings(additionalSettings), url, forAPKDownload)

    override val searchQuerySettingFormItems: List<SettingItem>
        get() = searchQuerySettingItemsForUrl(hosts.firstOrNull() ?: DEFAULT_HOST)

    override fun searchQuerySettingItemsForUrl(url: String): List<SettingItem> {
        val defaultHost = isDefaultHost(tryHost(url))
        val savedToken = SourceEnv.settings.getString(TOKEN_KEY) ?: ""
        return listOf(tokenFormItem(defaultHost, if (defaultHost) savedToken else "")) + gh.searchQuerySettingFormItems
    }

    override fun sourceSpecificStandardizeURL(url: String, forSelection: Boolean): String =
        standardizeUrlWithRegex(url, subdomainPrefix = "(www\\.)?", pathPattern = "/[^/]+/[^/]+")

    override fun changeLogPageFromStandardUrl(standardUrl: String): String = "$standardUrl/releases"

    override fun postProcessApp(app: TrackedApp): TrackedApp {
        if (app.additionalSettings.keys.none { it in githubOnlySettingKeys }) return app
        return app.copy(additionalSettings = app.additionalSettings.filterKeys { it !in githubOnlySettingKeys })
    }

    override fun getLatestAPKDetails(standardUrl: String, additionalSettings: Map<String, Any?>): ApkDetails = try {
        gh.fetchReleaseDetailsWithTagFallback(
            standardUrl,
            requestSettings(additionalSettings),
            { useTagUrl ->
                val standardUri = Url.parse(standardUrl)
                standardUri
                    .withPath("/api/v1/repos${standardUri.path}/${if (useTagUrl) "tags" else "releases"}")
                    .withQueryParameters(mapOf("per_page" to "100"))
                    .toString()
            },
            null,
        )
    } catch (e: Throwable) {
        rethrowOrWrap(e)
    }

    override fun search(query: String, querySettings: Map<String, Any?>): Map<String, List<String>> {
        val origin = searchUriFor(querySettings["url"] as? String, hosts.firstOrNull() ?: DEFAULT_HOST)
        val defaultHost = isDefaultHost(origin.host)
        val fieldProvided = querySettings.containsKey(TOKEN_KEY)
        val savedToken = SourceEnv.settings.getString(TOKEN_KEY)
        val enteredToken = (querySettings[TOKEN_KEY] as? String) ?: ""
        val token = if (defaultHost) (if (fieldProvided) enteredToken else (savedToken ?: "")) else enteredToken
        // A token entered for codeberg.org is kept; one for another instance is used once.
        if (defaultHost && fieldProvided && enteredToken != (savedToken ?: "")) {
            SourceEnv.settings.setString(TOKEN_KEY, enteredToken)
        }
        val requestSettings = if (token.isNotEmpty()) mapOf("github-creds" to token) else emptyMap()
        return gh.searchCommon(
            "${origin.origin}/api/v1/repos/search?q=${Url.encodeQueryComponent(query)}&limit=100",
            "data",
            querySettings = querySettings,
            additionalSettings = requestSettings,
        )
    }

    companion object {
        private const val DEFAULT_HOST = "codeberg.org"
        private const val TOKEN_KEY = "forgejo-creds"

        /** Settings inherited from the GitHub implementation that mean nothing here. */
        private val githubOnlySettingKeys = listOf("GHReqPrefix", "checkRepoRename")

        private fun isDefaultHost(host: String?): Boolean = host == DEFAULT_HOST || host == "www.$DEFAULT_HOST"

        /** The instance to search: a host without a scheme is accepted, a path is ignored. */
        fun searchUriFor(configuredUrl: String?, fallbackHost: String = DEFAULT_HOST): Url {
            var raw = configuredUrl?.trim() ?: ""
            if (raw.isEmpty()) raw = fallbackHost
            if (!raw.lowercase().startsWith("http://") && !raw.lowercase().startsWith("https://")) {
                raw = "https://$raw"
            }
            val uri = Url.tryParse(raw)
            if (uri == null || (uri.scheme != "http" && uri.scheme != "https") || uri.host.isEmpty()) {
                throw SourceError(Tr.get("invalidInput"))
            }
            return uri
        }

        private fun tryHost(url: String): String? = try {
            searchUriFor(url).host
        } catch (e: Exception) {
            null
        }
    }
}
