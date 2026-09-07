pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

// Fail before configuring the app or reading signing passwords, including when
// command-line options override gradle.properties.
@Suppress("DEPRECATION")
val configurationCacheRequested = gradle.startParameter.isConfigurationCacheRequested
check(!configurationCacheRequested) {
    "Configuration cache is disabled to protect signing secrets. Use --no-configuration-cache."
}
if (!System.getenv("ANDROID_KEYSTORE_PATH").isNullOrBlank()) {
    check(!gradle.startParameter.isBuildCacheEnabled) {
        "Signed builds require --no-build-cache to protect signing secrets."
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // spake2-java (транзитивная зависимость kadb-android, нужна для
        // спаривания Android 11+) опубликована ТОЛЬКО здесь: в Maven Central
        // её нет, проверено — 404. Без этого репозитория сборка падает на
        // разрешении зависимостей.
        maven("https://jitpack.io") {
            content { includeGroupByRegex("com\\.github\\..*") }
        }
    }
}

rootProject.name = "AndroidTVTimeFixer"
include(":app")
