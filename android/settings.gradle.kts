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
            content { includeModule("com.github.Flyfish233", "spake2-java") }
        }
    }
}

rootProject.name = "AndroidTVTimeFixer"
include(":app")
include(":terminal-install-fixture")

// Security fixes for transitive build tools. Match audited old requests only,
// so a future plugin update can select a newer version without being downgraded.
val buildDependencyFixes = mapOf(
    "org.apache.commons:commons-lang3:3.16.0" to "3.18.0", // CVE-2025-48924
    "org.apache.httpcomponents:httpclient:4.5.6" to "4.5.14", // CVE-2020-13956
    "org.bitbucket.b_c:jose4j:0.9.5" to "0.9.6", // CVE-2024-29371
    "org.bouncycastle:bcprov-jdk18on:1.80.2" to "1.84", // CVE-2026-0636
    "org.bouncycastle:bcpkix-jdk18on:1.80.2" to "1.84", // CVE-2026-5588
    "org.bouncycastle:bcutil-jdk18on:1.80.2" to "1.84", // keep the BC modules aligned
    "org.jdom:jdom2:2.0.6" to "2.0.6.1", // CVE-2021-33813
)
gradle.beforeProject {
    fun org.gradle.api.artifacts.ConfigurationContainer.patchBuildDependencies() {
        configureEach {
            resolutionStrategy.eachDependency {
                buildDependencyFixes["${requested.group}:${requested.name}:${requested.version}"]?.let {
                    useVersion(it)
                    because("Patched transitive build dependency; see the CVE next to its version rule")
                }
            }
        }
    }
    buildscript.configurations.patchBuildDependencies()
    configurations.patchBuildDependencies()
}
