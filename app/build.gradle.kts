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

android {
    namespace = "io.github.bl3xand.apkcloner"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.bl3xand.apkcloner"
        minSdk = 35
        targetSdk = 36
        versionCode = 4
        versionName = "1.0"
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
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(files(strippedArsclib))
    implementation(libs.apksig)
}
