package com.civisrom.tvtimefixer

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

data class LocalSetupState(
    val apiLevel: Int = Build.VERSION.SDK_INT,
    val developerOptions: Boolean? = null,
    val usbDebugging: Boolean? = null,
) {
    // Для TV штатная поддержка указана с Android 13; конкретный OEM может отличаться.
    val wirelessGuide: Boolean get() = apiLevel >= 33
}

fun readLocalSetup(context: Context): LocalSetupState {
    fun flag(key: String): Boolean? = runCatching {
        when (Settings.Global.getInt(context.contentResolver, key, -1)) {
            0 -> false
            1 -> true
            else -> null
        }
    }.getOrNull()
    return LocalSetupState(developerOptions = flag(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED),
        usbDebugging = flag(Settings.Global.ADB_ENABLED))
}

/** Прошивка может не реализовать специальный Intent: открываем общие настройки. */
fun openLocalSettings(context: Context, action: String): Boolean {
    require(action in setOf(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, Settings.ACTION_DEVICE_INFO_SETTINGS,
        Settings.ACTION_DATE_SETTINGS))
    return listOf(action, Settings.ACTION_SETTINGS).distinct().any {
        runCatching { context.startActivity(Intent(it)); true }.getOrDefault(false)
    }
}
