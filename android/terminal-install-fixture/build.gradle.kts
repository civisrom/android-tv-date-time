plugins { alias(libs.plugins.android.application) }

// A tiny, permission-free APK used only by installation instrumentation tests.
android {
    namespace = "com.civisrom.tvtimefixer.terminalfixture"
    compileSdk = 37
    dynamicFeatures += setOf(":terminal_install_split")
    defaultConfig {
        applicationId = "com.civisrom.tvtimefixer.terminalfixture"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1"
    }
}
