plugins { id("com.android.dynamic-feature") }

// A real, code-free feature split for the installation fixture, never part of the app.
android {
    namespace = "com.civisrom.tvtimefixer.terminalfixture.split"
    compileSdk = 37
    defaultConfig { minSdk = 23 }
}

dependencies { implementation(project(":terminal-install-fixture")) }
