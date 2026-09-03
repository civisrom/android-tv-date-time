package com.civisrom.tvtimefixer

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/**
 * Где запущено приложение. От этого зависит и режим работы, и интерфейс:
 * на телевизоре нет тачскрина и всё управление идёт с пульта.
 */
enum class DeviceMode {
    /** Телефон или планшет: управляем телевизором по сети. */
    HANDHELD,

    /** Сам Android TV: пробуем подключиться к собственному adbd по loopback. */
    TELEVISION,
}

/**
 * Определяет режим по системным признакам.
 *
 * Основной признак — [UiModeManager.getCurrentModeType]: именно он говорит, что
 * система работает в телевизионном режиме. Признак `FEATURE_LEANBACK` берётся
 * как запасной: на части приставок (в том числе Chromecast с Google TV) он
 * присутствует, но uiMode может быть выставлен нестандартно прошивкой.
 * Достаточно любого из двух — ошибка в сторону телевизора безопаснее, потому
 * что интерфейс с фокусом на D-pad остаётся пригодным и для тача, а обратное
 * неверно.
 */
fun detectDeviceMode(context: Context): DeviceMode {
    val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    val isTelevisionUiMode =
        uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    val hasLeanback =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    return if (isTelevisionUiMode || hasLeanback) DeviceMode.TELEVISION else DeviceMode.HANDHELD
}
