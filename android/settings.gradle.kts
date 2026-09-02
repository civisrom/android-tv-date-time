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
