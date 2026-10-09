import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Prebuilt TDLib for Android; nothing else is taken from here.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.tdlibx") }
        }
    }
}

rootProject.name = "ApkToolbox"
include(":app")
