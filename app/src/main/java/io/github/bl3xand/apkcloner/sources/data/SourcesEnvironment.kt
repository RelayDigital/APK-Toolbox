package io.github.bl3xand.apkcloner.sources.data

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.icu.text.PluralRules
import android.os.Build
import io.github.bl3xand.apkcloner.BuildConfig
import io.github.bl3xand.apkcloner.sources.core.Platform
import io.github.bl3xand.apkcloner.sources.core.SourceEnv
import io.github.bl3xand.apkcloner.sources.core.Tr
import java.util.Locale
import org.json.JSONObject

/** Connects the Android-free sources code to the device: settings, device facts and texts. */
object SourcesEnvironment {
    private const val REFERENCE_NAME = "Obtainium"
    private const val OWN_NAME = "APK Toolbox"

    @Volatile
    private var loadedLanguage: String? = null
    private var texts: JSONObject = JSONObject()
    private var fallback: JSONObject = JSONObject()

    /** Texts the reference does not have: our own additions and a few it forgot. */
    private var own: JSONObject = JSONObject()

    fun init(context: Context) {
        val app = context.applicationContext
        SourceEnv.settings = SourcesSettings.get(app)
        SourceEnv.platform = object : Platform {
            override val supportedAbis: List<String> get() = Build.SUPPORTED_ABIS.toList()
            override val sdkInt: Int get() = Build.VERSION.SDK_INT
            override val appVersionName: String get() = BuildConfig.VERSION_NAME
            override val screenDensityDpi: Int get() = app.resources.displayMetrics.densityDpi
            override val isTv: Boolean
                get() = app.getSystemService(UiModeManager::class.java)?.currentModeType ==
                    Configuration.UI_MODE_TYPE_TELEVISION
        }
        loadTexts(app)
        Tr.resolver = { key, args -> fill(lookup(key) as? String ?: key, args) }
        Tr.pluralResolver = { key, count, args -> plural(key, count, args) }
    }

    /** The texts ship as the reference's JSON catalogues: English, Russian and Chinese. */
    private fun loadTexts(context: Context) {
        val language = when (context.resources.configuration.locales[0].language) {
            "ru" -> "ru"
            "zh" -> "zh"
            else -> "en"
        }
        if (language == loadedLanguage) return
        fun read(name: String) = JSONObject(
            context.assets.open("sources/i18n/$name.json").bufferedReader().use { it.readText() },
        )
        fallback = read("en")
        texts = if (language == "en") fallback else read(language)
        own = read("own_$language")
        loadedLanguage = language
        Tr.languageCode = language
    }

    /** Re-reads the catalogue after a locale change. */
    fun refresh(context: Context) = loadTexts(context.applicationContext)

    private fun lookup(key: String): Any? = own.opt(key) ?: texts.opt(key) ?: fallback.opt(key)

    private fun fill(template: String, args: List<String>): String {
        var result = template.replace(REFERENCE_NAME, OWN_NAME)
        for (arg in args) result = result.replaceFirst("{}", arg)
        return result
    }

    private fun plural(key: String, count: Int, args: List<String>): String {
        val forms = lookup(key) as? JSONObject ?: return fill(lookup(key) as? String ?: key, args)
        val locale = Locale.forLanguageTag(loadedLanguage ?: "en")
        val category = when {
            count == 0 && forms.has("zero") -> "zero"
            else -> PluralRules.forLocale(locale).select(count.toDouble())
        }
        val template = forms.optString(category).ifEmpty { forms.optString("other") }
        return fill(template, args.ifEmpty { listOf(count.toString()) })
    }
}
