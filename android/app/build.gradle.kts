plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

import groovy.json.JsonSlurper

android {
    namespace = "com.civisrom.tvtimefixer"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.civisrom.tvtimefixer"
        // Покрывает и старые приставки Android TV 9/10, и требования kadb-android
        // (его манифест объявляет minSdkVersion 23). Спаривание Android 11+
        // включается по факту версии устройства, а не через minSdk.
        minSdk = 23
        targetSdk = 36

        // CI подставляет github.run_number: Android требует монотонного роста
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "0.1.0-dev"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Подпись релиза берётся из окружения и только из него: ключ и пароли
    // не должны появляться ни в репозитории, ни в аргументах Gradle. Если
    // ANDROID_KEYSTORE_PATH не задан, конфигурация не создаётся вовсе, и
    // assembleRelease собирает неподписанный APK вместо того, чтобы упасть, —
    // так сборку можно проверять и без доступа к ключу.
    signingConfigs {
        val keystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
        if (!keystorePath.isNullOrBlank()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")

                // v1 и v2 нужны для установки: v1 — на Android 6, v2 — начиная
                // с 7. v3 на установку не влияет вовсе, но без неё невозможна
                // ротация ключа: потеряв ключ подписи, обновить уже
                // установленное приложение будет нечем — Android принимает
                // обновление только от того же ключа либо от его законного
                // преемника, а преемственность объявляется именно в v3.
                // Включать её нужно заранее: задним числом к выпущенному
                // приложению это не применить.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            // bouncycastle приезжает с kadb-android и тащит дублирующиеся
            // метаданные подписи, на которых упаковка падает
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
            )
        }
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
    }
}

// ──────────────────────────────────────────────────────────
// Генерация NtpData.kt из общего shared/ntp-data.json.
// Данные не дублируются вручную намеренно: расхождение между десктопной и
// мобильной половиной означало бы, что они предлагают разные серверы, а
// заметить это можно было бы очень нескоро.
// ──────────────────────────────────────────────────────────

abstract class GenerateNtpDataTask : DefaultTask() {

    @get:InputFile
    abstract val sourceJson: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    private fun quote(value: String?): String {
        val safe = (value ?: "")
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("$", "\\$")
        return "\"" + safe + "\""
    }

    @TaskAction
    fun generate() {
        @Suppress("UNCHECKED_CAST")
        val root = JsonSlurper().parse(sourceJson.get().asFile) as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val countries = root["countries"] as List<Map<String, String>>
        @Suppress("UNCHECKED_CAST")
        val alternatives = root["alternative_servers"] as List<String>

        val target = outputDir.get().asFile.resolve("com/civisrom/tvtimefixer/data/NtpData.kt")
        target.parentFile.mkdirs()
        target.writeText(buildString {
            appendLine("// Сгенерировано из shared/ntp-data.json. Не редактировать вручную:")
            appendLine("// правьте таблицы в src/android_time_fixer.py и запускайте")
            appendLine("// scripts/export_ntp_data.py.")
            appendLine("package com.civisrom.tvtimefixer.data")
            appendLine()
            appendLine("/** Страна и её NTP-сервер из общего справочника. */")
            appendLine("data class NtpCountry(")
            appendLine("    val code: String,")
            appendLine("    val server: String,")
            appendLine("    val nameEn: String,")
            appendLine("    val nameRu: String,")
            appendLine(")")
            appendLine()
            appendLine("object NtpData {")
            appendLine("    val countries: List<NtpCountry> = listOf(")
            countries.forEach {
                appendLine(
                    "        NtpCountry(" + quote(it["code"]) + ", " + quote(it["server"]) +
                        ", " + quote(it["name_en"]) + ", " + quote(it["name_ru"]) + "),"
                )
            }
            appendLine("    )")
            appendLine()
            appendLine("    /** Региональные пулы, Cloudflare, Google и прочие вне разбивки по странам. */")
            appendLine("    val alternativeServers: List<String> = listOf(")
            alternatives.forEach { appendLine("        " + quote(it) + ",") }
            appendLine("    )")
            appendLine()
            appendLine("    val byCode: Map<String, NtpCountry> = countries.associateBy { it.code }")
            appendLine()
            appendLine("    /** Все известные адреса без повторов, страны первыми — как в десктопной версии. */")
            appendLine("    val allServers: List<String> =")
            appendLine("        (countries.map { it.server } + alternativeServers).distinct()")
            appendLine("}")
        })
        logger.lifecycle(
            "NtpData.kt: " + countries.size + " стран, " + alternatives.size + " альтернативных серверов"
        )
    }
}

val generateNtpData = tasks.register<GenerateNtpDataTask>("generateNtpData") {
    description = "Генерирует NtpData.kt из общего shared/ntp-data.json"
    sourceJson.set(layout.projectDirectory.file("../../shared/ntp-data.json"))
    outputDir.set(layout.buildDirectory.dir("generated/ntpdata"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.kotlin?.addGeneratedSourceDirectory(
            generateNtpData,
            GenerateNtpDataTask::outputDir,
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.androidx.tv.material)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kadb.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}
