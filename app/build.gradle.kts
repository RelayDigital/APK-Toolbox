import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Kept out of git (see .gitignore) - absent on any machine that hasn't set up a release key,
// in which case release builds just come out unsigned instead of failing the whole build.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

// The app's own Telegram API id and hash (my.telegram.org). Kept out of git like the release
// key; without the file the app still builds, and Telegram channels are simply not available.
val telegramPropertiesFile = rootProject.file("telegram.properties")
val telegramProperties = Properties().apply {
    if (telegramPropertiesFile.exists()) {
        telegramPropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "io.github.bl3xand.apkcloner"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.bl3xand.apkcloner"
        // Android 8.0: adaptive icons and notification channels, which the app counts on.
        minSdk = 26
        targetSdk = 36
        versionCode = 13
        versionName = "1.1.5"

        buildConfigField("int", "TELEGRAM_API_ID", (telegramProperties.getProperty("apiId")?.trim()?.toIntOrNull() ?: 0).toString())
        buildConfigField("String", "TELEGRAM_API_HASH", "\"${telegramProperties.getProperty("apiHash")?.trim().orEmpty()}\"")

    }

    // TDLib is native code, a couple of dozen megabytes for each kind of processor. The
    // universal APK carries all of them and installs anywhere; next to it there is a smaller
    // APK for each kind, for whoever knows which one their device needs.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        aidl = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            // Tests that reach real sites only run on request: -Plive=true [-Plive.only=<Source>].
            it.systemProperty("live", providers.gradleProperty("live").getOrElse("false"))
            it.systemProperty("live.only", providers.gradleProperty("live.only").getOrElse(""))
            it.systemProperty("live.query", providers.gradleProperty("live.query").getOrElse(""))
            // A real APK for the desktop dex-patch repros (the Xiaomi write failure): -Pxiaomi.apk=...
            it.systemProperty("xiaomi.apk", providers.gradleProperty("xiaomi.apk").getOrElse(""))
            it.testLogging.showStandardStreams = true
        }
    }
}

// ARSCLib is a desktop library and bundles its own stubs of a few Android framework classes
// (android.util.AttributeSet, XmlResourceParser, org.xmlpull.*). Packaged into the app they
// shadow the real ones for R8, which then miscompiles AppCompat's layout inflation in release
// builds. The library is therefore repackaged without them.
val arsclibOriginal: Configuration by configurations.creating { isTransitive = false }

val strippedArsclib = tasks.register<Jar>("strippedArsclib") {
    archiveFileName.set("arsclib-stripped.jar")
    destinationDirectory.set(layout.buildDirectory.dir("stripped-libs"))
    from({ arsclibOriginal.map { zipTree(it) } }) {
        exclude("android/**", "org/xmlpull/**")
    }
}

dependencies {
    arsclibOriginal(libs.arsclib)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.recyclerview)
    // Pull-to-refresh around the Sources and Clones lists.
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(files(strippedArsclib))
    // dexlib2 is not bundled inside the stripped ARSCLib jar (thin, isTransitive=false), so it is
    // added directly. Used by SignatureBypass to rewrite dex bytecode without a new toolchain.
    implementation(libs.dexlib2)
    implementation(libs.apksig)
    implementation(libs.jsoup)
    implementation(libs.jbcrypt)
    // Tarballs offered as release assets: tar itself plus bzip2 and xz.
    implementation(libs.commons.compress)
    implementation(libs.xz)
    // Telegram channels as a source: reading a channel's files takes a signed-in client.
    implementation(libs.tdlib)
    // The QR code of a Telegram sign-in.
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    // The org.json classes in android.jar are stubs; unit tests need a real implementation.
    testImplementation(libs.org.json)

    // ---- UI instrumentation tests (app/src/androidTest) ----
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.uiautomator)
    // A tiny in-process HTTP server; the fake update source tests serve an APK from it.
    androidTestImplementation(libs.nanohttpd)
}
