plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

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

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

    implementation(libs.kadb.android)
    implementation(libs.kadb.mdns.android)

    testImplementation(libs.junit)
}
