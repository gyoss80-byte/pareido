val coreOnly = System.getenv("PAREIDO_CORE_ONLY") != null

pluginManagement {
    repositories {
        if (System.getenv("PAREIDO_CORE_ONLY") == null) {
            google {
                content {
                    includeGroupByRegex("androidx.*")
                    includeGroupByRegex("com\\.android.*")
                    includeGroupByRegex("com\\.google.*")
                }
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version "8.7.3"
        id("org.jetbrains.kotlin.android") version "2.0.21"
        id("org.jetbrains.kotlin.jvm") version "2.0.21"
        id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
        id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21"
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (!coreOnly) googleAndroidOnly()
        mavenCentral()
    }
}

fun RepositoryHandler.googleAndroidOnly() = google {
    content {
        includeGroupByRegex("androidx.*")
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google\\.android.*")
        includeGroupByRegex("com\\.google\\.testing.*")
    }
}

rootProject.name = "Pareido"
include(":core")
// The Android module needs Google's Maven repo + the Android SDK. Set
// PAREIDO_CORE_ONLY=1 to build/test just the pure-Kotlin core without them.
if (!coreOnly) include(":app")
